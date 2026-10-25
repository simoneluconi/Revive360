/*
 * HTTP/1.1 subset (protocol §2): one request per connection, thread per
 * connection, responses always with Content-Length and Connection: close.
 *
 * Limits: header block 8 KiB (413), request body 4 KiB (413), bodies only with
 * Content-Length (chunked -> 400). Socket timeouts keep a stalled client from
 * pinning a thread forever.
 */
#include "server.h"

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define MAX_CONN       24
#define RECV_TIMEOUT_S 15
#define SEND_TIMEOUT_S 60

static volatile int g_active;
static pthread_mutex_t g_active_mu = PTHREAD_MUTEX_INITIALIZER;

const char *http_reason(int status)
{
    switch (status) {
    case 200: return "OK";
    case 206: return "Partial Content";
    case 400: return "Bad Request";
    case 404: return "Not Found";
    case 405: return "Method Not Allowed";
    case 409: return "Conflict";
    case 413: return "Payload Too Large";
    case 416: return "Range Not Satisfiable";
    case 500: return "Internal Server Error";
    case 501: return "Not Implemented";
    case 503: return "Service Unavailable";
    case 504: return "Gateway Timeout";
    default:  return "Unknown";
    }
}

int http_send_all(int fd, const void *p, size_t n)
{
    const char *c = p;
    while (n > 0) {
        ssize_t w = send(fd, c, n, MSG_NOSIGNAL);
        if (w < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        c += w;
        n -= (size_t)w;
    }
    return 0;
}

void http_format_date(int64_t t, char *out, size_t outlen)
{
    static const char *const wd[] = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
    static const char *const mo[] = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                      "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
    struct tm tm;
    time_t tt = (time_t)t;
    gmtime_r(&tt, &tm);
    snprintf(out, outlen, "%s, %02d %s %04d %02d:%02d:%02d GMT", wd[tm.tm_wday], tm.tm_mday,
             mo[tm.tm_mon], tm.tm_year + 1900, tm.tm_hour, tm.tm_min, tm.tm_sec);
}

int http_send(req_t *r, int status, const char *ctype, const char *extra,
              const void *body, size_t len, int head_only)
{
    char hdr[1024];
    int n = snprintf(hdr, sizeof hdr,
                     "HTTP/1.1 %d %s\r\n"
                     "Server: " G360RD_NAME "/" G360RD_VERSION "\r\n"
                     "Content-Type: %s\r\n"
                     "Content-Length: %zu\r\n"
                     "Connection: close\r\n"
                     "%s"
                     "\r\n",
                     status, http_reason(status), ctype, len, extra ? extra : "");
    LOGI("http: %s %s %s -> %d (%zu bytes%s)", r->peer, r->method, r->target, status, len,
         head_only ? ", head" : "");
    if (http_send_all(r->fd, hdr, (size_t)n) < 0)
        return -1;
    if (!head_only && len > 0 && http_send_all(r->fd, body, len) < 0)
        return -1;
    return 0;
}

int http_send_json(req_t *r, int status, sbuf_t *json)
{
    if (json->oom)
        return http_send_error(r, 500, "internal", "out of memory");
    return http_send(r, status, "application/json; charset=utf-8", NULL, json->buf, json->len, 0);
}

int http_send_error(req_t *r, int status, const char *code, const char *fmt, ...)
{
    char msg[512];
    char extra[96] = "";
    sbuf_t b;
    va_list ap;
    int rc;

    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    LOGW("http: error %d %s: %s", status, code, msg);
    sbuf_init(&b);
    sbuf_puts(&b, "{\"error\":{\"code\":");
    json_str(&b, code);
    sbuf_puts(&b, ",\"message\":");
    json_str(&b, msg);
    sbuf_puts(&b, "}}");
    if (status == 405 && r->allow[0])
        snprintf(extra, sizeof extra, "Allow: %s\r\n", r->allow);
    rc = http_send(r, status, "application/json; charset=utf-8", extra[0] ? extra : NULL,
                   b.buf, b.len, strcmp(r->method, "HEAD") == 0);
    sbuf_free(&b);
    return rc;
}

/* ------------------------------------------------------------ parsing */

static char *find_hdr_end(char *buf, size_t len, size_t *hdr_len)
{
    size_t i;
    for (i = 0; i + 1 < len; i++) {
        if (buf[i] == '\n' && buf[i + 1] == '\n') {
            *hdr_len = i + 2;
            return buf + i;
        }
        if (i + 3 < len && buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
            *hdr_len = i + 4;
            return buf + i;
        }
    }
    return NULL;
}

/* Reads and parses one request. Returns 0 ok, or an HTTP status to answer
 * with (400/413), or -1 to just close (client went away). */
static int read_request(req_t *r)
{
    char *buf = malloc(HDR_MAX + 1);
    size_t got = 0, hdr_len = 0;
    char *line, *save = NULL, *q;
    int rc = 0, first = 1, chunked = 0;

    if (!buf)
        return -1;
    r->content_length = -1;
    for (;;) {
        ssize_t n;
        if (got >= HDR_MAX) {
            rc = 413;
            goto out;
        }
        n = recv(r->fd, buf + got, HDR_MAX - got, 0);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            rc = -1;
            goto out;
        }
        got += (size_t)n;
        if (find_hdr_end(buf, got, &hdr_len))
            break;
    }
    buf[hdr_len - 1] = 0;  /* terminate header block */

    for (line = strtok_r(buf, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        size_t ll = strlen(line);
        if (ll && line[ll - 1] == '\r')
            line[--ll] = 0;
        if (first) {
            char ver[16];
            first = 0;
            if (sscanf(line, "%15s %2047s %15s", r->method, r->target, ver) != 3 ||
                strncmp(ver, "HTTP/1.", 7) != 0) {
                rc = 400;
                goto out;
            }
            continue;
        }
        if (!ll)
            continue;
        q = strchr(line, ':');
        if (!q)
            continue;
        *q++ = 0;
        while (*q == ' ' || *q == '\t')
            q++;
        if (strcasecmp(line, "Content-Length") == 0) {
            char *end;
            long v;
            trim(q);
            if (!*q || !isdigit((unsigned char)*q)) {
                rc = 400;
                goto out;
            }
            v = strtol(q, &end, 10);
            if (*end || v < 0) {
                rc = 400;
                goto out;
            }
            r->content_length = v;
        } else if (strcasecmp(line, "Transfer-Encoding") == 0) {
            chunked = 1;
        } else if (strcasecmp(line, "Range") == 0) {
            snprintf(r->range, sizeof r->range, "%s", q);
            trim(r->range);
        }
    }
    if (first) {
        rc = 400;
        goto out;
    }
    if (chunked) {
        rc = 400;   /* protocol: bodies only with Content-Length */
        goto out;
    }

    /* Split target into path and query. */
    snprintf(r->path, sizeof r->path, "%s", r->target);
    q = strchr(r->path, '?');
    if (q) {
        *q++ = 0;
        snprintf(r->query, sizeof r->query, "%s", q);
    }

    /* Body. */
    if (r->content_length > 0) {
        size_t have = got - hdr_len;
        if (r->content_length > BODY_MAX) {
            /* The debug upload reads its own body; it keeps what came along */
            if (strcmp(r->method, "PUT") == 0 && strcmp(r->path, UPLOAD_PATH) == 0 &&
                r->content_length <= UPLOAD_MAX) {   /* have <= HDR_MAX: it came with the headers */
                if (have > (size_t)r->content_length)
                    have = (size_t)r->content_length;
                memcpy(r->body, buf + hdr_len, have);
                r->body_len = have;
                r->body_streamed = 1;
                r->body[r->body_len] = 0;
                goto out;
            }
            rc = 413;
            goto out;
        }
        if (have > (size_t)r->content_length)
            have = (size_t)r->content_length;
        memcpy(r->body, buf + hdr_len, have);
        while (have < (size_t)r->content_length) {
            ssize_t n = recv(r->fd, r->body + have, (size_t)r->content_length - have, 0);
            if (n < 0 && errno == EINTR)
                continue;
            if (n <= 0) {
                rc = -1;
                goto out;
            }
            have += (size_t)n;
        }
        r->body_len = have;
    }
    r->body[r->body_len] = 0;
out:
    free(buf);
    return rc;
}

/* ------------------------------------------------------------ threads */

static void *conn_thread(void *arg)
{
    req_t *r = arg;
    int rc;
    struct timeval tv;

    tv.tv_sec = RECV_TIMEOUT_S;
    tv.tv_usec = 0;
    setsockopt(r->fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    tv.tv_sec = SEND_TIMEOUT_S;
    setsockopt(r->fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);

    rc = read_request(r);
    g_srv.last_request_ms = now_ms_mono();
    if (rc == 0) {
        api_dispatch(r);
    } else if (rc == 413) {
        if (!r->method[0])
            snprintf(r->method, sizeof r->method, "?");
        http_send_error(r, 413, "too_large", "headers or body over the limit");
    } else if (rc == 400) {
        if (!r->method[0])
            snprintf(r->method, sizeof r->method, "?");
        http_send_error(r, 400, "bad_request", "malformed request");
    }
    /* Graceful close: half-close, then briefly drain so the peer gets our
     * data instead of a RST. */
    shutdown(r->fd, SHUT_WR);
    tv.tv_sec = 0;
    tv.tv_usec = 300000;
    setsockopt(r->fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    {
        char tmp[512];
        int i;
        for (i = 0; i < 16 && recv(r->fd, tmp, sizeof tmp, 0) > 0; i++)
            ;
    }
    close(r->fd);
    free(r);
    pthread_mutex_lock(&g_active_mu);
    g_active--;
    pthread_mutex_unlock(&g_active_mu);
    return NULL;
}

static void *housekeeping_thread(void *arg)
{
    (void)arg;
    for (;;) {
        sleep_ms(2000);
        log_flush_sync();
    }
    return NULL;
}

int server_run(const char *bind_addr, int port)
{
    int ls, one = 1;
    struct sockaddr_in sa;
    pthread_attr_t at;
    pthread_t th;

    signal(SIGPIPE, SIG_IGN);
    ls = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (ls < 0) {
        LOGE("server: socket: %s", strerror(errno));
        return 1;
    }
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, bind_addr, &sa.sin_addr) != 1) {
        LOGE("server: bad bind address %s", bind_addr);
        close(ls);
        return 1;
    }
    if (bind(ls, (struct sockaddr *)&sa, sizeof sa) < 0 || listen(ls, 16) < 0) {
        LOGE("server: bind/listen %s:%d: %s", bind_addr, port, strerror(errno));
        close(ls);
        return 1;
    }
    LOGI("server: listening on %s:%d", bind_addr, port);

    pthread_attr_init(&at);
    pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&at, 256 * 1024);
    pthread_create(&th, &at, housekeeping_thread, NULL);

    for (;;) {
        struct sockaddr_in peer;
        socklen_t pl = sizeof peer;
        int fd = accept4(ls, (struct sockaddr *)&peer, &pl, SOCK_CLOEXEC), busy;
        req_t *r;
        if (fd < 0) {
            if (errno != EINTR)
                LOGW("server: accept: %s", strerror(errno));
            if (errno == EMFILE || errno == ENFILE || errno == ENOMEM)
                sleep_ms(200);
            continue;
        }
        pthread_mutex_lock(&g_active_mu);
        busy = g_active >= MAX_CONN;
        if (!busy)
            g_active++;
        pthread_mutex_unlock(&g_active_mu);
        if (busy) {
            LOGW("server: too many connections, dropping one");
            close(fd);
            continue;
        }
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
        r = calloc(1, sizeof *r);
        if (!r) {
            close(fd);
            pthread_mutex_lock(&g_active_mu);
            g_active--;
            pthread_mutex_unlock(&g_active_mu);
            continue;
        }
        r->fd = fd;
        inet_ntop(AF_INET, &peer.sin_addr, r->peer, sizeof r->peer);
        if (pthread_create(&th, &at, conn_thread, r) != 0) {
            LOGE("server: pthread_create failed");
            close(fd);
            free(r);
            pthread_mutex_lock(&g_active_mu);
            g_active--;
            pthread_mutex_unlock(&g_active_mu);
        }
    }
    return 0;
}
