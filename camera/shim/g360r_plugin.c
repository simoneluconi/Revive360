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
 *                       (photo live view only, see release_lv_disp_change)
 *   lensswitch VAL      the settings menu's lens switch, in every mode
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

/* Live view display change (cap run state 0x33, LV_DISP_CHANGE_FLAG): set by
 * ui_set_360_lens_mode and released only by the photo live view's display
 * state callback, which never comes in a video mode. Held, it refuses every
 * later lens change and blocks the movie transitions. */
#define CAP_RUN_LV_DISP_CHANGE 0x33

static int release_lv_disp_change(void)
{
    get_fn held = (get_fn)app_fn("_Z20UI_Get_Cap_Run_State17eUI_CAP_RUN_STATE");
    int_fn release = (int_fn)app_fn("_Z24UI_Release_Cap_Run_State17eUI_CAP_RUN_STATE");

    if (!held || !release || !held(CAP_RUN_LV_DISP_CHANGE))
        return 0;
    release(CAP_RUN_LV_DISP_CHANGE);
    return 1;
}

/* The lens switch of the camera's own settings menu
 * (CSLCDDrawModeMenuSetting::set_menu_value_switch_lens): around a live view
 * stop/start, without the display change flag, so it works in every mode */
#define KEY_LENS 0x21a

static int lens_switch(int lens, char *resp, size_t len)
{
    void_fn lv_stop = (void_fn)app_fn("_Z26UI_Mm_Camera_Liveview_Stopv");
    void_fn lv_start = (void_fn)app_fn("_Z27UI_Mm_Camera_Liveview_Startv");
    set_fn set = (set_fn)app_fn("_Z12UI_Set_Value14eUI_VALUE_ITEMi");
    int_fn exceptional = (int_fn)app_fn("_Z42User_Spec_Exceptional_Value_On_Item_Changei");
    set_fn send = (set_fn)app_fn("_Z34UI_Send_Message_Changed_Attributes14eUI_VALUE_ITEMi");
    set_fn remain = (set_fn)app_fn("_Z19UI_Calculate_Remain15eUI_REMAIN_TYPEb");
    void_fn led = (void_fn)app_fn("_Z24UI_On_LED_Curr_Lens_Modev");
    int released;

    if (!lv_stop || !lv_start || !set || !exceptional || !send || !remain || !led) {
        snprintf(resp, len, "error a lens switch function is missing");
        return -1;
    }
    released = release_lv_disp_change();
    lv_stop();
    set(KEY_LENS, lens);
    exceptional(KEY_LENS);
    send(KEY_LENS, lens);
    remain(0, 0);
    led();
    lv_start();
    snprintf(resp, len, "ok lensswitch %d%s", lens, released ? " (released a held display change)" : "");
    return 0;
}

/* Calls an exported void(void) function of the app */
static int call_void(const char *mangled, const char *what, char *resp, size_t len)
{
    void_fn f = (void_fn)app_fn(mangled);
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
    if (n >= 2 && strcmp(verb, "movsize") == 0) {
        /* The menu value alone did not reach the recorder (a 60 fps choice still
         * recorded at 30): this pushes the size to the camcorder attributes */
        int_fn movie = (int_fn)app_fn("_Z22UI_Set_Attr_Movie_Size14eUS_MOVIE_SIZE");
        if (!movie) {
            snprintf(resp, len, "error no UI_Set_Attr_Movie_Size");
            return -1;
        }
        movie((int)item);
        snprintf(resp, len, "ok movsize %ld", item);
        return 0;
    }
    /* The state transitions the camera's own menu runs (a2, docs/firmware/12):
     * a menu value alone does not re-realize the capture/record engine */
    if (n >= 2 && strcmp(verb, "lensswitch") == 0)
        return lens_switch((int)item, resp, len);
    if (n >= 1 && strcmp(verb, "photomode") == 0)
        return call_void("_Z29UI_Liveview_State_Constructorv", "photomode", resp, len);
    if (n >= 1 && strcmp(verb, "moviestandby") == 0) {
        /* The standby realizes the recorder but leaves the status display on the
         * photo icon: only the movie stop path redraws it, so redraw here */
        release_lv_disp_change();
        if (call_void("_Z23UI_Manage_Movie_Standbyv", "moviestandby", resp, len) < 0)
            return -1;
        return call_void("_Z31UI_Draw_Stby_Or_Card_Or_Factoryv", "moviestandby", resp, len);
    }
    if (n >= 1 && strcmp(verb, "camrealize") == 0) {
        /* Recorder re-realize, for a movie size the standby did not pick up */
        int_fn rvf = (int_fn)app_fn("_Z31UI_Set_Attribute_For_RVF_Streamb");
        if (call_void("_Z25UI_Mm_Camcorder_Unrealizev", "unrealize", resp, len) < 0 || !rvf)
            return -1;
        rvf(1);
        if (call_void("_Z23UI_Mm_Camcorder_Realizev", "realize", resp, len) < 0)
            return -1;
        return call_void("_Z23UI_Manage_Movie_Standbyv", "camrealize", resp, len);
    }
    if (n >= 1 && strcmp(verb, "restandby") == 0) {
        /* The standby is a no-op while it is already on: the movie stop leaves
         * it (synchronously, when not recording), then a new standby realizes
         * the recorder with the current mode and movie size (0x109/0x22a) */
        void_fn standby = (void_fn)app_fn("_Z23UI_Manage_Movie_Standbyv");
        int_fn stop = (int_fn)app_fn("_Z20UI_Manage_Movie_Stop17eUS_REC_STOP_TYPE");
        if (!standby || !stop) {
            snprintf(resp, len, "error no movie stop/standby");
            return -1;
        }
        release_lv_disp_change();
        stop(0);
        standby();
        return call_void("_Z31UI_Draw_Stby_Or_Card_Or_Factoryv", "restandby", resp, len);
    }
    /* The remote shot of the stock phone app (CUINETFuncRemoteShot): record
     * start/stop for every video mode, time-lapse through its interval capture */
    if (n >= 1 && (strcmp(verb, "remoterec") == 0 || strcmp(verb, "remotestop") == 0)) {
        void *(*inst)(void) = (void *(*)(void))app_fn("_ZN20CUINETFuncRemoteShot4InstEv");
        void (*fn)(void *) = (void (*)(void *))app_fn(strcmp(verb, "remoterec") == 0
                                                      ? "_ZN20CUINETFuncRemoteShot17process_movie_recEv"
                                                      : "_ZN20CUINETFuncRemoteShot22process_movie_rec_stopEv");
        void *self;
        if (!inst || !fn || !(self = inst())) {
            snprintf(resp, len, "error no remote shot");
            return -1;
        }
        fn(self);
        snprintf(resp, len, "ok %s", verb);
        return 0;
    }
    if (n >= 1 && strcmp(verb, "recording") == 0) {
        /* Time-lapse runs as an interval capture, not a movie recording */
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
    if (n >= 1 && strcmp(verb, "recstart") == 0) {
        int_fn rec = (int_fn)app_fn("_Z19UI_Manage_Movie_Recb");
        if (!rec) {
            snprintf(resp, len, "error no UI_Manage_Movie_Rec");
            return -1;
        }
        rec(1);
        snprintf(resp, len, "ok recstart");
        return 0;
    }
    if (n >= 1 && strcmp(verb, "tlstart") == 0)
        return call_void("_Z23UI_Manage_TimeLapse_Recv", "tlstart", resp, len);
    if (n >= 1 && strcmp(verb, "recstop") == 0) {
        int_fn stop = (int_fn)app_fn("_Z20UI_Manage_Movie_Stop17eUS_REC_STOP_TYPE");
        if (!stop) {
            snprintf(resp, len, "error no UI_Manage_Movie_Stop");
            return -1;
        }
        release_lv_disp_change();
        stop(0);
        snprintf(resp, len, "ok recstop");
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
