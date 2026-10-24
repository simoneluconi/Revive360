/*
 * Embedded thumbnail extraction, no image libraries, no decoding.
 *
 *  - JPEG: walk the marker segments up to SOS; for each APP1 "Exif\0\0"
 *    segment run the TIFF walker on its payload (IFD1 carries the standard
 *    EXIF thumbnail as JPEGInterchangeFormat/Length = tags 0x0201/0x0202).
 *  - TIFF-based RAW (Samsung SRW, DNG): run the TIFF walker on the file.
 *
 * The TIFF walker visits the IFD0 chain, SubIFDs (0x014A), the Exif IFD
 * (0x8769), and the MakerNote (0x927C) if it parses as an IFD (Samsung "Type2"
 * makernotes are plain IFDs; their PreviewIFD is tag 0x0035). Candidates are:
 *  - 0x0201/0x0202 pairs, and
 *  - single-strip images with Compression 6/7 (old-JPEG / JPEG).
 * A candidate counts only if its bytes start with FF D8 FF (a JPEG SOI), so
 * guessing offsets bases is harmless. Among valid candidates we prefer the
 * largest one <= THUMB_PREFERRED_MAX (a gallery-sized preview), otherwise the
 * smallest one.
 */
#include "g360rd.h"

#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define THUMB_PREFERRED_MAX (320 * 1024)
#define THUMB_HARD_MAX      (8 * 1024 * 1024)
#define MAX_CANDS 16
#define MAX_IFD_VISITS 48

typedef struct {
    /* Data source: either a memory block or a file region. */
    const unsigned char *mem;
    int fd;
    uint64_t base;     /* absolute offset of the TIFF header in the source */
    uint64_t size;     /* bytes available from base */
    int le;            /* byte order */
    struct { uint64_t off, len; } cand[MAX_CANDS];
    int ncand;
    int visits;
    uint32_t visited[MAX_IFD_VISITS];
} tiff_t;

static int rd(tiff_t *t, uint64_t off, void *dst, size_t n)
{
    if (off > t->size || n > t->size - off)
        return -1;
    if (t->mem) {
        memcpy(dst, t->mem + t->base + off, n);
        return 0;
    }
    return pread(t->fd, dst, n, (off_t)(t->base + off)) == (ssize_t)n ? 0 : -1;
}

static uint16_t u16(const tiff_t *t, const unsigned char *p)
{
    return t->le ? (uint16_t)(p[0] | p[1] << 8) : (uint16_t)(p[0] << 8 | p[1]);
}

static uint32_t u32(const tiff_t *t, const unsigned char *p)
{
    return t->le ? (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24
                 : (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | (uint32_t)p[3];
}

static void add_cand(tiff_t *t, uint64_t off, uint64_t len)
{
    unsigned char soi[3];
    int i;
    if (len < 64 || len > THUMB_HARD_MAX || t->ncand >= MAX_CANDS)
        return;
    if (rd(t, off, soi, 3) < 0 || soi[0] != 0xFF || soi[1] != 0xD8 || soi[2] != 0xFF)
        return;
    if (off + len > t->size)
        return;
    for (i = 0; i < t->ncand; i++)
        if (t->cand[i].off == off)
            return;
    t->cand[t->ncand].off = off;
    t->cand[t->ncand].len = len;
    t->ncand++;
}

/* Value of a SHORT/LONG entry (first element), or 0. */
static uint32_t entry_val(const tiff_t *t, const unsigned char *e)
{
    uint16_t type = u16(t, e + 2);
    if (type == 3)
        return u16(t, e + 8);
    if (type == 4 || type == 13)
        return u32(t, e + 8);
    return 0;
}

static void walk_ifd(tiff_t *t, uint32_t off, int depth);

/* Follow every LONG offset of a (SubIFDs-style) entry. */
static void walk_offsets(tiff_t *t, const unsigned char *e, int depth)
{
    uint16_t type = u16(t, e + 2);
    uint32_t count = u32(t, e + 4), i;
    if (type != 4 && type != 13)
        return;
    if (count == 1) {
        walk_ifd(t, u32(t, e + 8), depth + 1);
        return;
    }
    if (count > 8)
        count = 8;
    for (i = 0; i < count; i++) {
        unsigned char b[4];
        if (rd(t, (uint64_t)u32(t, e + 8) + i * 4, b, 4) == 0)
            walk_ifd(t, u32(t, b), depth + 1);
    }
}

static void walk_ifd(tiff_t *t, uint32_t off, int depth)
{
    int chain;
    for (chain = 0; chain < 8 && off != 0 && depth <= 4; chain++) {
        unsigned char cnt[2], e[12], nxt[4];
        uint16_t n, i;
        uint32_t jpeg_off = 0, jpeg_len = 0, strip_off = 0, strip_len = 0, strips = 0;
        int compression = 0, k;

        if (t->visits >= MAX_IFD_VISITS)
            return;
        for (k = 0; k < t->visits; k++)
            if (t->visited[k] == off)
                return;   /* loop protection */
        t->visited[t->visits++] = off;

        if (rd(t, off, cnt, 2) < 0)
            return;
        n = u16(t, cnt);
        if (n == 0 || n > 512)
            return;
        for (i = 0; i < n; i++) {
            uint16_t tag;
            if (rd(t, (uint64_t)off + 2 + (uint64_t)i * 12, e, 12) < 0)
                return;
            tag = u16(t, e);
            switch (tag) {
            case 0x0201: jpeg_off = entry_val(t, e); break;
            case 0x0202: jpeg_len = entry_val(t, e); break;
            case 0x0103: compression = (int)entry_val(t, e); break;
            case 0x0111: strip_off = entry_val(t, e); strips = u32(t, e + 4); break;
            case 0x0117: strip_len = entry_val(t, e); break;
            case 0x014A: /* SubIFDs */
            case 0x8769: /* Exif IFD */
                walk_offsets(t, e, depth);
                break;
            case 0x0035: /* Samsung PreviewIFD (inside the makernote) */
                walk_ifd(t, entry_val(t, e), depth + 1);
                break;
            case 0x927C: { /* MakerNote (UNDEFINED); try it as a bare IFD */
                uint32_t mcount = u32(t, e + 4);
                if (mcount > 12)
                    walk_ifd(t, u32(t, e + 8), depth + 1);
                break;
            }
            default:
                break;
            }
        }
        if (jpeg_off && jpeg_len)
            add_cand(t, jpeg_off, jpeg_len);
        if ((compression == 6 || compression == 7) && strips == 1 && strip_off && strip_len)
            add_cand(t, strip_off, strip_len);
        if (rd(t, (uint64_t)off + 2 + (uint64_t)n * 12, nxt, 4) < 0)
            return;
        off = u32(t, nxt);
    }
}

/* Parse the TIFF header at t->base and walk it. */
static int tiff_scan(tiff_t *t)
{
    unsigned char h[8];
    if (rd(t, 0, h, 8) < 0)
        return -1;
    if (h[0] == 'I' && h[1] == 'I')
        t->le = 1;
    else if (h[0] == 'M' && h[1] == 'M')
        t->le = 0;
    else
        return -1;
    /* Magic 42 (TIFF/DNG/SRW). Some RAWs use other magics; accept any. */
    walk_ifd(t, u32(t, h + 4), 0);
    return 0;
}

static int pick_and_copy(tiff_t *t, unsigned char **out, size_t *outlen)
{
    int i, best = -1;
    unsigned char *buf;
    for (i = 0; i < t->ncand; i++) {
        if (t->cand[i].len <= THUMB_PREFERRED_MAX &&
            (best < 0 || t->cand[best].len > THUMB_PREFERRED_MAX || t->cand[i].len > t->cand[best].len))
            best = i;
    }
    if (best < 0) {
        for (i = 0; i < t->ncand; i++)
            if (best < 0 || t->cand[i].len < t->cand[best].len)
                best = i;
    }
    if (best < 0)
        return -1;
    buf = malloc((size_t)t->cand[best].len);
    if (!buf)
        return -1;
    if (rd(t, t->cand[best].off, buf, (size_t)t->cand[best].len) < 0) {
        free(buf);
        return -1;
    }
    *out = buf;
    *outlen = (size_t)t->cand[best].len;
    return 0;
}

static int jpeg_exif_thumb(int fd, uint64_t fsize, unsigned char **out, size_t *outlen)
{
    uint64_t pos = 2;
    int seg;
    for (seg = 0; seg < 64 && pos + 4 <= fsize; seg++) {
        unsigned char m[4];
        uint32_t len;
        if (pread(fd, m, 4, (off_t)pos) != 4 || m[0] != 0xFF)
            return -1;
        if (m[1] == 0xFF) {  /* fill byte */
            pos++;
            continue;
        }
        if (m[1] == 0xDA || m[1] == 0xD9)   /* SOS / EOI: no more metadata */
            return -1;
        len = (uint32_t)m[2] << 8 | m[3];
        if (len < 2)
            return -1;
        if (m[1] == 0xE1 && len > 8 + 6) {
            unsigned char *segbuf = malloc(len - 2);
            if (!segbuf)
                return -1;
            if (pread(fd, segbuf, len - 2, (off_t)(pos + 4)) == (ssize_t)(len - 2) &&
                memcmp(segbuf, "Exif\0\0", 6) == 0) {
                tiff_t t;
                int rc;
                memset(&t, 0, sizeof t);
                t.mem = segbuf;
                t.fd = -1;
                t.base = 6;
                t.size = len - 2 - 6;
                rc = (tiff_scan(&t) == 0) ? pick_and_copy(&t, out, outlen) : -1;
                free(segbuf);
                if (rc == 0)
                    return 0;
            } else {
                free(segbuf);
            }
        }
        pos += 2 + len;
    }
    return -1;
}

int thumb_extract(const char *path, unsigned char **out, size_t *outlen)
{
    unsigned char h[4];
    struct stat st;
    int fd = open(path, O_RDONLY | O_CLOEXEC), rc = -1;

    *out = NULL;
    *outlen = 0;
    if (fd < 0)
        return -1;
    if (fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || pread(fd, h, 4, 0) != 4) {
        close(fd);
        return -1;
    }
    if (h[0] == 0xFF && h[1] == 0xD8) {
        rc = jpeg_exif_thumb(fd, (uint64_t)st.st_size, out, outlen);
    } else if ((h[0] == 'I' && h[1] == 'I') || (h[0] == 'M' && h[1] == 'M')) {
        tiff_t t;
        memset(&t, 0, sizeof t);
        t.fd = fd;
        t.base = 0;
        t.size = (uint64_t)st.st_size;
        if (tiff_scan(&t) == 0)
            rc = pick_and_copy(&t, out, outlen);
    }
    close(fd);
    return rc;
}
