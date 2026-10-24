/*
 * Adapter detection and helpers shared by the adapters.
 */
#include "adapter.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

const char *mode_name(int mode)
{
    switch (mode) {
    case MODE_PHOTO:    return "photo";
    case MODE_RAW:      return "raw";
    case MODE_RAWPHOTO: return "raw+photo";
    default:            return "?";
    }
}

int mode_from_name(const char *s)
{
    if (!s) return 0;
    if (strcmp(s, "photo") == 0) return MODE_PHOTO;
    if (strcmp(s, "raw") == 0) return MODE_RAW;
    if (strcmp(s, "raw+photo") == 0) return MODE_RAWPHOTO;
    return 0;
}

/* info.ini lines look like "Model=SMC200;" and "Build=C200GLU0AQK1;". */
void adapter_read_info_ini(const char *path, char *model_raw, size_t mlen, char *build, size_t blen)
{
    FILE *fp;
    char line[256];
    trace_note("fopen", path);
    fp = fopen(path, "r");
    model_raw[0] = 0;
    build[0] = 0;
    if (!fp)
        return;
    while (fgets(line, sizeof line, fp)) {
        char *v;
        size_t n;
        trim(line);
        v = strchr(line, '=');
        if (!v)
            continue;
        *v++ = 0;
        trim(line);
        trim(v);
        n = strlen(v);
        while (n > 0 && v[n - 1] == ';')
            v[--n] = 0;
        if (strcmp(line, "Model") == 0 && !model_raw[0])
            snprintf(model_raw, mlen, "%s", v);
        else if (strcmp(line, "Build") == 0 && !build[0])
            snprintf(build, blen, "%s", v);
    }
    fclose(fp);
}

static const char *role_label(const char *role)
{
    if (strcmp(role, "status_red") == 0)   return "Status (red)";
    if (strcmp(role, "status_green") == 0) return "Status (green)";
    if (strcmp(role, "status_blue") == 0)  return "Status (blue)";
    if (strcmp(role, "front") == 0)        return "Front";
    if (strcmp(role, "rear") == 0)         return "Rear";
    if (strcmp(role, "charge") == 0)       return "Charge";
    return NULL;
}

static int valid_role(const char *r)
{
    return role_label(r) != NULL || strcmp(r, "unknown") == 0;
}

/* LEDs are led.0 .. led.<max-1> under leds_dir (standard leds class). Roles
 * come from the config key led_roles (comma list by index) because the
 * physical mapping is not known until the user runs the LED diagnostic; an
 * unmapped LED is reported honestly as role "unknown", label "LED n". */
void adapter_discover_leds(adapter_t *a, const char *leds_dir, int max, const config_t *cfg)
{
    char roles[256], *save = NULL, *tok;
    char rolev[MAX_LEDS][16];
    int i;

    for (i = 0; i < MAX_LEDS; i++)
        snprintf(rolev[i], sizeof rolev[i], "unknown");
    snprintf(roles, sizeof roles, "%s", cfg ? config_get(cfg, "led_roles", "") : "");
    for (i = 0, tok = strtok_r(roles, ",", &save); tok && i < MAX_LEDS; tok = strtok_r(NULL, ",", &save), i++) {
        trim(tok);
        if (valid_role(tok))
            snprintf(rolev[i], sizeof rolev[i], "%s", tok);
        else
            LOGW("adapter: led_roles[%d]=%s is not a protocol role, using unknown", i, tok);
    }

    a->n_leds = 0;
    for (i = 0; i < max && i < MAX_LEDS; i++) {
        char dir[200], p[256], v[32];
        led_chan_t *l;
        snprintf(dir, sizeof dir, "%s/led.%d", leds_dir, i);
        snprintf(p, sizeof p, "%s/brightness", dir);
        if (!file_exists(p))
            continue;
        l = &a->leds[a->n_leds++];
        memset(l, 0, sizeof *l);
        snprintf(l->id, sizeof l->id, "led%d", i);
        snprintf(l->dir, sizeof l->dir, "%s", dir);
        snprintf(l->role, sizeof l->role, "%s", rolev[i]);
        if (role_label(l->role))
            snprintf(l->label, sizeof l->label, "%s", role_label(l->role));
        else
            snprintf(l->label, sizeof l->label, "LED %d", i);
        snprintf(p, sizeof p, "%s/max_brightness", dir);
        l->max_brightness = (read_text_file(p, v, sizeof v) > 0 && atoi(v) > 0) ? atoi(v) : 1;
    }
}

int adapter_storage_mounted(const adapter_t *a)
{
    FILE *fp;
    char line[512];
    int found = 0;

    if (!dir_exists(a->storage_root))
        return 0;
    if (a->mount_point[0]) {
        trace_note("fopen", "/proc/mounts");
        fp = fopen("/proc/mounts", "r");
        if (!fp)
            return 0;
        while (fgets(line, sizeof line, fp)) {
            char dev[256], mnt[256];
            if (sscanf(line, "%255s %255s", dev, mnt) == 2 && strcmp(mnt, a->mount_point) == 0) {
                found = 1;
                break;
            }
        }
        fclose(fp);
        if (!found)
            return 0;
    }
    /* A mounted card without DCIM is still "mounted" (empty listing). */
    return 1;
}

int adapter_detect(adapter_t *a, const char *forced, const char *sim_dir, const config_t *cfg)
{
    char model_raw[32], build[48];

    memset(a, 0, sizeof *a);
    snprintf(a->power_supply_dir, sizeof a->power_supply_dir, "/sys/class/power_supply");
    snprintf(a->run_dir, sizeof a->run_dir, "/tmp/g360r");
    snprintf(a->wifi_if, sizeof a->wifi_if, "wlan0");

    if (sim_dir)
        return adapter_sim_init(a, sim_dir, cfg);

    adapter_read_info_ini("/etc/info.ini", model_raw, sizeof model_raw, build, sizeof build);
    snprintf(a->model_raw, sizeof a->model_raw, "%s", model_raw[0] ? model_raw : "unknown");
    snprintf(a->firmware, sizeof a->firmware, "%s", build[0] ? build : "unknown");
    LOGI("adapter: info.ini Model=%s Build=%s forced=%s", a->model_raw, a->firmware,
         forced && *forced ? forced : "-");

    if (forced && *forced) {
        if (strcmp(forced, "c200") == 0) adapter_c200_init(a, cfg);
        else if (strcmp(forced, "r210") == 0) adapter_r210_init(a, cfg);
        else adapter_generic_init(a, cfg);
    } else if (strcmp(a->model_raw, "SMC200") == 0) {
        adapter_c200_init(a, cfg);
    } else if (strcmp(a->model_raw, "SMR210") == 0) {
        adapter_r210_init(a, cfg);
    } else {
        adapter_generic_init(a, cfg);
    }
    LOGI("adapter: selected %s (%s), model %s, storage %s", a->id, a->status, a->model, a->storage_root);
    return 0;
}
