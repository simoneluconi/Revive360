/*
 * Adapter "r210": Samsung Gear 360 (2017), SM-R210 (info.ini Model=SMR210;).
 *
 * STUB. Detected and reported with adapter_status "stub". Only what is safely
 * generic is enabled: status (battery/storage) and the file endpoints. Capture
 * and LEDs stay OFF until verified on an R210:
 *
 * TODO(r210):
 *  - confirm the dfmsd info.tg gating and `shell script` on R210 firmware;
 *  - confirm `st app nx capture quality/run` strings (R210 di-camera-app has a
 *    rewritten AppNet:: layer; the st tree may differ) and set capture_modes;
 *  - confirm the storage mount point and DCIM naming;
 *  - map /sys/class/leds/led.N (run `g360rd ledtest`) and call adapter_discover_leds;
 *  - photo resolutions and qualities (from the R210 st help / UI tables).
 */
#include "adapter.h"

#include <stdio.h>

void adapter_r210_init(adapter_t *a, const config_t *cfg)
{
    a->id = "r210";
    a->status = "stub";
    snprintf(a->model, sizeof a->model, "SM-R210");
    snprintf(a->name_prefix, sizeof a->name_prefix, "Gear 360 (2017)");
    /* Same Tizen 2.2 storage layout as the C200 is expected; overridable. */
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
    a->trigger_capture = NULL;
}
