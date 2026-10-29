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
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

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
    snprintf(resp, len, "error usage: ping | menus | menu TYPE | menuset TYPE VALUE | shot | recstart | recstop"
                        " | state | powerstop | powerrestart");
    return -1;
}
