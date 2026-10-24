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
    snprintf(resp, len, "error usage: uiget ITEM | uiset ITEM VAL | uisend ITEM VAL | menu ITEM VAL | allsend | lens VAL");
    return -1;
}
