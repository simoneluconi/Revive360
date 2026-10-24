package com.simoneluconi.revive360.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.BuildConfig
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.protocol.LedChannel
import com.simoneluconi.revive360.protocol.LedState
import com.simoneluconi.revive360.settings.Accent
import com.simoneluconi.revive360.settings.ThemeMode
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val nav = LocalActions.current
    val app = nav.app
    val colors = LocalAppColors.current
    val theme by app.settings.theme.collectAsStateWithLifecycle()
    val accent by app.settings.accent.collectAsStateWithLifecycle()
    val haptics by app.settings.haptics.collectAsStateWithLifecycle()
    val camera by app.store.camera.collectAsStateWithLifecycle()
    val state by app.controller.state.collectAsStateWithLifecycle()
    val info = (state as? LinkState.Connected)?.info
    var confirmForget by remember { mutableStateOf(false) }

    ScreenScaffold(title = stringResource(R.string.settings_title)) {
        SectionTitle(stringResource(R.string.settings_camera))
        Card(Modifier.fillMaxWidth()) {
            if (camera == null) {
                SettingRow(
                    title = stringResource(R.string.welcome_action),
                    icon = AppIcons.Wifi,
                    onClick = { nav.navigate(Screen.Pair) },
                )
            } else {
                InfoRow(stringResource(R.string.info_model), info?.model ?: camera?.model ?: "—")
                Hairline()
                InfoRow(stringResource(R.string.info_firmware), info?.firmware ?: camera?.firmware ?: "—", mono = true)
                Hairline()
                InfoRow(stringResource(R.string.info_network), camera?.ssid ?: "G360R-*", mono = true)
                if (info != null) {
                    Hairline()
                    InfoRow(stringResource(R.string.info_adapter), "${info.adapter} (${info.adapterStatus})", mono = true)
                    Hairline()
                    InfoRow(stringResource(R.string.info_server), "${info.serverName} ${info.serverVersion} · v${info.protocol}", mono = true)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(R.string.settings_repair), icon = AppIcons.Key) { nav.navigate(Screen.Pair) }
                    PillButton(stringResource(R.string.settings_forget), icon = AppIcons.Trash, style = ButtonStyle.DANGER) {
                        confirmForget = true
                    }
                }
            }
        }

        val leds = info?.capabilities?.leds.orEmpty()
        if (leds.isNotEmpty()) {
            SectionTitle(stringResource(R.string.settings_leds))
            Card {
                leds.forEachIndexed { i, led ->
                    if (i > 0) Hairline()
                    LedRow(led)
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_look))
        Card {
            Label(stringResource(R.string.settings_theme), size = 14.sp, color = colors.text2)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    Chip(
                        stringResource(
                            when (mode) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            }
                        ),
                        selected = mode == theme,
                    ) { app.settings.setTheme(mode) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Label(stringResource(R.string.settings_accent), size = 14.sp, color = colors.text2)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Accent.entries.forEach { option ->
                    val swatch = Color(if (colors.dark) option.night else option.day)
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .then(if (option == accent) Modifier.border(3.dp, colors.text, CircleShape) else Modifier)
                            .pressable(label = option.name) { app.settings.setAccent(option) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SettingRow(title = stringResource(R.string.settings_haptics), trailing = {
                Toggle(haptics, app.settings::setHaptics)
            })
        }

        SectionTitle(stringResource(R.string.settings_support))
        Card(padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
            SettingRow(title = stringResource(R.string.log_title), icon = AppIcons.List, onClick = { nav.navigate(Screen.Log) })
        }

        if (BuildConfig.DEBUG) DeveloperCard()

        Label(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), Modifier.padding(start = 6.dp, top = 8.dp), size = 13.sp, color = colors.text2)
    }

    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(R.string.forget_title),
            message = stringResource(R.string.forget_message),
            confirm = stringResource(R.string.settings_forget),
            cancel = stringResource(R.string.action_cancel),
            danger = true,
            onDismiss = { confirmForget = false },
        ) {
            app.controller.forget()
            nav.home()
        }
    }
}

@Composable
private fun LedRow(led: LedChannel) {
    val nav = LocalActions.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var current by rememberSaveable(led.id) { mutableStateOf<String?>(null) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Label(led.label, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            led.states.forEach { state ->
                Chip(
                    stringResource(
                        when (state) {
                            LedState.OFF -> R.string.led_off
                            LedState.ON -> R.string.led_on
                            LedState.BLINK -> R.string.led_blink
                            LedState.AUTO -> R.string.led_auto
                        }
                    ),
                    selected = current == state.wire,
                ) {
                    scope.launch {
                        try {
                            nav.app.controller.setLed(led.id, state)
                            current = state.wire
                        } catch (e: Exception) {
                            nav.message(Format.error(resources, e), MessageKind.BAD)
                        }
                    }
                }
            }
        }
    }
}

/** Debug builds: point the app at a camera server on the normal network (the PC simulator). */
@Composable
private fun DeveloperCard() {
    val app = LocalActions.current.app
    val colors = LocalAppColors.current
    val saved by app.settings.devHost.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf(saved ?: "") }
    SectionTitle(stringResource(R.string.settings_developer))
    Card {
        Label(stringResource(R.string.dev_host_text), size = 14.sp, color = colors.text2)
        Spacer(Modifier.height(10.dp))
        TextInput(
            value = host,
            onChange = { host = it.trim() },
            placeholder = "192.168.1.10:8360",
            mono = true,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.dev_host_use), style = ButtonStyle.PRIMARY, enabled = host.isNotEmpty()) {
                app.settings.setDevHost(host)
                app.controller.connect()
            }
            if (saved != null) {
                PillButton(stringResource(R.string.dev_host_clear)) {
                    host = ""
                    app.settings.setDevHost(null)
                    app.controller.connect()
                }
            }
        }
    }
}
