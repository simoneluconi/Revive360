/*
 * libg360r_plugin.so for the SM-R210 (Gear 360 2017, R210GLU0ARB2): loaded by
 * the shim from the card like the C200's (g360r_plugin.c), so it changes without
 * a flash; every request runs on di-camera-app's main loop.
 *
 * The R210 app has its own managers (docs/firmware/15_r210_port.md, section 6).
 * Their singletons are exported as unique objects
 * (_ZZN6AppNet4Menu8CManager11getInstanceEvE4inst ...) and are used only once
 * their guard variable says the app has built them:
 *
 *   AppNet::Menu::CManager  the 44 settings (AppNet::Menu::Type 0..43): each is a
 *                           module with its int value, the item values and their
 *                           strings (getValueStr* return a std::string reference,
 *                           whose object is the pointer to its characters)
 *   CUINETFuncRemoteShot    the stock remote: shot, movie record and stop
 *   CUINETUtil              state flags and the auto power off timer (static)
 *
 * Requests:
 *   ping
 *   menus                   every menu: TYPE"title"=value"str"/count, ';' separated
 *   menu TYPE               one menu: title, value, items (index=value"string")
 *   menuset TYPE VALUE      setValue(TYPE, VALUE), then the value read back
 *   shot                    a still, as the stock remote takes it
 *   recstart | recstop      movie record start / stop (stock remote)
 *   state                   photo video timelapse looping recording tlrun dual standby timer
 *                           (tlrun: a time-lapse's interval capture runs)
 *   powerstop | powerrestart   holds off / re-arms the auto power off timer
 *   dlnastart | dlnastop    the stock remote viewfinder service (CUINETDlnaMan), as
 *                           CUINETFuncDLNA::process_common_activate starts it
 *   mpegstop                stops its live stream session
 *   rawfix                  guards the RTOS dump's file I/O in libcapture (see raw_fix)
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <link.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define MENU_TYPES 44

static void *app_sym(const char *mangled)
{
    return dlsym(RTLD_DEFAULT, mangled);
}

/* The singleton object, or NULL while its guard says it is not built yet */
static void *instance(const char *obj, const char *guard)
{
    const unsigned char *g = app_sym(guard);
    void *o = app_sym(obj);
    return o && g && (*g & 1) ? o : NULL;
}

/* The characters of a returned const std::string& (old libstdc++: one pointer) */
static const char *cstr(const void *ref)
{
    const char *s = ref ? *(const char *const *)ref : NULL;
    return s ? s : "";
}

/* ------------------------------------------------ menu manager */

typedef int (*menu_int_fn)(void *, int);
typedef int (*menu_int_idx_fn)(void *, int, unsigned);
typedef const void *(*menu_ref_fn)(void *, int);
typedef const void *(*menu_ref_idx_fn)(void *, int, unsigned);
typedef void (*menu_set_fn)(void *, int, int);

typedef struct {
    void *self;
    menu_int_fn value, size;
    menu_int_idx_fn value_at;
    menu_ref_fn title, str;
    menu_ref_idx_fn str_at;
    menu_set_fn set;
} menu_t;

static int menu_open(menu_t *m, char *resp, size_t len)
{
    m->self = instance("_ZZN6AppNet4Menu8CManager11getInstanceEvE4inst",
                       "_ZGVZN6AppNet4Menu8CManager11getInstanceEvE4inst");
    m->value = (menu_int_fn)app_sym("_ZN6AppNet4Menu8CManager8getValueENS0_4TypeE");
    m->size = (menu_int_fn)app_sym("_ZN6AppNet4Menu8CManager7getSizeENS0_4TypeE");
    m->value_at = (menu_int_idx_fn)app_sym("_ZN6AppNet4Menu8CManager8getValueENS0_4TypeEj");
    m->title = (menu_ref_fn)app_sym("_ZN6AppNet4Menu8CManager8getTitleENS0_4TypeE");
    m->str = (menu_ref_fn)app_sym("_ZN6AppNet4Menu8CManager11getValueStrENS0_4TypeE");
    m->str_at = (menu_ref_idx_fn)app_sym("_ZN6AppNet4Menu8CManager18getValueStrByIndexENS0_4TypeEj");
    m->set = (menu_set_fn)app_sym("_ZN6AppNet4Menu8CManager8setValueENS0_4TypeEi");
    if (!m->self || !m->value || !m->size || !m->value_at || !m->title || !m->str || !m->str_at || !m->set) {
        snprintf(resp, len, "error no menu manager (%s)", m->self ? "missing method" : "not built yet");
        return -1;
    }
    return 0;
}

static int menus(char *resp, size_t len)
{
    menu_t m;
    size_t used;
    int t;

    if (menu_open(&m, resp, len) < 0)
        return -1;
    used = (size_t)snprintf(resp, len, "ok");
    for (t = 0; t < MENU_TYPES && used < len; t++)
        used += (size_t)snprintf(resp + used, len - used, "%s%d\"%s\"=%d\"%s\"/%d", t ? ";" : " ", t,
                                 cstr(m.title(m.self, t)), m.value(m.self, t), cstr(m.str(m.self, t)),
                                 m.size(m.self, t));
    return 0;
}

static int menu_one(int t, char *resp, size_t len)
{
    menu_t m;
    size_t used;
    int i, n;

    if (t < 0 || t >= MENU_TYPES) {
        snprintf(resp, len, "error menu type 0-%d", MENU_TYPES - 1);
        return -1;
    }
    if (menu_open(&m, resp, len) < 0)
        return -1;
    n = m.size(m.self, t);
    used = (size_t)snprintf(resp, len, "ok %d \"%s\" value=%d\"%s\" items:", t, cstr(m.title(m.self, t)),
                            m.value(m.self, t), cstr(m.str(m.self, t)));
    for (i = 0; i < n && used < len; i++)
        used += (size_t)snprintf(resp + used, len - used, " %d=%d\"%s\"", i, m.value_at(m.self, t, (unsigned)i),
                                 cstr(m.str_at(m.self, t, (unsigned)i)));
    return 0;
}

static int menu_set(int t, int v, char *resp, size_t len)
{
    menu_t m;

    if (t < 0 || t >= MENU_TYPES) {
        snprintf(resp, len, "error menu type 0-%d", MENU_TYPES - 1);
        return -1;
    }
    if (menu_open(&m, resp, len) < 0)
        return -1;
    m.set(m.self, t, v);
    snprintf(resp, len, "ok %d set %d, now %d\"%s\"", t, v, m.value(m.self, t), cstr(m.str(m.self, t)));
    return 0;
}

/* ------------------------------------------------ remote shot, state, power timer */

static int remote(const char *mangled, const char *what, char *resp, size_t len)
{
    void *self = instance("_ZZN20CUINETFuncRemoteShot11getInstanceEvE4inst",
                          "_ZGVZN20CUINETFuncRemoteShot11getInstanceEvE4inst");
    void (*f)(void *) = (void (*)(void *))app_sym(mangled);
    if (!self || !f) {
        snprintf(resp, len, "error no remote shot (%s)", self ? mangled : "not built yet");
        return -1;
    }
    f(self);
    snprintf(resp, len, "ok %s", what);
    return 0;
}

static int flag(const char *mangled)
{
    int (*f)(void) = (int (*)(void))app_sym(mangled);
    return f ? (f() ? 1 : 0) : -1;
}

static int state(char *resp, size_t len)
{
    snprintf(resp, len, "ok photo=%d video=%d timelapse=%d looping=%d recording=%d tlrun=%d dual=%d standby=%d"
             " timer=%d",
             flag("_ZN10CUINETUtil11IsPhotoModeEv"), flag("_ZN10CUINETUtil11IsVideoModeEv"),
             flag("_ZN10CUINETUtil15IsTimelapseModeEv"), flag("_ZN10CUINETUtil15IsLoopVideoModeEv"),
             flag("_ZN10CUINETUtil11IsRecordingEv"), flag("_ZN10CUINETUtil20IsTimelapseCapturingEv"),
             flag("_ZN10CUINETUtil10IsDualLensEv"),
             flag("_ZN10CUINETUtil11IsStbyStateEv"), flag("_ZN10CUINETUtil15IsTimerCountingEv"));
    return 0;
}

/* ------------------------------------------------ remote viewfinder (DLNA/UPnP) */

/* CUINETFuncDLNA (1860 bytes on ARB2) holds its CUINETDlnaMan* at +0x608 and the
 * INETDlnaReciever it hands to StartDlna at +0x5d4; the manager keeps "started"
 * at +4. process_common_activate calls StartDlna(man, func + 0x5d4, type == 0,
 * no card), type 0 being the viewfinder. Started here without the function's
 * own network bring-up, which would replace our softAP. */
#define FUNC_DLNA_MAN   0x608
#define FUNC_DLNA_RECV  0x5d4

static int dlna(int start, int stop, char *resp, size_t len)
{
    unsigned char *func = instance("_ZZN14CUINETFuncDLNA11getInstanceEvE4inst",
                                   "_ZGVZN14CUINETFuncDLNA11getInstanceEvE4inst");
    void (*begin)(void *, void *, int, int) =
        (void (*)(void *, void *, int, int))app_sym("_ZN13CUINETDlnaMan9StartDlnaEP16INETDlnaRecieverbb");
    void (*end)(void *, int) = (void (*)(void *, int))app_sym("_ZN13CUINETDlnaMan8StopDlnaEb");
    void (*desc_url)(char **) = (void (*)(char **))app_sym("DlnaGetDeviceDescUrl");
    char url[128] = "", *p = url;
    unsigned char *man;
    int was, nocard;

    if (!func || !begin || !end || !desc_url || !(man = *(unsigned char **)(func + FUNC_DLNA_MAN))) {
        snprintf(resp, len, "error a DLNA object or function is missing");
        return -1;
    }
    was = man[4];
    if (start && !was) {
        nocard = flag("_ZN10CUINETUtil8IsNoCardEv") == 1 || flag("_ZN10CUINETUtil11IsErrorCardEv") == 1;
        begin(man, func + FUNC_DLNA_RECV, 1, nocard);
    }
    if (stop && was)
        end(man, 0);
    if (man[4])
        desc_url(&p);
    snprintf(resp, len, "ok dlna was=%d now=%d url=%s", was, man[4], url);
    return 0;
}

static int mpeg_stop(char *resp, size_t len)
{
    void *func = instance("_ZZN14CUINETFuncDLNA11getInstanceEvE4inst",
                          "_ZGVZN14CUINETFuncDLNA11getInstanceEvE4inst");
    void (*stop)(void *, int) = (void (*)(void *, int))app_sym("_ZN14CUINETFuncDLNA14dlna_mpeg_stopEi");
    if (!func || !stop) {
        snprintf(resp, len, "error no dlna_mpeg_stop");
        return -1;
    }
    stop(func, 0);
    snprintf(resp, len, "ok mpegstop");
    return 0;
}

/* ------------------------------------------------ RTOS dump file I/O */

/* The A7 RTOS writes its SSIF raw dumps ("st cap back unitest ssifdump on")
 * through libcapture's RunAdjustFunction: fopen() of the name straight from the
 * RTOS's RAM, one fwrite() per row, fclose(). On the C200 a name reached Linux
 * with leftovers after ".raw" (the RTOS cleans its cache for strlen bytes only);
 * on the R210 a still with the dump on restarted the camera, the front file
 * missing and the rear one empty. "rawfix" points libcapture's fopen, fwrite and
 * fclose slots at wrappers that cut card names after ".raw", skip a write to a
 * stream that failed to open (instead of crashing the app) and log every card
 * file to the card, synced line by line so it outlives a restart. */
#define RAW_LIB  "libcapture-fw-prod.so"
#define RAW_CARD "/mnt/mmc/"
#define RAW_LOG  "/opt/storage/sdcard/g360r/log/rawfix.log"
#define RAW_FILES 4

typedef FILE *(*fopen_fn)(const char *, const char *);
typedef size_t (*fwrite_fn)(const void *, size_t, size_t, FILE *);
typedef int (*fclose_fn)(FILE *);
typedef void *(*mmap_fn)(void *, size_t, int, int, int, off_t);
static fopen_fn g_libc_fopen;
static fwrite_fn g_libc_fwrite;
static fclose_fn g_libc_fclose;
static mmap_fn g_libc_mmap;
static int g_raw_opens, g_raw_cut, g_null_writes, g_bad_writes, g_map_fails, g_other_writes;
static int g_probe[2] = { -1, -1 };

static struct {
    FILE *f;
    char name[96];
    unsigned writes;
    unsigned long long bytes;
} g_raw_files[RAW_FILES];

static void raw_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void raw_log(const char *fmt, ...)
{
    FILE *f;
    va_list ap;
    if (!g_libc_fopen || !(f = g_libc_fopen(RAW_LOG, "a")))
        return;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fflush(f);
    fsync(fileno(f));
    g_libc_fclose(f);
}

/* the name as it came, bytes outside printable ASCII as \xNN */
static void shown(char *out, size_t cap, const char *path, size_t n)
{
    size_t o = 0;
    for (size_t i = 0; i < n && o + 5 < cap; i++) {
        unsigned char c = (unsigned char)path[i];
        o += (size_t)snprintf(out + o, cap - o, c >= 0x20 && c < 0x7f ? "%c" : "\\x%02x", c);
    }
    out[o] = 0;
}

static FILE *raw_fopen(const char *path, const char *mode)
{
    char name[256], text[512];
    const char *ext;
    size_t n, tail;
    FILE *res;

    if (!path || strncmp(path, RAW_CARD, sizeof RAW_CARD - 1) != 0) {
        /* a dump name that reached Linux garbled does not start with the card: show it too */
        res = g_libc_fopen(path, mode);
        if (path) {
            tail = 4096 - ((uintptr_t)path & 4095);
            shown(text, sizeof text, path, strnlen(path, tail < 96 ? tail : 96));
            raw_log("fopen (not on the card) \"%s\" mode %s -> %s%s", text, mode, res ? "open" : "FAILED",
                    res || errno == 0 ? "" : strerror(errno));
        } else {
            raw_log("fopen NULL mode %s", mode);
        }
        return res;
    }
    g_raw_opens++;
    ext = strstr(path, ".raw");
    n = ext ? (size_t)(ext - path) + 4 : strlen(path);
    if (n < sizeof name && ext && path[n]) {
        memcpy(name, path, n);
        name[n] = 0;
        g_raw_cut++;
    } else {
        snprintf(name, sizeof name, "%s", path);
    }
    res = g_libc_fopen(name, mode);
    /* the name with up to 32 bytes after it, but not past the page (mapped from /dev/mem) */
    tail = 4096 - (((uintptr_t)path + n) & 4095);
    shown(text, sizeof text, path, n + strnlen(path + n, tail < 32 ? tail : 32));
    raw_log("fopen \"%s\" mode %s -> %s%s", text, mode, res ? "open" : "FAILED",
            strcmp(name, path) != 0 ? " (name cut)" : "");
    for (int i = 0; res && i < RAW_FILES; i++) {
        if (!g_raw_files[i].f) {
            g_raw_files[i].f = res;
            snprintf(g_raw_files[i].name, sizeof g_raw_files[i].name, "%.95s", name + sizeof RAW_CARD - 1);
            g_raw_files[i].writes = 0;
            g_raw_files[i].bytes = 0;
            break;
        }
    }
    return res;
}

/* whether the byte at P can be read: the kernel copies it into a pipe or says EFAULT */
static int readable(const void *p)
{
    char c;
    if (g_probe[0] < 0)
        return 1;
    if (write(g_probe[1], p, 1) != 1)
        return 0;
    return read(g_probe[0], &c, 1) == 1;
}

/* FILE_WRITE hands fwrite the address GetVirtualAddr left behind, which is stale or NULL when its
 * mmap of /dev/mem failed: a buffer that cannot be read is skipped instead of crashing the app */
static size_t raw_fwrite(const void *p, size_t size, size_t count, FILE *f)
{
    size_t r, n = size * count;
    int i;

    for (i = 0; f && i < RAW_FILES && g_raw_files[i].f != f; i++)
        ;
    if (!f) {
        if (++g_null_writes == 1 || g_null_writes % 1000 == 0)
            raw_log("fwrite of %u bytes to a stream that did not open: skipped (%d so far)", (unsigned)n,
                    g_null_writes);
        return 0;
    }
    if (i == RAW_FILES) {
        if (++g_other_writes <= 3)
            raw_log("fwrite of %u bytes from %p to a stream not on the card (%p)", (unsigned)n, p, (void *)f);
        return g_libc_fwrite(p, size, count, f);
    }
    if (g_raw_files[i].writes == 0)
        raw_log("first fwrite to %s: %u bytes from %p", g_raw_files[i].name, (unsigned)n, p);
    if (n && (!p || !readable(p) || !readable((const char *)p + n - 1))) {
        if (++g_bad_writes <= 3 || g_bad_writes % 1000 == 0)
            raw_log("fwrite %u to %s: %u bytes from %p cannot be read: skipped (%d so far)",
                    g_raw_files[i].writes, g_raw_files[i].name, (unsigned)n, p, g_bad_writes);
        g_raw_files[i].writes++;
        return 0;
    }
    r = g_libc_fwrite(p, size, count, f);
    g_raw_files[i].writes++;
    g_raw_files[i].bytes += r * size;
    return r;
}

/* GetVirtualAddr's mapping of the RTOS buffer: a failure is logged with the physical address */
static void *raw_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off)
{
    void *res = g_libc_mmap(addr, len, prot, flags, fd, off);
    if (res == MAP_FAILED) {
        int e = errno;
        if (++g_map_fails <= 5 || g_map_fails % 1000 == 0)
            raw_log("mmap of %u bytes at 0x%08lx (fd %d) failed: %s (%d so far)", (unsigned)len,
                    (unsigned long)off, fd, strerror(e), g_map_fails);
        errno = e;
    }
    return res;
}

static int raw_fclose(FILE *f)
{
    for (int i = 0; f && i < RAW_FILES; i++) {
        if (g_raw_files[i].f == f) {
            raw_log("fclose %s: %u writes, %llu bytes", g_raw_files[i].name, g_raw_files[i].writes,
                    g_raw_files[i].bytes);
            g_raw_files[i].f = NULL;
            break;
        }
    }
    return g_libc_fclose(f);
}

/* the GOT slot of SYM in the loaded library whose path ends in LIB */
struct got_find {
    const char *lib, *sym;
    void **slot;
};

static int got_find_cb(struct dl_phdr_info *info, size_t size, void *data)
{
    struct got_find *g = data;
    const char *name = info->dlpi_name;
    size_t ln = strlen(name), lb = strlen(g->lib);
    uintptr_t base = info->dlpi_addr, jmprel = 0, symtab = 0, strtab = 0, relsz = 0;
    const ElfW(Dyn) *dyn = NULL;

    (void)size;
    if (ln < lb || strcmp(name + ln - lb, g->lib) != 0)
        return 0;
    for (int i = 0; i < info->dlpi_phnum; i++)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC)
            dyn = (const ElfW(Dyn) *)(base + info->dlpi_phdr[i].p_vaddr);
    for (; dyn && dyn->d_tag != DT_NULL; dyn++) {
        /* glibc relocates these in place on ARM; an unrelocated one is below the base */
        uintptr_t p = dyn->d_un.d_ptr < base ? base + dyn->d_un.d_ptr : dyn->d_un.d_ptr;
        switch (dyn->d_tag) {
        case DT_JMPREL:   jmprel = p; break;
        case DT_SYMTAB:   symtab = p; break;
        case DT_STRTAB:   strtab = p; break;
        case DT_PLTRELSZ: relsz = dyn->d_un.d_val; break;
        }
    }
    if (!jmprel || !symtab || !strtab)
        return 1;
    for (const ElfW(Rel) *r = (const ElfW(Rel) *)jmprel; (uintptr_t)(r + 1) <= jmprel + relsz; r++) {
        const ElfW(Sym) *s = (const ElfW(Sym) *)symtab + ELF32_R_SYM(r->r_info);
        if (ELF32_R_TYPE(r->r_info) == R_ARM_JUMP_SLOT && strcmp((const char *)strtab + s->st_name, g->sym) == 0) {
            g->slot = (void **)(base + r->r_offset);
            break;
        }
    }
    return 1;
}

static int got_hook(const char *sym, void *to, char *resp, size_t len)
{
    struct got_find g = { RAW_LIB, sym, NULL };
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t at;

    dl_iterate_phdr(got_find_cb, &g);
    if (!g.slot) {
        snprintf(resp, len, "error no %s slot in %s (not loaded here?)", sym, RAW_LIB);
        return -1;
    }
    if (*g.slot != to) {
        at = (uintptr_t)g.slot & ~(uintptr_t)(page - 1);
        if (mprotect((void *)at, (size_t)page, PROT_READ | PROT_WRITE) != 0) {
            snprintf(resp, len, "error mprotect %p failed", (void *)at);
            return -1;
        }
        *(void *volatile *)g.slot = to;   /* one aligned word: atomic on ARM */
    }
    return 0;
}

static int raw_fix(char *resp, size_t len)
{
    int mapped;

    if (!g_libc_fopen) {
        g_libc_fopen = (fopen_fn)dlsym(RTLD_DEFAULT, "fopen");
        g_libc_fwrite = (fwrite_fn)dlsym(RTLD_DEFAULT, "fwrite");
        g_libc_fclose = (fclose_fn)dlsym(RTLD_DEFAULT, "fclose");
        g_libc_mmap = (mmap_fn)dlsym(RTLD_DEFAULT, "mmap");
        if (pipe(g_probe) != 0)
            g_probe[0] = g_probe[1] = -1;
    }
    if (!g_libc_fopen || !g_libc_fwrite || !g_libc_fclose || !g_libc_mmap) {
        snprintf(resp, len, "error no libc stdio");
        return -1;
    }
    /* fwrite and fclose first: a card stream is only ever opened through the new fopen */
    if (got_hook("fwrite", (void *)raw_fwrite, resp, len) < 0 || got_hook("fclose", (void *)raw_fclose, resp, len) < 0 ||
        got_hook("fopen", (void *)raw_fopen, resp, len) < 0)
        return -1;
    /* only to log a failed mapping: not having it is no reason to refuse */
    mapped = got_hook("mmap", (void *)raw_mmap, resp, len) == 0;
    snprintf(resp, len,
             "ok rawfix: card opens %d, names cut %d, writes to no stream %d, unreadable %d, mmap %s (failed %d)",
             g_raw_opens, g_raw_cut, g_null_writes, g_bad_writes, mapped ? "hooked" : "not hooked", g_map_fails);
    return 0;
}

static int call_static(const char *mangled, const char *what, char *resp, size_t len)
{
    void (*f)(void) = (void (*)(void))app_sym(mangled);
    if (!f) {
        snprintf(resp, len, "error no %s", mangled);
        return -1;
    }
    f();
    snprintf(resp, len, "ok %s", what);
    return 0;
}

int g360r_plugin_request(const char *req, char *resp, size_t len)
{
    char verb[16];
    int a = 0, b = 0;
    int n = sscanf(req, "%15s %i %i", verb, &a, &b);

    if (n < 1) {
        snprintf(resp, len, "error empty request");
        return -1;
    }
    if (strcmp(verb, "ping") == 0) {
        snprintf(resp, len, "ok pong (r210 plugin)");
        return 0;
    }
    if (strcmp(verb, "menus") == 0)
        return menus(resp, len);
    if (strcmp(verb, "menu") == 0 && n == 2)
        return menu_one(a, resp, len);
    if (strcmp(verb, "menuset") == 0 && n == 3)
        return menu_set(a, b, resp, len);
    if (strcmp(verb, "shot") == 0)
        return remote("_ZN20CUINETFuncRemoteShot15process_captureEv", "shot", resp, len);
    if (strcmp(verb, "recstart") == 0)
        return remote("_ZN20CUINETFuncRemoteShot17process_movie_recEv", "recstart", resp, len);
    if (strcmp(verb, "recstop") == 0)
        return remote("_ZN20CUINETFuncRemoteShot22process_movie_rec_stopEv", "recstop", resp, len);
    if (strcmp(verb, "state") == 0)
        return state(resp, len);
    if (strcmp(verb, "powerstop") == 0)
        return call_static("_ZN10CUINETUtil14StopPowerTimerEv", "powerstop", resp, len);
    if (strcmp(verb, "powerrestart") == 0)
        return call_static("_ZN10CUINETUtil17RestartPowerTimerEv", "powerrestart", resp, len);
    if (strcmp(verb, "dlnastart") == 0)
        return dlna(1, 0, resp, len);
    if (strcmp(verb, "dlnastop") == 0)
        return dlna(0, 1, resp, len);
    if (strcmp(verb, "dlnastate") == 0)
        return dlna(0, 0, resp, len);
    if (strcmp(verb, "mpegstop") == 0)
        return mpeg_stop(resp, len);
    if (strcmp(verb, "rawfix") == 0)
        return raw_fix(resp, len);
    snprintf(resp, len, "error usage: ping | menus | menu TYPE | menuset TYPE VALUE | shot | recstart | recstop"
                        " | state | powerstop | powerrestart | dlnastart | dlnastop | dlnastate | mpegstop | rawfix");
    return -1;
}
