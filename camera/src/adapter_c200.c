/*
 * Adapter "c200": Samsung Gear 360 (2016), SM-C200, firmware APE4 and AQK1.
 *
 * Mechanisms (docs/firmware/03, 08, 09) - all by name/path, no addresses:
 *  - capture: JPEG only, at the camera's fixed quality. The 360 pipeline has no
 *    quality or RAW setting (its settings are size, movie size, lens, timer,
 *    format); `st app nx capture quality` writes the NX menu it never reads
 *    (no size change even on the physical shutter, 2026-10-24). The shot
 *    goes through dfms: dfmstool --script "capture still" sets the vconf key
 *    memory/dfms/script_cmd, which the dfmsd that ran our SD hook keeps watching
 *    and forwards to di-camera-app's dfms_app_cmd_capture. (`st app nx capture run`
 *    only injects shutter keys into the normal UI, which does not run in factory
 *    mode: Test 1h produced no file, nor in normal mode on the modified
 *    firmware.) dfmsd runs in normal mode too, so dfms is the shot in both.
 *    Both are ASYNC: the caller polls DCIM.
 *    Every call has a hard timeout.
 *  - storage: exFAT card mounted at /opt/storage/sdcard (/sdcard -> /mnt/mmc ->
 *    /opt/storage/sdcard). Files: DCIM/<nnn>PHOTO/360_XXXX.JPG.
 *  - LEDs: 5 gpio LEDs /sys/class/leds/led.0..4: led.0-2 charge LED R/G/B,
 *    led.3 lens LED on the display side, led.4 the other lens LED (mapped on
 *    the hardware); roles still come from the SD config.
 *  - battery: /sys/class/power_supply/<name> (sec-battery "battery", "adc_battery").
 *
 * adapter_status is "experimental" until the hardware tests in camera/README.md
 * pass on the firmware; then flip C200_STATUS to "supported".
 */
#include "server.h"

#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Tested on AQK1 with the modified firmware: capture, files, time, LEDs,
 * settings and recording (2026-10-25) */
#define C200_STATUS "supported"
#define ST_BIN      "/usr/bin/st"
#define ST_TIMEOUT_MS 10000
#define DFMSTOOL_BIN  "/usr/bin/dfmstool"
#define VCONFTOOL_BIN "/usr/bin/vconftool"

static void log_app_result(const char *what, int rc, const char *out)
{
    char res[128];
    if (read_text_file("/tmp/app_result", res, sizeof res) < 0)
        snprintf(res, sizeof res, "(none)");
    LOGI("c200: st %s -> rc=%d out=[%s] app_result=[%s]", what, rc, out, res);
}

static int st_call(char *const argv[], const char *what, char *err, size_t errlen)
{
    char out[256];
    int rc;
    unlink("/tmp/app_result");
    rc = run_cmd(argv, ST_TIMEOUT_MS, out, sizeof out);
    log_app_result(what, rc, out);
    if (rc == -1) {
        snprintf(err, errlen, "cannot run %s", ST_BIN);
        return -1;
    }
    if (rc == -2) {
        /* st blocks on the result semaphore if the app does not answer. The
         * request may still have been delivered, so this is only a warning:
         * the DCIM poll decides. */
        LOGW("c200: st %s timed out after %d ms", what, ST_TIMEOUT_MS);
    }
    return 0;
}

/* Whether a dfmsd is running: it must be, to receive the dfms capture command. */
static int dfmsd_running(void)
{
    DIR *d = opendir("/proc");
    struct dirent *de;
    int found = 0;
    if (!d)
        return 0;
    while (!found && (de = readdir(d)) != NULL) {
        char p[64], comm[32];
        if (!isdigit((unsigned char)de->d_name[0]))
            continue;
        snprintf(p, sizeof p, "/proc/%s/comm", de->d_name);
        if (read_text_file(p, comm, sizeof comm) > 0 && strcmp(comm, "dfmsd") == 0)
            found = 1;
    }
    closedir(d);
    return found;
}

/* The shutter through dfms (`capture still`); returns once requested */
static int dfms_capture_still(char *err, size_t errlen)
{
    char out[256];
    char *vargv[] = { VCONFTOOL_BIN, "get", "memory/dfms/script_cmd", NULL };
    char *argv[] = { DFMSTOOL_BIN, "--script", "capture still", NULL };
    int rc;
    if (access(DFMSTOOL_BIN, X_OK) != 0) {
        snprintf(err, errlen, "%s not found", DFMSTOOL_BIN);
        return -1;
    }
    if (!dfmsd_running()) {
        snprintf(err, errlen, "no dfmsd running to take the capture command");
        return -1;
    }
    rc = run_cmd(vargv, ST_TIMEOUT_MS, out, sizeof out);
    LOGI("c200: dfmsd listening, %s (rc=%d)", out, rc);
    rc = run_cmd(argv, ST_TIMEOUT_MS, out, sizeof out);
    LOGI("c200: dfmstool --script \"capture still\" -> rc=%d out=[%s]", rc, out);
    if (rc == -1) {
        snprintf(err, errlen, "cannot run %s", DFMSTOOL_BIN);
        return -1;
    }
    return 0;
}

static int c200_capture(adapter_t *a, int mode, const char *quality,
                        const char *resolution, char *err, size_t errlen)
{
    /* The shot always goes through dfms: dfmsd runs in normal mode too, and
     * there `capture still` works (8.9 s) while `st app nx capture run` writes
     * nothing (both tested on the modified firmware). Factory mode (SD hook)
     * also needs the live view asked once; normal mode (boot.sh exports
     * G360R_MODE=normal) has it already. */
    const char *run_mode = getenv("G360R_MODE");
    int factory = !(run_mode && strcmp(run_mode, "normal") == 0);
    (void)mode;       /* JPEG only (capture_modes) */
    (void)quality;    /* fixed in the 360 pipeline */
    (void)resolution; /* not settable in v1 */

    if (factory && access(ST_BIN, X_OK) != 0) {
        snprintf(err, errlen, "%s not found", ST_BIN);
        return -1;
    }
    LOGI("c200: capture via dfms (%s mode)", factory ? "factory" : "normal");
    /* Factory mode only: 360 photo mode, and the live view asked once (dfms
     * `capture still` needs a live pipeline, Test 2). In normal mode both are
     * there already, and `st app mode_360` has no visible effect anyway. */
    if (factory) {
        /* A marker file, not a static: each request runs in a forked child, and
         * `show liveview` toggles, so it must be sent once per controller run. */
        char marker[300];
        char *mode_argv[] = { ST_BIN, "app", "mode_360", "photo", NULL };
        char *lv_argv[] = { ST_BIN, "app", "show", "liveview", NULL };
        snprintf(marker, sizeof marker, "%s/liveview_asked", a->run_dir);
        if (st_call(mode_argv, "mode_360 photo", err, errlen) < 0)
            return -1;
        if (!file_exists(marker)) {
            write_text_file(marker, "1\n");
            if (st_call(lv_argv, "show liveview", err, errlen) < 0)
                return -1;
            sleep_ms(1000);
        }
    }
    return dfms_capture_still(err, errlen);
}

/* ------------------------------------------------------------ settings */

/* Protocol token <-> the app's UI value, per UI key (AQK1, from the app's
 * menu tables, docs/firmware/12). Read with UI_Get_Value, written the way the
 * app's own menus do (UI_Change_Menu_Value), through the shim plugin. */
typedef struct {
    const char *id;
    int key;
    int n;
    const char *tok[10];
    int val[10];
} c200_setting_t;

static const c200_setting_t C200_SETTINGS[] = {
    { "mode", 0x21c, 4, { "photo", "video", "timelapse", "looping" }, { 1, 0, 2, 3 } },
    { "lens", 0x21a, 3, { "dual", "rear", "front" }, { 0, 1, 2 } },
    { "photo_size", 0xcf, 4, { "dual_large", "dual_small", "single_large", "single_wide" }, { 0, 1, 3, 5 } },
    /* eUS_MOVIE_SIZE; 3, 4 and 8 are in the firmware's menu but not the stock app's */
    { "video_size", 0x223, 9,
      { "3840x1920@30", "2880x1440@30", "2560x1440@30", "2560x1280@60", "2560x1280@30",
        "1920x1080@60", "1920x1080@30", "1920x960@30", "1280x720@30" },
      { 0, 3, 5, 4, 1, 8, 6, 2, 7 } },
    { "timer", 0x225, 4, { "off", "2", "5", "10" }, { 0, 1, 2, 3 } },
    { "timelapse_interval", 0x224, 8, { "0.5", "1", "2", "5", "10", "20", "30", "60" },
      { 0, 1, 2, 3, 4, 5, 6, 7 } },
    { "loop_time", 0x21b, 4, { "5", "20", "60", "max" }, { 0, 1, 2, 3 } },
};
#define N_C200_SETTINGS ((int)(sizeof C200_SETTINGS / sizeof C200_SETTINGS[0]))

#define KEY_MODE        0x21c   /* the 360 mode selection, values as in C200_SETTINGS */
#define MODE_VALUE_PHOTO     1
#define MODE_VALUE_TIMELAPSE 2
#define KEY_LENS        0x21a
#define LENS_VALUE_DUAL      0
#define KEY_MOVIE_SIZE_MENU  0x223   /* the menu's choice (video_size) */
#define KEY_MOVIE_SIZE       0x109   /* what the recorder realizes, eUS_MOVIE_SIZE */
#define KEY_TIMELAPSE_SIZE   0x22a   /* the same for the time-lapse */

/* Video and looping record from the movie standby */
static int movie_mode(int mode)
{
    return mode != MODE_VALUE_PHOTO && mode != MODE_VALUE_TIMELAPSE && mode >= 0;
}

static const c200_setting_t *c200_setting(const char *id)
{
    for (int i = 0; i < N_C200_SETTINGS; i++)
        if (strcmp(C200_SETTINGS[i].id, id) == 0)
            return &C200_SETTINGS[i];
    return NULL;
}

static int c200_get_setting(adapter_t *a, const char *id, char *out, size_t outlen)
{
    const c200_setting_t *s = c200_setting(id);
    int v;
    (void)a;
    if (!s || shim_ui_get(s->key, &v) < 0)
        return -1;
    for (int i = 0; i < s->n; i++) {
        if (s->val[i] == v) {
            snprintf(out, outlen, "%s", s->tok[i]);
            return 0;
        }
    }
    LOGW("c200: %s has value %d, which has no protocol token", id, v);
    return -1;
}

/* The recorder realizes 0x109 (0x22a in time-lapse), which a mode or lens
 * change resets to the lens default, not the menu's 0x223 (the same
 * eUS_MOVIE_SIZE values). Copy the menu's size over when it fits the lens
 * (2:1 dual, the others single); otherwise the menu follows the recorder. */
static int c200_movie_size_sync(char *err, size_t errlen)
{
    const c200_setting_t *s = c200_setting("video_size");
    char req[64], reply[256] = "cannot read the recorder's movie size";
    int menu = -1, lens = -1, w = 0, h = 0, fits = 0;

    if (shim_ui_get(KEY_MOVIE_SIZE_MENU, &menu) < 0 || shim_ui_get(KEY_LENS, &lens) < 0) {
        snprintf(err, errlen, "cannot read the movie size or lens");
        return -1;
    }
    for (int i = 0; i < s->n; i++)
        if (s->val[i] == menu && sscanf(s->tok[i], "%dx%d", &w, &h) == 2)
            fits = (w == 2 * h) == (lens == LENS_VALUE_DUAL);
    if (fits) {
        snprintf(req, sizeof req, "uiset 0x%x %d", KEY_MOVIE_SIZE, menu);
        if (shim_plugin(req, reply, sizeof reply) == 0) {
            snprintf(req, sizeof req, "uiset 0x%x %d", KEY_TIMELAPSE_SIZE, menu);
            if (shim_plugin(req, reply, sizeof reply) == 0)
                return 0;
        }
    } else {
        int rec = -1;
        if (shim_ui_get(KEY_MOVIE_SIZE, &rec) == 0) {
            LOGI("c200: movie size %d does not fit lens %d, menu follows the recorder (%d)", menu, lens, rec);
            snprintf(req, sizeof req, "uiset 0x%x %d", KEY_MOVIE_SIZE_MENU, rec);
            if (shim_plugin(req, reply, sizeof reply) == 0)
                return 0;
        }
    }
    snprintf(err, errlen, "%s", reply);
    return -1;
}

static int c200_set_setting(adapter_t *a, const char *id, const char *value, char *err, size_t errlen)
{
    const c200_setting_t *s = c200_setting(id);
    char req[64], reply[256];
    int v = -1, prev_mode = MODE_VALUE_PHOTO;
    (void)a;

    for (int i = 0; s && i < s->n; i++)
        if (strcmp(s->tok[i], value) == 0)
            v = s->val[i];
    if (v < 0) {
        snprintf(err, errlen, "no value for %s=%s", id, value);
        return -1;
    }
    if (strcmp(id, "mode") == 0)
        shim_ui_get(KEY_MODE, &prev_mode);
    if (strcmp(id, "lens") == 0)
        snprintf(req, sizeof req, "lensswitch %d", v);   /* the settings menu's own lens switch */
    else
        snprintf(req, sizeof req, "menu 0x%x %d", s->key, v);
    if (shim_plugin(req, reply, sizeof reply) < 0) {
        snprintf(err, errlen, "%s", reply);
        return -1;
    }
    /* A menu value alone does not re-realize the capture/record engine: run
     * the transition the camera's own menu runs (a2, docs/firmware/12). Photo
     * and time-lapse run in the photo live view (the time-lapse is an interval
     * capture of stills, its menu switch keeps the live view); video and
     * looping in the movie standby. */
    if (strcmp(id, "mode") == 0) {
        int movie = movie_mode(v), was_movie = movie_mode(prev_mode);
        const char *transition;
        if (movie)
            /* from a movie mode the standby is already on: a stop first */
            transition = was_movie ? "restandby" : "moviestandby";
        else
            /* from a movie mode only the movie stop path leaves its sub-state
             * (it ends in the photo live view; a live view restart alone left
             * the next shutter as a movie stop, no photo) */
            transition = was_movie ? "recstop" : "photomode";
        if (shim_plugin("allsend", reply, sizeof reply) < 0 ||
            (v != MODE_VALUE_PHOTO && c200_movie_size_sync(reply, sizeof reply) < 0) ||
            shim_plugin(transition, reply, sizeof reply) < 0) {
            snprintf(err, errlen, "%s", reply);
            return -1;
        }
    } else if (strcmp(id, "lens") == 0 || strcmp(id, "video_size") == 0) {
        /* The movie size follows the lens and the menu; a movie mode realizes
         * it on a new standby, the time-lapse when it records, photo on the
         * next switch to a video mode */
        int mode = -1;
        if (shim_ui_get(KEY_MODE, &mode) < 0 || mode == MODE_VALUE_PHOTO)
            return 0;
        if (c200_movie_size_sync(reply, sizeof reply) < 0 ||
            (movie_mode(mode) && shim_plugin("restandby", reply, sizeof reply) < 0)) {
            snprintf(err, errlen, "%s", reply);
            return -1;
        }
    }
    return 0;
}

/* ------------------------------------------------------------ record */

/* Recording or not: the movie state in video/looping, the interval capture
 * in time-lapse (plugin "recording") */
static int c200_recording(int *out)
{
    char reply[256];
    return shim_plugin("recording", reply, sizeof reply) == 0 &&
           sscanf(reply, "ok recording = %d", out) == 1 ? 0 : -1;
}

/* Video and looping: the app's movie entries (the stock remote shot refused
 * them after a new standby, "Recording..."). Time-lapse: the stock phone app's
 * remote shot, which starts and stops its interval capture (the movie entries
 * alone skip it). */
static int c200_record(adapter_t *a, int start, char *err, size_t errlen)
{
    int rec = -1, mode = -1, timelapse;
    char reply[256];
    (void)a;

    if (c200_recording(&rec) < 0 || shim_ui_get(KEY_MODE, &mode) < 0) {
        snprintf(err, errlen, "cannot read the recording state");
        return -1;
    }
    timelapse = mode == MODE_VALUE_TIMELAPSE;
    LOGI("c200: record %s, mode %d, recording %d", start ? "start" : "stop", mode, rec);
    if (rec == start)
        return 0;   /* already where we want it */
    if (shim_plugin(timelapse ? (start ? "remoterec" : "remotestop") : (start ? "recstart" : "recstop"),
                    reply, sizeof reply) < 0) {
        snprintf(err, errlen, "%s", reply);
        return -1;
    }
    if (start) {
        /* Confirm, so a mode that does not record is reported */
        for (int i = 0; i < 10; i++) {
            sleep_ms(300);
            if (c200_recording(&rec) == 0 && rec)
                return 0;
        }
        snprintf(err, errlen, "the recording did not start");
        return -1;
    }
    return 0;
}

void adapter_c200_init(adapter_t *a, const config_t *cfg)
{
    static const char *const qualities[] = { "sfine" };
    static const char *const resolutions[] = { "7776x3888", "3072x1728" };
    static const char *const video[] = { "3840x1920@30", "2560x1280@30", "1920x960@30" };
    int i;

    a->id = "c200";
    a->status = C200_STATUS;
    snprintf(a->model, sizeof a->model, "SM-C200");
    snprintf(a->name_prefix, sizeof a->name_prefix, "Gear 360");
    snprintf(a->storage_root, sizeof a->storage_root, "%s",
             config_get(cfg, "storage_root", "/opt/storage/sdcard"));
    snprintf(a->mount_point, sizeof a->mount_point, "%s",
             config_get(cfg, "storage_mount", "/opt/storage/sdcard"));

    /* JPEG at one fixed quality: the 360 pipeline has no RAW and ignores the
     * quality menu (docs/firmware/08). */
    a->capture_modes = MODE_PHOTO;
    if (!config_get_int(cfg, "capture_enable", 1))
        a->capture_modes = 0;
    a->qualities[0] = qualities[0];
    a->n_qualities = 1;
    for (i = 0; i < 2; i++) a->resolutions[i] = resolutions[i];
    a->n_resolutions = 2;
    a->resolution_settable = 0;
    for (i = 0; i < 3; i++) a->video_modes[i] = video[i];
    a->n_video_modes = 3;
    a->files_delete = 1;
    a->thumbnails = 1;
    /* The camera shows its clock through a fixed zone of its own (AQK1: +9 h
     * in EXIF and libc localtime, first normal-mode test): timeset.c sets the
     * clock so that the camera shows the phone's local time. */
    a->clock_local = 1;
    a->trigger_capture = c200_capture;
    /* Settings and recording go through the shim inside di-camera-app, which
     * only the modified firmware loads (normal mode) */
    {
        const char *run_mode = getenv("G360R_MODE");
        if (run_mode && strcmp(run_mode, "normal") == 0 && config_get_int(cfg, "settings_enable", 1)) {
            for (i = 0; i < N_C200_SETTINGS; i++)
                adapter_add_360_setting(a, C200_SETTINGS[i].id);
            a->get_setting = c200_get_setting;
            a->set_setting = c200_set_setting;
            a->record = c200_record;
        }
    }
    adapter_discover_leds(a, "/sys/class/leds", 5, cfg);
}
