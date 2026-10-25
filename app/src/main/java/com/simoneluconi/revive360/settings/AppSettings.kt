package com.simoneluconi.revive360.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Accent colors the user can pick; values are (day, night). */
enum class Accent(val day: Long, val night: Long) {
    BLUE(0xFF0B63CE, 0xFF5EA8FF),
    TEAL(0xFF00796B, 0xFF4DD0C1),
    GREEN(0xFF2E7D32, 0xFF7BD389),
    AMBER(0xFFB26A00, 0xFFFFB74D),
    RED(0xFFC62828, 0xFFFF7A7A),
    PURPLE(0xFF6A3FC8, 0xFFB79CFF),
    GRAPHITE(0xFF37474F, 0xFFB0BEC5),
}

/** The user's preferences, persisted, as StateFlows the UI follows. The paired camera lives in CameraStore. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(enumOr(prefs.getString(THEME, null), ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _accent = MutableStateFlow(enumOr(prefs.getString(ACCENT, null), Accent.BLUE))
    val accent: StateFlow<Accent> = _accent.asStateFlow()

    private val _haptics = MutableStateFlow(prefs.getBoolean(HAPTICS, true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _devHost = MutableStateFlow(prefs.getString(DEV_HOST, null))
    /**
     * Debug builds only: "host:port" of a camera server reached over the phone's normal network
     * (the controller's simulator on a PC) instead of the camera's Wi-Fi. Null = the real camera.
     */
    val devHost: StateFlow<String?> = _devHost.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit { putString(THEME, mode.name) }
    }

    fun setAccent(accent: Accent) {
        _accent.value = accent
        prefs.edit { putString(ACCENT, accent.name) }
    }

    fun setHaptics(on: Boolean) {
        _haptics.value = on
        prefs.edit { putBoolean(HAPTICS, on) }
    }

    fun setDevHost(host: String?) {
        val value = host?.trim()?.takeIf { it.isNotEmpty() }
        _devHost.value = value
        prefs.edit { putString(DEV_HOST, value) }
    }

    private companion object {
        const val THEME = "theme"
        const val ACCENT = "accent"
        const val HAPTICS = "haptics"
        const val DEV_HOST = "dev_host"

        inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
