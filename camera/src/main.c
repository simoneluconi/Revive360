/*
 * g360rd entry point and subcommands.
 *
 *   g360rd serve [--sim DIR] [--conf FILE] [--log FILE] [--port 8360]
 *                [--bind 0.0.0.0] [--adapter c200|r210|generic] [--net-error] [--quiet]
 *   g360rd detect [--sim DIR] [--conf FILE]     adapter + capabilities JSON
 *   g360rd ledtest [--log FILE] [--leds-dir D] [--count 5] [--on-ms 5000]
 *                  [--gap-ms 2000] [--no-st] [--beep WAV]
 *   g360rd thumb <image> <out.jpg>              thumbnail extraction test
 *   g360rd mac|ssid|ifflags|ifup|ifdown|ifaddr|waitif|privcmd ...  (netif.c)
 *   g360rd version
 */
#include "server.h"

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

server_t g_srv;

static const char *opt(int argc, char **argv, const char *name, const char *def)
{
    int i;
    for (i = 1; i < argc - 1; i++)
        if (strcmp(argv[i], name) == 0)
            return argv[i + 1];
    return def;
}

static int flag(int argc, char **argv, const char *name)
{
    int i;
    for (i = 1; i < argc; i++)
        if (strcmp(argv[i], name) == 0)
            return 1;
    return 0;
}

/* /opt/etc/.mac.info holds the factory MAC; format not confirmed, so accept
 * any 12 hex digits with or without separators. */
static int mac_from_file(const char *path, char *out, size_t outlen)
{
    char raw[128], hex[13];
    int n = 0, i;
    if (read_text_file(path, raw, sizeof raw) <= 0)
        return -1;
    for (i = 0; raw[i] && n < 12; i++)
        if (isxdigit((unsigned char)raw[i]))
            hex[n++] = (char)tolower((unsigned char)raw[i]);
    if (n != 12)
        return -1;
    hex[12] = 0;
    snprintf(out, outlen, "%.2s:%.2s:%.2s:%.2s:%.2s:%.2s", hex, hex + 2, hex + 4, hex + 6, hex + 8, hex + 10);
    return 0;
}

/* Never asks the Wi-Fi interface: an ioctl on wlan0 before the dhd firmware is
 * loaded reset the SM-C200 (Tests 1b-1e all died in `g360rd detect`, whose only
 * new hardware access was SIOCGIFHWADDR on wlan0). The factory file has the same MAC. */
static void resolve_mac(void)
{
    const adapter_t *a = &g_srv.ad;
    if (a->mac_override[0]) {
        snprintf(g_srv.mac, sizeof g_srv.mac, "%s", a->mac_override);
    } else if (mac_from_file("/opt/etc/.mac.info", g_srv.mac, sizeof g_srv.mac) == 0) {
        /* from the factory MAC file */
    } else {
        snprintf(g_srv.mac, sizeof g_srv.mac, "00:00:00:00:00:00");
        LOGW("mac: no MAC available");
    }
    LOGI("mac: %s", g_srv.mac);
}

static int setup_common(int argc, char **argv)
{
    const char *sim = opt(argc, argv, "--sim", NULL);
    const char *conf = opt(argc, argv, "--conf", NULL);
    const char *forced = opt(argc, argv, "--adapter", NULL);

    if (conf && config_load(&g_srv.cfg, conf) < 0)
        LOGW("config: %s not readable, using defaults", conf);
    trace_start(config_get(&g_srv.cfg, "trace", NULL));
    trace_note("step", argv[0]);
    trace_note("step", "adapter_detect");
    if (!forced)
        forced = config_get(&g_srv.cfg, "adapter", NULL);
    if (adapter_detect(&g_srv.ad, forced, sim, &g_srv.cfg) < 0) {
        LOGE("adapter detection failed");
        return -1;
    }
    {
        const char *rd = opt(argc, argv, "--run-dir", NULL);
        if (rd)
            snprintf(g_srv.ad.run_dir, sizeof g_srv.ad.run_dir, "%s", rd);
    }
    g_srv.start_ms = now_ms_mono();
    trace_note("step", "resolve_mac");
    resolve_mac();
    trace_note("step", "status_init");
    status_init();
    trace_note("step", "setup done");
    return 0;
}

static void on_term(int sig)
{
    (void)sig;
    leds_all_off();
    _exit(0);
}

int cmd_serve(int argc, char **argv)
{
    const char *logf = opt(argc, argv, "--log", NULL);
    int port = atoi(opt(argc, argv, "--port", "8360"));
    const char *bind_addr = opt(argc, argv, "--bind", "0.0.0.0");
    struct sigaction sa;

    log_open(logf, !flag(argc, argv, "--quiet"));
    LOGI("g360rd %s starting (pid %d)", G360RD_VERSION, (int)getpid());
    if (setup_common(argc, argv) < 0)
        return 1;
    g_srv.net_error = flag(argc, argv, "--net-error");
    capture_lock_init();

    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    leds_start();
    leds_set_system_state(g_srv.net_error ? LEDSTATE_ERROR : LEDSTATE_READY);
    if (g_srv.net_error)
        LOGW("serve: started with --net-error (softAP bring-up failed); LEDs show error");
    return server_run(bind_addr, port);
}

int cmd_detect(int argc, char **argv)
{
    sbuf_t b;
    log_open(NULL, 1);
    if (setup_common(argc, argv) < 0)
        return 1;
    sbuf_init(&b);
    sbuf_printf(&b, "{\"adapter\":\"%s\",\"adapter_status\":\"%s\",\"model\":", g_srv.ad.id, g_srv.ad.status);
    json_str(&b, g_srv.ad.model);
    sbuf_puts(&b, ",\"firmware\":");
    json_str(&b, g_srv.ad.firmware);
    sbuf_puts(&b, ",\"mac\":");
    json_str(&b, g_srv.mac);
    sbuf_puts(&b, ",\"storage_root\":");
    json_str(&b, g_srv.ad.storage_root);
    trace_note("step", "storage_mounted + capabilities");
    sbuf_printf(&b, ",\"storage_mounted\":%s,\"capabilities\":",
                adapter_storage_mounted(&g_srv.ad) ? "true" : "false");
    api_capabilities_json(&b);
    sbuf_puts(&b, "}\n");
    fputs(b.buf, stdout);
    sbuf_free(&b);
    trace_note("step", "detect done");
    return 0;
}

/* ------------------------------------------------------------ keytest */

/* Holds the camera's buttons (EVIOCGRAB on the GPIO keyboard) for a while and
 * logs every key event, so a hardware test shows the key codes and whether the
 * factory UI stops reacting while we own them. The grab ends with the command. */
int cmd_keytest(int argc, char **argv)
{
    const char *dev = opt(argc, argv, "--dev", "/dev/input/event0");
    int seconds = atoi(opt(argc, argv, "--seconds", "40"));
    int grab = !flag(argc, argv, "--no-grab");
    char name[80] = "";
    int fd, events = 0;
    int64_t deadline;

    log_open(opt(argc, argv, "--log", NULL), 1);
    fd = open(dev, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("KEYTEST cannot open %s: %s", dev, strerror(errno));
        return 1;
    }
    if (ioctl(fd, EVIOCGNAME(sizeof name - 1), name) < 0)
        snprintf(name, sizeof name, "?");
    LOGI("KEYTEST %s (%s) for %d s, grab=%d", dev, name, seconds, grab);
    if (grab) {
        if (ioctl(fd, EVIOCGRAB, 1) < 0)
            LOGW("KEYTEST EVIOCGRAB failed: %s (reading in parallel)", strerror(errno));
        else
            LOGI("KEYTEST buttons grabbed: the factory UI should not see them now");
    }
    log_flush_sync();
    deadline = now_ms_mono() + (int64_t)seconds * 1000;
    for (;;) {
        int64_t left = deadline - now_ms_mono();
        struct pollfd p = { fd, POLLIN, 0 };
        struct input_event ev[16];
        ssize_t n;
        int i;
        if (left <= 0)
            break;
        if (poll(&p, 1, (int)left) <= 0)
            continue;
        n = read(fd, ev, sizeof ev);
        if (n <= 0)
            break;
        for (i = 0; i < (int)(n / (ssize_t)sizeof ev[0]); i++) {
            if (ev[i].type != EV_KEY)
                continue;
            events++;
            LOGI("KEYTEST key 0x%02x %s", ev[i].code,
                 ev[i].value == 1 ? "down" : ev[i].value == 0 ? "up" : "repeat");
        }
        log_flush_sync();
    }
    if (grab)
        ioctl(fd, EVIOCGRAB, 0);
    close(fd);
    LOGI("KEYTEST done: %d key events, buttons released", events);
    log_flush_sync();
    return 0;
}

/* ------------------------------------------------------------ ledtest */

static void beep(const char *wav)
{
    char *argv[] = { "/usr/bin/paplay", (char *)wav, NULL };
    if (wav && *wav && access(argv[0], X_OK) == 0)
        run_cmd(argv, 3000, NULL, 0);
}

static void led_set_path(const char *dir, int i, const char *v)
{
    char p[300];
    snprintf(p, sizeof p, "%s/led.%d/brightness", dir, i);
    write_text_file(p, v);
}

static void led_report(const char *dir, int count, const char *what)
{
    char line[256] = "";
    int i;
    for (i = 0; i < count; i++) {
        char p[300], v[16];
        snprintf(p, sizeof p, "%s/led.%d/brightness", dir, i);
        if (read_text_file(p, v, sizeof v) < 0)
            snprintf(v, sizeof v, "?");
        snprintf(line + strlen(line), sizeof line - strlen(line), " led.%d=%s", i, v);
    }
    LOGI("LEDTEST readback after %s:%s", what, line);
}

/* Lights each sysfs LED in turn (the user notes which physical LED lights),
 * then drives the firmware's own `st app 360 led ...` names and reads back
 * the sysfs brightness, which may give the mapping without a human. */
int cmd_ledtest(int argc, char **argv)
{
    const char *dir = opt(argc, argv, "--leds-dir", "/sys/class/leds");
    const char *wav = opt(argc, argv, "--beep", NULL);
    int count = atoi(opt(argc, argv, "--count", "5"));
    int on_ms = atoi(opt(argc, argv, "--on-ms", "5000"));
    int gap_ms = atoi(opt(argc, argv, "--gap-ms", "2000"));
    int i, step = 0;
    static const char *const st_names[][2] = {
        { "status", "r" }, { "status", "g" }, { "status", "b" }, { "front", "r" }, { "rear", "r" },
    };

    log_open(opt(argc, argv, "--log", NULL), 1);
    LOGI("LEDTEST start: %d LEDs under %s, %d ms on, %d ms gap", count, dir, on_ms, gap_ms);
    for (i = 0; i < count; i++) {
        char p[300];
        snprintf(p, sizeof p, "%s/led.%d/trigger", dir, i);
        write_text_file(p, "none");
    }
    led_report(dir, count, "initial");

    /* Marker: everything on for 3 s, then off. */
    LOGI("LEDTEST marker: ALL LEDs ON (3 s)");
    beep(wav);
    for (i = 0; i < count; i++)
        led_set_path(dir, i, "1");
    sleep_ms(3000);
    for (i = 0; i < count; i++)
        led_set_path(dir, i, "0");
    sleep_ms(gap_ms);

    /* Phase 1: one sysfs LED at a time. */
    for (i = 0; i < count; i++) {
        step++;
        LOGI("LEDTEST step %d: sysfs led.%d ON for %d ms (note which physical LED/colour lights)", step, i, on_ms);
        beep(wav);
        led_set_path(dir, i, "1");
        sleep_ms(on_ms);
        led_set_path(dir, i, "0");
        LOGI("LEDTEST step %d: led.%d OFF", step, i);
        sleep_ms(gap_ms);
    }

    if (!flag(argc, argv, "--no-st") && access("/usr/bin/st", X_OK) == 0) {
        /* Marker: two short all-on flashes. */
        LOGI("LEDTEST marker: two short ALL-ON flashes, then the st phase");
        for (i = 0; i < 2; i++) {
            int k;
            for (k = 0; k < count; k++) led_set_path(dir, k, "1");
            sleep_ms(500);
            for (k = 0; k < count; k++) led_set_path(dir, k, "0");
            sleep_ms(500);
        }
        sleep_ms(gap_ms);
        /* Phase 2: the firmware's names. */
        for (i = 0; i < (int)(sizeof st_names / sizeof st_names[0]); i++) {
            char out[256], res[64];
            char *on[] = { "/usr/bin/st", "app", "360", "led", (char *)st_names[i][0], (char *)st_names[i][1], "on", NULL };
            char *off[] = { "/usr/bin/st", "app", "360", "led", (char *)st_names[i][0], (char *)st_names[i][1], "off", NULL };
            int rc;
            step++;
            LOGI("LEDTEST step %d: st app 360 led %s %s on (%d ms)", step, st_names[i][0], st_names[i][1], on_ms);
            beep(wav);
            unlink("/tmp/app_result");
            rc = run_cmd(on, 8000, out, sizeof out);
            if (read_text_file("/tmp/app_result", res, sizeof res) < 0)
                snprintf(res, sizeof res, "(none)");
            LOGI("LEDTEST step %d: st rc=%d out=[%s] app_result=[%s]", step, rc, out, res);
            sleep_ms(300);
            led_report(dir, count, "st on");
            sleep_ms(on_ms > 300 ? on_ms - 300 : 0);
            rc = run_cmd(off, 8000, out, sizeof out);
            LOGI("LEDTEST step %d: st off rc=%d", step, rc);
            sleep_ms(gap_ms);
        }
    }
    for (i = 0; i < count; i++)
        led_set_path(dir, i, "0");
    LOGI("LEDTEST done (%d steps)", step);
    beep(wav);
    log_flush_sync();
    return 0;
}

static int cmd_thumb(int argc, char **argv)
{
    unsigned char *jpg;
    size_t len;
    FILE *fp;
    if (argc < 3) {
        fprintf(stderr, "usage: g360rd thumb <image> <out.jpg>\n");
        return 2;
    }
    if (thumb_extract(argv[1], &jpg, &len) < 0) {
        fprintf(stderr, "no thumbnail in %s\n", argv[1]);
        return 1;
    }
    fp = fopen(argv[2], "wb");
    if (!fp || fwrite(jpg, 1, len, fp) != len) {
        fprintf(stderr, "cannot write %s\n", argv[2]);
        return 1;
    }
    fclose(fp);
    printf("%zu bytes\n", len);
    free(jpg);
    return 0;
}

static int usage(void)
{
    fprintf(stderr,
            "g360rd %s - Revive360 camera controller\n"
            "usage: g360rd serve|detect|ledtest|keytest|thumb|version|mac|ssid|ifflags|ifup|ifdown|ifaddr|waitif|privcmd ...\n",
            G360RD_VERSION);
    return 2;
}

int main(int argc, char **argv)
{
    const char *sub;
    if (argc < 2)
        return usage();
    sub = argv[1];
    if (strcmp(sub, "serve") == 0)   return cmd_serve(argc - 1, argv + 1);
    if (strcmp(sub, "detect") == 0)  return cmd_detect(argc - 1, argv + 1);
    if (strcmp(sub, "ledtest") == 0) return cmd_ledtest(argc - 1, argv + 1);
    if (strcmp(sub, "keytest") == 0) return cmd_keytest(argc - 1, argv + 1);
    if (strcmp(sub, "thumb") == 0)   return cmd_thumb(argc - 1, argv + 1);
    if (strcmp(sub, "version") == 0) {
        printf("%s %s (protocol %d)\n", G360RD_NAME, G360RD_VERSION, PROTOCOL_VERSION);
        return 0;
    }
    if (!strcmp(sub, "mac") || !strcmp(sub, "ssid") || !strcmp(sub, "ifflags") || !strcmp(sub, "ifup") ||
        !strcmp(sub, "ifdown") || !strcmp(sub, "ifaddr") || !strcmp(sub, "waitif") || !strcmp(sub, "privcmd")) {
        log_open(NULL, 1);
        return cmd_net(argc - 1, argv + 1);
    }
    return usage();
}
