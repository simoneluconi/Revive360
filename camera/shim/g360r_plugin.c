/*
 * libg360r_plugin.so: loaded at run time by the shim ("plugin PATH" request),
 * from the card, so its requests change without a firmware flash. Every
 * request runs on di-camera-app's main loop (the shim posts it there).
 *
 * It calls the app's own exported UI value functions, the ones the app uses
 * itself when a setting changes (e.g. its one-touch RAW restores the image
 * quality with UI_Set_Value + UI_Send_Message_Changed_Attributes):
 *   uiget ITEM          UI_Get_Value(item)
 *   uiset ITEM VAL      UI_Set_Value(item, val)
 *   uisend ITEM VAL     UI_Send_Message_Changed_Attributes(item, val): the app
 *                       pushes the changed setting to the camera
 *   menu ITEM VAL       UI_Change_Menu_Value(item, val, 1): what the app's own
 *                       menus do (set, exceptional handling, push, remaining count)
 *   allsend             UI_Send_Message_Changed_All_Attributes(): after a mode change
 *   lens VAL            ui_set_360_lens_mode(val): 0 dual, 1 rear, 2 front
 * Keys used by g360rd (AQK1): mode 0x21c, lens 0x21a, photo size 0xcf, movie
 * size 0x223, self-timer 0x225, time-lapse interval 0x224, loop time 0x21b,
 * movie state 0x2bc (2 = recording), elapsed seconds 0x2bd.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

typedef int (*get_fn)(int);
typedef void (*set_fn)(int, int);
typedef void (*menu_fn)(int, int, int);
typedef void (*void_fn)(void);
typedef void (*int_fn)(int);

static void *app_fn(const char *mangled)
{
    return dlsym(RTLD_DEFAULT, mangled);
}

/* The mode transition the app runs itself (a2, from its mode/capture paths):
 * the menu value 0x21c only selects; the engine mode 0x7d9 is what the screen
 * and the still/movie pipeline follow. All exported, all void. Only from
 * standby: not while capturing (0x27b == 2), recording (0x2bc == 2) or during
 * another mode change (run state & 0x200). The caller waits for 0x7d9 to equal
 * the target and the run state to clear before the next shot. */
#define KEY_CAPTURE_STATE 0x27b
#define KEY_MOVIE_STATE   0x2bc
#define KEY_ENGINE_MODE   0x7d9
#define RUN_MODE_CHANGE   0x200

static int mode_change(int engine_mode, char *resp, size_t len)
{
    get_fn get = (get_fn)app_fn("_Z12UI_Get_Value14eUI_VALUE_ITEM");
    get_fn run = (get_fn)app_fn("_Z23Ui_Get_Common_Run_Statev");
    set_fn load = (set_fn)app_fn("_Z28UI_Load_Variable_Mode_Changeib");
    int_fn manage = (int_fn)app_fn("_Z36UI_Manage_Capture_Mode_By_Modechangei");
    void (*ae)(int, int, int, int) = (void (*)(int, int, int, int))app_fn("_Z26UI_Update_Liveview_Ae_Infobbbb");
    void_fn live = (void_fn)app_fn("_Z29UI_Liveview_State_Constructorv");
    void (*display)(int, int, int, int, int) =
        (void (*)(int, int, int, int, int))app_fn("_Z17UI_Display_Manage16eUI_DISPLAY_ITEMiiii");

    if (!get || !run || !load || !manage || !ae || !live || !display) {
        snprintf(resp, len, "error a mode-change function is missing");
        return -1;
    }
    if (get(KEY_CAPTURE_STATE) == 2 || get(KEY_MOVIE_STATE) == 2 || (run(0) & RUN_MODE_CHANGE)) {
        snprintf(resp, len, "error busy (capture %d, movie %d, run state 0x%x)",
                 get(KEY_CAPTURE_STATE), get(KEY_MOVIE_STATE), run(0));
        return -1;
    }
    load(engine_mode, 0);       /* 0: from the given mode, not the (fixed) dial */
    manage(get(KEY_ENGINE_MODE));
    ae(1, 1, 0, 0);
    live();
    display(100, 1, 0, 0, 0);   /* standby screen redraw */
    snprintf(resp, len, "ok modechange %d -> engine %d, run state 0x%x", engine_mode, get(KEY_ENGINE_MODE), run(0));
    return 0;
}

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
    if (n == 3 && strcmp(verb, "uisend") == 0) {
        set_fn send = (set_fn)app_fn("_Z34UI_Send_Message_Changed_Attributes14eUI_VALUE_ITEMi");
        if (!send) {
            snprintf(resp, len, "error no UI_Send_Message_Changed_Attributes");
            return -1;
        }
        send((int)item, (int)val);
        snprintf(resp, len, "ok uisend 0x%lx %ld", item, val);
        return 0;
    }
    if (n == 3 && strcmp(verb, "menu") == 0) {
        menu_fn menu = (menu_fn)app_fn("_Z20UI_Change_Menu_Value14eUI_VALUE_ITEMib");
        if (!menu) {
            snprintf(resp, len, "error no UI_Change_Menu_Value");
            return -1;
        }
        menu((int)item, (int)val, 1);
        snprintf(resp, len, "ok menu 0x%lx %ld", item, val);
        return 0;
    }
    if (n >= 1 && strcmp(verb, "allsend") == 0) {
        void_fn all = (void_fn)app_fn("_Z38UI_Send_Message_Changed_All_Attributesv");
        if (!all) {
            snprintf(resp, len, "error no UI_Send_Message_Changed_All_Attributes");
            return -1;
        }
        all();
        snprintf(resp, len, "ok allsend");
        return 0;
    }
    if (n >= 2 && strcmp(verb, "lens") == 0) {
        int_fn lens = (int_fn)app_fn("_Z20ui_set_360_lens_modei");
        if (!lens) {
            snprintf(resp, len, "error no ui_set_360_lens_mode");
            return -1;
        }
        lens((int)item);
        snprintf(resp, len, "ok lens %ld", item);
        return 0;
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
    if (n >= 2 && strcmp(verb, "modechange") == 0)
        return mode_change((int)item, resp, len);
    snprintf(resp, len, "error usage: uiget ITEM | uiset ITEM VAL | uisend ITEM VAL | menu ITEM VAL | allsend | lens VAL"
                        " | runstate | modechange ENGINE_MODE");
    return -1;
}
