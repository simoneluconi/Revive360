/*
 * g360r-ble: the camera's Bluetooth LE side, which only negotiates the Wi-Fi
 * (docs/protocol.md §6). Everything else goes over the Wi-Fi it switches on.
 *
 * It advertises the G360R service and serves three characteristics through the
 * camera's own BlueZ (Tizen 5.30: GattManager1.RegisterService, the object model
 * of Tizen's libbluetooth-api):
 *
 *   INFO  read   {"v":1,"name":...,"ssid":...,"wifi":"off|starting|on|failed","pair":bool}
 *   WIFI  read   {"ssid":...,"psk":...} while pairing is open, else {"pair":false}
 *   CMD   write  one byte: 1 = Wi-Fi on, 0 = Wi-Fi off
 *
 * Pairing is open for the first minutes after the camera was switched on: the
 * phone gets the network's name and passphrase only from someone holding the
 * camera. Switching the Wi-Fi on needs nothing more: joining it needs the
 * passphrase anyway.
 *
 * The Wi-Fi itself is the launcher's: this writes the wanted state to a file and
 * reads back the one the launcher reports (launcher.sh, "Wi-Fi on demand").
 *
 * Built against the camera's glibc 2.13 and GLib 2.32 (camera/Makefile, ble).
 */
#include "glib_min.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>

#define BASE_UUID(n)   "d9c7" n "-5a3b-4e21-9f6d-7c8e0a1b2c3d"
#define SERVICE_UUID   BASE_UUID("0001")
#define CMD_UUID       BASE_UUID("0002")
#define INFO_UUID      BASE_UUID("0004")
#define WIFI_UUID      BASE_UUID("0005")

#define SERVICE_PATH   "/org/g360r/gatt/service0"
#define CMD_PATH       SERVICE_PATH "/char0"
#define INFO_PATH      SERVICE_PATH "/char1"
#define WIFI_PATH      SERVICE_PATH "/char2"
#define ADAPTER_PATH   "/org/bluez/hci0"
#define ADV_SLOT       1
#define PAIR_WINDOW_S  180

static GDBusConnection *g_bus;
static const char *g_conf = "/opt/storage/sdcard/g360r/g360r.conf";
static const char *g_dir = "/tmp/g360r";
static FILE *g_log;
static char g_name[32] = "G360R";

static void log_line(const char *fmt, ...)
{
    struct timeval tv;
    struct tm tm;
    va_list ap;
    FILE *f = g_log ? g_log : stderr;

    gettimeofday(&tv, NULL);
    localtime_r(&tv.tv_sec, &tm);
    fprintf(f, "%04d-%02d-%02d %02d:%02d:%02d ble: ", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
            tm.tm_hour, tm.tm_min, tm.tm_sec);
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fflush(f);
}

static long uptime_s(void)
{
    char buf[64];
    FILE *f = fopen("/proc/uptime", "r");
    long up = 0;

    if (f) {
        if (fgets(buf, sizeof buf, f))
            up = strtol(buf, NULL, 10);
        fclose(f);
    }
    return up;
}

static int pairing_open(void)
{
    return uptime_s() < PAIR_WINDOW_S;
}

/* The first line of g_dir/name, trimmed; "" when there is none */
static void read_state(const char *name, char *out, size_t cap)
{
    char path[128];
    FILE *f;
    size_t n;

    out[0] = 0;
    snprintf(path, sizeof path, "%s/%s", g_dir, name);
    if (!(f = fopen(path, "r")))
        return;
    if (fgets(out, (int)cap, f)) {
        n = strlen(out);
        while (n && (out[n - 1] == '\n' || out[n - 1] == '\r' || out[n - 1] == ' '))
            out[--n] = 0;
    }
    fclose(f);
}

static void write_state(const char *name, const char *value)
{
    char path[128], part[136];
    FILE *f;

    snprintf(path, sizeof path, "%s/%s", g_dir, name);
    snprintf(part, sizeof part, "%s.part", path);
    if (!(f = fopen(part, "w")))
        return;
    fprintf(f, "%s\n", value);
    fclose(f);
    rename(part, path);
}

/* A key = value of g360r.conf */
static void conf_value(const char *key, char *out, size_t cap)
{
    char line[256];
    size_t klen = strlen(key);
    FILE *f = fopen(g_conf, "r");

    out[0] = 0;
    if (!f)
        return;
    while (fgets(line, sizeof line, f)) {
        char *p = line, *e;
        while (*p == ' ' || *p == '\t')
            p++;
        if (strncmp(p, key, klen) != 0)
            continue;
        p += klen;
        while (*p == ' ' || *p == '\t')
            p++;
        if (*p++ != '=')
            continue;
        while (*p == ' ' || *p == '\t' || *p == '"')
            p++;
        e = p + strlen(p);
        while (e > p && (e[-1] == '\n' || e[-1] == '\r' || e[-1] == ' ' || e[-1] == '"'))
            e--;
        if ((size_t)(e - p) < cap) {
            memcpy(out, p, (size_t)(e - p));
            out[e - p] = 0;
        }
    }
    fclose(f);
}

/* A JSON string with quotes and backslashes escaped (the values here are plain ASCII) */
static void json_text(char *out, size_t cap, const char *s)
{
    size_t n = 0;
    while (*s && n + 2 < cap) {
        if (*s == '"' || *s == '\\')
            out[n++] = '\\';
        out[n++] = *s++;
    }
    out[n] = 0;
}

/* The network's name is no secret (it is broadcast while on): a phone that paired over the
 * Wi-Fi alone finds its camera among others by it */
static void info_value(char *out, size_t cap)
{
    char wifi[32], ssid[64], es[140];
    read_state("wifi.state", wifi, sizeof wifi);
    read_state("wifi.ssid", ssid, sizeof ssid);
    json_text(es, sizeof es, ssid);
    snprintf(out, cap, "{\"v\":1,\"name\":\"%s\",\"ssid\":\"%s\",\"wifi\":\"%s\",\"pair\":%s}", g_name, es,
             wifi[0] ? wifi : "off", pairing_open() ? "true" : "false");
}

static void wifi_value(char *out, size_t cap)
{
    char ssid[64], psk[96], es[140], ep[200];

    if (!pairing_open()) {
        snprintf(out, cap, "{\"pair\":false}");
        return;
    }
    read_state("wifi.ssid", ssid, sizeof ssid);
    conf_value("psk", psk, sizeof psk);
    json_text(es, sizeof es, ssid);
    json_text(ep, sizeof ep, psk);
    snprintf(out, cap, "{\"pair\":true,\"ssid\":\"%s\",\"psk\":\"%s\"}", es, ep);
}

/* ------------------------------------------------------------------ D-Bus objects */

static const char SERVICE_XML[] =
    "<node>"
    " <interface name='org.freedesktop.DBus.ObjectManager'>"
    "  <method name='GetManagedObjects'><arg type='a{oa{sa{sv}}}' name='objects' direction='out'/></method>"
    " </interface>"
    " <interface name='org.bluez.GattService1'>"
    "  <property type='s' name='UUID' access='read'/>"
    "  <property type='b' name='Primary' access='read'/>"
    "  <property type='ao' name='Characteristics' access='read'/>"
    " </interface>"
    "</node>";

static const char CHAR_XML[] =
    "<node>"
    " <interface name='org.bluez.GattCharacteristic1'>"
    "  <method name='ReadValue'><arg type='s' name='address' direction='in'/><arg type='y' name='id' direction='in'/>"
    "   <arg type='q' name='offset' direction='in'/><arg type='ay' name='value' direction='out'/></method>"
    "  <method name='WriteValue'><arg type='s' name='address' direction='in'/><arg type='y' name='id' direction='in'/>"
    "   <arg type='q' name='offset' direction='in'/><arg type='ay' name='value' direction='in'/></method>"
    "  <method name='StartNotify'/>"
    "  <method name='StopNotify'/>"
    "  <method name='IndicateConfirm'><arg type='s' name='address' direction='in'/>"
    "   <arg type='b' name='complete' direction='in'/></method>"
    "  <property type='s' name='UUID' access='read'/>"
    "  <property type='o' name='Service' access='read'/>"
    "  <property type='ay' name='Value' access='read'/>"
    "  <property type='b' name='Notifying' access='read'/>"
    "  <property type='as' name='Flags' access='read'/>"
    "  <property type='ao' name='Descriptors' access='read'/>"
    " </interface>"
    "</node>";

typedef struct {
    const char *path;
    const char *uuid;
    const char *flags[3];
} chrc_t;

enum { CH_CMD, CH_INFO, CH_WIFI };

static const chrc_t CHRCS[] = {
    { CMD_PATH, CMD_UUID, { "write", "write-without-response", NULL } },
    { INFO_PATH, INFO_UUID, { "read", NULL, NULL } },
    { WIFI_PATH, WIFI_UUID, { "read", NULL, NULL } },
};
#define N_CHRCS (sizeof CHRCS / sizeof CHRCS[0])

static const chrc_t *chrc_at(const char *path)
{
    size_t i;
    for (i = 0; i < N_CHRCS; i++)
        if (strcmp(CHRCS[i].path, path) == 0)
            return &CHRCS[i];
    return NULL;
}

static GVariant *empty_bytes(void)
{
    return g_variant_new_fixed_array(G_VARIANT_TYPE("y"), "", 0, 1);
}

static GVariant *chrc_property(const chrc_t *c, const char *name)
{
    if (strcmp(name, "UUID") == 0)
        return g_variant_new_string(c->uuid);
    if (strcmp(name, "Service") == 0)
        return g_variant_new_object_path(SERVICE_PATH);
    if (strcmp(name, "Value") == 0)
        return empty_bytes();
    if (strcmp(name, "Notifying") == 0)
        return g_variant_new_boolean(0);
    if (strcmp(name, "Flags") == 0)
        return g_variant_new_strv(c->flags, -1);
    if (strcmp(name, "Descriptors") == 0)
        return g_variant_new_objv(NULL, 0);
    return NULL;
}

static GVariant *service_property(const char *name)
{
    static const char *const paths[] = { CMD_PATH, INFO_PATH, WIFI_PATH, NULL };
    if (strcmp(name, "UUID") == 0)
        return g_variant_new_string(SERVICE_UUID);
    if (strcmp(name, "Primary") == 0)
        return g_variant_new_boolean(1);
    if (strcmp(name, "Characteristics") == 0)
        return g_variant_new_objv(paths, -1);
    return NULL;
}

static GVariant *managed_objects(void)
{
    static const char *const sprops[] = { "UUID", "Primary", "Characteristics" };
    static const char *const cprops[] = { "UUID", "Service", "Value", "Notifying", "Flags", "Descriptors" };
    GVariantBuilder *objects = g_variant_builder_new(G_VARIANT_TYPE("a{oa{sa{sv}}}"));
    GVariantBuilder *ifaces, *props;
    GVariant *v;
    size_t i, j;

    ifaces = g_variant_builder_new(G_VARIANT_TYPE("a{sa{sv}}"));
    props = g_variant_builder_new(G_VARIANT_TYPE("a{sv}"));
    for (j = 0; j < sizeof sprops / sizeof sprops[0]; j++)
        g_variant_builder_add(props, "{sv}", sprops[j], service_property(sprops[j]));
    v = g_variant_builder_end(props);
    g_variant_builder_unref(props);
    g_variant_builder_add(ifaces, "{s@a{sv}}", "org.bluez.GattService1", v);
    v = g_variant_builder_end(ifaces);
    g_variant_builder_unref(ifaces);
    g_variant_builder_add(objects, "{o@a{sa{sv}}}", SERVICE_PATH, v);

    for (i = 0; i < N_CHRCS; i++) {
        ifaces = g_variant_builder_new(G_VARIANT_TYPE("a{sa{sv}}"));
        props = g_variant_builder_new(G_VARIANT_TYPE("a{sv}"));
        for (j = 0; j < sizeof cprops / sizeof cprops[0]; j++)
            g_variant_builder_add(props, "{sv}", cprops[j], chrc_property(&CHRCS[i], cprops[j]));
        v = g_variant_builder_end(props);
        g_variant_builder_unref(props);
        g_variant_builder_add(ifaces, "{s@a{sv}}", "org.bluez.GattCharacteristic1", v);
        v = g_variant_builder_end(ifaces);
        g_variant_builder_unref(ifaces);
        g_variant_builder_add(objects, "{o@a{sa{sv}}}", CHRCS[i].path, v);
    }
    v = g_variant_builder_end(objects);
    g_variant_builder_unref(objects);
    return v;
}

static void service_call(GDBusConnection *c, const gchar *sender, const gchar *path, const gchar *iface,
                         const gchar *method, GVariant *params, GDBusMethodInvocation *inv, gpointer data)
{
    (void)c; (void)sender; (void)path; (void)iface; (void)params; (void)data;
    if (strcmp(method, "GetManagedObjects") == 0) {
        log_line("bluez reads our service");
        g_dbus_method_invocation_return_value(inv, g_variant_new("(@a{oa{sa{sv}}})", managed_objects()));
    } else {
        g_dbus_method_invocation_return_dbus_error(inv, "org.bluez.Error.NotSupported", method);
    }
}

static GVariant *service_get(GDBusConnection *c, const gchar *sender, const gchar *path, const gchar *iface,
                             const gchar *name, GError **error, gpointer data)
{
    (void)c; (void)sender; (void)path; (void)iface; (void)error; (void)data;
    return service_property(name);
}

static void chrc_call(GDBusConnection *c, const gchar *sender, const gchar *path, const gchar *iface,
                      const gchar *method, GVariant *params, GDBusMethodInvocation *inv, gpointer data)
{
    const chrc_t *ch = chrc_at(path);
    (void)c; (void)sender; (void)iface; (void)data;

    if (!ch) {
        g_dbus_method_invocation_return_dbus_error(inv, "org.bluez.Error.Failed", "no such characteristic");
        return;
    }
    if (strcmp(method, "ReadValue") == 0) {
        const gchar *address;
        guint8 id;
        guint16 offset;
        char value[512];
        size_t len;

        g_variant_get(params, "(&syq)", &address, &id, &offset);
        if (ch == &CHRCS[CH_INFO])
            info_value(value, sizeof value);
        else if (ch == &CHRCS[CH_WIFI])
            wifi_value(value, sizeof value);
        else
            value[0] = 0;
        len = strlen(value);
        if (offset == 0)
            log_line("%s reads %s", address, ch == &CHRCS[CH_WIFI] ? "the Wi-Fi details" : "the info");
        if (offset > len)
            offset = (guint16)len;
        g_dbus_method_invocation_return_value(
            inv, g_variant_new("(@ay)", g_variant_new_fixed_array(G_VARIANT_TYPE("y"), value + offset, len - offset, 1)));
    } else if (strcmp(method, "WriteValue") == 0) {
        const gchar *address;
        guint8 id;
        guint16 offset;
        GVariant *value;
        gsize n = 0;
        const guint8 *bytes;

        g_variant_get(params, "(&syq@ay)", &address, &id, &offset, &value);
        bytes = g_variant_get_fixed_array(value, &n, 1);
        if (ch == &CHRCS[CH_CMD] && n >= 1 && (bytes[0] == 0 || bytes[0] == 1)) {
            log_line("%s asks for the Wi-Fi %s", address, bytes[0] ? "on" : "off");
            write_state("wifi.want", bytes[0] ? "on" : "off");
            g_dbus_method_invocation_return_value(inv, NULL);
        } else {
            g_dbus_method_invocation_return_dbus_error(inv, "org.bluez.Error.InvalidValueLength", "unknown command");
        }
        g_variant_unref(value);
    } else if (strcmp(method, "StartNotify") == 0 || strcmp(method, "StopNotify") == 0 ||
               strcmp(method, "IndicateConfirm") == 0) {
        g_dbus_method_invocation_return_value(inv, NULL);
    } else {
        g_dbus_method_invocation_return_dbus_error(inv, "org.bluez.Error.NotSupported", method);
    }
}

static GVariant *chrc_get(GDBusConnection *c, const gchar *sender, const gchar *path, const gchar *iface,
                          const gchar *name, GError **error, gpointer data)
{
    const chrc_t *ch = chrc_at(path);
    (void)c; (void)sender; (void)iface; (void)error; (void)data;
    return ch ? chrc_property(ch, name) : NULL;
}

/* ------------------------------------------------------------------ registration and advertising */

static void registered(GObject *source, GAsyncResult *res, gpointer data)
{
    GError *error = NULL;
    GVariant *ret = g_dbus_connection_call_finish(g_bus, res, &error);
    (void)source; (void)data;
    if (ret) {
        log_line("service registered");
        write_state("ble.state", "ready");
        g_variant_unref(ret);
    } else {
        log_line("service registration FAILED: %s", error ? error->message : "?");
        write_state("ble.state", "failed");
        if (error)
            g_error_free(error);
    }
}

static gboolean register_service(gpointer data)
{
    (void)data;
    g_dbus_connection_call(g_bus, "org.bluez", ADAPTER_PATH, "org.bluez.GattManager1", "RegisterService",
                           g_variant_new("(o@a{sv})", SERVICE_PATH,
                                         g_variant_builder_end(g_variant_builder_new(G_VARIANT_TYPE("a{sv}")))),
                           NULL, G_DBUS_CALL_FLAGS_NONE, 20000, NULL, registered, NULL);
    return 0;
}

static void adapter_call(const char *method, GVariant *params)
{
    GError *error = NULL;
    GVariant *ret = g_dbus_connection_call_sync(g_bus, "org.bluez", ADAPTER_PATH, "org.bluez.Adapter1", method,
                                                params, NULL, G_DBUS_CALL_FLAGS_NONE, 5000, NULL, &error);
    if (ret) {
        g_variant_unref(ret);
    } else {
        log_line("%s failed: %s", method, error ? error->message : "?");
        if (error)
            g_error_free(error);
    }
}

static void start_advertising(void)
{
    /* Flags, then our 128-bit service UUID (little endian); the name in the scan response */
    static const guint8 adv[] = { 0x02, 0x01, 0x06, 0x11, 0x07, 0x3d, 0x2c, 0x1b, 0x0a, 0x8e, 0x7c, 0x6d,
                                  0x9f, 0x21, 0x4e, 0x3b, 0x5a, 0x01, 0x00, 0xc7, 0xd9 };
    guint8 scan[31];
    size_t n = strlen(g_name);

    if (n > 29)
        n = 29;
    scan[0] = (guint8)(n + 1);
    scan[1] = 0x09;
    memcpy(scan + 2, g_name, n);
    /* The controller refuses new parameters while the slot advertises (an earlier run's) */
    adapter_call("SetAdvertising", g_variant_new("(bi)", 0, ADV_SLOT));
    adapter_call("SetAdvertisingData",
                 g_variant_new("(@ayi)", g_variant_new_fixed_array(G_VARIANT_TYPE("y"), adv, sizeof adv, 1), ADV_SLOT));
    adapter_call("SetScanRespData",
                 g_variant_new("(@ayi)", g_variant_new_fixed_array(G_VARIANT_TYPE("y"), scan, n + 2, 1), ADV_SLOT));
    /* 100-200 ms, connectable undirected */
    adapter_call("SetAdvertisingParameters", g_variant_new("(uuuui)", 160u, 320u, 0u, 0u, ADV_SLOT));
    adapter_call("SetAdvertising", g_variant_new("(bi)", 1, ADV_SLOT));
}

/* A connection stops the advertising on some controllers: switch it back on now and then */
static gboolean keep_advertising(gpointer data)
{
    (void)data;
    adapter_call("SetAdvertising", g_variant_new("(bi)", 1, ADV_SLOT));
    return 1;
}

/* The name the phone sees: G360R- and the end of the Bluetooth address */
static void make_name(void)
{
    GError *error = NULL;
    GVariant *ret = g_dbus_connection_call_sync(g_bus, "org.bluez", ADAPTER_PATH, "org.freedesktop.DBus.Properties",
                                                "Get", g_variant_new("(ss)", "org.bluez.Adapter1", "Address"),
                                                G_VARIANT_TYPE("(v)"), G_DBUS_CALL_FLAGS_NONE, 5000, NULL, &error);
    if (ret) {
        GVariant *v;
        const gchar *addr = NULL;
        g_variant_get(ret, "(v)", &v);
        g_variant_get(v, "&s", &addr);
        if (addr && strlen(addr) == 17)
            snprintf(g_name, sizeof g_name, "G360R-%c%c%c%c%c%c", addr[9], addr[10], addr[12], addr[13], addr[15],
                     addr[16]);
        g_variant_unref(v);
        g_variant_unref(ret);
    } else if (error) {
        log_line("no adapter address: %s", error->message);
        g_error_free(error);
    }
}

int main(int argc, char **argv)
{
    static const GDBusInterfaceVTable service_vt = { service_call, service_get, NULL, { NULL } };
    static const GDBusInterfaceVTable chrc_vt = { chrc_call, chrc_get, NULL, { NULL } };
    GError *error = NULL;
    GDBusNodeInfo *sinfo, *cinfo;
    size_t i;
    int a;

    for (a = 1; a + 1 < argc; a += 2) {
        if (strcmp(argv[a], "--conf") == 0)
            g_conf = argv[a + 1];
        else if (strcmp(argv[a], "--dir") == 0)
            g_dir = argv[a + 1];
        else if (strcmp(argv[a], "--log") == 0)
            g_log = fopen(argv[a + 1], "a");
    }
    g_type_init();
    if (!(g_bus = g_bus_get_sync(G_BUS_TYPE_SYSTEM, NULL, &error))) {
        log_line("no system bus: %s", error ? error->message : "?");
        return 1;
    }
    sinfo = g_dbus_node_info_new_for_xml(SERVICE_XML, &error);
    cinfo = g_dbus_node_info_new_for_xml(CHAR_XML, &error);
    if (!sinfo || !cinfo) {
        log_line("bad introspection XML: %s", error ? error->message : "?");
        return 1;
    }
    if (!g_dbus_connection_register_object(g_bus, SERVICE_PATH,
                                           g_dbus_node_info_lookup_interface(sinfo, "org.freedesktop.DBus.ObjectManager"),
                                           &service_vt, NULL, NULL, &error) ||
        !g_dbus_connection_register_object(g_bus, SERVICE_PATH,
                                           g_dbus_node_info_lookup_interface(sinfo, "org.bluez.GattService1"),
                                           &service_vt, NULL, NULL, &error)) {
        log_line("cannot export the service: %s", error ? error->message : "?");
        return 1;
    }
    for (i = 0; i < N_CHRCS; i++) {
        if (!g_dbus_connection_register_object(g_bus, CHRCS[i].path,
                                               g_dbus_node_info_lookup_interface(cinfo, "org.bluez.GattCharacteristic1"),
                                               &chrc_vt, NULL, NULL, &error)) {
            log_line("cannot export %s: %s", CHRCS[i].path, error ? error->message : "?");
            return 1;
        }
    }
    make_name();
    log_line("starting as %s (pairing open for %ld more s)", g_name,
             pairing_open() ? PAIR_WINDOW_S - uptime_s() : 0L);
    start_advertising();
    g_idle_add(register_service, NULL);
    g_timeout_add_seconds(15, keep_advertising, NULL);
    g_main_loop_run(g_main_loop_new(NULL, 0));
    return 0;
}
