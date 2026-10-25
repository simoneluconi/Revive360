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
    val cameras by app.store.cameras.collectAsStateWithLifecycle()
    val state by app.controller.state.collectAsStateWithLifecycle()
    val info = (state as? LinkState.Connected)?.info
    var confirmForget by remember { mutableStateOf(false) }
    var confirmPower by remember { mutableStateOf<Boolean?>(null) }   // true = off, false = reboot
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    ScreenScaffold(title = stringResource(R.string.settings_title)) {
        SectionTitle(stringResource(R.string.settings_camera))
        val others = cameras.filter { it.id != camera?.id }
        if (others.isNotEmpty()) {
            // The other paired cameras: tap one to use it
            Card(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                others.forEachIndexed { i, other ->
                    if (i > 0) Hairline()
                    SettingRow(
                        title = other.label,
                        subtitle = listOfNotNull(other.model, other.ssid).joinToString(" · ").ifEmpty { null },
                        icon = AppIcons.Camera360,
                        onClick = { app.controller.select(other.id) },
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            if (camera == null) {
                SettingRow(
                    title = stringResource(R.string.welcome_action),
                    icon = AppIcons.Wifi,
                    onClick = { nav.navigate(Screen.Pair()) },
                )
            } else {
                Label(stringResource(R.string.camera_in_use, camera!!.label), weight = FontWeight.ExtraBold, size = 17.sp)
                Spacer(Modifier.height(6.dp))
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
                if (info?.capabilities?.power == true) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(stringResource(R.string.power_reboot), icon = AppIcons.Refresh) { confirmPower = false }
                        PillButton(stringResource(R.string.power_off), icon = AppIcons.Close) { confirmPower = true }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(R.string.settings_repair), icon = AppIcons.Key) { nav.navigate(Screen.Pair(camera!!.id)) }
                    PillButton(stringResource(R.string.settings_forget), icon = AppIcons.Trash, style = ButtonStyle.DANGER) {
                        confirmForget = true
                    }
                }
            }
        }
        if (camera != null) {
            Card(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                SettingRow(
                    title = stringResource(R.string.settings_add_camera),
                    icon = AppIcons.Wifi,
                    onClick = { nav.navigate(Screen.Pair()) },
                )
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

    confirmPower?.let { off ->
        ConfirmDialog(
            title = stringResource(if (off) R.string.power_off_title else R.string.power_reboot_title),
            message = stringResource(if (off) R.string.power_off_message else R.string.power_reboot_message),
            confirm = stringResource(if (off) R.string.power_off else R.string.power_reboot),
            cancel = stringResource(R.string.action_cancel),
            danger = off,
            onDismiss = { confirmPower = null },
        ) {
            scope.launch {
                try {
                    app.controller.power(off)
                    nav.message(resources.getString(if (off) R.string.power_off_done else R.string.power_reboot_done), MessageKind.GOOD)
                } catch (e: Exception) {
                    nav.message(Format.error(resources, e), MessageKind.BAD)
                }
            }
        }
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
