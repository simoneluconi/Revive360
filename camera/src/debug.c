/*
 * POST /api/v1/debug/dfms (protocol.md §5.11): run one dfms script command on
 * the camera and list the files it wrote. A development aid to explore what the
 * stock app can do in normal mode (where it ignores st and obeys dfms), e.g. the
 * uncompressed capture modes, without a card swap and a reboot per question.
 *
 * Body {"script": "+SCAMCAPT,0,0,0", "wait_ms": 8000}. The script is passed as
 * one argv element to dfmstool (no shell) and limited to [A-Za-z0-9 +,._-].
 * After wait_ms the answer lists every file newer than the request under the
 * card and the RAM disks.
 */
#include "server.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DFMSTOOL "/usr/bin/dfmstool"
#define MARKER   "/tmp/g360r/dfms_marker"

void api_debug_dfms(req_t *r)
{
    jobj_t o;
    const char *script;
    double wait_ms = 5000;
    int wrong = 0, rc;
    char out[4096], found[16384];
    char *dargv[] = { DFMSTOOL, "--script", NULL, NULL };
    char *fargv[] = { "/bin/sh", "-c",
        "find /mnt/mmc /opt/storage /tmp /dev/shm -xdev -type f -newer " MARKER
        " ! -path '/tmp/g360r/*' -exec ls -la {} \\; 2>/dev/null | head -n 60", NULL };
    sbuf_t b;

    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    script = jobj_get_str(&o, "script", &wrong);
    if (!script || wrong || !*script || strlen(script) > 200) {
        http_send_error(r, 400, "bad_request", "script must be a non-empty string");
        return;
    }
    for (const char *c = script; *c; c++)
        if (!isalnum((unsigned char)*c) && !strchr(" +,._-", *c)) {
            http_send_error(r, 400, "bad_request", "script may only hold letters, digits, space and +,._-");
            return;
        }
    if (jobj_get_num(&o, "wait_ms", &wait_ms) < 0 || wait_ms < 0 || wait_ms > 60000) {
        http_send_error(r, 400, "bad_request", "wait_ms must be 0..60000");
        return;
    }
    if (access(DFMSTOOL, X_OK) != 0) {
        http_send_error(r, 501, "unsupported", "no dfmstool on this camera");
        return;
    }

    write_text_file(MARKER, "1\n");
    sleep_ms(1100); /* mtime granularity: files written after this are newer */
    dargv[2] = (char *)script;
    rc = run_cmd(dargv, 15000, out, sizeof out);
    LOGI("debug: dfmstool --script \"%s\" -> rc=%d out=[%s]", script, rc, out);
    sleep_ms((int)wait_ms);
    run_cmd(fargv, 15000, found, sizeof found);

    sbuf_init(&b);
    sbuf_printf(&b, "{\"rc\":%d,\"out\":", rc);
    json_str(&b, out);
    sbuf_puts(&b, ",\"new_files\":");
    json_str(&b, found);
    sbuf_puts(&b, "}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}

/*
 * POST /api/v1/debug/st and /api/v1/debug/vconf: {"args": "app nx capture quality"}
 * runs /usr/bin/st or /usr/bin/vconftool with those space-separated arguments
 * (no shell, same character set as above) and answers its output, st's
 * /tmp/app_result and the system log lines written meanwhile.
 */
static void debug_run(req_t *r, const char *bin)
{
    jobj_t o;
    const char *args;
    int wrong = 0, rc, argc = 0;
    char buf[256], out[4096], result[2048], dlog[16384];
    char *argv[24], *save = NULL, *tok;
    char *lastlog[] = { "/bin/sh", "-c", "dlogutil -d 2>/dev/null | tail -n 40", NULL };
    sbuf_t b;

    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    args = jobj_get_str(&o, "args", &wrong);
    if (!args || wrong || strlen(args) >= sizeof buf) {
        http_send_error(r, 400, "bad_request", "args must be a string");
        return;
    }
    for (const char *c = args; *c; c++)
        if (!isalnum((unsigned char)*c) && !strchr(" +,._-/=", *c)) {
            http_send_error(r, 400, "bad_request", "args may only hold letters, digits, space and +,._-/=");
            return;
        }
    if (access(bin, X_OK) != 0) {
        http_send_error(r, 501, "unsupported", "no %s on this camera", bin);
        return;
    }
    snprintf(buf, sizeof buf, "%s", args);
    argv[argc++] = (char *)bin;
    for (tok = strtok_r(buf, " ", &save); tok && argc < 22; tok = strtok_r(NULL, " ", &save))
        argv[argc++] = tok;
    argv[argc] = NULL;

    unlink("/tmp/app_result");
    rc = run_cmd(argv, 8000, out, sizeof out);
    LOGI("debug: %s %s -> rc=%d out=[%s]", bin, args, rc, out);
    sleep_ms(500);
    if (read_text_file("/tmp/app_result", result, sizeof result) < 0)
        result[0] = 0;
    run_cmd(lastlog, 10000, dlog, sizeof dlog);

    sbuf_init(&b);
    sbuf_printf(&b, "{\"rc\":%d,\"out\":", rc);
    json_str(&b, out);
    sbuf_puts(&b, ",\"app_result\":");
    json_str(&b, result);
    sbuf_puts(&b, ",\"system_log\":");
    json_str(&b, dlog);
    sbuf_puts(&b, "}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}

void api_debug_st(req_t *r)
{
    debug_run(r, "/usr/bin/st");
}

void api_debug_vconf(req_t *r)
{
    debug_run(r, "/usr/bin/vconftool");
}
