/*
 * POST /api/v1/time (protocol.md §5.10): the phone sets the camera clock.
 *
 * The camera has no network time and a firmware flash resets its RTC, so the
 * app sends UTC on every connection. We set the system clock (settimeofday)
 * and the battery-backed RTC (/dev/rtc0), and report what was applied. On an
 * adapter with clock_local the camera shows local time through its own fixed
 * zone (C200 AQK1: +9 h, seen in EXIF and in libc localtime), so the clock is
 * set to utc + tz_offset_min - <camera zone offset>: the camera then shows the
 * phone's local time. The IANA id is only logged. The sim adapter never
 * touches the host clock.
 */
#include "server.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/rtc.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define MIN_UTC_MS 1577836800000.0   /* 2020-01-01: anything older is a phone without time */
#define MAX_UTC_MS 4102444800000.0   /* 2100-01-01 */

static int rtc_write(time_t t)
{
    static const char *const devs[] = { "/dev/rtc0", "/dev/rtc" };
    struct tm tm;
    struct rtc_time rt;
    unsigned i;

    gmtime_r(&t, &tm);
    memset(&rt, 0, sizeof rt);
    rt.tm_sec = tm.tm_sec;
    rt.tm_min = tm.tm_min;
    rt.tm_hour = tm.tm_hour;
    rt.tm_mday = tm.tm_mday;
    rt.tm_mon = tm.tm_mon;
    rt.tm_year = tm.tm_year;
    for (i = 0; i < sizeof devs / sizeof devs[0]; i++) {
        int fd = open(devs[i], O_RDONLY | O_CLOEXEC), rc;
        if (fd < 0)
            continue;
        rc = ioctl(fd, RTC_SET_TIME, &rt);
        close(fd);
        if (rc == 0) {
            LOGI("time: RTC %s set", devs[i]);
            return 0;
        }
        LOGW("time: RTC %s: %s", devs[i], strerror(errno));
    }
    return -1;
}

void api_time(req_t *r)
{
    jobj_t o;
    double utc_ms, offset_min = 0;
    const char *tz;
    int wrong = 0, n = 0;
    int64_t shift_ms;
    struct timeval tv;
    sbuf_t b;
    const char *applied[3];

    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    if (jobj_get_num(&o, "utc_ms", &utc_ms) != 1 || utc_ms < MIN_UTC_MS || utc_ms > MAX_UTC_MS) {
        http_send_error(r, 400, "bad_request", "missing or implausible utc_ms");
        return;
    }
    if (jobj_get_num(&o, "tz_offset_min", &offset_min) < 0) {
        http_send_error(r, 400, "bad_request", "invalid tz_offset_min");
        return;
    }
    tz = jobj_get_str(&o, "tz", &wrong);
    LOGI("time: request utc_ms=%.0f tz=%s offset=%+.0f min", utc_ms, tz && !wrong ? tz : "-", offset_min);

    if (offset_min < -14 * 60 || offset_min > 14 * 60) {
        http_send_error(r, 400, "bad_request", "implausible tz_offset_min");
        return;
    }
    if (g_srv.ad.clock_local) {
        time_t now = time(NULL);
        struct tm lt;
        long cam_off_s = localtime_r(&now, &lt) ? lt.tm_gmtoff : 0;
        shift_ms = ((int64_t)offset_min * 60 - cam_off_s) * 1000;
        LOGI("time: camera zone %+ld min, phone %+.0f min", cam_off_s / 60, offset_min);
    } else {
        shift_ms = 0;
    }

    if (strcmp(g_srv.ad.id, "sim") != 0) {
        int64_t wall_ms = (int64_t)utc_ms + shift_ms;
        tv.tv_sec = (time_t)(wall_ms / 1000);
        tv.tv_usec = (suseconds_t)((wall_ms % 1000) * 1000);
        if (settimeofday(&tv, NULL) == 0)
            applied[n++] = "system";
        else
            LOGW("time: settimeofday: %s", strerror(errno));
        if (rtc_write(tv.tv_sec) == 0)
            applied[n++] = "rtc";
        if (shift_ms && n)
            applied[n++] = "timezone";
    }

    gettimeofday(&tv, NULL);
    sbuf_init(&b);
    sbuf_printf(&b, "{\"utc_ms\":%lld,\"applied\":[", (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000 - (long long)shift_ms);
    for (int i = 0; i < n; i++) {
        if (i)
            sbuf_puts(&b, ",");
        json_str(&b, applied[i]);
    }
    sbuf_puts(&b, "]}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}
