/*
 * Adapter "c200": Samsung Gear 360 (2016), SM-C200, firmware APE4 and AQK1.
 *
 * Mechanisms (docs/firmware/03, 08, 09) - all by name/path, no addresses:
 *  - capture: quality through the factory `st` CLI (SysV queue 0x8828 serviced
 *    by the running di-camera-app): st app nx capture quality <q>. The shot itself
 *    goes through dfms: dfmstool --script "capture still" sets the vconf key
 *    memory/dfms/script_cmd, which the dfmsd that ran our SD hook keeps watching
 *    and forwards to di-camera-app's dfms_app_cmd_capture. (`st app nx capture run`
 *    only injects shutter keys into the normal UI, which does not run in factory
 *    mode: Test 1h produced no file, nor in normal mode on the modified
 *    firmware.) dfmsd runs in normal mode too, so dfms is the shot in both.
 *    Both are ASYNC: the caller polls DCIM.
 *    Every call has a hard timeout.
 *  - storage: exFAT card mounted at /opt/storage/sdcard (/sdcard -> /mnt/mmc ->
 *    /opt/storage/sdcard). Files: DCIM/<nnn>PHOTO/360_XXXX.JPG (+ .SRW beside
 *    the JPG in the raw quality modes).
 *  - LEDs: 5 gpio LEDs /sys/class/leds/led.0..4 (status R/G/B, front, rear),
 *    physical mapping not yet confirmed -> roles come from the SD config.
 *  - battery: /sys/class/power_supply/<name> (sec-battery "battery", "adc_battery").
 *
 * adapter_status is "experimental" until the hardware tests in camera/README.md
 * pass on the firmware; then flip C200_STATUS to "supported".
 */
#include "adapter.h"

#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define C200_STATUS "experimental"
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

static int c200_capture(adapter_t *a, int mode, const char *quality,
                        const char *resolution, char *err, size_t errlen)
{
    char q[32];
    /* The shot always goes through dfms: dfmsd runs in normal mode too, and
     * there `capture still` works (8.9 s) while `st app nx capture run` writes
     * nothing (both tested on the modified firmware). Factory mode (SD hook)
     * also needs the live view asked once; normal mode (boot.sh exports
     * G360R_MODE=normal) has it already. */
    const char *run_mode = getenv("G360R_MODE");
    int factory = !(run_mode && strcmp(run_mode, "normal") == 0);
    (void)resolution; /* not settable in v1 */

    if (access(ST_BIN, X_OK) != 0) {
        snprintf(err, errlen, "%s not found", ST_BIN);
        return -1;
    }
    /* st quality tokens: sfine fine norm raw raw+sfine raw+fine raw+norm */
    if (mode == MODE_RAW)
        snprintf(q, sizeof q, "raw");
    else if (mode == MODE_RAWPHOTO)
        snprintf(q, sizeof q, "raw+%s", quality ? quality : "sfine");
    else
        snprintf(q, sizeof q, "%s", quality ? quality : "sfine");

    LOGI("c200: capture via dfms (%s mode)", factory ? "factory" : "normal");
    /* Put the app in 360 photo mode first. In factory mode also ask once for
     * the live view: dfms `capture still` needs a live pipeline (Test 2). */
    {
        /* A marker file, not a static: each request runs in a forked child, and
         * `show liveview` toggles, so it must be sent once per controller run. */
        char marker[300];
        char *mode_argv[] = { ST_BIN, "app", "mode_360", "photo", NULL };
        char *lv_argv[] = { ST_BIN, "app", "show", "liveview", NULL };
        snprintf(marker, sizeof marker, "%s/liveview_asked", a->run_dir);
        if (st_call(mode_argv, "mode_360 photo", err, errlen) < 0)
            return -1;
        if (factory && !file_exists(marker)) {
            write_text_file(marker, "1\n");
            if (st_call(lv_argv, "show liveview", err, errlen) < 0)
                return -1;
            sleep_ms(1000);
        }
    }
    {
        char *argv[] = { ST_BIN, "app", "nx", "capture", "quality", q, NULL };
        if (st_call(argv, "capture quality", err, errlen) < 0)
            return -1;
    }
    sleep_ms(300);
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
    }
    return 0;
}

void adapter_c200_init(adapter_t *a, const config_t *cfg)
{
    static const char *const qualities[] = { "sfine", "fine", "norm" };
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

    a->capture_modes = MODE_PHOTO | MODE_RAW | MODE_RAWPHOTO;
    if (!config_get_int(cfg, "capture_enable", 1))
        a->capture_modes = 0;
    for (i = 0; i < 3; i++) a->qualities[i] = qualities[i];
    a->n_qualities = 3;
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
    adapter_discover_leds(a, "/sys/class/leds", 5, cfg);
}
