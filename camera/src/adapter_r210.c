/*
 * Adapter "r210": Samsung Gear 360 (2017), SM-R210 (info.ini Model=SMR210;),
 * firmware R210GLU0ARB2 (docs/firmware/15_r210_port.md).
 *
 * STUB, reported with adapter_status "stub": status (battery/storage), the file
 * endpoints, and the camera kept awake while a client talks to us. The first
 * R210 image has no shim, so capture, settings and recording (which go through
 * the camera app's AppNet::Capture/Record/Menu managers) wait for the second one.
 *
 * TODO(r210):
 *  - shim + plugin on AppNet::Menu::CManager / Capture / Record (doc 15 §6);
 *  - map /sys/class/leds/led.0-6 (run `g360rd ledtest`) and call adapter_discover_leds;
 *  - the Bluetooth side: BlueZ there has no GattManager1.RegisterService.
 */
#include "adapter.h"
#include "server.h"

#include <pthread.h>
#include <stdio.h>
#include <unistd.h>

#define ST_BIN        "/usr/bin/st"
#define ST_TIMEOUT_MS 10000

/* ------------------------------------------------------------ power timer */

/* The camera switches itself off after its auto power off time. `st app dev
 * power` makes the camera app skip that (CAPPDataManager's disable-auto-power-off
 * flag, "[SET] AutoPower Timer Disable"); a bare `st app dev` clears the flag
 * again. Both also make the app save its preferences, so they are sent only when
 * the state changes, plus a reminder every REASSERT_S while held, not on every
 * tick as the C200's shim call is. Held while a client talks to us or records. */
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
        if (client || g_srv.recording) {
            if (!held || now - held_at >= REASSERT_S * 1000LL) {
                if (st_dev("power") == 0) {
                    if (!held)
                        LOGI("r210: auto power off held off (a client)");
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
    a->status = "stub";
    snprintf(a->model, sizeof a->model, "SM-R210");
    snprintf(a->name_prefix, sizeof a->name_prefix, "Gear 360 2017");
    /* Same Tizen 2.2 storage layout as the C200 (checked on ARB2); overridable. */
    snprintf(a->storage_root, sizeof a->storage_root, "%s",
             config_get(cfg, "storage_root", "/opt/storage/sdcard"));
    snprintf(a->mount_point, sizeof a->mount_point, "%s",
             config_get(cfg, "storage_mount", "/opt/storage/sdcard"));
    a->capture_modes = 0;
    a->n_qualities = 0;
    a->n_resolutions = 0;
    a->n_video_modes = 0;
    a->files_delete = 1;
    a->thumbnails = 1;
    a->n_leds = 0;
    /* As on the C200, the camera shows its clock through a fixed +9 h zone (ARB2:
     * set to 19:33 UTC, its log read 04:33): timeset.c compensates */
    a->clock_local = 1;
    /* Reboot and power off through systemd (/usr/bin/systemctl, power.c) */
    a->power = 1;
    a->trigger_capture = NULL;
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
