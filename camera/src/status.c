/*
 * GET /api/v1/status (protocol §5.2): battery from the Linux power_supply
 * class (discovered at startup, every supply logged once for diagnosis),
 * storage from statvfs on the storage root, busy flag, uptime.
 */
#include "server.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/statvfs.h>

static int read_attr(const char *dir, const char *name, char *out, size_t len)
{
    char p[400];
    snprintf(p, sizeof p, "%s/%s", dir, name);
    return read_text_file(p, out, len);
}

/* Log every power supply and its small attributes (diagnostics for the
 * hardware test; the exact sec-battery attribute set is unconfirmed). */
static void log_supplies(const char *base)
{
    DIR *d, *ad;
    struct dirent *de, *ae;
    trace_note("opendir", base);
    d = opendir(base);
    if (!d) {
        LOGW("status: no %s", base);
        return;
    }
    while ((de = readdir(d)) != NULL) {
        char dir[400];
        sbuf_t b;
        if (de->d_name[0] == '.')
            continue;
        snprintf(dir, sizeof dir, "%s/%s", base, de->d_name);
        trace_note("opendir", dir);
        ad = opendir(dir);
        if (!ad)
            continue;
        sbuf_init(&b);
        while ((ae = readdir(ad)) != NULL) {
            char v[96];
            if (ae->d_name[0] == '.' || strcmp(ae->d_name, "uevent") == 0)
                continue;
            if (read_attr(dir, ae->d_name, v, sizeof v) >= 0 && !strchr(v, '\n'))
                sbuf_printf(&b, " %s=%s", ae->d_name, v);
        }
        closedir(ad);
        LOGI("status: power_supply %s:%s", de->d_name, b.buf ? b.buf : "");
        sbuf_free(&b);
    }
    closedir(d);
}

void status_init(void)
{
    const char *base = g_srv.ad.power_supply_dir;
    DIR *d;
    struct dirent *de;
    char best[300] = "", v[64];
    int best_score = -1;

    log_supplies(base);
    trace_note("opendir", base);
    d = opendir(base);
    if (d) {
        while ((de = readdir(d)) != NULL) {
            char dir[300];
            int score = 0;
            if (de->d_name[0] == '.')
                continue;
            snprintf(dir, sizeof dir, "%s/%s", base, de->d_name);
            if (read_attr(dir, "capacity", v, sizeof v) <= 0)
                continue;
            /* Prefer sec-battery ("battery", type Battery) over fuel-gauge
             * helpers such as adc_battery. */
            if (read_attr(dir, "type", v, sizeof v) > 0 && strcasecmp(v, "Battery") == 0)
                score += 2;
            if (strcmp(de->d_name, "battery") == 0)
                score += 4;
            if (read_attr(dir, "status", v, sizeof v) > 0)
                score += 1;
            if (score > best_score) {
                best_score = score;
                snprintf(best, sizeof best, "%s", dir);
            }
        }
        closedir(d);
    }
    /* Explicit override from the SD config. */
    {
        const char *o = config_get(&g_srv.cfg, "battery_supply", NULL);
        if (o && *o)
            snprintf(best, sizeof best, "%s/%s", base, o);
    }
    if (best[0]) {
        snprintf(g_srv.battery_dir, sizeof g_srv.battery_dir, "%s", best);
        g_srv.have_battery = 1;
        g_srv.have_battery_temp = read_attr(best, "temp", v, sizeof v) > 0 ||
                                  read_attr(best, "batt_temp", v, sizeof v) > 0;
        LOGI("status: battery source %s (temperature %s)", best, g_srv.have_battery_temp ? "yes" : "no");
    } else {
        LOGW("status: no battery found under %s", base);
    }
}

/* Any charger supply (Mains/USB/Wireless...) reporting online=1. */
static int any_charger_online(void)
{
    DIR *d = opendir(g_srv.ad.power_supply_dir);
    struct dirent *de;
    int on = 0;
    if (!d)
        return 0;
    while (!on && (de = readdir(d)) != NULL) {
        char dir[400], v[32];
        if (de->d_name[0] == '.')
            continue;
        snprintf(dir, sizeof dir, "%s/%s", g_srv.ad.power_supply_dir, de->d_name);
        if (read_attr(dir, "type", v, sizeof v) <= 0 || strcasecmp(v, "Battery") == 0)
            continue;
        if (read_attr(dir, "online", v, sizeof v) > 0 && atoi(v) == 1)
            on = 1;
    }
    closedir(d);
    return on;
}

static void battery_json(sbuf_t *b)
{
    const char *dir = g_srv.battery_dir;
    char v[64];
    int pct = -1, ext;
    const char *st = "unknown";

    if (read_attr(dir, "capacity", v, sizeof v) > 0)
        pct = atoi(v);
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    if (read_attr(dir, "status", v, sizeof v) > 0) {
        if (strcasecmp(v, "Charging") == 0) st = "charging";
        else if (strcasecmp(v, "Discharging") == 0) st = "discharging";
        else if (strcasecmp(v, "Full") == 0) st = "full";
        else if (strcasecmp(v, "Not charging") == 0) st = "not_charging";
    }
    ext = any_charger_online() || strcmp(st, "charging") == 0 || strcmp(st, "full") == 0;
    /* sec-battery reports the cable type as "online": 1 = battery only. */
    if (!ext && read_attr(dir, "online", v, sizeof v) > 0 && atoi(v) > 1)
        ext = 1;

    sbuf_printf(b, "\"battery\":{\"percent\":%d,\"charging\":%s,\"status\":\"%s\",\"external_power\":%s",
                pct, strcmp(st, "charging") == 0 ? "true" : "false", st, ext ? "true" : "false");
    if (g_srv.have_battery_temp &&
        (read_attr(dir, "temp", v, sizeof v) > 0 || read_attr(dir, "batt_temp", v, sizeof v) > 0)) {
        double t = atof(v) / 10.0;     /* power_supply temp is in 0.1 degC */
        if (t > -40.0 && t < 125.0)
            sbuf_printf(b, ",\"temperature_c\":%.1f", t);
        else
            sbuf_puts(b, ",\"temperature_c\":null");
    } else {
        sbuf_puts(b, ",\"temperature_c\":null");
    }
    sbuf_puts(b, "}");
}

static void storage_json(sbuf_t *b)
{
    struct statvfs vs;
    if (adapter_storage_mounted(&g_srv.ad) && statvfs(g_srv.ad.storage_root, &vs) == 0) {
        unsigned long long total = (unsigned long long)vs.f_blocks * vs.f_frsize;
        unsigned long long avail = (unsigned long long)vs.f_bavail * vs.f_frsize;
        sbuf_printf(b, "\"storage\":{\"mounted\":true,\"total_bytes\":%llu,\"free_bytes\":%llu,\"used_bytes\":%llu}",
                    total, avail, total >= avail ? total - avail : 0ULL);
    } else {
        sbuf_puts(b, "\"storage\":{\"mounted\":false,\"total_bytes\":0,\"free_bytes\":0,\"used_bytes\":0}");
    }
}

void api_status(req_t *r)
{
    sbuf_t b;
    sbuf_init(&b);
    sbuf_puts(&b, "{");
    if (g_srv.have_battery) {
        battery_json(&b);
        sbuf_puts(&b, ",");
    }
    storage_json(&b);
    sbuf_printf(&b, ",\"busy\":%s,\"uptime_s\":%lld}", g_srv.busy ? "true" : "false",
                (long long)((now_ms_mono() - g_srv.start_ms) / 1000));
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}
