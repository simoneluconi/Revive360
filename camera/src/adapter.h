/*
 * Per-model camera adapter.
 *
 * Everything model- or firmware-specific lives behind this struct: identity,
 * storage location, capability lists, how to trigger a capture, which LEDs
 * exist and how they are driven, and where the battery lives. The HTTP/API
 * layer only talks to the adapter and never branches on a model name.
 *
 * Adapters: c200 (SM-C200, fw APE4/AQK1), r210 (SM-R210, stub), generic
 * (unknown model: status + files only), sim (host test mode rooted in a dir).
 */
#ifndef G360R_ADAPTER_H
#define G360R_ADAPTER_H

#include "g360rd.h"

#define MODE_PHOTO     0x1
#define MODE_RAW       0x2
#define MODE_RAWPHOTO  0x4

#define MAX_LEDS   8
#define MAX_LIST   8

typedef struct {
    char id[16];          /* opaque protocol id, e.g. "led0" */
    char label[40];       /* display label */
    char role[16];        /* status_red ... rear, charge, unknown */
    char dir[200];        /* sysfs LED directory (brightness, trigger) */
    int  max_brightness;  /* value written for "on" */
} led_chan_t;

typedef struct adapter adapter_t;

struct adapter {
    /* identity (filled by detection) */
    const char *id;             /* c200 | r210 | generic | sim */
    const char *status;         /* supported | experimental | stub */
    char model[32];             /* SM-C200 ... or model_raw */
    char model_raw[32];         /* Model= from info.ini, ';' stripped */
    char firmware[48];          /* Build= from info.ini, ';' stripped */
    char name_prefix[32];       /* "Gear 360" -> "Gear 360 (65:EF:1E)" */
    char wifi_if[16];           /* interface whose MAC identifies the camera */
    char mac_override[24];      /* sim / fallback MAC */

    /* paths */
    char storage_root[256];     /* directory that contains DCIM */
    char mount_point[256];      /* must appear in /proc/mounts; "" = no check */
    char power_supply_dir[256]; /* normally /sys/class/power_supply */
    char run_dir[256];          /* lock files etc. (/tmp/g360r) */

    /* capabilities */
    int capture_modes;          /* MODE_* bitmask; 0 = no capture */
    const char *qualities[MAX_LIST];   int n_qualities;
    const char *resolutions[MAX_LIST]; int n_resolutions;
    int resolution_settable;
    const char *video_modes[MAX_LIST]; int n_video_modes;
    int files_delete;
    int thumbnails;
    led_chan_t leds[MAX_LEDS];  int n_leds;

    /* Trigger one capture. Must return once the shot was requested (files
     * are detected by the caller polling DCIM). quality is already validated
     * (may be NULL for raw). Returns 0, or -1 with err filled in. */
    int (*trigger_capture)(adapter_t *a, int mode, const char *quality,
                           const char *resolution, char *err, size_t errlen);

    void *priv;                 /* adapter-private state (sim dir ...) */
};

/* Detection from <root>/etc/info.ini ("" = real /). forced may name an adapter
 * id ("c200", "r210", "generic") to override detection. sim_dir != NULL
 * selects the sim adapter. Returns 0 on success. */
int adapter_detect(adapter_t *a, const char *forced, const char *sim_dir, const config_t *cfg);

/* Mode names */
const char *mode_name(int mode);
int mode_from_name(const char *s);

/* Shared helpers used by several adapters */
void adapter_read_info_ini(const char *path, char *model_raw, size_t mlen, char *build, size_t blen);
void adapter_discover_leds(adapter_t *a, const char *leds_dir, int max, const config_t *cfg);
int  adapter_storage_mounted(const adapter_t *a);

/* Adapter constructors (adapter_*.c) */
void adapter_c200_init(adapter_t *a, const config_t *cfg);
void adapter_r210_init(adapter_t *a, const config_t *cfg);
void adapter_generic_init(adapter_t *a, const config_t *cfg);
int  adapter_sim_init(adapter_t *a, const char *dir, const config_t *cfg);

#endif
