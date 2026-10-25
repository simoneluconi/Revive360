/*
 * POST /api/v1/power (protocol §5.15): {"action": "reboot"} or {"action": "off"}.
 * A live view session ends cleanly first (its abrupt end freezes the camera
 * app), the card is synced, then systemd reboots or powers off the camera
 * (the card unmounted on the way). The answer goes out before; should the
 * system still be up after a while (a stuck service), the kernel's own
 * reboot/power-off follows.
 */
#include "server.h"

#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/reboot.h>
#include <unistd.h>

#define FORCE_AFTER_S 20

static void *power_thread(void *arg)
{
    int off = arg != NULL;
    char out[256];
    char *argv[] = { "/usr/bin/systemctl", off ? "poweroff" : "reboot", NULL };

    sleep_ms(500);                  /* the answer reaches the client */
    liveview_shutdown();
    sync();
    LOGI("power: systemctl %s", argv[1]);
    run_cmd(argv, 10000, out, sizeof out);
    sleep(FORCE_AFTER_S);
    LOGW("power: still up %d s later, the kernel's %s", FORCE_AFTER_S, off ? "power off" : "reboot");
    sync();
    reboot(off ? RB_POWER_OFF : RB_AUTOBOOT);
    return NULL;
}

void api_power(req_t *r)
{
    jobj_t o;
    const char *action;
    int wrong = 0, off;
    pthread_t th;
    sbuf_t b;

    if (!g_srv.ad.power) {
        http_send_error(r, 501, "unsupported", "this server cannot power the camera");
        return;
    }
    if (jobj_parse(r->body, r->body_len, &o) < 0) {
        http_send_error(r, 400, "bad_request", "body is not a JSON object");
        return;
    }
    action = jobj_get_str(&o, "action", &wrong);
    if (!action || wrong || (strcmp(action, "reboot") != 0 && strcmp(action, "off") != 0)) {
        http_send_error(r, 400, "bad_request", "action must be reboot or off");
        return;
    }
    if (g_srv.recording) {
        http_send_error(r, 409, "busy", "a video recording is in progress");
        return;
    }
    off = strcmp(action, "off") == 0;
    LOGI("power: %s requested by %s", action, r->peer);
    sbuf_init(&b);
    sbuf_printf(&b, "{\"action\":\"%s\"}", action);
    http_send_json(r, 202, &b);
    sbuf_free(&b);
    if (pthread_create(&th, NULL, power_thread, off ? (void *)1 : NULL) == 0)
        pthread_detach(th);
}
