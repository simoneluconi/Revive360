/*
 * Adapter "c200": Samsung Gear 360 (2016), SM-C200, firmware APE4 and AQK1.
 *
 * Mechanisms (docs/firmware/03, 08, 09) - all by name/path, no addresses:
 *  - capture: JPEG at the camera's fixed quality. The 360 pipeline has no
 *    quality or RAW setting (its settings are size, movie size, lens, timer,
 *    format; the sensor data comes from an RTOS debug dump instead, see "RAW
 *    dumps"); `st app nx capture quality` writes the NX menu it never reads
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
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* Tested on AQK1 with the modified firmware: capture, files, time, LEDs,
 * settings and recording (2026-10-25) */
#define C200_STATUS "supported"
#define ST_BIN      "/usr/bin/st"
#define ST_TIMEOUT_MS 10000
#define DFMSTOOL_BIN  "/usr/bin/dfmstool"
#define VCONFTOOL_BIN "/usr/bin/vconftool"

static int raw_saving(void);
static void raw_arm(void);

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
    if (!factory && raw_saving())
        raw_arm();   /* the still's recipe reads the flag when it is built */
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

/* ------------------------------------------------------------ RAW dumps */

/* "raw" on: for every 360 still the A7 RTOS also writes each lens's sensor data
 * (Bayer RGGB, 12 bit left-aligned in 16, 3888x3888 from row 40) with its own
 * SSIF debug dump, in normal mode (st cap back unitest ssifdump on). Kept on the
 * card (raw_save), like keep_awake:
 *  - the dump flag is RTOS RAM, cleared at every boot and when its capture back
 *    end restarts: a thread watches it and re-arms it, and so does every still
 *    taken through us;
 *  - the plugin's rawfix makes the rear lens's file come out under its name;
 *  - the RTOS writes the dumps into the card root, row by row: once complete
 *    they move to DCIM/RAW.
 * Dual-lens photos in good light only: the single-lens, time-lapse and high-ISO
 * still pipelines have no dump. */
#define RAW_PERIOD_MS    2000
#define RAW_REFRESH_MS   30000    /* rawfix again, and the flag when it cannot be read */
#define RAW_SETTLE_S     2        /* a complete dump left alone this long is done */
#define RAW_DIR          "DCIM/RAW"
/* AQK1: the back end's uniTest singleton 0x8160f788, flag at +0x19 (RTOS RAM,
 * mapped 1:1 in /dev/mem) */
#define RAW_FLAG_PHYS    0x8160f7a1UL

static char g_raw_save_path[300];
static int g_raw_flag_readable;   /* the firmware is AQK1 */

static int raw_saving(void)
{
    return g_raw_save_path[0] && access(g_raw_save_path, F_OK) == 0;
}

/* The RTOS dump flag: 0/1, or -1 when it cannot be read */
static int raw_flag(void)
{
    long page = sysconf(_SC_PAGESIZE);
    unsigned long base = RAW_FLAG_PHYS & ~(unsigned long)(page - 1);
    volatile unsigned char *p;
    int fd, v;

    if (!g_raw_flag_readable || (fd = open("/dev/mem", O_RDONLY | O_SYNC)) < 0)
        return -1;
    p = mmap(NULL, (size_t)page, PROT_READ, MAP_SHARED, fd, (off_t)base);
    close(fd);
    if (p == MAP_FAILED)
        return -1;
    v = p[RAW_FLAG_PHYS - base];
    munmap((void *)p, (size_t)page);
    return v <= 1 ? v : -1;
}

static void raw_st(int on)
{
    char out[256];
    char *argv[] = { ST_BIN, "cap", "back", "unitest", "ssifdump", on ? "on" : "off", NULL };
    int rc = run_cmd(argv, ST_TIMEOUT_MS, out, sizeof out);
    LOGI("c200: RAW dump flag %s -> rc=%d", on ? "on" : "off", rc);
}

/* rawfix, and the dump flag on unless it reads on already */
static void raw_arm(void)
{
    char reply[256];
    if (shim_plugin("rawfix", reply, sizeof reply) < 0)
        LOGW("c200: rawfix: %s", reply);
    if (raw_flag() != 1)
        raw_st(1);
}

/* Moves the complete dumps from the card root to DCIM/RAW. The name keeps the
 * RTOS's AxBxC (row bytes x width x rows, the size check here and the DNG
 * converter's input), without its parentheses; its counter restarts at every
 * boot, so a name already taken gets a "-N". */
static void raw_collect(const adapter_t *a)
{
    DIR *d = opendir(a->storage_root);
    struct dirent *de;
    char dir[600];

    if (!d)
        return;
    snprintf(dir, sizeof dir, "%s/" RAW_DIR, a->storage_root);
    while ((de = readdir(d)) != NULL) {
        char from[600], name[256], to[900], *paren;
        unsigned long rowbytes, width, rows;
        struct stat st;
        size_t n;

        if (strncmp(de->d_name, "ssif_", 5) != 0 || !str_iendswith(de->d_name, ".raw") ||
            sscanf(strchr(de->d_name + 5, '_') ? strchr(de->d_name + 5, '_') + 1 : "", "%lux%lux%lu",
                   &rowbytes, &width, &rows) != 3)
            continue;
        snprintf(from, sizeof from, "%s/%s", a->storage_root, de->d_name);
        if (stat(from, &st) < 0 || (unsigned long long)st.st_size != (unsigned long long)rowbytes * rows ||
            time(NULL) - st.st_mtime < RAW_SETTLE_S)
            continue;   /* still being written */
        snprintf(name, sizeof name, "%s", de->d_name);
        if ((paren = strchr(name, '(')) != NULL) {   /* "_10bit(16bit).raw" -> "_10bit_16bit.raw" */
            *paren = '_';
            if ((paren = strchr(paren, ')')) != NULL)
                memmove(paren, paren + 1, strlen(paren));
        }
        n = strlen(name) - 4;   /* before ".raw" */
        mkdir_p(dir, 0755);
        snprintf(to, sizeof to, "%s/%s", dir, name);
        for (int i = 1; file_exists(to) && i < 1000; i++)
            snprintf(to, sizeof to, "%s/%.*s-%d.raw", dir, (int)n, name, i);
        if (rename(from, to) == 0)
            LOGI("c200: RAW dump %s -> %s", de->d_name, to + strlen(a->storage_root) + 1);
        else
            LOGW("c200: cannot move %s: %s", de->d_name, strerror(errno));
    }
    closedir(d);
}

static void *c200_raw_thread(void *arg)
{
    const adapter_t *a = arg;
    int64_t refreshed = 0;
    int was = -1;
    for (;;) {
        int on = raw_saving();
        if (on != was)
            LOGI("c200: RAW saving %s", on ? "on" : "off");
        was = on;
        if (on) {
            int64_t now = now_ms_mono();
            int flag = raw_flag();
            /* A cleared flag right away; rawfix (the camera app may have
             * restarted) and an unreadable flag now and then */
            if (flag == 0 || !refreshed || now - refreshed >= RAW_REFRESH_MS) {
                if (flag == 0)
                    LOGI("c200: RAW dump flag found off");
                raw_arm();
                refreshed = now;
            }
        }
        raw_collect(a);   /* also the dumps of a shot taken before "off" */
        sleep_ms(RAW_PERIOD_MS);
    }
    return NULL;
}

/* ------------------------------------------------------------ settings */

/* Every setting goes through the camera's own remote menu manager
 * (NETCameraMenuMan, docs/firmware/13), the path the Samsung phone app used:
 * the camera runs its whole handling of the change (the mode change, the
 * exceptional values, the status display), as with its buttons. Protocol
 * token <-> item value per remote menu (the AQK1 table, dumped with the
 * plugin's netmenu); the camera's current item list says what it offers now
 * (the size menus follow the lens). The value is read back from the UI key
 * the menu writes. */
typedef struct {
    const char *id;
    int menu;          /* E_CAMERA_MENU_ID; for the size menus the dual-lens one */
    int menu_single;   /* the single-lens menu, -1 when the same for both */
    int key;           /* the UI key the menu writes */
    int n;
    const char *tok[16];
    int val[16];
} c200_setting_t;

static const c200_setting_t C200_SETTINGS[] = {
    { "mode", 8, -1, 0x21c, 4, { "photo", "video", "timelapse", "looping" }, { 1, 0, 2, 3 } },
    /* "Single CAM - F" is item 1, "Single CAM - R" item 2 */
    { "lens", 23, -1, 0x21a, 3, { "dual", "rear", "front" }, { 0, 2, 1 } },
    { "photo_size", 0, 1, 0xcf, 3, { "dual_large", "dual_small", "single_wide" }, { 0, 1, 5 } },
    { "video_size", 9, 10, 0x109, 9,
      { "3840x1920@30", "2880x1440@30", "2560x1440@30", "2560x1280@60", "2560x1280@30",
        "1920x1080@60", "1920x1080@30", "1920x960@30", "1280x720@30" },
      { 0, 3, 5, 4, 1, 8, 6, 2, 7 } },
    { "timer", 27, -1, 0x225, 4, { "off", "2", "5", "10" }, { 0, 1, 2, 3 } },
    { "timelapse_interval", 24, -1, 0x224, 7, { "0.5", "1", "2", "5", "10", "30", "60" },
      { 0, 1, 2, 3, 4, 6, 7 } },
    { "loop_time", 25, -1, 0x21b, 4, { "5", "30", "60", "max" }, { 0, 1, 2, 3 } },
    /* EV items are -3.0 … +3.0 in 0.1 (value 30 = 0.0); the 0.5 steps of the protocol */
    { "ev", 3, -1, 0xc9, 13,
      { "-3.0", "-2.5", "-2.0", "-1.5", "-1.0", "-0.5", "0.0", "+0.5", "+1.0", "+1.5", "+2.0", "+2.5", "+3.0" },
      { 0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60 } },
    { "iso_limit", 4, -1, 0x111, 5, { "400", "800", "1600", "3200", "6400" }, { 1, 2, 3, 4, 5 } },
    { "white_balance", 5, -1, 0xd3, 6,
      { "auto", "daylight", "cloudy", "incandescent", "fluorescent", "underwater" }, { 0, 3, 4, 2, 5, 11 } },
    { "metering", 6, -1, 0x105, 3, { "multi", "center", "spot" }, { 0, 1, 2 } },
    { "hdr", 20, -1, 0x106, 2, { "off", "on" }, { 0, 1 } },
    { "sharpness", 22, -1, 0x222, 2, { "1", "2" }, { 0, 1 } },
    { "wind_cut", 21, -1, 0x10f, 2, { "off", "on" }, { 0, 1 } },
    { "audio", 26, -1, 0x10e, 2, { "off", "on" }, { 0, 1 } },
    { "beep", 16, -1, 0x14, 4, { "off", "low", "mid", "high" }, { 0, 1, 2, 3 } },
    { "led", 17, -1, 0x21e, 2, { "off", "on" }, { 0, 1 } },
    /* "never" is ours: the camera's power timer held off (keep_awake below) */
    { "auto_power_off", 18, -1, 0xd, 5, { "1", "3", "5", "30", "never" }, { 1, 2, 3, 5, 1000 } },
};
#define VALUE_NEVER 1000
#define N_C200_SETTINGS ((int)(sizeof C200_SETTINGS / sizeof C200_SETTINGS[0]))

#define KEY_LENS        0x21a
#define LENS_VALUE_DUAL 0

static const c200_setting_t *c200_setting(const char *id)
{
    for (int i = 0; i < N_C200_SETTINGS; i++)
        if (strcmp(C200_SETTINGS[i].id, id) == 0)
            return &C200_SETTINGS[i];
    return NULL;
}

/* The remote menu a setting is in now: the size menus come per lens */
static int c200_menu(const c200_setting_t *s)
{
    int lens = LENS_VALUE_DUAL;
    if (s->menu_single < 0)
        return s->menu;
    shim_ui_get(KEY_LENS, &lens);
    return lens == LENS_VALUE_DUAL ? s->menu : s->menu_single;
}

/* ------------------------------------------------------------ power timer */

/* The camera switches itself off after its auto power off time without a key
 * press. The stock phone app held that timer off while connected
 * (CUINETUtil::StopPowerTimer); so does this, while a client talks to us or
 * records or watches the live view, and always with auto_power_off "never",
 * kept on the card (keep_awake). The camera re-arms the timer on its own
 * events (a shot, a menu), hence the period. */
#define AWAKE_PERIOD_S  15
#define CLIENT_AWAKE_MS 20000

static char g_keep_awake_path[300];
/* The camera's stream service was started (only then can a session be left behind) */
static volatile int g_stream_used;

static int keep_awake(void)
{
    return access(g_keep_awake_path, F_OK) == 0;
}

static void *c200_awake_thread(void *arg)
{
    char reply[256];
    int held = 0;
    (void)arg;
    for (;;) {
        int client = g_srv.last_request_ms && now_ms_mono() - g_srv.last_request_ms < CLIENT_AWAKE_MS;
        if (keep_awake() || client || g_liveview || g_srv.recording) {
            if (shim_plugin("powerstop", reply, sizeof reply) == 0 && !held)
                LOGI("c200: power timer held off (%s)", keep_awake() ? "never" : "a client");
            held = 1;
        } else if (held) {
            /* Back to the camera's own auto power off time */
            if (shim_plugin("powerrestart", reply, sizeof reply) == 0)
                LOGI("c200: power timer running again");
            held = 0;
        }
        /* No live view served: a stream session the camera started late, or
         * left behind, would run its encoder for nobody (it heats the camera).
         * The stop is a no-op when none runs. */
        if (g_stream_used && !g_liveview)
            shim_plugin("mpegstop", reply, sizeof reply);
        sleep(AWAKE_PERIOD_S);
    }
    return NULL;
}

static int c200_get_setting(adapter_t *a, const char *id, char *out, size_t outlen)
{
    const c200_setting_t *s = c200_setting(id);
    int v, best = -1;
    (void)a;
    if (strcmp(id, "raw") == 0) {
        snprintf(out, outlen, "%s", raw_saving() ? "on" : "off");
        return 0;
    }
    if (s && strcmp(id, "auto_power_off") == 0 && keep_awake()) {
        snprintf(out, outlen, "never");
        return 0;
    }
    if (!s || shim_ui_get(s->key, &v) < 0)
        return -1;
    for (int i = 0; i < s->n; i++) {
        if (s->val[i] == v) {
            snprintf(out, outlen, "%s", s->tok[i]);
            return 0;
        }
        /* EV set on the camera in 0.1 steps: the nearest 0.5 step */
        if (strcmp(id, "ev") == 0 && (best < 0 || abs(s->val[i] - v) < abs(s->val[best] - v)))
            best = i;
    }
    if (best >= 0) {
        snprintf(out, outlen, "%s", s->tok[best]);
        return 0;
    }
    LOGW("c200: %s has value %d, which has no protocol token", id, v);
    return -1;
}

/* The tokens the camera offers now, in its own order */
static int c200_setting_available(adapter_t *a, const char *id, const char **out, int max)
{
    const c200_setting_t *s = c200_setting(id);
    char req[64], reply[512];
    const char *p;
    int n = 0;
    (void)a;

    if (!s)
        return -1;
    snprintf(req, sizeof req, "netvalues %d", c200_menu(s));
    if (shim_plugin(req, reply, sizeof reply) < 0 || strncmp(reply, "ok netvalues ", 13) != 0)
        return -1;
    p = strchr(reply + 13, ' ');   /* past the menu id */
    while (p && n < max) {
        char *end;
        int v = (int)strtol(p, &end, 10);
        if (end == p)
            break;
        for (int i = 0; i < s->n; i++) {
            if (s->val[i] == v) {
                out[n++] = s->tok[i];
                break;
            }
        }
        p = end;
    }
    if (strcmp(id, "auto_power_off") == 0 && n < max)
        out[n++] = "never";
    return n;
}

static int c200_set_setting(adapter_t *a, const char *id, const char *value, char *err, size_t errlen)
{
    const c200_setting_t *s = c200_setting(id);
    char req[64], reply[256];
    int v = -1;
    (void)a;

    if (strcmp(id, "raw") == 0) {
        /* ours, on the card; "on" arms the dump now, for the next still */
        if (strcmp(value, "on") == 0) {
            FILE *f = fopen(g_raw_save_path, "w");
            if (!f) {
                snprintf(err, errlen, "cannot write %s", g_raw_save_path);
                return -1;
            }
            fclose(f);
            raw_arm();
        } else {
            unlink(g_raw_save_path);
            raw_st(0);
        }
        return 0;
    }
    for (int i = 0; s && i < s->n; i++)
        if (strcmp(s->tok[i], value) == 0)
            v = s->val[i];
    if (v < 0) {
        snprintf(err, errlen, "no value for %s=%s", id, value);
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
            shim_plugin("powerstop", reply, sizeof reply);
            return 0;
        }
        unlink(g_keep_awake_path);   /* the camera's time again (the thread re-arms it) */
    }
    snprintf(req, sizeof req, "netset %d %d", c200_menu(s), v);
    if (shim_plugin(req, reply, sizeof reply) < 0) {
        snprintf(err, errlen, "%s", reply);
        return -1;
    }
    return 0;
}

/* ------------------------------------------------------------ record */

/* Recording or not: the movie state, or in time-lapse its interval capture */
static int c200_recording(int *out)
{
    char reply[256];
    return shim_plugin("recording", reply, sizeof reply) == 0 &&
           sscanf(reply, "ok recording = %d", out) == 1 ? 0 : -1;
}

/* The camera's own remote shot (CUINETFuncRemoteShot, the Samsung phone app's
 * record button): video and looping record from the live view, the time-lapse
 * runs its interval capture and its stop waits for the shot in progress */
static int c200_record(adapter_t *a, int start, char *err, size_t errlen)
{
    int rec = -1;
    char reply[256];
    (void)a;

    if (c200_recording(&rec) < 0) {
        snprintf(err, errlen, "cannot read the recording state");
        return -1;
    }
    LOGI("c200: record %s, recording %d", start ? "start" : "stop", rec);
    if (rec == start)
        return 0;   /* already where we want it */
    if (shim_plugin(start ? "remoterec" : "remotestop", reply, sizeof reply) < 0) {
        snprintf(err, errlen, "%s", reply);
        return -1;
    }
    if (start) {
        /* Confirm: the remote shot refuses silently (no card, busy, ...) */
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

/* ------------------------------------------------------------ live view */

/* The Samsung phone app's remote viewfinder (docs/firmware/13): its DLNA
 * service in di-camera-app, started without its own network bring-up, serves
 * the live picture on port 7679. A session left running would feed the next
 * client from mid-stream, so it is stopped before and after each one. */
#define LIVEVIEW_PORT 7679

static int c200_liveview_open(adapter_t *a, const char *quality, char *host, size_t hostlen, int *port,
                              char *path, size_t pathlen, char *err, size_t errlen)
{
    char reply[256];
    const char *u;
    (void)a;
    /* "ok dlna was=0 now=1 url=http://192.168.107.1:7676/smp_2_" */
    if (shim_plugin("dlnastart", reply, sizeof reply) < 0 || !(u = strstr(reply, "url=http://"))) {
        snprintf(err, errlen, "the camera's stream service did not start: %s", reply);
        return -1;
    }
    g_stream_used = 1;
    u += strlen("url=http://");
    snprintf(host, hostlen, "%.*s", (int)strcspn(u, ":/"), u);
    shim_plugin("mpegstop", reply, sizeof reply);
    *port = LIVEVIEW_PORT;
    /* The stock app's streams: /livestream_high.avi, _middle, _low */
    snprintf(path, pathlen, "/livestream_%s.avi", quality);
    return 0;
}

static void c200_liveview_close(adapter_t *a)
{
    char reply[256];
    (void)a;
    shim_plugin("mpegstop", reply, sizeof reply);
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
    a->power = 1;
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
            a->setting_available = c200_setting_available;
            a->record = c200_record;
            if (config_get_int(cfg, "liveview_enable", 1)) {
                a->liveview_open = c200_liveview_open;
                a->liveview_close = c200_liveview_close;
            }
            snprintf(g_keep_awake_path, sizeof g_keep_awake_path, "%s/g360r/keep_awake", a->storage_root);
            /* RAW: proven on AQK1 (2026-10-26), where the dump flag's address is known */
            g_raw_flag_readable = strstr(a->firmware, "AQK1") != NULL;
            if (g_raw_flag_readable && config_get_int(cfg, "raw_enable", 1)) {
                adapter_add_360_setting(a, "raw");
                snprintf(g_raw_save_path, sizeof g_raw_save_path, "%s/g360r/raw_save", a->storage_root);
            }
            {
                pthread_t th;
                pthread_attr_t at;
                pthread_attr_init(&at);
                pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
                pthread_attr_setstacksize(&at, 128 * 1024);
                pthread_create(&th, &at, c200_awake_thread, NULL);
                if (g_raw_save_path[0])
                    pthread_create(&th, &at, c200_raw_thread, a);
            }
        }
    }
    adapter_discover_leds(a, "/sys/class/leds", 5, cfg);
}
