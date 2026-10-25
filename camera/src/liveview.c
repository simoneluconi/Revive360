/*
 * GET /api/v1/liveview (protocol §5.14): the camera's live picture. The
 * adapter starts the camera's own stream (the C200's is the Samsung phone
 * app's remote viewfinder, an HTTP stream inside di-camera-app) and this
 * relays it to the client.
 *
 * The camera's stream server is slow to let a session go (its streaming
 * thread notices a closed connection only after its own timeout, 10-20 s) and
 * a new one waits behind it. So the upstream session outlives its client for
 * a grace period: a client that comes back in time (the app rotating, leaving
 * and reopening the screen) takes it over, starting from the stream header
 * and the next key frame. One client at a time.
 *
 * The stream is Samsung's TTTS: a header up to "movi", then chunks of
 * id(4) size(4, big-endian) timestamp(8) data(size); video chunks ("00VD")
 * hold one HEVC access unit, key frames starting with their VPS.
 */
#include "server.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

volatile int g_liveview;

/* The camera starts its camcorder for the stream a few seconds after the
 * request, and longer while it lets an old session go */
#define UPSTREAM_TIMEOUT_S 25
#define GRACE_MS           15000
#define HEADER_MAX         4096
#define CHUNK_MAX          (8 * 1024 * 1024)

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_cv = PTHREAD_COND_INITIALIZER;
/* The relay: running, and the client it serves (-1 none, parked) */
static int g_running;
static int g_client = -1;
static int g_client_done;   /* the relay let g_client go */
static int g_stop;          /* end the session now (a capture wants the camera) */

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

static int read_full(int s, void *p, size_t n)
{
    char *c = p;
    while (n > 0) {
        ssize_t got = recv(s, c, n, 0);
        if (got < 0 && errno == EINTR)
            continue;
        if (got <= 0)
            return -1;
        c += got;
        n -= (size_t)got;
    }
    return 0;
}

/* The HTTP answer and the TTTS header (up to and with "movi") into hdr;
 * its length, or -1 + err */
static int upstream_header(int s, char *hdr, size_t cap, char *err, size_t errlen)
{
    char line[2048];
    size_t got = 0;
    int status = 0;
    char *end;

    line[0] = 0;
    while (got < sizeof line - 1 && !(end = strstr(line, "\r\n\r\n"))) {
        ssize_t n = recv(s, line + got, 1, 0);   /* byte by byte: the body follows */
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            snprintf(err, errlen, "the stream closed before its header");
            return -1;
        }
        got += (size_t)n;
        line[got] = 0;
    }
    if (sscanf(line, "HTTP/1.%*d %d", &status) != 1 || (status != 200 && status != 206)) {
        snprintf(err, errlen, "the stream answered %d", status);
        return -1;
    }
    got = 0;
    while (got < cap) {
        if (read_full(s, hdr + got, 1) < 0) {
            snprintf(err, errlen, "the stream closed in its TTTS header");
            return -1;
        }
        got++;
        if (got >= 4 && memcmp(hdr + got - 4, "movi", 4) == 0)
            return (int)got;
    }
    snprintf(err, errlen, "no TTTS header");
    return -1;
}

/* Whether a video chunk starts a key frame (its first NAL unit a VPS) */
static int key_frame(const unsigned char *p, size_t n)
{
    size_t i = 0;
    while (i + 3 < n && !(p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1))
        i++;
    return i + 3 < n && ((p[i + 3] >> 1) & 0x3f) == 32;
}

static const char LIVE_HEADER[] =
    "HTTP/1.1 200 OK\r\nContent-Type: video/x-avi\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";

/* The relay loop, on the first client's request thread: forwards chunks to
 * the current client, keeps the session through a grace period without one */
static void relay(int up, const char *hdr, int hdr_len)
{
    unsigned char head[16];
    unsigned char *data = NULL;
    size_t cap = 0;
    int client = -1, synced = 0;
    int64_t parked_at = 0;
    long long sent = 0;

    for (;;) {
        uint32_t size;
        int is_video;

        /* Take the client posted (a new one, or the first) */
        pthread_mutex_lock(&g_mu);
        if (g_client != client) {
            client = g_client;
            synced = 0;
            if (client >= 0) {
                if (http_send_all(client, LIVE_HEADER, sizeof LIVE_HEADER - 1) < 0 ||
                    http_send_all(client, hdr, (size_t)hdr_len) < 0) {
                    g_client = client = -1;
                    g_client_done = 1;
                    pthread_cond_broadcast(&g_cv);
                } else {
                    LOGI("liveview: serving a client");
                }
            }
            if (client < 0)
                parked_at = now_ms_mono();
        }
        if (g_stop || (client < 0 && now_ms_mono() - parked_at > GRACE_MS)) {
            g_running = 0;
            pthread_mutex_unlock(&g_mu);
            break;
        }
        pthread_mutex_unlock(&g_mu);

        if (read_full(up, head, sizeof head) < 0)
            break;
        size = (uint32_t)head[4] << 24 | (uint32_t)head[5] << 16 | (uint32_t)head[6] << 8 | head[7];
        if (size > CHUNK_MAX)
            break;
        if (size > cap) {
            unsigned char *more = realloc(data, size);
            if (!more)
                break;
            data = more;
            cap = size;
        }
        if (read_full(up, data, size) < 0)
            break;
        if (client < 0)
            continue;
        is_video = memcmp(head, "00VD", 4) == 0;
        /* A client taken over mid-stream starts at a key frame */
        if (!synced && !(is_video && key_frame(data, size)))
            continue;
        synced = 1;
        if (http_send_all(client, head, sizeof head) < 0 || http_send_all(client, data, size) < 0) {
            LOGI("liveview: the client left after %lld bytes; the stream stays %d s", sent, GRACE_MS / 1000);
            pthread_mutex_lock(&g_mu);
            g_client = client = -1;
            g_client_done = 1;
            parked_at = now_ms_mono();
            pthread_cond_broadcast(&g_cv);
            pthread_mutex_unlock(&g_mu);
            sent = 0;
            continue;
        }
        sent += (long long)(sizeof head + size);
    }
    free(data);
    pthread_mutex_lock(&g_mu);
    g_running = 0;
    if (g_client >= 0) {
        g_client = -1;
        g_client_done = 1;
        pthread_cond_broadcast(&g_cv);
    }
    pthread_mutex_unlock(&g_mu);
}

void api_liveview(req_t *r)
{
    adapter_t *a = &g_srv.ad;
    char host[64], path[128], err[200];
    char *hdr;
    int port = 0, up = -1, hdr_len;

    if (!a->liveview_open) {
        http_send_error(r, 501, "unsupported", "this camera has no live view");
        return;
    }
    if (g_srv.busy || g_srv.recording) {
        http_send_error(r, 409, "busy", "a capture or a recording is in progress");
        return;
    }
    pthread_mutex_lock(&g_mu);
    if (g_running) {
        /* A session is up: take it over if it has no client, then wait for it to let go */
        if (g_client >= 0) {
            pthread_mutex_unlock(&g_mu);
            http_send_error(r, 409, "busy", "a live view is already being served");
            return;
        }
        g_client = r->fd;
        g_client_done = 0;
        while (!g_client_done)
            pthread_cond_wait(&g_cv, &g_mu);
        pthread_mutex_unlock(&g_mu);
        return;
    }
    g_running = 1;
    g_liveview = 1;
    g_stop = 0;
    g_client = r->fd;
    g_client_done = 0;
    pthread_mutex_unlock(&g_mu);

    err[0] = 0;
    hdr = malloc(HEADER_MAX);
    if (!hdr || a->liveview_open(a, host, sizeof host, &port, path, sizeof path, err, sizeof err) < 0) {
        http_send_error(r, 500, "internal", "live view: %s", hdr ? err : "out of memory");
        free(hdr);
        pthread_mutex_lock(&g_mu);
        g_running = 0;
        g_liveview = 0;
        g_client = -1;
        pthread_mutex_unlock(&g_mu);
        return;
    }
    if ((up = upstream_open(host, port, path, err, sizeof err)) < 0 ||
        (hdr_len = upstream_header(up, hdr, HEADER_MAX, err, sizeof err)) < 0) {
        LOGE("liveview: %s", err);
        http_send_error(r, 502, "internal", "live view: %s", err);
        pthread_mutex_lock(&g_mu);
        g_running = 0;
        g_client = -1;
        pthread_mutex_unlock(&g_mu);
    } else {
        LOGI("liveview: %s:%d%s to %s", host, port, path, r->peer);
        relay(up, hdr, hdr_len);
        LOGI("liveview: stream closed");
    }
    if (up >= 0)
        close(up);
    free(hdr);
    a->liveview_close(a);
    g_liveview = 0;
}

/* A capture or a recording needs the camera: a session kept without a client
 * ends now. 0 when the live view is off (in at most a few seconds), -1 while a
 * client still watches. */
int liveview_release(void)
{
    int64_t until = now_ms_mono() + 5000;

    pthread_mutex_lock(&g_mu);
    if (g_running && g_client >= 0) {
        pthread_mutex_unlock(&g_mu);
        return -1;
    }
    if (g_running)
        g_stop = 1;
    pthread_mutex_unlock(&g_mu);
    while (g_liveview && now_ms_mono() < until)
        sleep_ms(50);
    return g_liveview ? -1 : 0;
}
