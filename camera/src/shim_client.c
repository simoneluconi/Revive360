/*
 * Talking to libg360r_shim.so, loaded into di-camera-app by the modified
 * firmware (camera/shim/). One request line per connection to
 * /tmp/g360r/shim.sock, one answer line; the shim runs it on the app's main
 * loop and answers within ~10 s.
 *
 * shim_plugin() sends a "p ..." request to the plugin (camera/shim/g360r_plugin.c,
 * the app's own UI value functions) and loads the plugin from the card first if
 * the shim has none yet.
 */
#include "server.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define SHIM_SOCK   "/tmp/g360r/shim.sock"
#define PLUGIN_PATH "/opt/storage/sdcard/g360r/libg360r_plugin.so"

static pthread_mutex_t g_shim_mu = PTHREAD_MUTEX_INITIALIZER;

int shim_request(const char *req, char *reply, size_t len)
{
    struct sockaddr_un sa;
    struct timeval tv = { 15, 0 };
    char line[1024];
    ssize_t n = 0;
    size_t got = 0;
    int s, rc = -1;

    reply[0] = 0;
    s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) {
        snprintf(reply, len, "error socket: %s", strerror(errno));
        return -1;
    }
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s", SHIM_SOCK);
    snprintf(line, sizeof line, "%s\n", req);
    if (connect(s, (struct sockaddr *)&sa, sizeof sa) < 0) {
        snprintf(reply, len, "error no shim (%s: %s)", SHIM_SOCK, strerror(errno));
    } else if (send(s, line, strlen(line), MSG_NOSIGNAL) < 0) {
        snprintf(reply, len, "error send: %s", strerror(errno));
    } else {
        /* One line back, possibly in several pieces (the shim answers up to 16 KB) */
        while (got < len - 1 && (n = read(s, reply + got, len - 1 - got)) > 0) {
            got += (size_t)n;
            reply[got] = 0;
            if (strchr(reply, '\n'))
                break;
        }
        if (got == 0) {
            snprintf(reply, len, "error read: %s", n < 0 ? strerror(errno) : "no answer");
        } else {
            reply[strcspn(reply, "\r\n")] = 0;
            rc = 0;
        }
    }
    close(s);
    return rc;
}

int shim_plugin(const char *preq, char *reply, size_t len)
{
    char req[256];
    int rc;

    snprintf(req, sizeof req, "p %s", preq);
    pthread_mutex_lock(&g_shim_mu);
    rc = shim_request(req, reply, len);
    if (rc == 0 && strstr(reply, "no plugin loaded")) {
        char lr[256];
        snprintf(lr, sizeof lr, "plugin %s", PLUGIN_PATH);
        if (shim_request(lr, reply, len) == 0 && strncmp(reply, "ok", 2) == 0) {
            LOGI("shim: %s", reply);
            rc = shim_request(req, reply, len);
        }
    }
    pthread_mutex_unlock(&g_shim_mu);
    if (rc == 0 && strncmp(reply, "ok", 2) != 0)
        rc = -1;
    if (rc < 0)
        LOGW("shim: %s -> %s", preq, reply);
    return rc;
}

/* UI_Get_Value(item) through the plugin: 0 and *out, or -1 */
int shim_ui_get(int item, int *out)
{
    char req[64], reply[256];
    const char *eq;

    snprintf(req, sizeof req, "uiget 0x%x", item);
    if (shim_plugin(req, reply, sizeof reply) < 0)
        return -1;
    eq = strstr(reply, "= ");
    if (!eq || sscanf(eq + 2, "%d", out) != 1)
        return -1;
    return 0;
}
