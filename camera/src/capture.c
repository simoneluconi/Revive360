/*
 * POST /api/v1/capture (protocol §5.3).
 *
 * Synchronous from the client's point of view, asynchronous underneath: the
 * adapter only *requests* the shot (on the C200, `st` injects key events and
 * returns before the file exists). We snapshot the names in every
 * DCIM/<dir>/ before triggering, then poll for new names until the expected
 * kinds are present and every new file has been size-stable for SETTLE_MS, or
 * until CAPTURE_TIMEOUT_MS (-> 504 capture_timeout).
 *
 * Captures are serialized by an in-process mutex plus an flock() on
 * <run_dir>/capture.lock (so a diagnostic tool can see/hold it too). `busy`
 * in /status reflects it.
 *
 * Also POST /api/v1/record (§5.13): start snapshots DCIM, stop waits for the
 * new video file(s) the same way a capture waits for its photo.
 */
#include "server.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#define CAPTURE_TIMEOUT_MS 30000
#define POLL_MS            300
#define SETTLE_MS          1200
#define MODE_VIDEO_FILE    0x100   /* track_done: wait for a video file */

static pthread_mutex_t g_cap_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_lock_fd = -1;

int capture_lock_init(void)
{
    char p[300];
    mkdir_p(g_srv.ad.run_dir, 0755);
    snprintf(p, sizeof p, "%s/capture.lock", g_srv.ad.run_dir);
    g_lock_fd = open(p, O_RDWR | O_CREAT | O_CLOEXEC, 0644);
    if (g_lock_fd < 0)
        LOGW("capture: cannot open %s: %s (in-process lock only)", p, strerror(errno));
    return 0;
}

/* ------------------------------------------------------------ snapshot */

typedef struct {
    char  **names;     /* "100PHOTO/360_0001.JPG", sorted */
    size_t  n, cap;
} nameset_t;

static int cmp_str(const void *a, const void *b)
{
    return strcmp(*(char *const *)a, *(char *const *)b);
}

static void ns_add(nameset_t *s, const char *name)
{
    if (s->n == s->cap) {
        size_t nc = s->cap ? s->cap * 2 : 256;
        char **nv = realloc(s->names, nc * sizeof *nv);
        if (!nv)
            return;
        s->names = nv;
        s->cap = nc;
    }
    s->names[s->n] = strdup(name);
    if (s->names[s->n])
        s->n++;
}

static int ns_has(const nameset_t *s, const char *name)
{
    return bsearch(&name, s->names, s->n, sizeof *s->names, cmp_str) != NULL;
}

static void ns_free(nameset_t *s)
{
    size_t i;
    for (i = 0; i < s->n; i++)
        free(s->names[i]);
    free(s->names);
    memset(s, 0, sizeof *s);
}

/* Names of the regular entries in every DCIM/<dir>/ (no stat: readdir only,
 * cheap even with thousands of files). */
typedef void (*name_cb)(void *ctx, const char *sub, const char *name);

static void scan_dcim(name_cb cb, void *ctx)
{
    char dcim[600];
    DIR *d, *sd;
    struct dirent *de, *fe;
    snprintf(dcim, sizeof dcim, "%s/DCIM", g_srv.ad.storage_root);
    d = opendir(dcim);
    if (!d)
        return;
    while ((de = readdir(d)) != NULL) {
        char sub[900];
        if (de->d_name[0] == '.')
            continue;
        snprintf(sub, sizeof sub, "%s/%s", dcim, de->d_name);
        sd = opendir(sub);
        if (!sd)
            continue;
        while ((fe = readdir(sd)) != NULL)
            if (fe->d_name[0] != '.')
                cb(ctx, de->d_name, fe->d_name);
        closedir(sd);
    }
    closedir(d);
}

static void snap_cb(void *ctx, const char *sub, const char *name)
{
    char rel[600];
    snprintf(rel, sizeof rel, "%s/%s", sub, name);
    ns_add(ctx, rel);
}

/* ------------------------------------------------------------ tracking */

#define MAX_NEW 8
typedef struct {
    const nameset_t *before;
    struct {
        char    rel[600];       /* "DCIM/100PHOTO/360_0042.JPG" */
        int64_t size, mtime;
        int64_t changed_ms;     /* last time the size changed */
        int     seen;           /* seen in the current poll */
    } f[MAX_NEW];
    int n;
    int64_t last_new_ms;
    int64_t now;
} track_t;

static void track_cb(void *ctx, const char *sub, const char *name)
{
    track_t *t = ctx;
    char rel[600], abs[1200];
    struct stat st;
    int i;
    snprintf(rel, sizeof rel, "%s/%s", sub, name);
    if (ns_has(t->before, rel))
        return;
    snprintf(abs, sizeof abs, "%s/DCIM/%s", g_srv.ad.storage_root, rel);
    if (stat(abs, &st) < 0 || !S_ISREG(st.st_mode))
        return;
    for (i = 0; i < t->n; i++) {
        if (strcmp(t->f[i].rel + 5, rel) == 0) {
            if ((int64_t)st.st_size != t->f[i].size) {
                t->f[i].size = (int64_t)st.st_size;
                t->f[i].changed_ms = t->now;
            }
            t->f[i].mtime = (int64_t)st.st_mtime;
            t->f[i].seen = 1;
            return;
        }
    }
    if (t->n >= MAX_NEW)
        return;
    snprintf(t->f[t->n].rel, sizeof t->f[0].rel, "DCIM/%s", rel);
    t->f[t->n].size = (int64_t)st.st_size;
    t->f[t->n].mtime = (int64_t)st.st_mtime;
    t->f[t->n].changed_ms = t->now;
    t->f[t->n].seen = 1;
    t->n++;
    t->last_new_ms = t->now;
    LOGI("capture: new file %s (%lld bytes so far)", t->f[t->n - 1].rel, (long long)st.st_size);
}

static int track_done(const track_t *t, int mode)
{
    int i, photo = 0, raw = 0;
    if (t->n == 0 || t->now - t->last_new_ms < SETTLE_MS)
        return 0;
    for (i = 0; i < t->n; i++) {
        const char *k = file_kind(t->f[i].rel);
        if (t->f[i].size <= 0 || t->now - t->f[i].changed_ms < SETTLE_MS)
            return 0;
        if (strcmp(k, "photo") == 0) photo = 1;
        if (strcmp(k, "raw") == 0) raw = 1;
    }
    switch (mode) {
    case MODE_PHOTO:    return photo;
    case MODE_RAW:      return raw;
    case MODE_RAWPHOTO: return photo && raw;
    case MODE_VIDEO_FILE:
        for (i = 0; i < t->n; i++)
            if (strcmp(file_kind(t->f[i].rel), "video") == 0)
                return 1;
        return 0;
    }
    return 0;
}

/* Poll DCIM for the files new since `before` until track_done(mode) or timeout. */
static int wait_new_files(const nameset_t *before, track_t *t, int mode, int timeout_ms)
{
    int64_t t0 = now_ms_mono();
    int i;
    memset(t, 0, sizeof *t);
    t->before = before;
    while (now_ms_mono() - t0 < timeout_ms) {
        sleep_ms(POLL_MS);
        t->now = now_ms_mono();
        for (i = 0; i < t->n; i++)
            t->f[i].seen = 0;
        scan_dcim(track_cb, t);
        /* Drop entries that vanished (temporary names renamed by the writer). */
        for (i = 0; i < t->n;) {
            if (!t->f[i].seen) {
                LOGI("capture: %s disappeared", t->f[i].rel);
                t->f[i] = t->f[--t->n];
            } else {
                i++;
            }
        }
        if (track_done(t, mode))
            return 1;
    }
    return 0;
}

static void files_json(sbuf_t *b, track_t *t)
{
    sbuf_puts(b, "[");
    for (int i = 0; i < t->n; i++) {
        fentry_t e;
        e.path = t->f[i].rel;
        e.size = t->f[i].size;
        e.mtime = t->f[i].mtime;
        if (i)
            sbuf_puts(b, ",");
        fentry_json(b, &e);
    }
    sbuf_puts(b, "]");
}

/* ------------------------------------------------------------ handler */

static int in_list(const char *v, const char *const *list, int n)
{
    int i;
    for (i = 0; i < n; i++)
        if (strcmp(v, list[i]) == 0)
            return 1;
    return 0;
}

void api_capture(req_t *r)
{
    adapter_t *a = &g_srv.ad;
    jobj_t o;
    const char *mode_s, *quality, *resolution;
    int wrong = 0, mode;
    nameset_t before;
    track_t t;
    char err[256];
    int64_t t0;
    sbuf_t b;
    int ok = 0;

    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    mode_s = jobj_get_str(&o, "mode", &wrong);
    if (!mode_s || wrong) {
        http_send_error(r, 400, "bad_request", "missing or invalid field: mode");
        return;
    }
    quality = jobj_get_str(&o, "quality", &wrong);
    if (wrong) {
        http_send_error(r, 400, "bad_request", "invalid field: quality");
        return;
    }
    resolution = jobj_get_str(&o, "resolution", &wrong);
    if (wrong) {
        http_send_error(r, 400, "bad_request", "invalid field: resolution");
        return;
    }
    mode = mode_from_name(mode_s);
    if (!mode || !(a->capture_modes & mode) || !a->trigger_capture) {
        http_send_error(r, 501, "unsupported", "capture mode '%s' is not supported", mode_s);
        return;
    }
    if (mode == MODE_RAW) {
        quality = NULL;                 /* ignored for raw (protocol §5.3) */
    } else if (quality) {
        if (!in_list(quality, a->qualities, a->n_qualities)) {
            http_send_error(r, 501, "unsupported", "quality '%s' is not supported", quality);
            return;
        }
    } else if (a->n_qualities > 0) {
        quality = a->qualities[0];      /* first = default */
    }
    if (resolution) {
        int current = a->n_resolutions > 0 && strcmp(resolution, a->resolutions[0]) == 0;
        if (!in_list(resolution, a->resolutions, a->n_resolutions) ||
            (!a->resolution_settable && !current)) {
            http_send_error(r, 501, "unsupported", "resolution '%s' is not settable", resolution);
            return;
        }
    }
    if (!adapter_storage_mounted(a)) {
        http_send_error(r, 503, "no_storage", "the SD card is not mounted");
        return;
    }
    if (g_srv.recording) {
        http_send_error(r, 409, "busy", "a video recording is in progress");
        return;
    }
    if (g_liveview) {
        /* The camera's stream runs its camcorder: the client closes the live view first */
        http_send_error(r, 409, "busy", "the live view is on");
        return;
    }

    /* Serialize. */
    if (pthread_mutex_trylock(&g_cap_mu) != 0) {
        http_send_error(r, 409, "busy", "a capture is already in progress");
        return;
    }
    if (g_lock_fd >= 0 && flock(g_lock_fd, LOCK_EX | LOCK_NB) < 0) {
        pthread_mutex_unlock(&g_cap_mu);
        http_send_error(r, 409, "busy", "capture lock held by another process");
        return;
    }
    g_srv.busy = 1;

    /* In another shooting mode the shutter would start a recording (§5.3) */
    err[0] = 0;
    if (settings_ensure_mode("photo", err, sizeof err) < 0) {
        g_srv.busy = 0;
        if (g_lock_fd >= 0)
            flock(g_lock_fd, LOCK_UN);
        pthread_mutex_unlock(&g_cap_mu);
        http_send_error(r, 500, "internal", "cannot switch to photo mode: %s", err);
        return;
    }

    memset(&before, 0, sizeof before);
    scan_dcim(snap_cb, &before);
    if (before.n > 1)
        qsort(before.names, before.n, sizeof *before.names, cmp_str);
    LOGI("capture: start mode=%s quality=%s (%zu files before)", mode_name(mode),
         quality ? quality : "-", before.n);

    t0 = now_ms_mono();
    err[0] = 0;
    if (a->trigger_capture(a, mode, quality, resolution, err, sizeof err) < 0) {
        LOGE("capture: trigger failed: %s", err);
        ns_free(&before);
        g_srv.busy = 0;
        if (g_lock_fd >= 0)
            flock(g_lock_fd, LOCK_UN);
        pthread_mutex_unlock(&g_cap_mu);
        http_send_error(r, 500, "internal", "capture trigger failed: %s", err);
        return;
    }

    ok = wait_new_files(&before, &t, mode, CAPTURE_TIMEOUT_MS);
    ns_free(&before);
    g_srv.busy = 0;
    if (g_lock_fd >= 0)
        flock(g_lock_fd, LOCK_UN);
    pthread_mutex_unlock(&g_cap_mu);

    if (!ok) {
        LOGW("capture: timeout after %d ms (%d new file(s) seen)", CAPTURE_TIMEOUT_MS, t.n);
        http_send_error(r, 504, "capture_timeout",
                        "no complete file after %d s (%d new file(s) seen)", CAPTURE_TIMEOUT_MS / 1000, t.n);
        return;
    }
    LOGI("capture: done in %lld ms, %d file(s)", (long long)(now_ms_mono() - t0), t.n);

    sbuf_init(&b);
    sbuf_puts(&b, "{\"files\":");
    files_json(&b, &t);
    sbuf_printf(&b, ",\"duration_ms\":%lld}", (long long)(now_ms_mono() - t0));
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}

/* ------------------------------------------------------------ record */

static nameset_t g_rec_before;      /* DCIM names when the recording started */

void recording_json(sbuf_t *b)
{
    if (g_srv.recording)
        sbuf_printf(b, "{\"active\":true,\"elapsed_s\":%lld}",
                    (long long)((now_ms_mono() - g_srv.rec_started_ms) / 1000));
    else
        sbuf_puts(b, "{\"active\":false}");
}

static void send_recording(req_t *r, track_t *t)
{
    sbuf_t b;
    sbuf_init(&b);
    sbuf_puts(&b, "{\"recording\":");
    recording_json(&b);
    if (t) {
        sbuf_puts(&b, ",\"files\":");
        files_json(&b, t);
    }
    sbuf_puts(&b, "}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}

static void record_start(req_t *r, adapter_t *a)
{
    char err[256], mode[64];

    if (g_srv.recording) {
        http_send_error(r, 409, "busy", "already recording");
        return;
    }
    if (g_liveview) {
        http_send_error(r, 409, "busy", "the live view is on");
        return;
    }
    if (a->get_setting && a->get_setting(a, "mode", mode, sizeof mode) == 0 &&
        strcmp(mode, "video") != 0 && strcmp(mode, "looping") != 0 && strcmp(mode, "timelapse") != 0) {
        http_send_error(r, 409, "wrong_mode", "recording needs mode video, looping or timelapse (now %s)", mode);
        return;
    }
    ns_free(&g_rec_before);
    scan_dcim(snap_cb, &g_rec_before);
    if (g_rec_before.n > 1)
        qsort(g_rec_before.names, g_rec_before.n, sizeof *g_rec_before.names, cmp_str);
    err[0] = 0;
    if (a->record(a, 1, err, sizeof err) < 0) {
        http_send_error(r, 500, "internal", "record start failed: %s", err);
        return;
    }
    g_srv.rec_started_ms = now_ms_mono();
    g_srv.recording = 1;
    LOGI("record: started");
    send_recording(r, NULL);
}

static void record_stop(req_t *r, adapter_t *a)
{
    char err[256];
    track_t t;
    int ok;

    if (!g_srv.recording) {
        memset(&t, 0, sizeof t);
        send_recording(r, &t);
        return;
    }
    err[0] = 0;
    if (a->record(a, 0, err, sizeof err) < 0) {
        http_send_error(r, 500, "internal", "record stop failed: %s", err);
        return;
    }
    g_srv.busy = 1;     /* the video is being finalized */
    ok = wait_new_files(&g_rec_before, &t, MODE_VIDEO_FILE, CAPTURE_TIMEOUT_MS);
    g_srv.busy = 0;
    g_srv.recording = 0;
    ns_free(&g_rec_before);
    LOGI("record: stopped, %d new file(s)%s", t.n, ok ? "" : " (no finished video seen)");
    send_recording(r, &t);
}

void api_record(req_t *r)
{
    adapter_t *a = &g_srv.ad;
    jobj_t o;
    const char *action;
    int wrong = 0;

    if (!a->record) {
        http_send_error(r, 501, "unsupported", "this camera cannot record through the controller");
        return;
    }
    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    action = jobj_get_str(&o, "action", &wrong);
    if (!action || wrong || (strcmp(action, "start") != 0 && strcmp(action, "stop") != 0)) {
        http_send_error(r, 400, "bad_request", "action must be start or stop");
        return;
    }
    if (!adapter_storage_mounted(a)) {
        http_send_error(r, 503, "no_storage", "the SD card is not mounted");
        return;
    }
    /* Serialized with captures */
    if (pthread_mutex_trylock(&g_cap_mu) != 0) {
        http_send_error(r, 409, "busy", "a capture is in progress");
        return;
    }
    if (strcmp(action, "start") == 0)
        record_start(r, a);
    else
        record_stop(r, a);
    pthread_mutex_unlock(&g_cap_mu);
}
