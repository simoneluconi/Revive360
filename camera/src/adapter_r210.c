/*
 * Adapter "r210": Samsung Gear 360 (2017), SM-R210 (info.ini Model=SMR210;),
 * firmware R210GLU0ARB2 (docs/firmware/15_r210_port.md).
 *
 * Settings, the still and recording go through the shim and the R210 plugin
 * (shim/g360r_plugin_r210.c) to the camera app's own managers: the menu manager
 * AppNet::Menu::CManager for every setting, the stock remote shot
 * CUINETFuncRemoteShot for the still and the movie, as the Samsung phone app
 * drove them. Status, files and time are the generic ones. The camera is kept
 * awake while a client talks to us.
 *
 * LEDs: 7 gpio LEDs /sys/class/leds/led.0..6 (mapped on the hardware,
 * 2026-10-29): led.0-2 charge LED red/green/blue, led.3 the lens LED on the
 * display side green, led.4 the other lens LED green, led.5 and led.6 the same
 * two lens LEDs red. 0-4 mean what they mean on the C200; roles still come
 * from the SD config.
 *
 * TODO(r210):
 *  - RAW (the RTOS has the same ssifdump command as the C200's).
 */
#include "adapter.h"
#include "server.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Tested on ARB2 with the second modified image (2026-10-29): every setting read and
 * written, a still in 2.1 s, a video recorded and stopped, the live stream read */
#define R210_STATUS   "experimental"
#define ST_BIN        "/usr/bin/st"
#define ST_TIMEOUT_MS 10000

/* ------------------------------------------------------------ settings */

/* One protocol setting: the AppNet::Menu::Type of its menu (menu_single when the
 * single lens has its own) and the menu value of each token, from the camera's
 * own lists (plugin `menu TYPE` on ARB2). */
typedef struct {
    const char *id;
    int menu, menu_single;
    int n;
    const char *tok[8];
    int val[8];
    /* the single lens's tokens and values, when it has its own menu */
    int n_single;
    const char *tok_single[4];
    int val_single[4];
} r210_setting_t;

#define MENU_MODE 16
#define MENU_LENS 25
#define LENS_DUAL 0
#define VALUE_NEVER 1000

static const r210_setting_t R210_SETTINGS[] = {
    /* 4 = Landscape HDR, not in the protocol */
    { "mode", MENU_MODE, -1, 4, { "photo", "video", "timelapse", "looping" }, { 1, 0, 2, 3 }, 0, { 0 }, { 0 } },
    /* "Single CAM - F" is 1, "Single CAM - R" 2 */
    { "lens", MENU_LENS, -1, 3, { "dual", "rear", "front" }, { 0, 2, 1 }, 0, { 0 }, { 0 } },
    { "photo_size", 17, 18, 1, { "5472x2736" }, { 0 }, 1, { "2304x1296" }, { 3 } },
    { "video_size", 30, 31, 6,
      { "4096x2048@24", "2880x1440@30", "2560x1280@60", "2560x1280@30", "1920x960@30", "1440x720@120" },
      { 0, 3, 4, 1, 2, 5 },
      3, { "1920x1080@60", "1920x1080@30", "1280x720@30" }, { 9, 7, 8 } },
    { "timer", 29, -1, 4, { "off", "2", "5", "10" }, { 0, 1, 2, 3 }, 0, { 0 }, { 0 } },
    { "timelapse_interval", 26, -1, 7, { "0.5", "1", "2", "5", "10", "30", "60" }, { 0, 1, 2, 3, 4, 6, 7 },
      0, { 0 }, { 0 } },
    { "loop_time", 12, -1, 4, { "5", "30", "60", "max" }, { 0, 1, 2, 3 }, 0, { 0 }, { 0 } },
    { "iso_limit", 8, -1, 3, { "400", "800", "1600" }, { 1, 2, 3 }, 0, { 0 }, { 0 } },
    { "white_balance", 32, -1, 5, { "auto", "daylight", "cloudy", "incandescent", "fluorescent" },
      { 0, 3, 4, 2, 5 }, 0, { 0 }, { 0 } },
    { "hdr", 4, -1, 2, { "off", "on" }, { 0, 1 }, 0, { 0 }, { 0 } },
    { "sharpness", 21, -1, 5, { "-2", "-1", "0", "+1", "+2" }, { 4, 3, 2, 1, 0 }, 0, { 0 }, { 0 } },
    { "wind_cut", 33, -1, 2, { "off", "on" }, { 0, 1 }, 0, { 0 }, { 0 } },
    { "beep", 1, -1, 4, { "off", "low", "mid", "high" }, { 0, 1, 2, 3 }, 0, { 0 }, { 0 } },
    { "led", 11, -1, 2, { "off", "on" }, { 0, 1 }, 0, { 0 }, { 0 } },
    /* "never" is ours: the auto power off held (the awake thread) */
    { "auto_power_off", 0, -1, 5, { "1", "3", "5", "15", "never" }, { 1, 2, 3, 5, VALUE_NEVER }, 0, { 0 }, { 0 } },
};
#define N_R210_SETTINGS ((int)(sizeof R210_SETTINGS / sizeof R210_SETTINGS[0]))

/* video_size has its own menus in time-lapse (10 fps sizes) and looping (one size per lens) */
#define MODE_VALUE_TIMELAPSE 2
#define MODE_VALUE_LOOPING   3
static const r210_setting_t R210_VIDEO_TIMELAPSE = {
    "video_size", 27, 28, 3, { "4096x2048@10", "2560x1280@10", "1920x960@10" }, { 0, 1, 2 },
    2, { "1920x1080@10", "1280x720@10" }, { 7, 8 } };
static const r210_setting_t R210_VIDEO_LOOPING = {
    "video_size", 14, 15, 1, { "2560x1280@30" }, { 1 }, 1, { "1920x1080@30" }, { 7 } };

/* EV: the camera's items are -3.0 ... +3.0 in 0.1 (value 30 = 0.0); the protocol's
 * 0.5 steps are every fifth */
#define MENU_EV 2
static const char *const R210_EV[] = { "-3.0", "-2.5", "-2.0", "-1.5", "-1.0", "-0.5", "0.0", "+0.5",
                                       "+1.0", "+1.5", "+2.0", "+2.5", "+3.0" };
#define N_R210_EV ((int)(sizeof R210_EV / sizeof R210_EV[0]))

static char g_keep_awake_path[300];

static int keep_awake(void)
{
    return access(g_keep_awake_path, F_OK) == 0;
}

static const r210_setting_t *r210_setting(const char *id)
{
    for (int i = 0; i < N_R210_SETTINGS; i++)
        if (strcmp(R210_SETTINGS[i].id, id) == 0)
            return &R210_SETTINGS[i];
    return NULL;
}

/* The menu's current value, from the plugin's `menu TYPE` ("... value=V"...) */
static int menu_value(int type, int *out)
{
    char req[32], reply[2048];
    const char *p;
    snprintf(req, sizeof req, "menu %d", type);
    if (shim_plugin(req, reply, sizeof reply) < 0 || !(p = strstr(reply, " value=")))
        return -1;
    *out = atoi(p + 7);
    return 0;
}

static int menu_set(int type, int value, char *err, size_t errlen)
{
    char req[48], reply[256];
    snprintf(req, sizeof req, "menuset %d %d", type, value);
    if (shim_plugin(req, reply, sizeof reply) < 0) {
        snprintf(err, errlen, "%s", reply);
        return -1;
    }
    LOGI("r210: %s", reply);
    return 0;
}

static int dual_lens(void)
{
    int lens = LENS_DUAL;
    menu_value(MENU_LENS, &lens);
    return lens == LENS_DUAL;
}

/* The menu, tokens and values for the shooting mode and the lens in use */
static void r210_menu(const r210_setting_t *s, int *menu, int *n, const char *const **tok, const int **val)
{
    int mode;
    if (strcmp(s->id, "video_size") == 0 && menu_value(MENU_MODE, &mode) == 0) {
        if (mode == MODE_VALUE_TIMELAPSE)
            s = &R210_VIDEO_TIMELAPSE;
        else if (mode == MODE_VALUE_LOOPING)
            s = &R210_VIDEO_LOOPING;
    }
    if (s->menu_single >= 0 && !dual_lens()) {
        *menu = s->menu_single, *n = s->n_single, *tok = s->tok_single, *val = s->val_single;
    } else {
        *menu = s->menu, *n = s->n, *tok = s->tok, *val = s->val;
    }
}

static int r210_get_setting(adapter_t *a, const char *id, char *out, size_t outlen)
{
    const r210_setting_t *s = r210_setting(id);
    const char *const *tok;
    const int *val;
    int menu, n, v;
    (void)a;
    if (strcmp(id, "ev") == 0) {
        if (menu_value(MENU_EV, &v) < 0)
            return -1;
        v = (v + 2) / 5;   /* the nearest 0.5 step */
        snprintf(out, outlen, "%s", R210_EV[v < 0 ? 0 : v >= N_R210_EV ? N_R210_EV - 1 : v]);
        return 0;
    }
    if (!s)
        return -1;
    if (strcmp(id, "auto_power_off") == 0 && keep_awake()) {
        snprintf(out, outlen, "never");
        return 0;
    }
    r210_menu(s, &menu, &n, &tok, &val);
    if (menu_value(menu, &v) < 0)
        return -1;
    for (int i = 0; i < n; i++) {
        if (val[i] == v) {
            snprintf(out, outlen, "%s", tok[i]);
            return 0;
        }
    }
    LOGW("r210: %s has value %d, which has no protocol token", id, v);
    return -1;
}

/* Photo and video sizes: the lens in use has its own list */
static int r210_setting_available(adapter_t *a, const char *id, const char **out, int max)
{
    const r210_setting_t *s = r210_setting(id);
    const char *const *tok;
    const int *val;
    int menu, n;
    (void)a;
    if (!s || s->menu_single < 0)
        return -1;
    r210_menu(s, &menu, &n, &tok, &val);
    for (int i = 0; i < n && i < max; i++)
        out[i] = tok[i];
    return n < max ? n : max;
}

static int r210_set_setting(adapter_t *a, const char *id, const char *value, char *err, size_t errlen)
{
    const r210_setting_t *s = r210_setting(id);
    const char *const *tok;
    const int *val;
    int menu, n, v = -1;
    (void)a;
    if (strcmp(id, "ev") == 0) {
        for (int i = 0; i < N_R210_EV; i++)
            if (strcmp(R210_EV[i], value) == 0)
                return menu_set(MENU_EV, i * 5, err, errlen);
        snprintf(err, errlen, "no value for ev=%s", value);
        return -1;
    }
    if (!s) {
        snprintf(err, errlen, "unknown setting %s", id);
        return -1;
    }
    r210_menu(s, &menu, &n, &tok, &val);
    for (int i = 0; i < n; i++)
        if (strcmp(tok[i], value) == 0)
            v = val[i];
    if (v < 0) {
        snprintf(err, errlen, "%s=%s is not offered%s", id, value,
                 s->menu_single >= 0 ? " with this lens" : "");
        return -1;
    }
    if (strcmp(id, "auto_power_off") == 0) {
        if (v == VALUE_NEVER) {
            FILE *f = fopen(g_keep_awake_path, "w");
            if (!f) {
                snprintf(err, errlen, "cannot write %s", g_keep_awake_path);
                return -1;
            }
            fclose(f);
            return 0;   /* the awake thread holds it */
        }
        unlink(g_keep_awake_path);
    }
    return menu_set(menu, v, err, errlen);
}

/* ------------------------------------------------------------ state, still, record */

/* One flag of the plugin's `state` ("ok photo=1 video=0 ... recording=0 tlrun=0 ...") */
static int state_flag(const char *name, int *out)
{
    char reply[512], key[24];
    const char *p;
    if (shim_plugin("state", reply, sizeof reply) < 0)
        return -1;
    snprintf(key, sizeof key, " %s=", name);
    if (!(p = strstr(reply, key)))
        return -1;
    *out = atoi(p + strlen(key));
    return 0;
}

/* The stock remote's shot, in photo mode (the app sets the mode first; a still
 * asked in another mode switches to photo, as the camera's own button would not) */
static int r210_capture(adapter_t *a, int mode, const char *quality,
                        const char *resolution, char *err, size_t errlen)
{
    char reply[256];
    int photo = 0;
    (void)a;
    (void)mode;       /* JPEG only (capture_modes) */
    (void)quality;    /* no quality menu on the R210 */
    (void)resolution; /* photo_size setting */
    if (state_flag("photo", &photo) < 0) {
        snprintf(err, errlen, "cannot read the camera's mode");
        return -1;
    }
    if (!photo) {
        if (menu_set(MENU_MODE, 1, err, errlen) < 0)
            return -1;
        for (int i = 0; i < 10 && !photo; i++) {
            sleep_ms(300);
            state_flag("photo", &photo);
        }
        if (!photo) {
            snprintf(err, errlen, "the camera did not switch to photo");
            return -1;
        }
    }
    if (shim_plugin("shot", reply, sizeof reply) < 0) {
        snprintf(err, errlen, "%s", reply);
        return -1;
    }
    LOGI("r210: still requested");
    return 0;
}

/* Recording or not: the movie, or in time-lapse its interval capture */
static int r210_recording(int *out)
{
    int rec = 0, tl = 0;
    if (state_flag("recording", &rec) < 0)
        return -1;
    state_flag("tlrun", &tl);
    *out = rec || tl > 0;
    return 0;
}

static int r210_record(adapter_t *a, int start, char *err, size_t errlen)
{
    char reply[256];
    int rec = -1;
    (void)a;
    if (r210_recording(&rec) < 0) {
        snprintf(err, errlen, "cannot read the recording state");
        return -1;
    }
    LOGI("r210: record %s, recording %d", start ? "start" : "stop", rec);
    if (rec == start)
        return 0;
    if (shim_plugin(start ? "recstart" : "recstop", reply, sizeof reply) < 0) {
        snprintf(err, errlen, "%s", reply);
        return -1;
    }
    if (start) {
        /* The remote shot refuses silently (no card, busy, photo mode ...) */
        for (int i = 0; i < 10; i++) {
            sleep_ms(300);
            if (r210_recording(&rec) == 0 && rec)
                return 0;
        }
        snprintf(err, errlen, "the recording did not start");
        return -1;
    }
    return 0;
}

/* ------------------------------------------------------------ live view */

/* As on the C200: the stock remote viewfinder (CUINETDlnaMan, started by the
 * plugin without its own network bring-up) serves Samsung's TTTS stream on
 * 7679. ARB2 serves /livestream_high.avi only (2560x1280, 15 fps; _middle and
 * _low answer 400), so every quality gets that one. As on the C200 the stream's
 * camcorder session must be stopped between clients (CUINETFuncDLNA::
 * dlna_mpeg_stop), or the next one gets the header only or nothing. But while
 * the DLNA stack still sends to a client that left ("Cannot send data", until
 * "Streaming Failed") that stop holds the camera app's main loop for up to a
 * minute: so the service is stopped first (its sockets closed), then the
 * session, both quick (tested on ARB2), and the service started again. */
static void r210_stream_stop(void)
{
    char reply[256];
    shim_plugin("dlnastop", reply, sizeof reply);
    shim_plugin("mpegstop", reply, sizeof reply);
}
#define LIVEVIEW_PORT 7679

static int r210_liveview_open(adapter_t *a, const char *quality, char *host, size_t hostlen, int *port,
                              char *path, size_t pathlen, char *err, size_t errlen)
{
    char reply[256];
    const char *u;
    (void)a;
    (void)quality;
    r210_stream_stop();
    /* "ok dlna was=0 now=1 url=http://192.168.107.1:7676/smp_2_" */
    if (shim_plugin("dlnastart", reply, sizeof reply) < 0 || !(u = strstr(reply, "url=http://"))) {
        snprintf(err, errlen, "the camera's stream service did not start: %s", reply);
        return -1;
    }
    u += strlen("url=http://");
    snprintf(host, hostlen, "%.*s", (int)strcspn(u, ":/"), u);
    *port = LIVEVIEW_PORT;
    snprintf(path, pathlen, "/livestream_high.avi");
    return 0;
}

static void r210_liveview_close(adapter_t *a)
{
    (void)a;
    r210_stream_stop();
}

/* ------------------------------------------------------------ power timer */

/* The camera switches itself off after its auto power off time. `st app dev
 * power` makes the camera app skip that (CAPPDataManager's disable-auto-power-off
 * flag, "[SET] AutoPower Timer Disable"); a bare `st app dev` clears the flag
 * again. Both also make the app save its preferences, so they are sent only when
 * the state changes, plus a reminder every REASSERT_S while held, not on every
 * tick as the C200's shim call is. Held while a client talks to us, while
 * recording, and always with auto_power_off "never". */
#define AWAKE_PERIOD_S  15
#define CLIENT_AWAKE_MS 20000
#define REASSERT_S      600

static int st_dev(const char *arg)
{
    char *argv_hold[] = { ST_BIN, "app", "dev", "power", NULL };
    char *argv_free[] = { ST_BIN, "app", "dev", NULL };
    char out[256];
    int rc = run_cmd(arg ? argv_hold : argv_free, ST_TIMEOUT_MS, out, sizeof out);
    LOGI("r210: st app dev%s%s -> rc=%d", arg ? " " : "", arg ? arg : "", rc);
    return rc == -1 ? -1 : 0;
}

static void *r210_awake_thread(void *arg)
{
    int held = 0;
    int64_t held_at = 0;
    (void)arg;
    for (;;) {
        int64_t now = now_ms_mono();
        int client = g_srv.last_request_ms && now - g_srv.last_request_ms < CLIENT_AWAKE_MS;
        if (keep_awake() || client || g_liveview || g_srv.recording) {
            if (!held || now - held_at >= REASSERT_S * 1000LL) {
                if (st_dev("power") == 0) {
                    if (!held)
                        LOGI("r210: auto power off held off (%s)", keep_awake() ? "never" : "a client");
                    held = 1;
                    held_at = now;
                }
            }
        } else if (held) {
            /* Back to the camera's own auto power off time */
            if (st_dev(NULL) == 0)
                LOGI("r210: auto power off running again");
            held = 0;
        }
        sleep(AWAKE_PERIOD_S);
    }
    return NULL;
}

void adapter_r210_init(adapter_t *a, const config_t *cfg)
{
    a->id = "r210";
    a->status = R210_STATUS;
    snprintf(a->model, sizeof a->model, "SM-R210");
    snprintf(a->name_prefix, sizeof a->name_prefix, "Gear 360 2017");
    /* Same Tizen 2.2 storage layout as the C200 (checked on ARB2); overridable. */
    snprintf(a->storage_root, sizeof a->storage_root, "%s",
             config_get(cfg, "storage_root", "/opt/storage/sdcard"));
    snprintf(a->mount_point, sizeof a->mount_point, "%s",
             config_get(cfg, "storage_mount", "/opt/storage/sdcard"));
    a->n_qualities = 0;
    a->n_resolutions = 0;
    a->n_video_modes = 0;
    a->files_delete = 1;
    a->thumbnails = 1;
    adapter_discover_leds(a, "/sys/class/leds", 7, cfg);
    /* As on the C200, the camera shows its clock through a fixed +9 h zone (ARB2:
     * set to 19:33 UTC, its log read 04:33): timeset.c compensates */
    a->clock_local = 1;
    /* Reboot and power off through systemd (/usr/bin/systemctl, power.c) */
    a->power = 1;
    snprintf(g_keep_awake_path, sizeof g_keep_awake_path, "%s/g360r/keep_awake", a->storage_root);
    /* Capture, settings and recording need the shim, which only the second R210
     * image (shim-r210) loads into the camera app */
    if (access("/usr/lib/libg360r_shim.so", F_OK) == 0 && config_get_int(cfg, "r210_control", 1)) {
        a->capture_modes = MODE_PHOTO;
        a->trigger_capture = r210_capture;
        for (int i = 0; i < N_R210_SETTINGS; i++) {
            const r210_setting_t *s = &R210_SETTINGS[i];
            const r210_setting_t *more[] = { &R210_VIDEO_TIMELAPSE, &R210_VIDEO_LOOPING };
            const char *all[MAX_SETTING_VALUES];
            int n = 0;
            for (int j = 0; j < s->n && n < MAX_SETTING_VALUES; j++)
                all[n++] = s->tok[j];
            for (int j = 0; j < s->n_single && n < MAX_SETTING_VALUES; j++)
                all[n++] = s->tok_single[j];
            /* video_size: every mode's sizes, each once */
            for (int k = 0; strcmp(s->id, "video_size") == 0 && k < 2; k++) {
                const r210_setting_t *m = more[k];
                for (int j = 0; j < m->n + m->n_single && n < MAX_SETTING_VALUES; j++) {
                    const char *t = j < m->n ? m->tok[j] : m->tok_single[j - m->n];
                    int seen = 0;
                    for (int q = 0; q < n; q++)
                        seen |= strcmp(all[q], t) == 0;
                    if (!seen)
                        all[n++] = t;
                }
            }
            adapter_def_setting(a, s->id, all, n);
        }
        adapter_def_setting(a, "ev", R210_EV, N_R210_EV);
        a->get_setting = r210_get_setting;
        a->set_setting = r210_set_setting;
        a->setting_available = r210_setting_available;
        a->record = r210_record;
        if (config_get_int(cfg, "liveview_enable", 1)) {
            a->liveview_open = r210_liveview_open;
            a->liveview_close = r210_liveview_close;
        }
    } else {
        a->status = "stub";
        a->capture_modes = 0;
        a->trigger_capture = NULL;
    }
    if (access(ST_BIN, X_OK) == 0 && config_get_int(cfg, "keep_awake_enable", 1)) {
        pthread_t th;
        pthread_attr_t at;
        pthread_attr_init(&at);
        pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
        pthread_attr_setstacksize(&at, 128 * 1024);
        pthread_create(&th, &at, r210_awake_thread, NULL);
        pthread_attr_destroy(&at);
    }
}
