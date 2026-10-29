/*
 * POST /api/v1/debug/dfms (protocol.md §5.11): run one dfms script command on
 * the camera and list the files it wrote. A development aid to explore what the
 * stock app can do in normal mode (where it ignores st and obeys dfms), e.g. the
 * uncompressed capture modes, without a card swap and a reboot per question.
 *
 * Body {"script": "+SCAMCAPT,0,0,0", "wait_ms": 8000}. The script is passed as
 * one argv element to dfmstool (no shell) and limited to [A-Za-z0-9 +,._-].
 * After wait_ms the answer lists the files that appeared under the card and the
 * RAM disks (listings before and after, compared: mtimes are no use, the clock
 * jumps when the app sets the time).
 */
#include "server.h"

#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define DFMSTOOL "/usr/bin/dfmstool"
#define LIST_CMD "find /mnt/mmc /opt/storage /tmp /dev/shm -xdev -type f ! -path '/tmp/g360r/*' 2>/dev/null | sort"

void api_debug_dfms(req_t *r)
{
    jobj_t o;
    const char *script;
    double wait_ms = 5000;
    int wrong = 0, rc;
    char out[4096], found[16384];
    char *dargv[] = { DFMSTOOL, "--script", NULL, NULL };
    char *before[] = { "/bin/sh", "-c", LIST_CMD " > /tmp/g360r/files_before", NULL };
    char *fargv[] = { "/bin/sh", "-c",
        LIST_CMD " > /tmp/g360r/files_after;"
        " grep -vxF -f /tmp/g360r/files_before /tmp/g360r/files_after | head -n 60 |"
        " while read -r f; do ls -la \"$f\"; done", NULL };
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

    run_cmd(before, 15000, NULL, 0);
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

/*
 * POST /api/v1/debug/shim {"req": "usr 0x10 1"}: one request to the shim loaded
 * into di-camera-app (shim/g360r_shim.c) over /tmp/g360r/shim.sock; answers its
 * reply and the tail of /tmp/g360r/shim.log (what the app itself set).
 */
#include <sys/socket.h>
#include <sys/un.h>

#define SHIM_SOCK "/tmp/g360r/shim.sock"

/*
 * PUT /api/v1/debug/upload?name=FILE: writes the body (up to UPLOAD_MAX) to
 * the card's g360r/FILE, through FILE.part and a rename. A development aid:
 * a new plugin goes onto the card over Wi-Fi and the running one loads it
 * ("chain FILE"), no card swap. FILE is a plain name ([A-Za-z0-9._-], not
 * starting with a dot).
 */
/* Puts part in place of to. The camera's exFAT driver does not replace on a
 * rename over an existing file: it adds a second entry of the same name (the
 * card ended up with seven bin/g360rd), so the old one goes first. */
static int replace_file(const char *part, const char *to)
{
    unlink(to);
    return rename(part, to);
}

/* Copies from to to through to.part, mode 0755; 0 or -1 */
static int copy_exec(const char *from, const char *to)
{
    char part[560], buf[16384];
    FILE *in = fopen(from, "rb"), *out;
    size_t n;
    int rc = 0;

    if (!in)
        return -1;
    snprintf(part, sizeof part, "%s.part", to);
    if (!(out = fopen(part, "wb"))) {
        fclose(in);
        return -1;
    }
    while ((n = fread(buf, 1, sizeof buf, in)) > 0)
        if (fwrite(buf, 1, n, out) != n)
            rc = -1;
    fclose(in);
    if (fclose(out) != 0 || rc < 0 || chmod(part, 0755) != 0 || replace_file(part, to) != 0) {
        unlink(part);
        return -1;
    }
    return 0;
}

/*
 * POST /api/v1/debug/restart?replace=FILE: puts the uploaded g360r/FILE in
 * place of the running controller (the copy in the run directory, which the
 * launcher restarts, and g360r/bin/g360rd on the card), then exits; the
 * launcher starts the new one within a few seconds. FILE must be an ARM ELF.
 * Without replace= it only restarts.
 */
void api_debug_restart(req_t *r)
{
    const char *name = strncmp(r->query, "replace=", 8) == 0 ? r->query + 8 : "";
    char from[512], run_bin[320], sd_bin[320], head[20] = "";
    FILE *f;

    if (*name) {
        for (const char *c = name; *c; c++)
            if ((!isalnum((unsigned char)*c) && !strchr("._-", *c)) || (c == name && *c == '.')) {
                http_send_error(r, 400, "bad_request", "replace= must be a file name in g360r/");
                return;
            }
        snprintf(from, sizeof from, "%s/g360r/%s", g_srv.ad.storage_root, name);
        if (!(f = fopen(from, "rb")) || fread(head, 1, sizeof head, f) != sizeof head) {
            if (f)
                fclose(f);
            http_send_error(r, 404, "not_found", "no %s to put in place", from);
            return;
        }
        fclose(f);
        /* ELF, 32-bit, little-endian, e_machine 40 (ARM) */
        if (memcmp(head, "\177ELF\001\001", 6) != 0 || head[18] != 40) {
            http_send_error(r, 400, "bad_request", "%s is not an ARM executable", name);
            return;
        }
        snprintf(run_bin, sizeof run_bin, "%s/g360rd", g_srv.ad.run_dir);
        snprintf(sd_bin, sizeof sd_bin, "%s/g360r/bin/g360rd", g_srv.ad.storage_root);
        if (copy_exec(from, run_bin) < 0 || copy_exec(from, sd_bin) < 0) {
            http_send_error(r, 500, "internal", "cannot put %s in place: %s", name, strerror(errno));
            return;
        }
        LOGI("debug: %s put in place of the controller", name);
    }
    {
        sbuf_t b;
        sbuf_init(&b);
        sbuf_puts(&b, "{\"restarting\":true}");
        http_send_json(r, 200, &b);
        sbuf_free(&b);
    }
    /* A live view session ends cleanly first (see liveview_shutdown) */
    liveview_shutdown();
    LOGI("debug: restarting the controller");
    /* The log is written line by line: nothing to flush */
    _exit(0);
}

void api_debug_upload(req_t *r)
{
    const char *name = strncmp(r->query, "name=", 5) == 0 ? r->query + 5 : "";
    char path[512], part[520], buf[16384];
    long left;
    FILE *f;
    size_t len = strlen(name);

    if (len == 0 || len > 64 || name[0] == '.') {
        http_send_error(r, 400, "bad_request", "name= must be a file name");
        return;
    }
    for (const char *c = name; *c; c++)
        if (!isalnum((unsigned char)*c) && !strchr("._-", *c)) {
            http_send_error(r, 400, "bad_request", "name may only hold letters, digits and ._-");
            return;
        }
    if (r->content_length < 0) {
        http_send_error(r, 411, "length_required", "the upload needs a Content-Length");
        return;
    }
    snprintf(path, sizeof path, "%s/g360r/%s", g_srv.ad.storage_root, name);
    snprintf(part, sizeof part, "%s.part", path);
    if (!(f = fopen(part, "wb"))) {
        http_send_error(r, 500, "internal", "cannot write %s: %s", part, strerror(errno));
        return;
    }
    fwrite(r->body, 1, r->body_len, f);
    left = r->content_length - (long)r->body_len;
    while (r->body_streamed && left > 0) {
        ssize_t n = recv(r->fd, buf, left < (long)sizeof buf ? (size_t)left : sizeof buf, 0);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            break;
        fwrite(buf, 1, (size_t)n, f);
        left -= n;
    }
    if (fclose(f) != 0 || left > 0 || replace_file(part, path) != 0) {
        unlink(part);
        http_send_error(r, 500, "internal", "upload of %s incomplete (%ld bytes missing)", name, left);
        return;
    }
    LOGI("debug: uploaded %s (%ld bytes)", path, r->content_length);
    chmod(path, 0755);
    {
        sbuf_t b;
        sbuf_init(&b);
        sbuf_puts(&b, "{\"written\":");
        json_str(&b, path);
        sbuf_printf(&b, ",\"bytes\":%ld}", r->content_length);
        http_send_json(r, 200, &b);
        sbuf_free(&b);
    }
}

void api_debug_shim(req_t *r)
{
    jobj_t o;
    const char *req;
    int wrong = 0, s;
    static char reply[16384], logtail[16384], files[4096];
    char *tail[] = { "/bin/sh", "-c", "tail -n 60 /tmp/g360r/shim.log 2>&1", NULL };
    char *lsdir[] = { "/bin/sh", "-c",
        "ls -la /tmp/g360r/; cat /proc/self/attr/current 2>/dev/null; echo;"
        " for p in $(pgrep -f di-camera-app); do echo \"app $p: $(cat /proc/$p/attr/current 2>/dev/null)"
        " maps: $(grep -c g360r_shim /proc/$p/maps 2>/dev/null)\"; done", NULL };
    struct sockaddr_un sa;
    sbuf_t b;
    ssize_t n;

    /* Static: 36 KB is a lot for a connection thread's stack; a debug aid, one request at a time */
    reply[0] = logtail[0] = files[0] = 0;
    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    req = jobj_get_str(&o, "req", &wrong);
    if (!req || wrong || !*req || strlen(req) > 200) {
        http_send_error(r, 400, "bad_request", "req must be a non-empty string");
        return;
    }
    for (const char *c = req; *c; c++)
        if (!isalnum((unsigned char)*c) && !strchr(" /._-", *c)) {
            http_send_error(r, 400, "bad_request", "req may only hold letters, digits, spaces and /._-");
            return;
        }
    /* Every step reports its errno, and the shim's log and files come back
     * whatever happened: on the first try connect worked and write failed. */
    s = socket(AF_UNIX, SOCK_STREAM, 0);
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s", SHIM_SOCK);
    if (s < 0) {
        snprintf(reply, sizeof reply, "error socket: %s", strerror(errno));
    } else if (connect(s, (struct sockaddr *)&sa, sizeof sa) < 0) {
        snprintf(reply, sizeof reply, "error connect %s: %s", SHIM_SOCK, strerror(errno));
    } else {
        char line[256];
        snprintf(line, sizeof line, "%s\n", req);
        if (send(s, line, strlen(line), MSG_NOSIGNAL) < 0) {
            snprintf(reply, sizeof reply, "error send: %s", strerror(errno));
        } else {
            n = read(s, reply, sizeof reply - 1); /* the shim answers within ~10 s */
            if (n > 0)
                reply[n] = 0;
            else
                snprintf(reply, sizeof reply, "error read: %s", n < 0 ? strerror(errno) : "connection closed, no answer");
        }
    }
    if (s >= 0)
        close(s);
    LOGI("debug: shim \"%s\" -> %s", req, reply);
    run_cmd(tail, 5000, logtail, sizeof logtail);
    run_cmd(lsdir, 5000, files, sizeof files);

    sbuf_init(&b);
    sbuf_puts(&b, "{\"reply\":");
    json_str(&b, reply);
    sbuf_puts(&b, ",\"shim_log\":");
    json_str(&b, logtail);
    sbuf_puts(&b, ",\"files\":");
    json_str(&b, files);
    sbuf_puts(&b, "}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}

/*
 * POST /api/v1/debug/script?name=FILE: runs the card's g360r/FILE (put there
 * with the upload above) with /bin/sh, as root, for at most 60 s, and answers
 * its exit code and output (stdout and stderr, the first 60 KB). A development
 * aid for probing the camera without a flash, like the rest of debug/.
 */
void api_debug_script(req_t *r)
{
    const char *name = strncmp(r->query, "name=", 5) == 0 ? r->query + 5 : "";
    char path[512];
    static char out[61440];
    char *argv[] = { "/bin/sh", path, NULL };
    size_t len = strlen(name);
    int rc;
    sbuf_t b;

    if (len == 0 || len > 64 || name[0] == '.') {
        http_send_error(r, 400, "bad_request", "name= must be a file name");
        return;
    }
    for (const char *c = name; *c; c++)
        if (!isalnum((unsigned char)*c) && !strchr("._-", *c)) {
            http_send_error(r, 400, "bad_request", "name may only hold letters, digits and ._-");
            return;
        }
    snprintf(path, sizeof path, "%s/g360r/%s", g_srv.ad.storage_root, name);
    if (access(path, R_OK) != 0) {
        http_send_error(r, 404, "not_found", "no g360r/%s on the card", name);
        return;
    }
    out[0] = 0;
    rc = run_cmd(argv, 60000, out, sizeof out);
    LOGI("debug: script %s -> rc=%d (%zu bytes out)", name, rc, strlen(out));
    sbuf_init(&b);
    sbuf_printf(&b, "{\"rc\":%d,\"out\":", rc);
    json_str(&b, out);
    sbuf_puts(&b, "}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}
