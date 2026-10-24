/*
 * g360rd - Revive360 camera-side controller.
 *
 * Shared declarations. The program is a single static binary with subcommands
 * (see main.c). It implements docs/protocol.md (v1) on top of a small per-model
 * adapter layer (adapter.h). libc + pthreads only.
 */
#ifndef G360RD_H
#define G360RD_H

#define _GNU_SOURCE 1
#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>
#include <sys/types.h>

#define G360RD_NAME     "g360rd"
#define G360RD_VERSION  "0.1.0"
#define PROTOCOL_VERSION 1

/* ---------------------------------------------------------------- log.c */

enum { LOG_ERR = 0, LOG_WARN = 1, LOG_INFO = 2, LOG_DEBUG = 3 };

/* Open the log file (append). NULL or "" = stderr only. */
void log_open(const char *path, int also_stderr);
const char *log_path(void);
void log_msg(int level, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
void log_flush_sync(void);          /* fflush + fdatasync, called periodically */
/* Return a malloc'd buffer with the last `lines` lines of the log file. */
char *log_tail(int lines, size_t *len_out);

#define LOGE(...) log_msg(LOG_ERR, __VA_ARGS__)
#define LOGW(...) log_msg(LOG_WARN, __VA_ARGS__)
#define LOGI(...) log_msg(LOG_INFO, __VA_ARGS__)
#define LOGD(...) log_msg(LOG_DEBUG, __VA_ARGS__)

/* --------------------------------------------------------------- util.c */

/* Growable byte buffer. All appenders keep buf NUL-terminated. On OOM the
 * buffer sets .oom and silently stops growing; callers check it at the end. */
typedef struct {
    char  *buf;
    size_t len;
    size_t cap;
    int    oom;
} sbuf_t;

void sbuf_init(sbuf_t *b);
void sbuf_free(sbuf_t *b);
void sbuf_append(sbuf_t *b, const void *data, size_t n);
void sbuf_puts(sbuf_t *b, const char *s);
void sbuf_printf(sbuf_t *b, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

int64_t now_ms_mono(void);          /* CLOCK_MONOTONIC in ms */
void    sleep_ms(int ms);

/* Read a whole small file (sysfs, ini). Returns length or -1. Trailing
 * whitespace/newlines are stripped; out is always NUL-terminated. */
int  read_text_file(const char *path, char *out, size_t outlen);
/* Access trace for hardware debugging: trace_start(file) once, then
 * trace_note("read"/"opendir"/"step", path) before each access (fsync'd). */
void trace_start(const char *path);
void trace_note(const char *what, const char *path);
/* Write a string to a file (sysfs). Returns 0 on success, -1 on error. */
int  write_text_file(const char *path, const char *s);
int  file_exists(const char *path);
int  dir_exists(const char *path);
int  mkdir_p(const char *path, mode_t mode);
void trim(char *s);
int  str_ieq(const char *a, const char *b);
int  str_iendswith(const char *s, const char *suffix);

/* Run argv[0] with a hard timeout (fork/exec, own process group, SIGKILL on
 * timeout). stdout+stderr are captured into out (truncated, NUL-terminated)
 * if out != NULL. Returns the exit status (0..255), 128+signal if killed by a
 * signal, -1 if it could not be started, -2 on timeout. */
int  run_cmd(char *const argv[], int timeout_ms, char *out, size_t outlen);

/* ------------------------------------------------------------- config.c */

/* Flat KEY=VALUE config (shell-style, '#' comments, optional quotes). */
#define CFG_MAX 64
typedef struct {
    int  n;
    char key[CFG_MAX][48];
    char val[CFG_MAX][256];
} config_t;

int         config_load(config_t *c, const char *path);   /* 0 ok, -1 missing */
const char *config_get(const config_t *c, const char *key, const char *def);
int         config_get_int(const config_t *c, const char *key, int def);

/* --------------------------------------------------------------- json.c */

/* JSON string escaping into a buffer (adds the surrounding quotes). */
void json_str(sbuf_t *b, const char *s);

/* Minimal JSON request parser: a top-level object whose members we read.
 * Nested values are validated and skipped. */
enum { JV_NONE = 0, JV_STRING, JV_NUMBER, JV_BOOL, JV_NULL, JV_OTHER };
#define JOBJ_MAX 16
typedef struct {
    int n;
    struct {
        char   key[32];
        int    type;
        char   str[128];   /* JV_STRING (unescaped, truncated) */
        double num;        /* JV_NUMBER, JV_BOOL (0/1) */
    } m[JOBJ_MAX];
} jobj_t;

int jobj_parse(const char *text, size_t len, jobj_t *out);   /* 0 ok, -1 invalid */
const char *jobj_get_str(const jobj_t *o, const char *key, int *present_wrong_type);
int jobj_get_num(const jobj_t *o, const char *key, double *out); /* 1 found, 0 absent, -1 wrong type */

/* ------------------------------------------------------------- netif.c */

int netif_get_mac(const char *ifname, unsigned char mac[6]);
int netif_get_flags(const char *ifname, int *flags);
int netif_set_updown(const char *ifname, int up);
int netif_set_addr(const char *ifname, const char *ip, const char *mask);
/* Broadcom dhd ASCII private command. style: 0 = android_wifi_priv_cmd in
 * ifr_data (stock mobileap-agent), 1 = iw_point (classic wext SIOCSIWPRIV).
 * The reply (if any) is copied back to reply. Returns ioctl result. */
int netif_priv_cmd(const char *ifname, unsigned long req, int style,
                   const char *cmd, char *reply, size_t replylen);
int netif_mac_str(const char *ifname, char *out, size_t outlen); /* "aa:bb:.." */
int cmd_net(int argc, char **argv);           /* subcommands mac/ifup/... */

/* ---------------------------------------------------------------- thumb.c */

/* Extract an embedded JPEG thumbnail/preview from a JPEG (EXIF IFD1) or a
 * TIFF-based RAW (SRW/DNG). Returns 0 and a malloc'd buffer, or -1. */
int thumb_extract(const char *path, unsigned char **out, size_t *outlen);

/* ------------------------------------------------------------- server */

int cmd_serve(int argc, char **argv);
int cmd_ledtest(int argc, char **argv);
int cmd_detect(int argc, char **argv);

#endif /* G360RD_H */
