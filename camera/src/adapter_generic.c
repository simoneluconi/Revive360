/*
 * Adapter "generic": fallback for an unknown model/firmware.
 *
 * Status (battery via power_supply discovery, storage via statvfs) and the
 * file endpoints only. No capture, no LEDs: we do not poke unknown hardware.
 * The storage root is the first existing candidate directory.
 */
#include "adapter.h"

#include <stdio.h>

void adapter_generic_init(adapter_t *a, const config_t *cfg)
{
    static const char *const candidates[] = { "/opt/storage/sdcard", "/mnt/mmc", "/sdcard", NULL };
    const char *root = config_get(cfg, "storage_root", NULL);
    int i;

    a->id = "generic";
    a->status = "stub";
    snprintf(a->model, sizeof a->model, "%s", a->model_raw);
    snprintf(a->name_prefix, sizeof a->name_prefix, "Camera");
    if (!root) {
        root = candidates[0];
        for (i = 0; candidates[i]; i++) {
            if (dir_exists(candidates[i])) {
                root = candidates[i];
                break;
            }
        }
    }
    snprintf(a->storage_root, sizeof a->storage_root, "%s", root);
    /* No mount check: we do not know this model's mount layout. */
    snprintf(a->mount_point, sizeof a->mount_point, "%s", config_get(cfg, "storage_mount", ""));
    a->capture_modes = 0;
    a->files_delete = 1;
    a->thumbnails = 1;
    a->n_leds = 0;
    a->trigger_capture = NULL;
}
