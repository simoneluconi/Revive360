/*
 * Adapter "c200": Samsung Gear 360 (2016), SM-C200, firmware APE4 and AQK1.
 *
 * Mechanisms (docs/firmware/03, 08, 09) - all by name/path, no addresses:
 *  - capture: the factory `st` CLI (SysV queue 0x8828 serviced by the running
 *    di-camera-app):  st app nx capture quality <q>;  st app nx capture run count 1
 *    The shot is injected as key events and is ASYNC: st returns before the
 *    file exists, the caller polls DCIM. st writes its integer result to
 *    /tmp/app_result. Every call has a hard timeout.
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

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define C200_STATUS "experimental"
#define ST_BIN      "/usr/bin/st"
#define ST_TIMEOUT_MS 10000

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

static int c200_capture(adapter_t *a, int mode, const char *quality,
                        const char *resolution, char *err, size_t errlen)
{
    char q[32];
    (void)a;
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

    {
        char *argv[] = { ST_BIN, "app", "nx", "capture", "quality", q, NULL };
        if (st_call(argv, "capture quality", err, errlen) < 0)
            return -1;
    }
    sleep_ms(300);
    {
        char *argv[] = { ST_BIN, "app", "nx", "capture", "run", "count", "1", NULL };
        if (st_call(argv, "capture run", err, errlen) < 0)
            return -1;
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
    a->trigger_capture = c200_capture;
    adapter_discover_leds(a, "/sys/class/leds", 5, cfg);
}
