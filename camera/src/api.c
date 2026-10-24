/*
 * Request routing and the small endpoints: info (§5.1), log (§5.9).
 * Status/capture/files/LEDs live in their own files.
 */
#include "server.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void str_list(sbuf_t *b, const char *const *v, int n)
{
    int i;
    sbuf_puts(b, "[");
    for (i = 0; i < n; i++) {
        if (i)
            sbuf_puts(b, ",");
        json_str(b, v[i]);
    }
    sbuf_puts(b, "]");
}

void api_capabilities_json(sbuf_t *b)
{
    const adapter_t *a = &g_srv.ad;
    const char *modes[3];
    int nm = 0, i;

    if (a->capture_modes & MODE_PHOTO) modes[nm++] = "photo";
    if (a->capture_modes & MODE_RAW) modes[nm++] = "raw";
    if (a->capture_modes & MODE_RAWPHOTO) modes[nm++] = "raw+photo";

    sbuf_puts(b, "{\"capture\":{\"modes\":");
    str_list(b, modes, nm);
    sbuf_puts(b, ",\"photo_qualities\":");
    str_list(b, nm ? a->qualities : NULL, nm ? a->n_qualities : 0);
    sbuf_puts(b, ",\"photo_resolutions\":");
    str_list(b, a->resolutions, a->n_resolutions);
    sbuf_printf(b, ",\"resolution_settable\":%s}", a->resolution_settable ? "true" : "false");
    sbuf_puts(b, ",\"video_modes\":");
    str_list(b, a->video_modes, a->n_video_modes);
    sbuf_puts(b, ",\"record\":false,\"liveview\":false");
    sbuf_printf(b, ",\"files\":{\"list\":true,\"download\":true,\"range\":true,\"delete\":%s,\"thumbnails\":%s}",
                a->files_delete ? "true" : "false", a->thumbnails ? "true" : "false");
    sbuf_printf(b, ",\"status\":{\"battery\":%s,\"storage\":true,\"battery_temperature\":%s}",
                g_srv.have_battery ? "true" : "false",
                (g_srv.have_battery && g_srv.have_battery_temp) ? "true" : "false");
    sbuf_puts(b, ",\"leds\":[");
    for (i = 0; i < a->n_leds; i++) {
        if (i)
            sbuf_puts(b, ",");
        sbuf_puts(b, "{\"id\":");
        json_str(b, a->leds[i].id);
        sbuf_puts(b, ",\"label\":");
        json_str(b, a->leds[i].label);
        sbuf_puts(b, ",\"role\":");
        json_str(b, a->leds[i].role);
        sbuf_puts(b, ",\"states\":[\"off\",\"on\",\"blink\",\"auto\"]}");
    }
    sbuf_printf(b, "],\"debug_log\":%s,\"time\":true}", log_path() ? "true" : "false");
}

static void api_info(req_t *r)
{
    const adapter_t *a = &g_srv.ad;
    sbuf_t b;
    char name[96], up[24];
    size_t i;

    /* "Gear 360 (65:EF:1E)" from the last 3 MAC bytes ("aa:bb:cc:65:ef:1e"). */
    for (i = 0; i + 1 < sizeof up && g_srv.mac[i]; i++)
        up[i] = (char)toupper((unsigned char)g_srv.mac[i]);
    up[i] = 0;
    snprintf(name, sizeof name, "%s (%s)", a->name_prefix, strlen(up) == 17 ? up + 9 : up);

    sbuf_init(&b);
    sbuf_printf(&b, "{\"protocol\":%d,\"server\":{\"name\":\"%s\",\"version\":\"%s\"},\"camera\":{",
                PROTOCOL_VERSION, G360RD_NAME, G360RD_VERSION);
    sbuf_puts(&b, "\"model\":");      json_str(&b, a->model);
    sbuf_puts(&b, ",\"model_raw\":"); json_str(&b, a->model_raw);
    sbuf_puts(&b, ",\"firmware\":");  json_str(&b, a->firmware);
    sbuf_puts(&b, ",\"adapter\":");   json_str(&b, a->id);
    sbuf_puts(&b, ",\"adapter_status\":"); json_str(&b, a->status);
    sbuf_puts(&b, ",\"mac\":");       json_str(&b, g_srv.mac);
    sbuf_puts(&b, ",\"name\":");      json_str(&b, name);
    sbuf_puts(&b, "},\"capabilities\":");
    api_capabilities_json(&b);
    sbuf_puts(&b, "}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}

/* GET /api/v1/log?source=system[&grep=TEXT][&lines=N]: the tail of the camera's
 * system log (dlogutil -d), for debugging the stock app from the phone.
 * grep is limited to [A-Za-z0-9_.:+-] and goes through grep -i -F. */
static void api_system_log(req_t *r, int lines, const char *grep)
{
    char cmd[256], *out;
    char *argv[] = { "/bin/sh", "-c", cmd, NULL };
    const size_t cap = 256 * 1024;

    for (const char *c = grep; *c; c++)
        if (!isalnum((unsigned char)*c) && !strchr("_.:+-", *c)) {
            http_send_error(r, 400, "bad_request", "grep may only hold letters, digits and _.:+-");
            return;
        }
    snprintf(cmd, sizeof cmd, "PATH=/usr/bin:/bin:/usr/sbin:/sbin; dlogutil -d 2>&1 | %s%s%s tail -n %d",
             *grep ? "grep -i -F -- '" : "", grep, *grep ? "' |" : "", lines);
    out = malloc(cap);
    if (!out) {
        http_send_error(r, 500, "internal", "out of memory");
        return;
    }
    run_cmd(argv, 10000, out, cap);
    http_send(r, 200, "text/plain; charset=utf-8", NULL, out, strlen(out), 0);
    free(out);
}

static void api_log(req_t *r)
{
    int lines = 200, system = 0;
    const char *q = r->query;
    char grep[64] = "";
    char *text;
    size_t len;

    /* lines=N, source=system, grep=TEXT; other parameters are ignored. */
    while (q && *q) {
        if (strncmp(q, "source=system", 13) == 0 && (q[13] == 0 || q[13] == '&'))
            system = 1;
        if (strncmp(q, "grep=", 5) == 0) {
            size_t n = strcspn(q + 5, "&");
            if (n >= sizeof grep)
                n = sizeof grep - 1;
            memcpy(grep, q + 5, n);
            grep[n] = 0;
        }
        if (strncmp(q, "lines=", 6) == 0) {
            char *end;
            long v = strtol(q + 6, &end, 10);
            if (end == q + 6 || (*end && *end != '&') || v < 1) {
                http_send_error(r, 400, "bad_request", "lines must be a positive integer");
                return;
            }
            lines = v > 2000 ? 2000 : (int)v;
        }
        q = strchr(q, '&');
        if (q)
            q++;
    }
    if (system) {
        api_system_log(r, lines, grep);
        return;
    }
    if (!log_path()) {
        http_send_error(r, 404, "not_found", "no log file configured");
        return;
    }
    text = log_tail(lines, &len);
    http_send(r, 200, "text/plain; charset=utf-8", NULL, text ? text : "", text ? len : 0, 0);
    free(text);
}

/* Method gate: returns 1 if allowed, else answers 405 and returns 0. */
static int allow(req_t *r, const char *allowed)
{
    char tmp[64], *save = NULL, *tok;
    snprintf(tmp, sizeof tmp, "%s", allowed);
    for (tok = strtok_r(tmp, ", ", &save); tok; tok = strtok_r(NULL, ", ", &save))
        if (strcmp(tok, r->method) == 0)
            return 1;
    snprintf(r->allow, sizeof r->allow, "%s", allowed);
    http_send_error(r, 405, "method_not_allowed", "%s not allowed here (allowed: %s)", r->method, allowed);
    return 0;
}

static int prefix(const char *s, const char *p)
{
    return strncmp(s, p, strlen(p)) == 0;
}

void api_dispatch(req_t *r)
{
    const char *p = r->path;
    char rel[2048];

    if (strcmp(p, "/api/v1/info") == 0) {
        if (allow(r, "GET")) api_info(r);
    } else if (strcmp(p, "/api/v1/status") == 0) {
        if (allow(r, "GET")) api_status(r);
    } else if (strcmp(p, "/api/v1/capture") == 0) {
        if (allow(r, "POST")) api_capture(r);
    } else if (strcmp(p, "/api/v1/files") == 0) {
        if (allow(r, "GET")) api_files_list(r);
    } else if (strcmp(p, "/api/v1/led") == 0) {
        if (allow(r, "POST")) api_led(r);
    } else if (strcmp(p, "/api/v1/time") == 0) {
        if (allow(r, "POST")) api_time(r);
    } else if (strcmp(p, "/api/v1/debug/dfms") == 0) {
        if (allow(r, "POST")) api_debug_dfms(r);
    } else if (strcmp(p, "/api/v1/debug/st") == 0) {
        if (allow(r, "POST")) api_debug_st(r);
    } else if (strcmp(p, "/api/v1/debug/vconf") == 0) {
        if (allow(r, "POST")) api_debug_vconf(r);
    } else if (strcmp(p, "/api/v1/log") == 0) {
        if (allow(r, "GET")) api_log(r);
    } else if (prefix(p, "/api/v1/thumb/")) {
        if (!allow(r, "GET"))
            return;
        if (path_decode_validate(p + strlen("/api/v1/thumb/"), rel, sizeof rel) < 0)
            http_send_error(r, 400, "bad_path", "path rejected");
        else
            api_thumb(r, rel);
    } else if (prefix(p, "/files/")) {
        if (!allow(r, "GET, HEAD, DELETE"))
            return;
        if (path_decode_validate(p + strlen("/files/"), rel, sizeof rel) < 0)
            http_send_error(r, 400, "bad_path", "path rejected");
        else if (strcmp(r->method, "DELETE") == 0)
            api_files_delete(r, rel);
        else
            api_files_get(r, rel, strcmp(r->method, "HEAD") == 0);
    } else {
        http_send_error(r, 404, "not_found", "unknown endpoint");
    }
}
