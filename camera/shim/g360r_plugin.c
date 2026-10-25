/*
 * libg360r_plugin.so: loaded at run time by the shim ("plugin PATH" request),
 * from the card, so its requests change without a firmware flash. Every
 * request runs on di-camera-app's main loop (the shim posts it there).
 *
 * Settings and recording go through the camera's own remote control, the
 * paths the Samsung phone app used (docs/firmware/13): the menu manager
 * NETCameraMenuMan for every setting, the remote shot CUINETFuncRemoteShot for
 * recording. They run the camera's whole handling of a change (mode change,
 * exceptional values, status display), as its buttons do.
 *
 *   uiget ITEM          UI_Get_Value(item)
 *   uiset ITEM VAL      UI_Set_Value(item, val) (debugging only)
 *   netmenu ID [FROM]   one remote menu: name, title, UI key, items (index=value"string")
 *   netvalues ID        the item values of a remote menu, in its order
 *   netset ID VALUE     selects the item with that value, as the stock remote does
 *   remoterec           record start (time-lapse: its interval capture)
 *   remotestop          record stop
 *   recording           1 while recording (time-lapse: while its interval capture runs)
 *   runstate            Ui_Get_Common_Run_State()
 *   dlnastate | dlnastart | dlnastop   the stock remote viewfinder service
 *   mpegstop            stops its live stream session
 *   powerstop | powerrestart   holds off / re-arms the auto power off timer
 *   chain FILE          loads FILE (in the card's g360r/) as a second plugin
 *   x TEXT              hands TEXT to that plugin
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

typedef int (*get_fn)(int);
typedef void (*set_fn)(int, int);

static void *app_fn(const char *mangled)
{
    return dlsym(RTLD_DEFAULT, mangled);
}

#define KEY_MOVIE_STATE 0x2bc   /* 2 while a movie records */

/* ------------------------------------------------ remote menu manager */

/* NETCameraMenuMan: one entry per E_CAMERA_MENU_ID (20 bytes: title, UI key,
 * item count, item strings, item values); members take the instance first */
typedef void *(*menu_inst_fn)(void);
typedef int (*menu_id_fn)(void *, int);
typedef const char *(*menu_str_fn)(void *, int);
typedef const char *(*menu_str_val_fn)(void *, int, int);
typedef int (*menu_id_idx_fn)(void *, int, unsigned);
typedef void (*menu_set_fn)(void *, int, unsigned);

typedef struct {
    void *self;
    menu_id_fn valid, count, cur;
    menu_str_fn name, title;
    menu_id_idx_fn value;
    menu_str_val_fn item;
    menu_set_fn set;
    void (*refresh)(int);
} menu_t;

static int menu_open(menu_t *m, char *resp, size_t len)
{
    menu_inst_fn inst = (menu_inst_fn)app_fn("_ZN16NETCameraMenuMan4InstEv");
    m->valid = (menu_id_fn)app_fn("_ZN16NETCameraMenuMan11is_valid_idE16E_CAMERA_MENU_ID");
    m->count = (menu_id_fn)app_fn("_ZN16NETCameraMenuMan19GetMaxNumOfItemListE16E_CAMERA_MENU_ID");
    m->cur = (menu_id_fn)app_fn("_ZN16NETCameraMenuMan16GetCurrItemIndexE16E_CAMERA_MENU_ID");
    m->name = (menu_str_fn)app_fn("_ZN16NETCameraMenuMan25ToString_E_CAMERA_MENU_IDEi");
    m->title = (menu_str_fn)app_fn("_ZN16NETCameraMenuMan12GetTitleNameE16E_CAMERA_MENU_ID");
    m->value = (menu_id_idx_fn)app_fn("_ZN16NETCameraMenuMan21GetItemValueWithIndexE16E_CAMERA_MENU_IDj");
    m->item = (menu_str_val_fn)app_fn("_ZN16NETCameraMenuMan17GetItemStrByValueE16E_CAMERA_MENU_IDi");
    m->set = (menu_set_fn)app_fn("_ZN16NETCameraMenuMan12SetMenuValueE16E_CAMERA_MENU_IDj");
    m->refresh = (void (*)(int))app_fn("_ZN10CUINETUtil16RefreshStatusLCDEb");
    if (!inst || !m->valid || !m->count || !m->cur || !m->name || !m->title || !m->value || !m->item ||
        !m->set || !m->refresh || !(m->self = inst())) {
        snprintf(resp, len, "error a menu manager function is missing");
        return -1;
    }
    return 0;
}

static int menu_check(menu_t *m, int id, char *resp, size_t len)
{
    if (menu_open(m, resp, len) < 0)
        return -1;
    if (!m->valid(m->self, id)) {
        snprintf(resp, len, "error no remote menu %d", id);
        return -1;
    }
    return 0;
}

static int net_menu_info(int id, int from, char *resp, size_t len)
{
    menu_t m;
    size_t off;
    int n;

    if (menu_check(&m, id, resp, len) < 0)
        return -1;
    n = m.count(m.self, id);
    off = snprintf(resp, len, "ok netmenu %d %s \"%s\" key=0x%x n=%d cur=%d:", id, m.name(m.self, id),
                   m.title(m.self, id), *(int *)((char *)m.self + id * 20 + 4), n, m.cur(m.self, id));
    for (int i = from; i < n && off < len; i++) {
        int v = m.value(m.self, id, (unsigned)i);
        const char *s = m.item(m.self, id, v);
        off += snprintf(resp + off, len - off, " %d=%d\"%s\"", i, v, s ? s : "");
    }
    return 0;
}

static int net_menu_values(int id, char *resp, size_t len)
{
    menu_t m;
    size_t off;
    int n;

    if (menu_check(&m, id, resp, len) < 0)
        return -1;
    n = m.count(m.self, id);
    off = snprintf(resp, len, "ok netvalues %d", id);
    for (int i = 0; i < n && off < len; i++)
        off += snprintf(resp + off, len - off, " %d", m.value(m.self, id, (unsigned)i));
    return 0;
}

/* SetMenuValue takes the item index: the UI value, exceptional handling, push
 * and remaining count (the mode through the camera's mode change); the stock
 * remote then refreshes the status display */
static int net_menu_set(int id, int value, char *resp, size_t len)
{
    menu_t m;
    int n;

    if (menu_check(&m, id, resp, len) < 0)
        return -1;
    n = m.count(m.self, id);
    for (int i = 0; i < n; i++) {
        if (m.value(m.self, id, (unsigned)i) == value) {
            m.set(m.self, id, (unsigned)i);
            m.refresh(1);
            snprintf(resp, len, "ok netset %d %d", id, value);
            return 0;
        }
    }
    snprintf(resp, len, "error %s has no item %d now", m.name(m.self, id), value);
    return -1;
}

/* ------------------------------------------------ remote shot */

/* CUINETFuncRemoteShot: start/stop for every video mode; the time-lapse runs
 * its interval capture (start capture, stop interval capture) */
static int remote_shot(int start, char *resp, size_t len)
{
    void *(*inst)(void) = (void *(*)(void))app_fn("_ZN20CUINETFuncRemoteShot4InstEv");
    void (*fn)(void *) = (void (*)(void *))app_fn(start ? "_ZN20CUINETFuncRemoteShot17process_movie_recEv"
                                                        : "_ZN20CUINETFuncRemoteShot22process_movie_rec_stopEv");
    void *self;

    if (!inst || !fn || !(self = inst())) {
        snprintf(resp, len, "error no remote shot");
        return -1;
    }
    fn(self);
    snprintf(resp, len, "ok %s", start ? "remoterec" : "remotestop");
    return 0;
}

static int recording(char *resp, size_t len)
{
    get_fn get = (get_fn)app_fn("_Z12UI_Get_Value14eUI_VALUE_ITEM");
    int (*tl_mode)(void) = (int (*)(void))app_fn("_ZN10CUINETUtil15IsTimelapseModeEv");
    int (*tl_run)(void) = (int (*)(void))app_fn("_ZN10CUINETUtil20IsTimelapseCapturingEv");

    if (!get || !tl_mode || !tl_run) {
        snprintf(resp, len, "error no recording state functions");
        return -1;
    }
    snprintf(resp, len, "ok recording = %d", tl_mode() ? tl_run() != 0 : get(KEY_MOVIE_STATE) == 2);
    return 0;
}

/* An exported static void(void) of the app */
static int call_static(const char *mangled, const char *what, char *resp, size_t len)
{
    void (*f)(void) = (void (*)(void))app_fn(mangled);
    if (!f) {
        snprintf(resp, len, "error no %s", mangled);
        return -1;
    }
    f();
    snprintf(resp, len, "ok %s", what);
    return 0;
}

/* ------------------------------------------------ remote viewfinder (DLNA/UPnP) */

/* CUINETDlnaMan: the Samsung phone app's Wi-Fi remote, a UPnP MediaServer with
 * the RVF (remote viewfinder) services and the live stream over HTTP
 * (docs/firmware/13). Started here without CUINETFuncDLNA::start_network,
 * which would bring up the stock softAP/Wi-Fi Direct over ours. The instance
 * keeps "started" at +4. */
static int dlna(int start, int stop, char *resp, size_t len)
{
    void *(*inst)(void) = (void *(*)(void))app_fn("_ZN13CUINETDlnaMan4InstEv");
    void (*begin)(void *, int, int) = (void (*)(void *, int, int))app_fn("_ZN13CUINETDlnaMan9StartDlnaEbb");
    void (*end)(void *, int) = (void (*)(void *, int))app_fn("_ZN13CUINETDlnaMan8StopDlnaEb");
    void (*desc_url)(char **) = (void (*)(char **))app_fn("DlnaGetDeviceDescUrl");
    char url[128] = "", *p = url;
    void *self;
    int was;

    if (!inst || !begin || !end || !desc_url || !(self = inst())) {
        snprintf(resp, len, "error a DLNA function is missing");
        return -1;
    }
    was = *((unsigned char *)self + 4);
    if (start && !was)
        begin(self, 1, 0);      /* RVF, a card present */
    if (stop && was)
        end(self, 0);
    if (*((unsigned char *)self + 4))
        desc_url(&p);
    snprintf(resp, len, "ok dlna was=%d now=%d url=%s", was, *((unsigned char *)self + 4), url);
    return 0;
}

/* The live stream's camcorder session (CUINETFuncDLNA::dlna_mpeg_stop): the
 * DLNA stack runs it when a client leaves cleanly, not when the socket just
 * drops; a session left running feeds the next client from mid-stream (no
 * stream header, the stack refuses it), so g360rd stops it around every one */
static int mpeg_stop(char *resp, size_t len)
{
    void *(*inst)(void) = (void *(*)(void))app_fn("_ZN14CUINETFuncDLNA4InstEv");
    void (*stop)(void *, int) = (void (*)(void *, int))app_fn("_ZN14CUINETFuncDLNA14dlna_mpeg_stopEi");
    void *self;

    if (!inst || !stop || !(self = inst())) {
        snprintf(resp, len, "error no dlna_mpeg_stop");
        return -1;
    }
    stop(self, 0);
    snprintf(resp, len, "ok mpegstop");
    return 0;
}

/* ------------------------------------------------ chained plugin */

/* The shim (in the firmware) loads one plugin for good. This one can load
 * another and hand it "x TEXT" requests, so a new plugin needs no card swap:
 * g360rd uploads it to the card, "chain FILE" loads a fresh copy of it. */
static int (*g_chain)(const char *, char *, size_t);

#define CHAIN_DIR "/opt/storage/sdcard/g360r/"

static int chain_load(const char *name, char *resp, size_t len)
{
    static int loads;
    char from[256], to[64], buf[8192];
    FILE *in, *out;
    size_t n;
    void *h;
    int (*f)(const char *, char *, size_t);

    for (const char *c = name; *c; c++)
        if (*c == '/' || (c == name && *c == '.')) {
            snprintf(resp, len, "error chain takes a file name in %s", CHAIN_DIR);
            return -1;
        }
    snprintf(from, sizeof from, "%s%s", CHAIN_DIR, name);
    /* A new name each time: dlopen hands back the library it already has for a path */
    snprintf(to, sizeof to, "/tmp/g360r/chain-%d.so", ++loads);
    if (!(in = fopen(from, "rb")) || !(out = fopen(to, "wb"))) {
        if (in)
            fclose(in);
        snprintf(resp, len, "error cannot copy %s to %s", from, to);
        return -1;
    }
    while ((n = fread(buf, 1, sizeof buf, in)) > 0)
        fwrite(buf, 1, n, out);
    fclose(in);
    fclose(out);
    if (!(h = dlopen(to, RTLD_NOW | RTLD_LOCAL))) {
        snprintf(resp, len, "error dlopen: %s", dlerror());
        return -1;
    }
    if (!(f = (int (*)(const char *, char *, size_t))dlsym(h, "g360r_plugin_request"))) {
        snprintf(resp, len, "error no g360r_plugin_request in %s", from);
        return -1;
    }
    g_chain = f;    /* the previous one stays mapped: its code may still be on a stack */
    snprintf(resp, len, "ok chain %s loaded (%s)", name, to);
    return 0;
}

/* ------------------------------------------------ requests */

int g360r_plugin_request(const char *req, char *resp, size_t len)
{
    char verb[16];
    long item = 0, val = 0;
    int n = sscanf(req, "%15s %li %li", verb, &item, &val);

    if (n >= 2 && strcmp(verb, "uiget") == 0) {
        get_fn get = (get_fn)app_fn("_Z12UI_Get_Value14eUI_VALUE_ITEM");
        if (!get) {
            snprintf(resp, len, "error no UI_Get_Value");
            return -1;
        }
        snprintf(resp, len, "ok uiget 0x%lx = %d", item, get((int)item));
        return 0;
    }
    if (n == 3 && strcmp(verb, "uiset") == 0) {
        set_fn set = (set_fn)app_fn("_Z12UI_Set_Value14eUI_VALUE_ITEMi");
        if (!set) {
            snprintf(resp, len, "error no UI_Set_Value");
            return -1;
        }
        set((int)item, (int)val);
        snprintf(resp, len, "ok uiset 0x%lx %ld", item, val);
        return 0;
    }
    if (n >= 2 && strcmp(verb, "netmenu") == 0)
        return net_menu_info((int)item, n == 3 ? (int)val : 0, resp, len);
    if (n >= 2 && strcmp(verb, "netvalues") == 0)
        return net_menu_values((int)item, resp, len);
    if (n == 3 && strcmp(verb, "netset") == 0)
        return net_menu_set((int)item, (int)val, resp, len);
    if (n >= 1 && strcmp(verb, "remoterec") == 0)
        return remote_shot(1, resp, len);
    if (n >= 1 && strcmp(verb, "remotestop") == 0)
        return remote_shot(0, resp, len);
    if (n >= 1 && strcmp(verb, "recording") == 0)
        return recording(resp, len);
    if (n >= 1 && strcmp(verb, "dlnastate") == 0)
        return dlna(0, 0, resp, len);
    if (n >= 1 && strcmp(verb, "dlnastart") == 0)
        return dlna(1, 0, resp, len);
    if (n >= 1 && strcmp(verb, "dlnastop") == 0)
        return dlna(0, 1, resp, len);
    if (n >= 1 && strcmp(verb, "mpegstop") == 0)
        return mpeg_stop(resp, len);
    /* The auto power off timer, as the stock remote holds it while connected */
    if (n >= 1 && strcmp(verb, "powerstop") == 0)
        return call_static("_ZN10CUINETUtil14StopPowerTimerEv", "powerstop", resp, len);
    if (n >= 1 && strcmp(verb, "powerrestart") == 0)
        return call_static("_ZN10CUINETUtil17RestartPowerTimerEv", "powerrestart", resp, len);
    if (strncmp(req, "chain ", 6) == 0)
        return chain_load(req + 6, resp, len);
    if (strncmp(req, "x ", 2) == 0) {
        if (!g_chain) {
            snprintf(resp, len, "error no chained plugin");
            return -1;
        }
        return g_chain(req + 2, resp, len);
    }
    if (n >= 1 && strcmp(verb, "runstate") == 0) {
        get_fn run = (get_fn)app_fn("_Z23Ui_Get_Common_Run_Statev");
        if (!run) {
            snprintf(resp, len, "error no Ui_Get_Common_Run_State");
            return -1;
        }
        snprintf(resp, len, "ok runstate = %d", run(0));
        return 0;
    }
    snprintf(resp, len, "error usage: uiget ITEM | uiset ITEM VAL | netmenu ID [FROM] | netvalues ID"
                        " | netset ID VALUE | remoterec | remotestop | recording | runstate");
    return -1;
}
