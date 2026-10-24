/*
 * libg360r_shim.so: loaded into di-camera-app with LD_PRELOAD (systemd drop-in
 * of the modified firmware), to reach settings the 360 UI never exposes (RAW)
 * through the app's own camera handles. Never loaded by the factory SD hook.
 *
 * - Does nothing in any process other than di-camera-app.
 * - Interposes mmf_camcorder_create / mm_camera_create to remember the app's
 *   handles (the real functions run unchanged, via dlsym(RTLD_NEXT)).
 * - Listens on /tmp/g360r/shim.sock, one line per request, one line answered.
 *   Requests that touch the camera are posted to the app's main loop
 *   (ecore_main_loop_thread_safe_call_async) and run there, serialized with
 *   the app's own camera calls; the socket thread only waits for the answer.
 * - Logs to /tmp/g360r/shim.log.
 *
 * Built against the camera's own glibc 2.13 (camera/.toolchain/aqk1/sysroot),
 * everything else is looked up with dlsym at run time.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define SHIM_DIR  "/tmp/g360r"
#define SHIM_SOCK SHIM_DIR "/shim.sock"
#define SHIM_LOG  SHIM_DIR "/shim.log"
#define APP_NAME  "di-camera-app"

typedef void *handle_t;

static int g_active;
static handle_t g_camcorder;    /* mmf_camcorder handle of the app */
static handle_t g_camera;       /* mm_camera handle of the app */
static pthread_mutex_t g_log_mu = PTHREAD_MUTEX_INITIALIZER;

static void slog(const char *fmt, ...)
{
    FILE *f;
    va_list ap;
    struct timespec ts;

    pthread_mutex_lock(&g_log_mu);
    f = fopen(SHIM_LOG, "a");
    if (f) {
        clock_gettime(CLOCK_MONOTONIC, &ts);
        fprintf(f, "[%5ld.%03ld] ", (long)ts.tv_sec, ts.tv_nsec / 1000000);
        va_start(ap, fmt);
        vfprintf(f, fmt, ap);
        va_end(ap);
        fputc('\n', f);
        fclose(f);
    }
    pthread_mutex_unlock(&g_log_mu);
}

/* ------------------------------------------------------------ interposers */

int mmf_camcorder_create(handle_t *camcorder, void *info)
{
    static int (*real)(handle_t *, void *);
    int rc;

    if (!real)
        real = (int (*)(handle_t *, void *))dlsym(RTLD_NEXT, "mmf_camcorder_create");
    if (!real)
        return -1;
    rc = real(camcorder, info);
    if (g_active && camcorder) {
        g_camcorder = *camcorder;
        slog("mmf_camcorder_create -> rc=%d handle=%p", rc, g_camcorder);
    }
    return rc;
}

int mm_camera_create(handle_t *camera, void *info)
{
    static int (*real)(handle_t *, void *);
    int rc;

    if (!real)
        real = (int (*)(handle_t *, void *))dlsym(RTLD_NEXT, "mm_camera_create");
    if (!real)
        return -1;
    rc = real(camera, info);
    if (g_active && camera) {
        g_camera = *camera;
        slog("mm_camera_create -> rc=%d handle=%p", rc, g_camera);
    }
    return rc;
}

/* ------------------------------------------------------------ main loop */

/* One request run on the app's main loop; the socket thread waits on done. */
struct job {
    char req[256];
    char resp[512];
    int done;
    pthread_mutex_t mu;
    pthread_cond_t cv;
};

static void run_on_main(struct job *j)
{
    if (strcmp(j->req, "ping") == 0) {
        snprintf(j->resp, sizeof j->resp, "ok pong (main loop)");
    } else if (strcmp(j->req, "handles") == 0) {
        snprintf(j->resp, sizeof j->resp, "ok camcorder=%p camera=%p", g_camcorder, g_camera);
    } else {
        snprintf(j->resp, sizeof j->resp, "error unknown request");
    }
}

static void main_cb(void *data)
{
    struct job *j = data;
    run_on_main(j);
    pthread_mutex_lock(&j->mu);
    j->done = 1;
    pthread_cond_signal(&j->cv);
    pthread_mutex_unlock(&j->mu);
}

/* Post to the ecore main loop and wait up to 10 s. */
static void post_and_wait(struct job *j)
{
    static void (*async)(void (*)(void *), const void *);
    struct timespec until;

    if (!async)
        async = (void (*)(void (*)(void *), const void *))dlsym(RTLD_DEFAULT,
                                                                 "ecore_main_loop_thread_safe_call_async");
    if (!async) {
        snprintf(j->resp, sizeof j->resp, "error no ecore_main_loop_thread_safe_call_async");
        return;
    }
    pthread_mutex_init(&j->mu, NULL);
    pthread_cond_init(&j->cv, NULL);
    j->done = 0;
    async(main_cb, j);
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += 10;
    pthread_mutex_lock(&j->mu);
    while (!j->done)
        if (pthread_cond_timedwait(&j->cv, &j->mu, &until) == ETIMEDOUT)
            break;
    pthread_mutex_unlock(&j->mu);
    if (!j->done)
        snprintf(j->resp, sizeof j->resp, "error main loop did not run the request in 10 s");
    /* a late callback still owns j: leak it rather than free it under the loop */
}

/* ------------------------------------------------------------ socket */

static void *server(void *arg)
{
    struct sockaddr_un sa;
    int s;
    (void)arg;

    mkdir(SHIM_DIR, 0755);
    unlink(SHIM_SOCK);
    s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) {
        slog("socket: %s", strerror(errno));
        return NULL;
    }
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s", SHIM_SOCK);
    if (bind(s, (struct sockaddr *)&sa, sizeof sa) < 0 || listen(s, 4) < 0) {
        slog("bind/listen %s: %s", SHIM_SOCK, strerror(errno));
        close(s);
        return NULL;
    }
    chmod(SHIM_SOCK, 0600);
    slog("listening on %s", SHIM_SOCK);
    for (;;) {
        int c = accept(s, NULL, NULL);
        ssize_t n;
        struct job *j;
        if (c < 0)
            continue;
        j = calloc(1, sizeof *j);
        if (!j) {
            close(c);
            continue;
        }
        n = read(c, j->req, sizeof j->req - 1);
        if (n > 0) {
            j->req[n] = 0;
            j->req[strcspn(j->req, "\r\n")] = 0;
            slog("request: %s", j->req);
            post_and_wait(j);
            slog("answer: %s", j->resp);
            dprintf(c, "%s\n", j->resp);
        }
        close(c);
        if (j->done)
            free(j);
    }
    return NULL;
}

__attribute__((constructor)) static void shim_init(void)
{
    char exe[256];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    pthread_t t;

    if (n <= 0)
        return;
    exe[n] = 0;
    if (!strstr(exe, APP_NAME))
        return; /* only inside di-camera-app: inert in every other process */
    g_active = 1;
    slog("loaded into %s (pid %d)", exe, (int)getpid());
    if (pthread_create(&t, NULL, server, NULL) == 0)
        pthread_detach(t);
}
