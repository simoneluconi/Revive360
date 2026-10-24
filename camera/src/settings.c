/*
 * Camera settings (protocol §5.12): GET/POST /api/v1/settings, the
 * capabilities.settings list, and the "photo mode before a still" rule of §5.3.
 * The adapter defines the settings and reads/writes them; this file validates.
 */
#include "server.h"

#include <stdio.h>
#include <string.h>

const setting_def_t *settings_find(const char *id)
{
    const adapter_t *a = &g_srv.ad;
    for (int i = 0; i < a->n_settings; i++)
        if (strcmp(a->settings[i].id, id) == 0)
            return &a->settings[i];
    return NULL;
}

static int token_ok(const setting_def_t *d, const char *v)
{
    for (int i = 0; i < d->n_values; i++)
        if (strcmp(d->values[i], v) == 0)
            return 1;
    return 0;
}

void settings_capabilities_json(sbuf_t *b)
{
    const adapter_t *a = &g_srv.ad;
    sbuf_puts(b, "[");
    for (int i = 0; i < a->n_settings; i++) {
        const setting_def_t *d = &a->settings[i];
        if (i)
            sbuf_puts(b, ",");
        sbuf_puts(b, "{\"id\":");
        json_str(b, d->id);
        sbuf_puts(b, ",\"values\":[");
        for (int j = 0; j < d->n_values; j++) {
            if (j)
                sbuf_puts(b, ",");
            json_str(b, d->values[j]);
        }
        sbuf_puts(b, "]}");
    }
    sbuf_puts(b, "]");
}

static void send_current(req_t *r)
{
    adapter_t *a = &g_srv.ad;
    sbuf_t b;
    char v[64];

    sbuf_init(&b);
    sbuf_puts(&b, "{\"settings\":{");
    for (int i = 0; i < a->n_settings; i++) {
        if (i)
            sbuf_puts(&b, ",");
        json_str(&b, a->settings[i].id);
        sbuf_puts(&b, ":");
        if (a->get_setting && a->get_setting(a, a->settings[i].id, v, sizeof v) == 0)
            json_str(&b, v);
        else
            sbuf_puts(&b, "null");
    }
    sbuf_puts(&b, "}}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}

void api_settings(req_t *r)
{
    adapter_t *a = &g_srv.ad;
    jobj_t o;
    const char *vals[MAX_SETTINGS];
    char err[200];

    if (a->n_settings == 0 || !a->set_setting) {
        http_send_error(r, 501, "unsupported", "this camera has no settings");
        return;
    }
    if (strcmp(r->method, "GET") == 0) {
        send_current(r);
        return;
    }
    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    /* Validate everything before changing anything. Unknown keys in the body
     * are rejected too: a typo must not look like success. */
    for (int k = 0; k < o.n; k++) {
        if (!settings_find(o.m[k].key)) {
            http_send_error(r, 400, "bad_request", "unknown setting '%s'", o.m[k].key);
            return;
        }
    }
    for (int i = 0; i < a->n_settings; i++) {
        int wrong = 0;
        vals[i] = jobj_get_str(&o, a->settings[i].id, &wrong);
        if (wrong || (vals[i] && !token_ok(&a->settings[i], vals[i]))) {
            http_send_error(r, 400, "bad_request", "invalid value for '%s'", a->settings[i].id);
            return;
        }
    }
    if (g_srv.busy || g_srv.recording) {
        http_send_error(r, 409, "busy", "a capture or a recording is in progress");
        return;
    }
    for (int i = 0; i < a->n_settings; i++) {
        if (!vals[i])
            continue;
        err[0] = 0;
        LOGI("settings: %s = %s", a->settings[i].id, vals[i]);
        if (a->set_setting(a, a->settings[i].id, vals[i], err, sizeof err) < 0) {
            LOGE("settings: %s = %s failed: %s", a->settings[i].id, vals[i], err);
            http_send_error(r, 500, "internal", "setting %s failed: %s", a->settings[i].id, err);
            return;
        }
    }
    send_current(r);
}

/* §5.3: a still needs photo mode; switch when the camera reports another one
 * (or cannot tell). 0 = ready, -1 + err when the switch failed. */
int settings_ensure_mode(const char *mode, char *err, size_t errlen)
{
    adapter_t *a = &g_srv.ad;
    char cur[64];

    if (!settings_find("mode") || !a->set_setting)
        return 0;
    if (a->get_setting && a->get_setting(a, "mode", cur, sizeof cur) == 0 && strcmp(cur, mode) == 0)
        return 0;
    LOGI("settings: switching mode to %s before the request", mode);
    return a->set_setting(a, "mode", mode, err, errlen);
}
