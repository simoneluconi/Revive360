/*
 * LEDs: manual control (POST /api/v1/led, protocol §5.8) and the controller's
 * own feedback for LEDs in "auto" (protocol §6).
 *
 * Every LED is a sysfs leds-class directory (brightness, trigger). A single
 * thread computes the desired level of each LED every TICK_MS and writes
 * brightness only on change (plus a periodic refresh, because di-camera-app
 * may also write these LEDs). Blinking is done in software so every pattern
 * is uniform and needs no trigger support.
 *
 * Which LEDs show the automatic feedback is chosen by config, because the
 * physical mapping of led.0..4 is not confirmed yet:
 *   led_feedback=all | none | <comma list of indices>   (default none;
 *                                                 "all" = every LED with a
 *                                                 known role in led_roles,
 *                                                 never an "unknown" one)
 *   led_error=<comma list of indices>            (default: LEDs whose role is
 *                                                 status_red, else feedback set)
 * LEDs in neither set are never touched while in "auto".
 *
 * Auto patterns:
 *   starting        slow blink 1000/1000
 *   ready           steady on
 *   ready + client  short double blink every 2 s (request seen in last 10 s)
 *   capture         fast blink 100/100
 *   error           error LEDs blink 250/250, other feedback LEDs off
 */
#include "server.h"

#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TICK_MS     30
#define REFRESH_MS  2000

enum { M_AUTO = 0, M_OFF, M_ON, M_BLINK };

typedef struct {
    int     mode;
    int     on_ms, off_ms;
    int64_t t0;
    int     cur;            /* last written level, -1 unknown */
    int64_t last_write;
    int     prepared;       /* trigger set to none */
    char    bpath[256];     /* .../brightness */
    char    on_str[16];
} ledst_t;

static ledst_t g_led[MAX_LEDS];
static unsigned g_feedback_mask, g_error_mask;
static volatile int g_sys_state = LEDSTATE_STARTING;
static pthread_mutex_t g_led_mu = PTHREAD_MUTEX_INITIALIZER;

static unsigned parse_mask(const char *s, unsigned all)
{
    unsigned m = 0;
    char tmp[128], *save = NULL, *tok;
    if (!s)
        return 0;
    if (strcmp(s, "all") == 0)
        return all;
    if (strcmp(s, "none") == 0)
        return 0;
    snprintf(tmp, sizeof tmp, "%s", s);
    for (tok = strtok_r(tmp, ", ", &save); tok; tok = strtok_r(NULL, ", ", &save)) {
        int i, idx = atoi(tok);
        /* Accept "3" (led.3) or "led3". */
        if (strncmp(tok, "led", 3) == 0)
            idx = atoi(tok + 3);
        for (i = 0; i < g_srv.ad.n_leds; i++) {
            if (atoi(g_srv.ad.leds[i].id + 3) == idx)
                m |= 1u << i;
        }
    }
    return m & all;
}

static void led_write(int i, int on, int64_t now)
{
    ledst_t *l = &g_led[i];
    int fd;
    if (!l->prepared) {
        char tp[256];
        snprintf(tp, sizeof tp, "%s/trigger", g_srv.ad.leds[i].dir);
        write_text_file(tp, "none");
        l->prepared = 1;
    }
    fd = open(l->bpath, O_WRONLY | O_CLOEXEC | O_TRUNC);
    if (fd >= 0) {
        const char *s = on ? l->on_str : "0";
        ssize_t w = write(fd, s, strlen(s));
        (void)w;
        close(fd);
    }
    l->cur = on;
    l->last_write = now;
}

static int blink(int64_t phase, int on_ms, int off_ms)
{
    int period = on_ms + off_ms;
    return period > 0 && (phase % period) < on_ms;
}

/* Desired level for LED i in auto, or -1 = leave alone. */
static int auto_level(int i, int64_t now)
{
    unsigned bit = 1u << i;
    int fb = (g_feedback_mask & bit) != 0, er = (g_error_mask & bit) != 0;
    if (!fb && !er)
        return -1;
    if (g_sys_state == LEDSTATE_ERROR || g_srv.net_error)
        return er ? blink(now, 250, 250) : 0;
    if (!fb)
        return 0;   /* error-only LED, no error: off */
    if (g_srv.busy)
        return blink(now, 100, 100);
    if (g_sys_state == LEDSTATE_STARTING)
        return blink(now, 1000, 1000);
    if (g_srv.last_request_ms && now - g_srv.last_request_ms < 10000) {
        int64_t p = now % 2000;
        return p < 150 || (p >= 300 && p < 450);
    }
    return 1;
}

static void *led_thread(void *arg)
{
    (void)arg;
    for (;;) {
        int64_t now = now_ms_mono();
        int i;
        pthread_mutex_lock(&g_led_mu);
        for (i = 0; i < g_srv.ad.n_leds; i++) {
            ledst_t *l = &g_led[i];
            int want;
            switch (l->mode) {
            case M_OFF:   want = 0; break;
            case M_ON:    want = 1; break;
            case M_BLINK: want = blink(now - l->t0, l->on_ms, l->off_ms); break;
            default:      want = auto_level(i, now); break;
            }
            if (want < 0)
                continue;
            if (want != l->cur || now - l->last_write >= REFRESH_MS)
                led_write(i, want, now);
        }
        pthread_mutex_unlock(&g_led_mu);
        sleep_ms(TICK_MS);
    }
    return NULL;
}

void leds_all_off(void)
{
    int i;
    for (i = 0; i < g_srv.ad.n_leds; i++) {
        /* Only LEDs we have driven; open/write/close are signal-safe. */
        if (g_led[i].prepared) {
            int fd = open(g_led[i].bpath, O_WRONLY);
            if (fd >= 0) {
                ssize_t w = write(fd, "0", 1);
                (void)w;
                close(fd);
            }
        }
    }
}

void leds_set_system_state(int st)
{
    g_sys_state = st;
}

void leds_start(void)
{
    unsigned all = (g_srv.ad.n_leds >= 32) ? ~0u : ((1u << g_srv.ad.n_leds) - 1);
    unsigned mapped = 0;
    const char *err_cfg;
    pthread_t th;
    pthread_attr_t at;
    int i;

    /* "all" never drives an unidentified led.N: one of them may be a GPIO
     * that is not an LED. Explicit index lists are taken as given. */
    for (i = 0; i < g_srv.ad.n_leds; i++)
        if (strcmp(g_srv.ad.leds[i].role, "unknown") != 0)
            mapped |= 1u << i;
    for (i = 0; i < g_srv.ad.n_leds; i++) {
        memset(&g_led[i], 0, sizeof g_led[i]);
        g_led[i].cur = -1;
        snprintf(g_led[i].bpath, sizeof g_led[i].bpath, "%s/brightness", g_srv.ad.leds[i].dir);
        snprintf(g_led[i].on_str, sizeof g_led[i].on_str, "%d", g_srv.ad.leds[i].max_brightness);
    }
    g_feedback_mask = parse_mask(config_get(&g_srv.cfg, "led_feedback", "none"), all);
    if (strcmp(config_get(&g_srv.cfg, "led_feedback", "none"), "all") == 0)
        g_feedback_mask &= mapped;
    err_cfg = config_get(&g_srv.cfg, "led_error", NULL);
    if (err_cfg) {
        g_error_mask = parse_mask(err_cfg, all);
        if (strcmp(err_cfg, "all") == 0)
            g_error_mask &= mapped;
    } else {
        g_error_mask = 0;
        for (i = 0; i < g_srv.ad.n_leds; i++)
            if (strcmp(g_srv.ad.leds[i].role, "status_red") == 0)
                g_error_mask |= 1u << i;
        if (!g_error_mask)
            g_error_mask = g_feedback_mask;
    }
    LOGI("leds: %d LED(s), feedback mask 0x%x, error mask 0x%x", g_srv.ad.n_leds, g_feedback_mask, g_error_mask);
    if (g_srv.ad.n_leds == 0)
        return;
    pthread_attr_init(&at);
    pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&at, 128 * 1024);
    pthread_create(&th, &at, led_thread, NULL);
}

int leds_manual(const char *id, const char *state, int on_ms, int off_ms)
{
    int i;
    for (i = 0; i < g_srv.ad.n_leds; i++) {
        if (strcmp(g_srv.ad.leds[i].id, id) != 0)
            continue;
        pthread_mutex_lock(&g_led_mu);
        if (strcmp(state, "off") == 0) g_led[i].mode = M_OFF;
        else if (strcmp(state, "on") == 0) g_led[i].mode = M_ON;
        else if (strcmp(state, "blink") == 0) g_led[i].mode = M_BLINK;
        else g_led[i].mode = M_AUTO;
        g_led[i].on_ms = on_ms;
        g_led[i].off_ms = off_ms;
        g_led[i].t0 = now_ms_mono();
        g_led[i].cur = -1;   /* force a write on the next tick */
        pthread_mutex_unlock(&g_led_mu);
        LOGI("leds: %s -> %s (%d/%d)", id, state, on_ms, off_ms);
        return 0;
    }
    return -1;
}

void api_led(req_t *r)
{
    jobj_t o;
    const char *id, *state;
    int wrong = 0, i, found = -1;
    double on = 500, off = 500;
    sbuf_t b;

    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    id = jobj_get_str(&o, "id", &wrong);
    if (!id || wrong) {
        http_send_error(r, 400, "bad_request", "missing or invalid field: id");
        return;
    }
    state = jobj_get_str(&o, "state", &wrong);
    if (!state || wrong) {
        http_send_error(r, 400, "bad_request", "missing or invalid field: state");
        return;
    }
    for (i = 0; i < g_srv.ad.n_leds; i++)
        if (strcmp(g_srv.ad.leds[i].id, id) == 0)
            found = i;
    if (found < 0) {
        http_send_error(r, 400, "bad_request", "unknown LED id '%s'", id);
        return;
    }
    if (strcmp(state, "off") && strcmp(state, "on") && strcmp(state, "blink") && strcmp(state, "auto")) {
        http_send_error(r, 400, "bad_request", "unknown LED state '%s'", state);
        return;
    }
    if (strcmp(state, "blink") == 0) {
        if (jobj_get_num(&o, "on_ms", &on) < 0 || jobj_get_num(&o, "off_ms", &off) < 0 ||
            on < 50 || on > 5000 || off < 50 || off > 5000) {
            http_send_error(r, 400, "bad_request", "on_ms/off_ms must be 50..5000");
            return;
        }
    }
    leds_manual(id, state, (int)on, (int)off);
    sbuf_init(&b);
    sbuf_puts(&b, "{\"id\":");
    json_str(&b, id);
    sbuf_puts(&b, ",\"state\":");
    json_str(&b, state);
    sbuf_puts(&b, "}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}
