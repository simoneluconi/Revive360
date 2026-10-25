/*
 * Server internals shared by http.c, api.c, files.c, status.c, capture.c,
 * leds.c.
 */
#ifndef G360R_SERVER_H
#define G360R_SERVER_H

#include "adapter.h"

#include <pthread.h>

#define HDR_MAX   8192          /* protocol §2: header block max 8 KiB */
#define BODY_MAX  4096          /* protocol §2: JSON body max 4 KiB */
#define UPLOAD_PATH "/api/v1/debug/upload"
#define UPLOAD_MAX (8L * 1024 * 1024)   /* its body is read by the handler, from the socket */

/* ------------------------------------------------------------ global state */

typedef struct {
    adapter_t   ad;
    config_t    cfg;
    int64_t     start_ms;           /* monotonic, for uptime_s */
    char        mac[24];            /* cached wlan0 MAC (lowercase, colons) */
    int         have_battery;       /* capabilities.status.battery */
    int         have_battery_temp;
    char        battery_dir[300];   /* chosen power_supply entry */
    volatile int busy;              /* capture in progress */
    volatile int recording;         /* a video recording runs (started through us) */
    int64_t rec_started_ms;         /* now_ms_mono() at its start */
    volatile int64_t last_request_ms;
    volatile int net_error;         /* launcher reported a network failure */
} server_t;

extern server_t g_srv;

/* ------------------------------------------------------------ http.c */

typedef struct {
    int    fd;
    char   peer[48];
    char   method[16];
    char   target[2048];   /* raw request target */
    char   path[2048];     /* target without query, still percent-encoded */
    char   query[512];
    long   content_length; /* -1 = absent */
    char   range[128];     /* Range header value or "" */
    char   allow[64];      /* set before a 405: value of the Allow header */
    char   body[HDR_MAX + 1];  /* JSON bodies up to BODY_MAX; an upload's first bytes up to HDR_MAX */
    size_t body_len;
    int    body_streamed;  /* an upload: body[] holds what came with the headers, the rest is on fd */
} req_t;

const char *http_reason(int status);
/* Send a complete response. Extra headers: "Name: value\r\n" lines or NULL.
 * head_only: send headers (with the real Content-Length) but no body. */
int  http_send(req_t *r, int status, const char *ctype, const char *extra,
               const void *body, size_t len, int head_only);
int  http_send_json(req_t *r, int status, sbuf_t *json);
int  http_send_error(req_t *r, int status, const char *code, const char *fmt, ...)
     __attribute__((format(printf, 4, 5)));
int  http_send_all(int fd, const void *p, size_t n);
void http_format_date(int64_t t, char *out, size_t outlen);

int  server_run(const char *bind_addr, int port);

/* ------------------------------------------------------------ api.c */

void api_dispatch(req_t *r);
void api_capabilities_json(sbuf_t *b);

/* ------------------------------------------------------------ files.c */

typedef struct {
    char   *path;      /* "DCIM/..." */
    int64_t size;
    int64_t mtime;
} fentry_t;

typedef struct {
    fentry_t *v;
    size_t    n, cap;
} flist_t;

const char *file_kind(const char *name);
const char *file_mime(const char *name);
/* Decode %XX and validate per protocol §2. Returns 0 and the decoded path, or
 * -1 (bad_path). */
int  path_decode_validate(const char *enc, char *out, size_t outlen);
/* Build the absolute path under the storage root and check it does not escape
 * it (symlinks). 0 ok, -1 bad_path. */
int  path_resolve(const char *rel, char *abs, size_t abslen);
int  files_list(flist_t *fl, int max_depth);   /* sorted newest first */
void flist_free(flist_t *fl);
void fentry_json(sbuf_t *b, const fentry_t *e);

void api_files_list(req_t *r);
void api_files_get(req_t *r, const char *rel, int head_only);
void api_files_delete(req_t *r, const char *rel);
void api_thumb(req_t *r, const char *rel);

/* ------------------------------------------------------------ status.c */

void status_init(void);
void api_status(req_t *r);

/* ------------------------------------------------------------ capture.c */

void api_capture(req_t *r);
int  capture_lock_init(void);

/* ------------------------------------------------------------ leds.c */

enum { LEDSTATE_STARTING = 0, LEDSTATE_READY, LEDSTATE_ERROR };
void leds_start(void);                 /* starts the LED thread */
void leds_set_system_state(int st);
int  leds_manual(const char *id, const char *state, int on_ms, int off_ms); /* 0 ok, -1 unknown id */
void leds_all_off(void);
void api_led(req_t *r);
void api_time(req_t *r);
void api_debug_dfms(req_t *r);
void api_debug_st(req_t *r);
void api_debug_vconf(req_t *r);
void api_debug_shim(req_t *r);
void api_debug_upload(req_t *r);
void api_debug_restart(req_t *r);

/* liveview.c (§5.14) */
void api_liveview(req_t *r);
extern volatile int g_liveview;   /* a live view stream is being served */
int liveview_release(void);        /* ends one kept without a client; 0 = off */
void liveview_shutdown(void);      /* ends any, cleanly: before the controller exits */

/* settings.c (§5.12) */
void api_settings(req_t *r);
void settings_capabilities_json(sbuf_t *b);
const setting_def_t *settings_find(const char *id);
int  settings_ensure_mode(const char *mode, char *err, size_t errlen);

/* shim_client.c: the shim loaded into di-camera-app (modified firmware) */
int shim_request(const char *req, char *reply, size_t len);  /* 0 = answered */
int shim_plugin(const char *preq, char *reply, size_t len);  /* 0 = "ok ..." */
int shim_ui_get(int item, int *out);

/* capture.c: video recording (§5.13) */
void api_record(req_t *r);
void recording_json(sbuf_t *b);

#endif
