/*
 * GET /api/v1/liveview (protocol §5.14): the camera's live picture. The
 * adapter starts the camera's own stream (the C200's is the Samsung phone
 * app's remote viewfinder, an HTTP stream inside di-camera-app) and this
 * relays it to the client as it comes, until either side goes. One client at a
 * time: the camera's stream has one session.
 */
#include "server.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

volatile int g_liveview;
static pthread_mutex_t g_lv_mu = PTHREAD_MUTEX_INITIALIZER;

#define UPSTREAM_TIMEOUT_S 5

/* Connects to host:port and sends the GET; the socket, or -1 + err */
static int upstream_open(const char *host, int port, const char *path, char *err, size_t errlen)
{
    struct sockaddr_in sa;
    struct timeval tv = { UPSTREAM_TIMEOUT_S, 0 };
    char req[256];
    int s = socket(AF_INET, SOCK_STREAM, 0);

    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_port = htons((uint16_t)port);
    if (s < 0 || inet_pton(AF_INET, host, &sa.sin_addr) != 1) {
        snprintf(err, errlen, "bad stream address %s", host);
        if (s >= 0)
            close(s);
        return -1;
    }
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    if (connect(s, (struct sockaddr *)&sa, sizeof sa) < 0) {
        snprintf(err, errlen, "connect %s:%d: %s", host, port, strerror(errno));
        close(s);
        return -1;
    }
    snprintf(req, sizeof req, "GET %s HTTP/1.1\r\nHost: %s:%d\r\nUser-Agent: g360rd\r\n\r\n", path, host, port);
    if (http_send_all(s, req, strlen(req)) < 0) {
        snprintf(err, errlen, "send to the stream: %s", strerror(errno));
        close(s);
        return -1;
    }
    return s;
}

/* Reads the stream's header block into buf; its length, the bytes after it in
 * *extra, the Content-Type into ctype. -1 + err when it is not a stream. */
static int upstream_header(int s, char *buf, size_t cap, size_t *got, char *ctype, size_t ctlen, char *err, size_t errlen)
{
    char *end = NULL, *ct;
    int status = 0;

    *got = 0;
    while (*got < cap - 1 && !(end = strstr(buf, "\r\n\r\n"))) {
        ssize_t n = recv(s, buf + *got, cap - 1 - *got, 0);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            snprintf(err, errlen, "the stream closed before its header");
            return -1;
        }
        *got += (size_t)n;
        buf[*got] = 0;
    }
    if (!end || sscanf(buf, "HTTP/1.%*d %d", &status) != 1 || (status != 200 && status != 206)) {
        snprintf(err, errlen, "the stream answered %d", status);
        return -1;
    }
    snprintf(ctype, ctlen, "video/x-avi");
    if ((ct = strstr(buf, "Content-Type:")) && ct < end) {
        ct += strlen("Content-Type:");
        while (*ct == ' ')
            ct++;
        if (*ct != '\r')
            snprintf(ctype, ctlen, "%.*s", (int)strcspn(ct, " \r;"), ct);
    }
    return (int)(end + 4 - buf);
}

void api_liveview(req_t *r)
{
    adapter_t *a = &g_srv.ad;
    char host[64], path[128], err[200], ctype[64], hdr[256];
    char buf[32768];
    int port = 0, up = -1, claimed = 0, opened = 0, body_at;
    size_t got = 0;
    long long sent = 0;
    int64_t t0 = now_ms_mono();

    if (!a->liveview_open) {
        http_send_error(r, 501, "unsupported", "this camera has no live view");
        return;
    }
    if (g_srv.busy || g_srv.recording) {
        http_send_error(r, 409, "busy", "a capture or a recording is in progress");
        return;
    }
    pthread_mutex_lock(&g_lv_mu);
    if (!g_liveview)
        claimed = g_liveview = 1;
    pthread_mutex_unlock(&g_lv_mu);
    if (!claimed) {
        http_send_error(r, 409, "busy", "a live view is already being served");
        return;
    }
    err[0] = 0;
    if (a->liveview_open(a, host, sizeof host, &port, path, sizeof path, err, sizeof err) < 0) {
        http_send_error(r, 500, "internal", "live view: %s", err);
        goto done;
    }
    opened = 1;
    if ((up = upstream_open(host, port, path, err, sizeof err)) < 0 ||
        (body_at = upstream_header(up, buf, sizeof buf, &got, ctype, sizeof ctype, err, sizeof err)) < 0) {
        LOGE("liveview: %s", err);
        http_send_error(r, 502, "internal", "live view: %s", err);
        goto done;
    }
    LOGI("liveview: %s:%d%s (%s) to %s", host, port, path, ctype, r->peer);
    /* As it comes: no length, the client reads until the camera or it stops */
    snprintf(hdr, sizeof hdr, "HTTP/1.1 200 OK\r\nContent-Type: %s\r\nCache-Control: no-cache\r\n"
             "Connection: close\r\n\r\n", ctype);
    if (http_send_all(r->fd, hdr, strlen(hdr)) < 0 ||
        (got > (size_t)body_at && http_send_all(r->fd, buf + body_at, got - (size_t)body_at) < 0))
        goto done;
    sent = (long long)(got - (size_t)body_at);
    for (;;) {
        ssize_t n = recv(up, buf, sizeof buf, 0);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0 || http_send_all(r->fd, buf, (size_t)n) < 0)
            break;
        sent += n;
    }
    LOGI("liveview: ended after %lld bytes, %lld s", sent, (long long)((now_ms_mono() - t0) / 1000));
done:
    if (up >= 0)
        close(up);
    if (opened)
        a->liveview_close(a);
    g_liveview = 0;
}
