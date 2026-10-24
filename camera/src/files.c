/*
 * File endpoints (protocol §2 Paths, §5.4-§5.7): list, thumbnail, download
 * (single Range + HEAD), delete. All paths are relative to the adapter's
 * storage root and must start with DCIM/.
 */
#include "server.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

/* ------------------------------------------------------------ kinds */

const char *file_kind(const char *name)
{
    if (str_iendswith(name, ".jpg") || str_iendswith(name, ".jpeg")) return "photo";
    if (str_iendswith(name, ".srw") || str_iendswith(name, ".dng"))  return "raw";
    if (str_iendswith(name, ".mp4") || str_iendswith(name, ".mov"))  return "video";
    return "other";
}

const char *file_mime(const char *name)
{
    if (str_iendswith(name, ".jpg") || str_iendswith(name, ".jpeg")) return "image/jpeg";
    if (str_iendswith(name, ".srw")) return "image/x-samsung-srw";
    if (str_iendswith(name, ".mp4")) return "video/mp4";
    return "application/octet-stream";
}

/* ------------------------------------------------------------ paths */

static int hexv(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

int path_decode_validate(const char *enc, char *out, size_t outlen)
{
    size_t n = 0;
    const char *s;
    char *seg, *next;

    for (s = enc; *s; s++) {
        char c = *s;
        if (c == '%') {
            int h = hexv(s[1]), l = h >= 0 ? hexv(s[2]) : -1;
            if (h < 0 || l < 0)
                return -1;
            c = (char)(h * 16 + l);
            s += 2;
            if (c == 0)
                return -1;         /* NUL */
        }
        if (c == '\\')
            return -1;
        if (n + 1 >= outlen)
            return -1;
        out[n++] = c;
    }
    out[n] = 0;
    if (strncmp(out, "DCIM/", 5) != 0)
        return -1;
    /* Every segment non-empty and not "." / "..". */
    for (seg = out; seg; seg = next) {
        size_t len;
        next = strchr(seg, '/');
        len = next ? (size_t)(next - seg) : strlen(seg);
        if (len == 0)
            return -1;
        if ((len == 1 && seg[0] == '.') || (len == 2 && seg[0] == '.' && seg[1] == '.'))
            return -1;
        if (next)
            next++;
    }
    return 0;
}

int path_resolve(const char *rel, char *abs, size_t abslen)
{
    char rroot[PATH_MAX], rabs[PATH_MAX];
    size_t rl;
    if ((size_t)snprintf(abs, abslen, "%s/%s", g_srv.ad.storage_root, rel) >= abslen)
        return -1;
    /* Defence in depth against symlinks pointing outside the card. */
    if (!realpath(g_srv.ad.storage_root, rroot))
        return 0;           /* storage gone: callers report 503/404 */
    if (!realpath(abs, rabs))
        return 0;           /* does not exist: callers report 404 */
    rl = strlen(rroot);
    if (strncmp(rabs, rroot, rl) != 0 || (rabs[rl] != '/' && rabs[rl] != 0))
        return -1;
    return 0;
}

/* ------------------------------------------------------------ listing */

static void flist_add(flist_t *fl, const char *path, const struct stat *st)
{
    if (fl->n == fl->cap) {
        size_t nc = fl->cap ? fl->cap * 2 : 128;
        fentry_t *nv = realloc(fl->v, nc * sizeof *nv);
        if (!nv)
            return;
        fl->v = nv;
        fl->cap = nc;
    }
    fl->v[fl->n].path = strdup(path);
    if (!fl->v[fl->n].path)
        return;
    fl->v[fl->n].size = (int64_t)st->st_size;
    fl->v[fl->n].mtime = (int64_t)st->st_mtime;
    fl->n++;
}

void flist_free(flist_t *fl)
{
    size_t i;
    for (i = 0; i < fl->n; i++)
        free(fl->v[i].path);
    free(fl->v);
    memset(fl, 0, sizeof *fl);
}

static void walk(flist_t *fl, const char *abs, const char *rel, int depth, int max_depth)
{
    DIR *d = opendir(abs);
    struct dirent *de;
    if (!d)
        return;
    while ((de = readdir(d)) != NULL) {
        char a[1024], r[1024];
        struct stat st;
        if (de->d_name[0] == '.')
            continue;   /* also skips "." and ".." */
        if ((size_t)snprintf(a, sizeof a, "%s/%s", abs, de->d_name) >= sizeof a ||
            (size_t)snprintf(r, sizeof r, "%s/%s", rel, de->d_name) >= sizeof r)
            continue;
        if (lstat(a, &st) < 0)
            continue;
        if (S_ISDIR(st.st_mode)) {
            if (depth < max_depth)
                walk(fl, a, r, depth + 1, max_depth);
        } else if (S_ISREG(st.st_mode)) {
            flist_add(fl, r, &st);
        }
    }
    closedir(d);
}

static int cmp_newest(const void *pa, const void *pb)
{
    const fentry_t *a = pa, *b = pb;
    if (a->mtime != b->mtime)
        return a->mtime > b->mtime ? -1 : 1;
    return -strcmp(a->path, b->path);
}

int files_list(flist_t *fl, int max_depth)
{
    char dcim[600];
    memset(fl, 0, sizeof *fl);
    snprintf(dcim, sizeof dcim, "%s/DCIM", g_srv.ad.storage_root);
    walk(fl, dcim, "DCIM", 0, max_depth);   /* dirs up to max_depth below DCIM */
    if (fl->n > 1)
        qsort(fl->v, fl->n, sizeof *fl->v, cmp_newest);
    return 0;
}

void fentry_json(sbuf_t *b, const fentry_t *e)
{
    const char *name = strrchr(e->path, '/');
    name = name ? name + 1 : e->path;
    sbuf_puts(b, "{\"path\":");
    json_str(b, e->path);
    sbuf_puts(b, ",\"name\":");
    json_str(b, name);
    sbuf_printf(b, ",\"size\":%lld,\"mtime\":%lld,\"kind\":\"%s\"}",
                (long long)e->size, (long long)e->mtime, file_kind(name));
}

void api_files_list(req_t *r)
{
    flist_t fl;
    sbuf_t b;
    size_t i;

    if (!adapter_storage_mounted(&g_srv.ad)) {
        http_send_error(r, 503, "no_storage", "the SD card is not mounted");
        return;
    }
    /* "max depth 3": up to DCIM/a/b/c/<file>. */
    files_list(&fl, 3);
    sbuf_init(&b);
    sbuf_puts(&b, "{\"files\":[");
    for (i = 0; i < fl.n; i++) {
        if (i)
            sbuf_puts(&b, ",");
        fentry_json(&b, &fl.v[i]);
    }
    sbuf_puts(&b, "]}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
    flist_free(&fl);
}

/* ------------------------------------------------------------ download */

/* Parse a single "bytes=" range. Returns 1 with [*a,*b] set (inclusive), 0 to
 * ignore the header (absent, multi-range, or syntactically invalid), -1 for
 * unsatisfiable. */
static int parse_range(const char *h, int64_t size, int64_t *a, int64_t *b)
{
    const char *p;
    char *end;
    long long x, y;

    if (!h[0] || strncasecmp(h, "bytes=", 6) != 0)
        return 0;
    p = h + 6;
    while (*p == ' ')
        p++;
    if (strchr(p, ','))
        return 0;                       /* multi-range: answer as if absent */
    if (*p == '-') {                    /* suffix range: last N bytes */
        y = strtoll(p + 1, &end, 10);
        if (end == p + 1 || *end)
            return 0;
        if (y <= 0 || size == 0)
            return -1;
        *a = y >= size ? 0 : size - y;
        *b = size - 1;
        return 1;
    }
    x = strtoll(p, &end, 10);
    if (end == p || *end != '-' || x < 0)
        return 0;
    p = end + 1;
    if (*p == 0) {
        y = size - 1;
    } else {
        y = strtoll(p, &end, 10);
        if (end == p || *end)
            return 0;
        if (y < x)
            return 0;                   /* invalid: ignore per RFC 9110 */
        if (y > size - 1)
            y = size - 1;
    }
    if (x >= size)
        return -1;
    *a = x;
    *b = y;
    return 1;
}

void api_files_get(req_t *r, const char *rel, int head_only)
{
    char abs[1200], extra[512], date[64];
    struct stat st;
    int fd, rr;
    int64_t a = 0, b = 0, len;
    int status = 200;
    char hdr[1024];
    int n;
    char *buf;

    if (!adapter_storage_mounted(&g_srv.ad)) {
        http_send_error(r, 503, "no_storage", "the SD card is not mounted");
        return;
    }
    if (path_resolve(rel, abs, sizeof abs) < 0) {
        http_send_error(r, 400, "bad_path", "path escapes the storage root");
        return;
    }
    fd = open(abs, O_RDONLY | O_CLOEXEC);
    if (fd < 0 || fstat(fd, &st) < 0 || !S_ISREG(st.st_mode)) {
        if (fd >= 0)
            close(fd);
        http_send_error(r, 404, "not_found", "no such file");
        return;
    }

    http_format_date((int64_t)st.st_mtime, date, sizeof date);
    rr = parse_range(r->range, (int64_t)st.st_size, &a, &b);
    if (rr < 0) {
        snprintf(extra, sizeof extra, "Content-Range: bytes */%lld\r\n", (long long)st.st_size);
        close(fd);
        {
            /* 416 with the JSON error body plus Content-Range. */
            sbuf_t eb;
            sbuf_init(&eb);
            sbuf_puts(&eb, "{\"error\":{\"code\":\"range_not_satisfiable\",\"message\":\"range outside the file\"}}");
            http_send(r, 416, "application/json; charset=utf-8", extra, eb.buf, eb.len, head_only);
            sbuf_free(&eb);
        }
        return;
    }
    if (rr == 1) {
        status = 206;
        len = b - a + 1;
        snprintf(extra, sizeof extra,
                 "Accept-Ranges: bytes\r\nLast-Modified: %s\r\nContent-Range: bytes %lld-%lld/%lld\r\n",
                 date, (long long)a, (long long)b, (long long)st.st_size);
    } else {
        a = 0;
        len = (int64_t)st.st_size;
        snprintf(extra, sizeof extra, "Accept-Ranges: bytes\r\nLast-Modified: %s\r\n", date);
    }

    /* Headers by hand: the body is streamed, not held in memory. */
    n = snprintf(hdr, sizeof hdr,
                 "HTTP/1.1 %d %s\r\n"
                 "Server: " G360RD_NAME "/" G360RD_VERSION "\r\n"
                 "Content-Type: %s\r\n"
                 "Content-Length: %lld\r\n"
                 "Connection: close\r\n"
                 "%s\r\n",
                 status, http_reason(status), file_mime(rel), (long long)len, extra);
    LOGI("http: %s %s %s -> %d (%lld bytes from %lld)", r->peer, r->method, r->target, status,
         (long long)len, (long long)a);
    if (http_send_all(r->fd, hdr, (size_t)n) < 0 || head_only) {
        close(fd);
        return;
    }
    buf = malloc(64 * 1024);
    if (!buf) {
        close(fd);
        return;
    }
    while (len > 0) {
        size_t want = len > 64 * 1024 ? 64 * 1024 : (size_t)len;
        ssize_t got = pread(fd, buf, want, (off_t)a);
        if (got < 0 && errno == EINTR)
            continue;
        if (got <= 0) {
            LOGW("files: short read on %s at %lld", rel, (long long)a);
            break;  /* client sees a short body and can resume with Range */
        }
        if (http_send_all(r->fd, buf, (size_t)got) < 0) {
            LOGI("files: client closed during %s at %lld", rel, (long long)a);
            break;
        }
        a += got;
        len -= got;
    }
    free(buf);
    close(fd);
}

/* ------------------------------------------------------------ delete */

void api_files_delete(req_t *r, const char *rel)
{
    char abs[1200];
    struct stat st;
    sbuf_t b;

    if (!g_srv.ad.files_delete) {
        http_send_error(r, 501, "unsupported", "delete is not supported on this camera");
        return;
    }
    if (!adapter_storage_mounted(&g_srv.ad)) {
        http_send_error(r, 503, "no_storage", "the SD card is not mounted");
        return;
    }
    if (g_srv.busy) {
        http_send_error(r, 409, "busy", "a capture is in progress");
        return;
    }
    if (path_resolve(rel, abs, sizeof abs) < 0) {
        http_send_error(r, 400, "bad_path", "path escapes the storage root");
        return;
    }
    if (lstat(abs, &st) < 0 || !S_ISREG(st.st_mode)) {
        http_send_error(r, 404, "not_found", "no such file");
        return;
    }
    if (unlink(abs) < 0) {
        http_send_error(r, 500, "internal", "unlink failed: %s", strerror(errno));
        return;
    }
    sync();
    LOGI("files: deleted %s", rel);
    sbuf_init(&b);
    sbuf_puts(&b, "{\"deleted\":");
    json_str(&b, rel);
    sbuf_puts(&b, "}");
    http_send_json(r, 200, &b);
    sbuf_free(&b);
}

/* ------------------------------------------------------------ thumbnails */

void api_thumb(req_t *r, const char *rel)
{
    char abs[1200];
    const char *kind;
    unsigned char *jpg = NULL;
    size_t len = 0;
    struct stat st;

    if (!g_srv.ad.thumbnails) {
        http_send_error(r, 501, "unsupported", "thumbnails are not supported on this camera");
        return;
    }
    if (!adapter_storage_mounted(&g_srv.ad)) {
        http_send_error(r, 503, "no_storage", "the SD card is not mounted");
        return;
    }
    if (path_resolve(rel, abs, sizeof abs) < 0) {
        http_send_error(r, 400, "bad_path", "path escapes the storage root");
        return;
    }
    if (stat(abs, &st) < 0 || !S_ISREG(st.st_mode)) {
        http_send_error(r, 404, "not_found", "no such file");
        return;
    }
    kind = file_kind(rel);
    if (strcmp(kind, "photo") == 0 || strcmp(kind, "raw") == 0) {
        if (thumb_extract(abs, &jpg, &len) < 0 && strcmp(kind, "raw") == 0) {
            /* No embedded preview in the RAW: fall back to the EXIF thumbnail
             * of the sibling JPG with the same base name (C200 writes
             * 360_XXXX.SRW beside 360_XXXX.JPG). */
            static const char *const exts[] = { ".JPG", ".jpg", ".JPEG", ".jpeg" };
            char sib[1300];
            char *dot;
            size_t i;
            snprintf(sib, sizeof sib, "%s", abs);
            dot = strrchr(sib, '.');
            for (i = 0; dot && i < sizeof exts / sizeof exts[0]; i++) {
                snprintf(dot, sizeof sib - (size_t)(dot - sib), "%s", exts[i]);
                if (file_exists(sib) && thumb_extract(sib, &jpg, &len) == 0) {
                    LOGD("thumb: %s via sibling %s", rel, sib);
                    break;
                }
            }
        }
    }
    if (!jpg) {
        http_send_error(r, 404, "no_thumbnail", "no embedded thumbnail");
        return;
    }
    http_send(r, 200, "image/jpeg", "Cache-Control: max-age=86400\r\n", jpg, len, 0);
    free(jpg);
}
