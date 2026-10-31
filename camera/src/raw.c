/*
 * RAW dumps, the "raw" setting of the C200 and R210 adapters (docs/firmware/14
 * and 15).
 *
 * "raw" on: for every 360 still the A7 RTOS also writes each lens's sensor data
 * with its own SSIF debug dump, in normal mode (st cap back unitest ssifdump on).
 * C200: Bayer RGGB, 12 bit left-aligned in 16, 3888x3888 from row 40. R210:
 * Bayer GRBG, 10 bit left-aligned in 16, 2912x2896. Kept on the card (raw_save),
 * like keep_awake:
 *  - the dump flag is RTOS RAM, cleared at every boot and when its capture back
 *    end restarts: a thread watches it and re-arms it, and so does every still
 *    taken through us;
 *  - the plugin's rawfix makes the rear lens's file come out under its name;
 *  - the RTOS writes the dumps into the card root, row by row: once complete
 *    they move to DCIM/RAW, named after the shot's JPEG.
 * Dual-lens photos only: the single-lens and time-lapse still pipelines have no
 * dump.
 *
 * raw_init() first patches the RTOS code in RAM (volatile: the RTOS is loaded
 * again at every boot), at start-up, before the first still could bring the old
 * code into the A7's caches; every word must hold its expected value first:
 *  - low light (both): the high-ISO pipeline has no dump. Its check (OrderSheet
 *    +0x53, the ISO noise reduction, and +0x7c, the gain, >= 0x73) is rewritten
 *    to also answer "no" while the dump flag is on, so a RAW shot in low light
 *    takes the normal pipeline (its JPEG without the high-ISO noise reduction).
 *    With the flag off it answers as before. Every caller (order sheet, recipes,
 *    buffers) asks the same function, so they agree;
 *  - R210: its still recipes free the SSIF buffer before the dump runs (the
 *    C200's keep it while the flag is on), so the dump read address 0 and the A7
 *    died: that release is patched out.
 */
#include "server.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define ST_BIN           "/usr/bin/st"
#define ST_TIMEOUT_MS    10000
#define RAW_PERIOD_MS    2000
#define RAW_REFRESH_MS   30000    /* rawfix again, and the flag when it cannot be read */
#define RAW_SETTLE_S     2        /* a complete dump left alone this long is done */
#define RAW_PAIR_S       60       /* the shot's JPEG comes within this after its dumps */
#define RAW_DIR          "DCIM/RAW"

static const raw_camera_t *g_raw_cam;
static char g_raw_save_path[300];

/* One 32-bit word of the RTOS's RAM (mapped 1:1 in /dev/mem): read into *now,
 * and written with "set" when it holds "old". 0, or -1 when it cannot be mapped. */
static int phys_word(unsigned long addr, uint32_t *now, const uint32_t *old, uint32_t set)
{
    long page = sysconf(_SC_PAGESIZE);
    unsigned long base = addr & ~(unsigned long)(page - 1);
    volatile uint32_t *p;
    void *m;
    int fd = open("/dev/mem", (old ? O_RDWR : O_RDONLY) | O_SYNC);

    if (fd < 0)
        return -1;
    m = mmap(NULL, (size_t)page, old ? PROT_READ | PROT_WRITE : PROT_READ, MAP_SHARED, fd, (off_t)base);
    close(fd);
    if (m == MAP_FAILED)
        return -1;
    p = (volatile uint32_t *)((char *)m + (addr - base));
    if (old && *p == *old) {
        *p = set;
        msync(m, (size_t)page, MS_SYNC);
    }
    *now = *p;
    munmap(m, (size_t)page);
    return 0;
}

/* The camera's code patch, all or nothing: every word must hold its original
 * or patched value (else this is not the firmware it was made for) before any
 * is written. 0 = patched (now or already). */
static int raw_patch(const raw_camera_t *c)
{
    uint32_t v;
    for (int i = 0; i < c->n_words; i++) {
        const raw_word_t *w = &c->words[i];
        if (phys_word(w->addr, &v, NULL, 0) < 0 || (v != w->old && (w->old == w->set || v != w->set))) {
            LOGW("%s: RAW: RTOS word %08lx holds %08x, expected %08x: no RAW", c->tag, w->addr, v, w->old);
            return -1;
        }
    }
    for (int i = 0; i < c->n_words; i++) {
        const raw_word_t *w = &c->words[i];
        if (w->old == w->set)
            continue;   /* a check only */
        if (phys_word(w->addr, &v, &w->old, w->set) < 0 || v != w->set) {
            LOGW("%s: RAW: RTOS word %08lx did not take %08x (holds %08x)", c->tag, w->addr, w->set, v);
            return -1;
        }
    }
    LOGI("%s: RAW: RTOS patched", c->tag);
    return 0;
}

int raw_saving(void)
{
    return g_raw_save_path[0] && access(g_raw_save_path, F_OK) == 0;
}

/* The RTOS dump flag: 0/1, or -1 when it cannot be read */
static int raw_flag(void)
{
    unsigned long addr = g_raw_cam->flag_phys;
    uint32_t v;
    if (phys_word(addr & ~3UL, &v, NULL, 0) < 0)
        return -1;
    v = (v >> (8 * (addr & 3))) & 0xff;   /* little-endian */
    return v <= 1 ? (int)v : -1;
}

static void raw_st(int on)
{
    char out[256];
    char *argv[] = { ST_BIN, "cap", "back", "unitest", "ssifdump", on ? "on" : "off", NULL };
    int rc = run_cmd(argv, ST_TIMEOUT_MS, out, sizeof out);
    LOGI("%s: RAW dump flag %s -> rc=%d", g_raw_cam->tag, on ? "on" : "off", rc);
}

/* rawfix, and the dump flag on unless it reads on already */
void raw_arm(void)
{
    char reply[256];
    if (!g_raw_cam)
        return;
    if (shim_plugin("rawfix", reply, sizeof reply) < 0)
        LOGW("%s: rawfix: %s", g_raw_cam->tag, reply);
    if (raw_flag() != 1)
        raw_st(1);
}

int raw_set(int on, char *err, size_t errlen)
{
    if (on) {
        /* ours, on the card; "on" arms the dump now, for the next still */
        FILE *f = fopen(g_raw_save_path, "w");
        if (!f) {
            snprintf(err, errlen, "cannot write %s", g_raw_save_path);
            return -1;
        }
        fclose(f);
        raw_arm();
    } else {
        unlink(g_raw_save_path);
        raw_st(0);
    }
    return 0;
}

/* The shot's JPEG, for a dump finished at "dumped": the RTOS writes both
 * dumps first and the JPEG after them (2026-10-26: front :47, rear :52, JPEG
 * :53), so it is the oldest JPEG in DCIM/<dir>/ not older than the dump (1 s
 * of timestamp slack) nor RAW_PAIR_S newer. Its name without ".JPG" into base;
 * -1 while there is none. */
static int raw_shot_jpeg(const adapter_t *a, time_t dumped, char *base, size_t len)
{
    char dcim[600];
    DIR *d, *sd;
    struct dirent *de, *fe;
    time_t best = 0;

    snprintf(dcim, sizeof dcim, "%s/DCIM", a->storage_root);
    if (!(d = opendir(dcim)))
        return -1;
    while ((de = readdir(d)) != NULL) {
        char sub[900];
        if (de->d_name[0] == '.' || strcmp(de->d_name, "RAW") == 0)
            continue;
        snprintf(sub, sizeof sub, "%s/%s", dcim, de->d_name);
        if (!(sd = opendir(sub)))
            continue;
        while ((fe = readdir(sd)) != NULL) {
            char f[1200];
            struct stat st;
            if (!str_iendswith(fe->d_name, ".jpg"))
                continue;
            snprintf(f, sizeof f, "%s/%s", sub, fe->d_name);
            if (stat(f, &st) < 0 || st.st_mtime < dumped - 1 || st.st_mtime > dumped + RAW_PAIR_S)
                continue;
            if (!best || st.st_mtime < best) {
                best = st.st_mtime;
                snprintf(base, len, "%.*s", (int)strlen(fe->d_name) - 4, fe->d_name);
            }
        }
        closedir(sd);
    }
    closedir(d);
    return best ? 0 : -1;
}

/* Moves the complete dumps from the card root to DCIM/RAW, named after the
 * shot's JPEG: 360_0383.JPG -> 360_0383_front_7776x3888x3950.raw and _rear_.
 * The AxBxC (row bytes x width x rows) is the size check here and the DNG
 * converter's input. A dump whose JPEG never comes (a failed shot) keeps the
 * RTOS name, without its parentheses. The RTOS counter restarts at every boot,
 * so a name already taken gets a "-N". */
static void raw_collect(const adapter_t *a)
{
    DIR *d = opendir(a->storage_root);
    struct dirent *de;
    char dir[600];

    if (!d)
        return;
    snprintf(dir, sizeof dir, "%s/" RAW_DIR, a->storage_root);
    while ((de = readdir(d)) != NULL) {
        char from[600], name[256], base[128], to[900], *paren;
        const char *lens;
        unsigned long rowbytes, width, rows;
        struct stat st;
        size_t n;

        if (strncmp(de->d_name, "ssif_", 5) != 0 || !str_iendswith(de->d_name, ".raw") ||
            sscanf(strchr(de->d_name + 5, '_') ? strchr(de->d_name + 5, '_') + 1 : "", "%lux%lux%lu",
                   &rowbytes, &width, &rows) != 3)
            continue;
        lens = strncmp(de->d_name + 5, "front_", 6) == 0 ? "front" :
               strncmp(de->d_name + 5, "rear_", 5) == 0 ? "rear" : NULL;
        snprintf(from, sizeof from, "%s/%s", a->storage_root, de->d_name);
        if (!lens || stat(from, &st) < 0 ||
            (unsigned long long)st.st_size != (unsigned long long)rowbytes * rows ||
            time(NULL) - st.st_mtime < RAW_SETTLE_S)
            continue;   /* still being written */
        if (raw_shot_jpeg(a, st.st_mtime, base, sizeof base) == 0) {
            snprintf(name, sizeof name, "%s_%s_%lux%lux%lu.raw", base, lens, rowbytes, width, rows);
        } else if (time(NULL) - st.st_mtime < RAW_PAIR_S) {
            continue;   /* its JPEG comes after it */
        } else {
            snprintf(name, sizeof name, "%s", de->d_name);
            if ((paren = strchr(name, '(')) != NULL) {   /* "_10bit(16bit).raw" -> "_10bit_16bit.raw" */
                *paren = '_';
                if ((paren = strchr(paren, ')')) != NULL)
                    memmove(paren, paren + 1, strlen(paren));
            }
        }
        n = strlen(name) - 4;   /* before ".raw" */
        mkdir_p(dir, 0755);
        snprintf(to, sizeof to, "%s/%s", dir, name);
        for (int i = 1; file_exists(to) && i < 1000; i++)
            snprintf(to, sizeof to, "%s/%.*s-%d.raw", dir, (int)n, name, i);
        if (rename(from, to) == 0)
            LOGI("%s: RAW dump %s -> %s", g_raw_cam->tag, de->d_name, to + strlen(a->storage_root) + 1);
        else
            LOGW("%s: cannot move %s: %s", g_raw_cam->tag, de->d_name, strerror(errno));
    }
    closedir(d);
}

static void *raw_thread(void *arg)
{
    const adapter_t *a = arg;
    int64_t refreshed = 0;
    int was = -1;
    for (;;) {
        int on = raw_saving();
        if (on != was)
            LOGI("%s: RAW saving %s", g_raw_cam->tag, on ? "on" : "off");
        was = on;
        if (on) {
            int64_t now = now_ms_mono();
            int flag = raw_flag();
            /* A cleared flag right away; rawfix (the camera app may have
             * restarted) and an unreadable flag now and then */
            if (flag == 0 || !refreshed || now - refreshed >= RAW_REFRESH_MS) {
                if (flag == 0)
                    LOGI("%s: RAW dump flag found off", g_raw_cam->tag);
                raw_arm();
                refreshed = now;
            }
        }
        raw_collect(a);   /* also the dumps of a shot taken before "off" */
        sleep_ms(RAW_PERIOD_MS);
    }
    return NULL;
}

int raw_init(const adapter_t *a, const raw_camera_t *cam)
{
    pthread_t th;
    pthread_attr_t at;

    if (cam->n_words && raw_patch(cam) < 0)
        return -1;
    g_raw_cam = cam;
    snprintf(g_raw_save_path, sizeof g_raw_save_path, "%s/g360r/raw_save", a->storage_root);
    pthread_attr_init(&at);
    pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&at, 128 * 1024);
    pthread_create(&th, &at, raw_thread, (void *)a);
    pthread_attr_destroy(&at);
    return 0;
}
