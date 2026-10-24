/*
 * Adapter "sim": host test mode, everything rooted in a directory.
 *
 *   <dir>/sdcard/DCIM/100PHOTO/...          storage (created if missing)
 *   <dir>/sys/class/power_supply/battery/   fake sec-battery (capacity, status, temp)
 *   <dir>/sys/class/power_supply/ac/        fake charger (online)
 *   <dir>/sys/class/leds/led.0..4/          fake LEDs (brightness files)
 *   <dir>/run/                              lock files
 *   <dir>/SIM_CAPTURE_TIMEOUT               if present, captures produce nothing
 *
 * Edit the fake battery files while the server runs to exercise the app.
 * Captures synthesize real (tiny, decodable) JPEGs with an EXIF IFD1
 * thumbnail and SRW-like TIFF files with an embedded preview JPEG, written
 * in chunks with delays so the DCIM size-stability logic is exercised like
 * on the camera (asynchronous writer).
 */
#include "adapter.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef struct {
    char dir[256];
} sim_t;

/* ------------------------------------------------------- tiny JPEG */

static void put16be(sbuf_t *b, unsigned v)
{
    unsigned char x[2] = { (unsigned char)(v >> 8), (unsigned char)v };
    sbuf_append(b, x, 2);
}

/* An 8x8 single-component baseline JPEG of uniform grey level `shade`
 * (16..240). Each image carries Huffman tables with exactly one code so the
 * entropy-coded data is a handful of bits. Starts at DQT (no SOI). */
static void jpeg_body(sbuf_t *b, int shade)
{
    int v = shade - 128;              /* DC = v * q(8) -> pixel = 128 + v */
    int mag = v < 0 ? -v : v, cat = 0, i;
    unsigned bits, nbits = 0, acc = 0;
    unsigned char x;

    while ((1 << cat) <= mag)
        cat++;
    /* DQT: table 0, all 8 (only DC matters for a flat block). */
    put16be(b, 0xFFDB); put16be(b, 67); x = 0; sbuf_append(b, &x, 1);
    for (i = 0; i < 64; i++) { x = 8; sbuf_append(b, &x, 1); }
    /* SOF0: 8 bit, 8x8, 1 component (id 1, 1x1 sampling, table 0). */
    {
        static const unsigned char sof[] = { 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x08, 0x00, 0x08,
                                             0x01, 0x01, 0x11, 0x00 };
        sbuf_append(b, sof, sizeof sof);
    }
    /* DHT DC0: one code of length 1 -> symbol cat. DHT AC0: one code -> EOB. */
    {
        unsigned char dht[2][22]; /* marker, len, Tc|Th, 16 counts, 1 symbol */
        int k;
        for (k = 0; k < 2; k++) {
            memset(dht[k], 0, sizeof dht[k]);
            dht[k][0] = 0xFF; dht[k][1] = 0xC4; dht[k][2] = 0x00; dht[k][3] = 0x14;
            dht[k][4] = (unsigned char)(k ? 0x10 : 0x00);
            dht[k][5] = 1;                     /* one code of length 1 */
            dht[k][21] = (unsigned char)(k ? 0x00 : cat);
            sbuf_append(b, dht[k], 22);
        }
    }
    /* SOS */
    {
        static const unsigned char sos[] = { 0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00 };
        sbuf_append(b, sos, sizeof sos);
    }
    /* Entropy data: DC code '0', cat magnitude bits, AC EOB '0', pad with 1s. */
    bits = (unsigned)(v >= 0 ? v : v + (1 << cat) - 1);
    acc = 0; nbits = 1;                                  /* '0' */
    acc = (acc << cat) | (bits & ((1u << cat) - 1)); nbits += (unsigned)cat;
    acc <<= 1; nbits += 1;                               /* '0' */
    while (nbits % 8) { acc = (acc << 1) | 1; nbits++; }
    for (i = (int)nbits - 8; i >= 0; i -= 8) {
        x = (unsigned char)(acc >> i);
        sbuf_append(b, &x, 1);
        if (x == 0xFF) { x = 0; sbuf_append(b, &x, 1); }  /* byte stuffing */
    }
    put16be(b, 0xFFD9);
}

static void tiny_jpeg(sbuf_t *b, int shade)
{
    put16be(b, 0xFFD8);
    jpeg_body(b, shade);
}

static void put16le(unsigned char *p, unsigned v) { p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8); }
static void put32le(unsigned char *p, uint32_t v) { put16le(p, v & 0xFFFF); put16le(p + 2, v >> 16); }

static void ifd_entry(unsigned char *e, unsigned tag, unsigned type, uint32_t count, uint32_t val)
{
    put16le(e, tag); put16le(e + 2, type); put32le(e + 4, count);
    if (type == 3) { put16le(e + 8, val); put16le(e + 10, 0); }
    else put32le(e + 8, val);
}

/* JPEG with an EXIF APP1 (IFD0 + IFD1 thumbnail) and COM padding. */
static void fake_photo(sbuf_t *b, int shade, size_t pad)
{
    sbuf_t th, tiff;
    unsigned char hdr[68];

    sbuf_init(&th);
    sbuf_init(&tiff);
    tiny_jpeg(&th, 255 - shade);

    memset(hdr, 0, sizeof hdr);
    memcpy(hdr, "II*\0", 4); put32le(hdr + 4, 8);
    put16le(hdr + 8, 1);                                 /* IFD0 @8: 1 entry */
    ifd_entry(hdr + 10, 0x0112, 3, 1, 1);                /* Orientation */
    put32le(hdr + 22, 26);                               /* -> IFD1 @26 */
    put16le(hdr + 26, 3);                                /* IFD1: 3 entries */
    ifd_entry(hdr + 28, 0x0103, 3, 1, 6);                /* Compression=6 */
    ifd_entry(hdr + 40, 0x0201, 4, 1, 68);               /* thumb offset */
    ifd_entry(hdr + 52, 0x0202, 4, 1, (uint32_t)th.len); /* thumb length */
    put32le(hdr + 64, 0);
    sbuf_append(&tiff, hdr, sizeof hdr);
    sbuf_append(&tiff, th.buf, th.len);

    put16be(b, 0xFFD8);
    put16be(b, 0xFFE1);
    put16be(b, (unsigned)(2 + 6 + tiff.len));
    sbuf_append(b, "Exif\0\0", 6);
    sbuf_append(b, tiff.buf, tiff.len);
    while (pad > 0) {                                    /* COM segments */
        size_t n = pad > 60000 ? 60000 : pad;
        static const char filler[64] = "g360rd sim padding ";
        size_t k;
        put16be(b, 0xFFFE);
        put16be(b, (unsigned)(n + 2));
        for (k = 0; k < n; k += sizeof filler)
            sbuf_append(b, filler, (n - k) < sizeof filler ? n - k : sizeof filler);
        pad -= n;
    }
    jpeg_body(b, shade);
    sbuf_free(&th);
    sbuf_free(&tiff);
}

/* TIFF (SRW-like) with IFD0 pointing at an embedded preview JPEG. */
static void fake_raw(sbuf_t *b, int shade, size_t pad)
{
    sbuf_t pv;
    unsigned char hdr[54];
    size_t k;
    static const unsigned char zeros[4096];

    sbuf_init(&pv);
    tiny_jpeg(&pv, shade);
    memset(hdr, 0, sizeof hdr);
    memcpy(hdr, "II*\0", 4); put32le(hdr + 4, 8);
    put16le(hdr + 8, 3);
    ifd_entry(hdr + 10, 0x0103, 3, 1, 6);
    ifd_entry(hdr + 22, 0x0201, 4, 1, 54);
    ifd_entry(hdr + 34, 0x0202, 4, 1, (uint32_t)pv.len);
    put32le(hdr + 46, 0);
    sbuf_append(b, hdr, sizeof hdr);
    sbuf_append(b, pv.buf, pv.len);
    for (k = 0; k < pad; k += sizeof zeros)
        sbuf_append(b, zeros, (pad - k) < sizeof zeros ? pad - k : sizeof zeros);
    sbuf_free(&pv);
}

/* ------------------------------------------------------- capture */

typedef struct {
    char path[2][512];
    sbuf_t data[2];
    int n;
} sim_job_t;

/* Write the files slowly, like the camera's asynchronous writer. */
static void *sim_writer(void *arg)
{
    sim_job_t *job = arg;
    int i;
    sleep_ms(700);
    for (i = 0; i < job->n; i++) {
        int fd = open(job->path[i], O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        size_t off = 0, chunk;
        if (fd < 0) {
            LOGE("sim: cannot create %s: %s", job->path[i], strerror(errno));
            continue;
        }
        chunk = job->data[i].len / 6 + 1;
        while (off < job->data[i].len) {
            size_t n = job->data[i].len - off < chunk ? job->data[i].len - off : chunk;
            if (write(fd, job->data[i].buf + off, n) != (ssize_t)n)
                break;
            off += n;
            sleep_ms(120);
        }
        close(fd);
        LOGI("sim: wrote %s (%zu bytes)", job->path[i], job->data[i].len);
        sbuf_free(&job->data[i]);
    }
    free(job);
    return NULL;
}

static int next_number(const char *dir)
{
    DIR *d = opendir(dir);
    struct dirent *de;
    int maxn = 0;
    if (!d)
        return 1;
    while ((de = readdir(d)) != NULL) {
        int n;
        if (sscanf(de->d_name, "360_%4d.", &n) == 1 && n > maxn)
            maxn = n;
    }
    closedir(d);
    return maxn + 1;
}

static int sim_capture(adapter_t *a, int mode, const char *quality,
                       const char *resolution, char *err, size_t errlen)
{
    sim_t *s = a->priv;
    char dir[512], flag[300];
    sim_job_t *job;
    pthread_t th;
    pthread_attr_t at;
    int num, shade;
    (void)resolution;

    snprintf(flag, sizeof flag, "%s/SIM_CAPTURE_TIMEOUT", s->dir);
    if (file_exists(flag)) {
        LOGW("sim: SIM_CAPTURE_TIMEOUT present, producing nothing");
        return 0;
    }
    snprintf(dir, sizeof dir, "%s/DCIM/100PHOTO", a->storage_root);
    if (mkdir_p(dir, 0755) < 0) {
        snprintf(err, errlen, "cannot create %s", dir);
        return -1;
    }
    num = next_number(dir);
    shade = 40 + (num * 37) % 180;
    job = calloc(1, sizeof *job);
    if (!job) {
        snprintf(err, errlen, "out of memory");
        return -1;
    }
    if (mode == MODE_RAW || mode == MODE_RAWPHOTO) {
        snprintf(job->path[job->n], sizeof job->path[0], "%s/360_%04d.SRW", dir, num);
        sbuf_init(&job->data[job->n]);
        fake_raw(&job->data[job->n], shade, 3u << 20);
        job->n++;
    }
    if (mode == MODE_PHOTO || mode == MODE_RAWPHOTO) {
        size_t pad = (quality && strcmp(quality, "norm") == 0) ? 256u << 10 : 768u << 10;
        snprintf(job->path[job->n], sizeof job->path[0], "%s/360_%04d.JPG", dir, num);
        sbuf_init(&job->data[job->n]);
        fake_photo(&job->data[job->n], shade, pad);
        job->n++;
    }
    LOGI("sim: capture mode=%s quality=%s -> #%04d", mode_name(mode), quality ? quality : "-", num);
    pthread_attr_init(&at);
    pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &at, sim_writer, job) != 0) {
        free(job);
        snprintf(err, errlen, "cannot start writer thread");
        return -1;
    }
    return 0;
}

/* ------------------------------------------------------- setup */

static void ensure_file(const char *path, const char *content)
{
    if (!file_exists(path))
        write_text_file(path, content);
}

static void seed_file(const char *path, int photo, int shade)
{
    sbuf_t b;
    FILE *fp;
    if (file_exists(path))
        return;
    sbuf_init(&b);
    if (photo)
        fake_photo(&b, shade, 128u << 10);
    else
        fake_raw(&b, shade, 1u << 20);
    fp = fopen(path, "wb");
    if (fp) {
        fwrite(b.buf, 1, b.len, fp);
        fclose(fp);
    }
    sbuf_free(&b);
}

int adapter_sim_init(adapter_t *a, const char *dir, const config_t *cfg)
{
    static const char *const qualities[] = { "sfine", "fine", "norm" };
    static const char *const resolutions[] = { "7776x3888", "3072x1728" };
    static const char *const video[] = { "3840x1920@30", "2560x1280@30", "1920x960@30" };
    sim_t *s = calloc(1, sizeof *s);
    char p[512], model_raw[32], build[48];
    int i;

    if (!s)
        return -1;
    snprintf(s->dir, sizeof s->dir, "%s", dir);
    a->priv = s;
    a->id = "sim";
    a->status = "experimental";

    snprintf(p, sizeof p, "%s/etc/info.ini", dir);
    adapter_read_info_ini(p, model_raw, sizeof model_raw, build, sizeof build);
    snprintf(a->model_raw, sizeof a->model_raw, "%s", model_raw[0] ? model_raw : "SIM");
    snprintf(a->firmware, sizeof a->firmware, "%s", build[0] ? build : "SIM0001");
    snprintf(a->model, sizeof a->model, "%s", a->model_raw);
    snprintf(a->name_prefix, sizeof a->name_prefix, "Gear 360 sim");
    snprintf(a->mac_override, sizeof a->mac_override, "%s", config_get(cfg, "sim_mac", "02:00:00:65:ef:1e"));

    snprintf(a->storage_root, sizeof a->storage_root, "%s/sdcard", dir);
    a->mount_point[0] = 0;
    snprintf(a->power_supply_dir, sizeof a->power_supply_dir, "%s/sys/class/power_supply", dir);
    snprintf(a->run_dir, sizeof a->run_dir, "%s/run", dir);

    /* Seed the tree (only what is missing, so edits survive restarts). */
    snprintf(p, sizeof p, "%s/DCIM/100PHOTO", a->storage_root);
    mkdir_p(p, 0755);
    snprintf(p, sizeof p, "%s/DCIM/100PHOTO/360_0001.JPG", a->storage_root);
    seed_file(p, 1, 90);
    snprintf(p, sizeof p, "%s/DCIM/100PHOTO/360_0002.JPG", a->storage_root);
    seed_file(p, 1, 170);
    snprintf(p, sizeof p, "%s/DCIM/100PHOTO/360_0002.SRW", a->storage_root);
    seed_file(p, 0, 170);
    snprintf(p, sizeof p, "%s/battery", a->power_supply_dir);
    mkdir_p(p, 0755);
    snprintf(p, sizeof p, "%s/battery/type", a->power_supply_dir);     ensure_file(p, "Battery\n");
    snprintf(p, sizeof p, "%s/battery/capacity", a->power_supply_dir); ensure_file(p, "87\n");
    snprintf(p, sizeof p, "%s/battery/status", a->power_supply_dir);   ensure_file(p, "Discharging\n");
    snprintf(p, sizeof p, "%s/battery/temp", a->power_supply_dir);     ensure_file(p, "315\n");
    snprintf(p, sizeof p, "%s/ac", a->power_supply_dir);
    mkdir_p(p, 0755);
    snprintf(p, sizeof p, "%s/ac/type", a->power_supply_dir);          ensure_file(p, "Mains\n");
    snprintf(p, sizeof p, "%s/ac/online", a->power_supply_dir);        ensure_file(p, "0\n");
    for (i = 0; i < 5; i++) {
        snprintf(p, sizeof p, "%s/sys/class/leds/led.%d", dir, i);
        mkdir_p(p, 0755);
        snprintf(p, sizeof p, "%s/sys/class/leds/led.%d/brightness", dir, i);     ensure_file(p, "0\n");
        snprintf(p, sizeof p, "%s/sys/class/leds/led.%d/max_brightness", dir, i); ensure_file(p, "1\n");
        snprintf(p, sizeof p, "%s/sys/class/leds/led.%d/trigger", dir, i);        ensure_file(p, "[none] timer\n");
    }
    mkdir_p(a->run_dir, 0755);

    a->capture_modes = MODE_PHOTO | MODE_RAW | MODE_RAWPHOTO;
    for (i = 0; i < 3; i++) a->qualities[i] = qualities[i];
    a->n_qualities = 3;
    for (i = 0; i < 2; i++) a->resolutions[i] = resolutions[i];
    a->n_resolutions = 2;
    for (i = 0; i < 3; i++) a->video_modes[i] = video[i];
    a->n_video_modes = 3;
    a->files_delete = 1;
    a->thumbnails = 1;
    a->trigger_capture = sim_capture;
    snprintf(p, sizeof p, "%s/sys/class/leds", dir);
    adapter_discover_leds(a, p, 5, cfg);
    LOGI("adapter: sim rooted at %s", dir);
    return 0;
}
