/*
 * The few GLib 2.32 / GDBus declarations the helper uses. The camera has the
 * libraries but no headers; these match the GLib 2.32 API, which is stable.
 */
#ifndef GLIB_MIN_H
#define GLIB_MIN_H

#include <stddef.h>

typedef char gchar;
typedef int gint;
typedef unsigned int guint;
typedef int gboolean;
typedef void *gpointer;
typedef const void *gconstpointer;
typedef unsigned char guchar;
typedef unsigned char guint8;
typedef unsigned short guint16;
typedef long gssize;
typedef unsigned long gsize;
typedef unsigned int GQuark;

typedef struct { GQuark domain; gint code; gchar *message; } GError;

typedef struct _GDBusConnection GDBusConnection;
typedef struct _GDBusMethodInvocation GDBusMethodInvocation;
typedef struct _GDBusNodeInfo GDBusNodeInfo;
typedef struct _GDBusInterfaceInfo GDBusInterfaceInfo;
typedef struct _GVariant GVariant;
typedef struct _GVariantType GVariantType;
typedef struct _GVariantBuilder GVariantBuilder;
typedef struct _GMainLoop GMainLoop;
typedef struct _GMainContext GMainContext;
typedef struct _GCancellable GCancellable;
typedef struct _GAsyncResult GAsyncResult;
typedef struct _GObject GObject;

#define G_VARIANT_TYPE(s) ((const GVariantType *)(s))
#define G_BUS_TYPE_SYSTEM 1
#define G_DBUS_CALL_FLAGS_NONE 0
#define G_DBUS_SIGNAL_FLAGS_NONE 0

typedef void (*GDestroyNotify)(gpointer);
typedef gboolean (*GSourceFunc)(gpointer);
typedef void (*GAsyncReadyCallback)(GObject *source, GAsyncResult *res, gpointer user_data);
typedef void (*GDBusSignalCallback)(GDBusConnection *connection, const gchar *sender, const gchar *object_path,
                                    const gchar *interface_name, const gchar *signal_name, GVariant *parameters,
                                    gpointer user_data);

typedef void (*GDBusInterfaceMethodCallFunc)(GDBusConnection *connection, const gchar *sender,
                                             const gchar *object_path, const gchar *interface_name,
                                             const gchar *method_name, GVariant *parameters,
                                             GDBusMethodInvocation *invocation, gpointer user_data);
typedef GVariant *(*GDBusInterfaceGetPropertyFunc)(GDBusConnection *connection, const gchar *sender,
                                                   const gchar *object_path, const gchar *interface_name,
                                                   const gchar *property_name, GError **error, gpointer user_data);
typedef gboolean (*GDBusInterfaceSetPropertyFunc)(GDBusConnection *connection, const gchar *sender,
                                                  const gchar *object_path, const gchar *interface_name,
                                                  const gchar *property_name, GVariant *value, GError **error,
                                                  gpointer user_data);
typedef struct {
    GDBusInterfaceMethodCallFunc method_call;
    GDBusInterfaceGetPropertyFunc get_property;
    GDBusInterfaceSetPropertyFunc set_property;
    gpointer padding[8];
} GDBusInterfaceVTable;

void g_type_init(void);
GMainLoop *g_main_loop_new(GMainContext *context, gboolean is_running);
void g_main_loop_run(GMainLoop *loop);
guint g_timeout_add_seconds(guint interval, GSourceFunc function, gpointer data);
guint g_idle_add(GSourceFunc function, gpointer data);
void g_error_free(GError *error);
void g_free(gpointer mem);

GDBusConnection *g_bus_get_sync(int bus_type, GCancellable *cancellable, GError **error);
GDBusNodeInfo *g_dbus_node_info_new_for_xml(const gchar *xml_data, GError **error);
GDBusInterfaceInfo *g_dbus_node_info_lookup_interface(GDBusNodeInfo *info, const gchar *name);
guint g_dbus_connection_register_object(GDBusConnection *connection, const gchar *object_path,
                                        GDBusInterfaceInfo *interface_info, const GDBusInterfaceVTable *vtable,
                                        gpointer user_data, GDestroyNotify user_data_free_func, GError **error);
void g_dbus_method_invocation_return_value(GDBusMethodInvocation *invocation, GVariant *parameters);
void g_dbus_method_invocation_return_dbus_error(GDBusMethodInvocation *invocation, const gchar *error_name,
                                                const gchar *error_message);
gboolean g_dbus_connection_emit_signal(GDBusConnection *connection, const gchar *destination_bus_name,
                                       const gchar *object_path, const gchar *interface_name,
                                       const gchar *signal_name, GVariant *parameters, GError **error);
void g_dbus_connection_call(GDBusConnection *connection, const gchar *bus_name, const gchar *object_path,
                            const gchar *interface_name, const gchar *method_name, GVariant *parameters,
                            const GVariantType *reply_type, int flags, gint timeout_msec,
                            GCancellable *cancellable, GAsyncReadyCallback callback, gpointer user_data);
GVariant *g_dbus_connection_call_finish(GDBusConnection *connection, GAsyncResult *res, GError **error);
GVariant *g_dbus_connection_call_sync(GDBusConnection *connection, const gchar *bus_name,
                                      const gchar *object_path, const gchar *interface_name,
                                      const gchar *method_name, GVariant *parameters,
                                      const GVariantType *reply_type, int flags, gint timeout_msec,
                                      GCancellable *cancellable, GError **error);
guint g_dbus_connection_signal_subscribe(GDBusConnection *connection, const gchar *sender,
                                         const gchar *interface_name, const gchar *member,
                                         const gchar *object_path, const gchar *arg0, int flags,
                                         GDBusSignalCallback callback, gpointer user_data,
                                         GDestroyNotify user_data_free_func);

GVariant *g_variant_new(const gchar *format_string, ...);
GVariant *g_variant_new_fixed_array(const GVariantType *element_type, gconstpointer elements, gsize n_elements,
                                    gsize element_size);
gconstpointer g_variant_get_fixed_array(GVariant *value, gsize *n_elements, gsize element_size);
void g_variant_get(GVariant *value, const gchar *format_string, ...);
GVariant *g_variant_new_string(const gchar *string);
GVariant *g_variant_new_boolean(gboolean value);
GVariant *g_variant_new_object_path(const gchar *object_path);
GVariant *g_variant_new_strv(const gchar *const *strv, gssize length);
GVariant *g_variant_new_objv(const gchar *const *strv, gssize length);
GVariant *g_variant_lookup_value(GVariant *dictionary, const gchar *key, const GVariantType *expected_type);
gboolean g_variant_get_boolean(GVariant *value);
GVariantBuilder *g_variant_builder_new(const GVariantType *type);
void g_variant_builder_add(GVariantBuilder *builder, const gchar *format_string, ...);
void g_variant_builder_add_value(GVariantBuilder *builder, GVariant *value);
GVariant *g_variant_builder_end(GVariantBuilder *builder);
void g_variant_builder_unref(GVariantBuilder *builder);
void g_variant_unref(GVariant *value);

#endif
