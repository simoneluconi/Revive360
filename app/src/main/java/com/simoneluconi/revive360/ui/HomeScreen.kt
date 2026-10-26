package com.simoneluconi.revive360.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.protocol.Battery
import com.simoneluconi.revive360.protocol.CameraFile
import com.simoneluconi.revive360.protocol.Capabilities
import com.simoneluconi.revive360.protocol.CameraInfo
import com.simoneluconi.revive360.protocol.CameraSettings
import com.simoneluconi.revive360.protocol.CaptureMode
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.protocol.SettingDef
import com.simoneluconi.revive360.protocol.SettingIds
import com.simoneluconi.revive360.protocol.SettingValues
import com.simoneluconi.revive360.protocol.ShootingModes
import com.simoneluconi.revive360.protocol.Storage
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val nav = LocalActions.current
    val controller = nav.app.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val camera by nav.app.store.camera.collectAsStateWithLifecycle()
    val cameras by nav.app.store.cameras.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val files by controller.files.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val connectedInfo = (state as? LinkState.Connected)?.info
    // Shared by the fixed bar (mode, shutter) and the scrolling card (settings)
    val shot = rememberSaveable(saver = ShotChoice.Saver) { ShotChoice() }
    val changer = remember(controller) {
        SettingChanger(scope, controller::setSetting) { nav.message(Format.error(resources, it), MessageKind.BAD) }
    }

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        showBack = false,
        actions = {
            RoundIconButton(AppIcons.Settings, label = stringResource(R.string.settings_title)) { nav.navigate(Screen.Settings) }
        },
        // The mode and the shutter stay put, whatever the page shows and wherever it is scrolled
        bottomBar = connectedInfo?.takeIf { it.capabilities.canCapture }?.let { info -> { CaptureBar(info, shot, changer) } },
    ) {
        if (state == LinkState.NotPaired && camera == null) {
            WelcomeCard()
            return@ScreenScaffold
        }
        if (cameras.size > 1) {
            // More than one camera paired: switch with a tap
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cameras.forEach { c ->
                    Chip(c.label, selected = c.id == camera?.id) { controller.select(c.id) }
                }
            }
        }
        ConnectionCard(state, camera?.label)
        val info = (state as? LinkState.Connected)?.info
        if (info == null) {
            // Without the camera, what is already on the phone is still there to look at
            Card(padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                SettingRow(
                    title = stringResource(R.string.gallery_tab_phone),
                    subtitle = stringResource(R.string.phone_subtitle),
                    icon = AppIcons.Gallery,
                    onClick = { nav.navigate(Screen.Gallery) },
                )
            }
            return@ScreenScaffold
        }
        val caps = info.capabilities
        if (info.adapterStatus != "supported") {
            Card(color = LocalAppColors.current.soft(LocalAppColors.current.warn)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Warning, LocalAppColors.current.warn, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Label(stringResource(R.string.adapter_not_supported, info.adapterStatus), size = 14.sp)
                }
            }
        }
        if (caps.battery || caps.storage) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (caps.battery) BatteryTile(status?.battery, Modifier.weight(1f))
                if (caps.storage) StorageTile(status?.storage, Modifier.weight(1f))
            }
        }
        if (caps.canCapture) CaptureCard(info, shot, changer)
        // Without a capture bar the gallery is a row here
        if (caps.listFiles && !caps.canCapture) {
            Card(padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                SettingRow(
                    title = stringResource(R.string.gallery_title),
                    subtitle = files?.let { stringResource(R.string.gallery_count, it.size) } ?: stringResource(R.string.gallery_loading),
                    icon = AppIcons.Gallery,
                    onClick = { nav.navigate(Screen.Gallery) },
                )
            }
        }
    }
}

@Composable
private fun WelcomeCard() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    Card {
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Camera360, colors.accent, size = 34.dp)
        }
        Spacer(Modifier.height(16.dp))
        Label(stringResource(R.string.welcome_title), size = 22.sp, weight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Label(stringResource(R.string.welcome_text), color = colors.text2)
        Spacer(Modifier.height(18.dp))
        PillButton(stringResource(R.string.welcome_action), icon = AppIcons.Wifi, style = ButtonStyle.PRIMARY) {
            nav.navigate(Screen.Pair())
        }
    }
}

@Composable
private fun ConnectionCard(state: LinkState, savedName: String?) {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val info = (state as? LinkState.Connected)?.info
    val dot = when (state) {
        is LinkState.Connected -> colors.good
        is LinkState.Failed -> colors.bad
        LinkState.Waking, LinkState.Joining, LinkState.Handshake -> colors.warn
        else -> colors.text2
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(colors.surface2), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Camera360, colors.text, size = 28.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(info?.name?.ifEmpty { null } ?: info?.model ?: savedName ?: stringResource(R.string.camera_generic),
                    size = 19.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(dot, size = 8.dp)
                    Spacer(Modifier.width(8.dp))
                    Label(Format.state(resources, state), size = 14.sp, color = colors.text2, maxLines = 1)
                }
            }
            when (state) {
                LinkState.Waking, LinkState.Joining, LinkState.Handshake -> Spinner()
                is LinkState.Failed, LinkState.Idle ->
                    RoundIconButton(AppIcons.Refresh, label = stringResource(R.string.action_retry), background = colors.surface2) {
                        nav.app.controller.connect()
                    }
                else -> {}
            }
        }
        if (info != null) {
            Spacer(Modifier.height(10.dp))
            Label("${info.model} · ${info.firmware}", size = 13.sp, color = colors.text2, mono = true, maxLines = 1)
            val link by nav.app.controller.link.collectAsStateWithLifecycle()
            link?.let { l ->
                Spacer(Modifier.height(4.dp))
                Label(stringResource(R.string.connection_wifi, l.band, l.channel, l.linkMbps), size = 13.sp,
                    color = colors.text2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BatteryTile(battery: Battery?, modifier: Modifier) {
    val colors = LocalAppColors.current
    val percent = battery?.percent?.takeIf { it >= 0 }
    val tint = when {
        percent == null -> colors.text2
        battery.charging -> colors.good
        percent <= 15 -> colors.bad
        percent <= 30 -> colors.warn
        else -> colors.text
    }
    StatTile(
        icon = if (battery?.charging == true) AppIcons.Charging else AppIcons.Battery,
        tint = tint,
        title = stringResource(R.string.status_battery),
        value = percent?.let { "$it%" } ?: "—",
        detail = when {
            battery == null -> null
            battery.charging -> stringResource(R.string.battery_charging)
            battery.status == "full" -> stringResource(R.string.battery_full)
            else -> null
        } ?: battery?.temperatureC?.let { stringResource(R.string.battery_temperature, it) },
        fraction = percent?.let { it / 100f },
        modifier = modifier,
    )
}

@Composable
private fun StorageTile(storage: Storage?, modifier: Modifier) {
    val colors = LocalAppColors.current
    val mounted = storage?.mounted == true
    val fraction = if (mounted && storage.totalBytes > 0) storage.usedBytes.toFloat() / storage.totalBytes else null
    StatTile(
        icon = AppIcons.SdCard,
        tint = when {
            storage == null -> colors.text2
            !mounted -> colors.bad
            (fraction ?: 0f) > 0.9f -> colors.warn
            else -> colors.text
        },
        title = stringResource(R.string.status_storage),
        value = when {
            storage == null -> "—"
            !mounted -> stringResource(R.string.storage_no_card)
            else -> Format.bytes(storage.freeBytes)
        },
        detail = if (mounted) stringResource(R.string.storage_free_of, Format.bytes(storage.totalBytes)) else null,
        fraction = fraction,
        modifier = modifier,
    )
}

@Composable
private fun StatTile(
    icon: ImageVector,
    tint: Color,
    title: String,
    value: String,
    detail: String?,
    fraction: Float?,
    modifier: Modifier,
) {
    val colors = LocalAppColors.current
    val shown by animateFloatAsState(fraction ?: 0f, tween(500), label = "fill")
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, tint, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Label(title, size = 14.sp, color = colors.text2, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        Label(value, size = 26.sp, weight = FontWeight.ExtraBold, maxLines = 1, tabular = true)
        Label(detail ?: " ", size = 13.sp, color = colors.text2, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.line)) {
            if (fraction != null) {
                Box(Modifier.fillMaxWidth(shown.coerceAtLeast(0.02f)).height(6.dp).clip(CircleShape).background(tint))
            }
        }
    }
}

/** What the app picks for a still, not the camera: photo or RAW, and the JPEG quality. */
private class ShotChoice(modeWire: String? = null, quality: String? = null) {
    var modeWire by mutableStateOf(modeWire)
    var quality by mutableStateOf(quality)

    fun mode(caps: Capabilities): CaptureMode = caps.captureModes.firstOrNull { it.wire == modeWire } ?: caps.captureModes.first()
    fun quality(caps: Capabilities): String? = quality?.takeIf { it in caps.photoQualities } ?: caps.photoQualities.firstOrNull()

    companion object {
        val Saver = listSaver<ShotChoice, String?>({ listOf(it.modeWire, it.quality) }, { ShotChoice(it[0], it[1]) })
    }
}

/** The capture state both parts of the screen read */
private class CaptureView(
    val caps: Capabilities,
    val settings: CameraSettings?,
    val shootingMode: String?,
    val recordMode: Boolean,
    val recording: Boolean,
    val busy: Boolean,
    val recordBusy: Boolean,
    val elapsedS: Long?,
)

@Composable
private fun captureView(info: CameraInfo, changer: SettingChanger): CaptureView {
    val controller = LocalActions.current.app.controller
    val caps = info.capabilities
    val capturing by controller.capturing.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val settings by controller.cameraSettings.collectAsStateWithLifecycle()
    val recordBusy by controller.recordBusy.collectAsStateWithLifecycle()
    val recording = status?.recording?.active == true
    val busy = capturing || recordBusy || status?.busy == true
    // The camera's shooting mode (protocol §5.12); a camera without the setting only shoots photos
    val shootingMode = if (caps.setting(SettingIds.MODE) != null) settings?.values?.get(SettingIds.MODE) else ShootingModes.PHOTO
    changer.locked = busy || recording
    return CaptureView(
        caps, settings, shootingMode, caps.record && ShootingModes.records(shootingMode), recording, busy, recordBusy,
        status?.recording?.elapsedS,
    )
}

/** The fixed bottom bar: the shooting mode, the shutter or record button and what it will do. */
@Composable
private fun CaptureBar(info: CameraInfo, shot: ShotChoice, changer: SettingChanger) {
    val nav = LocalActions.current
    val controller = nav.app.controller
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val v = captureView(info, changer)
    val recording = v.recording
    val mode = shot.mode(v.caps)

    v.caps.setting(SettingIds.MODE)?.let { ModeChips(it, v.settings, changer) }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The gallery on the left of the shutter, as in a camera app; the right side balances it
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (v.caps.listFiles) GalleryButton()
        }
        if (v.recordMode || recording) {
            RecordButton(active = recording, busy = v.recordBusy) {
                scope.launch {
                    try {
                        controller.record(start = !recording)
                        haptic(Haptic.CONFIRM)
                        val saved = controller.status.value?.recording?.active != true
                        val name = controller.files.value?.firstOrNull()?.name
                        if (saved && recording && name != null) {
                            nav.message(resources.getString(R.string.record_done, name), MessageKind.GOOD)
                        }
                    } catch (e: Exception) {
                        haptic(Haptic.REJECT)
                        nav.message(Format.error(resources, e), MessageKind.BAD)
                    }
                }
            }
        } else {
            ShutterButton(busy = v.busy) {
                scope.launch {
                    try {
                        val result = controller.capture(mode, if (mode == CaptureMode.RAW) null else shot.quality(v.caps))
                        haptic(Haptic.CONFIRM)
                        nav.message(resources.getString(R.string.capture_done, result.files.size), MessageKind.GOOD)
                    } catch (e: Exception) {
                        haptic(Haptic.REJECT)
                        nav.message(Format.error(resources, e), MessageKind.BAD)
                    }
                }
            }
        }
        // The live view on the right, the gallery's counterpart
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (v.caps.liveview && !recording) {
                RoundIconButton(AppIcons.Live, label = stringResource(R.string.liveview_title), size = 56.dp,
                    background = colors.surface2) { nav.navigate(Screen.LiveView) }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Label(
        when {
            v.recordBusy && recording -> stringResource(R.string.record_saving)
            recording -> stringResource(R.string.record_running, Format.elapsed(elapsedSeconds(v.elapsedS)))
            v.busy -> stringResource(R.string.capture_busy)
            v.recordMode && v.shootingMode == ShootingModes.TIMELAPSE -> stringResource(R.string.record_hint_timelapse)
            v.recordMode -> stringResource(R.string.record_hint)
            else -> stringResource(R.string.capture_hint)
        },
        Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2, align = TextAlign.Center, maxLines = 1,
    )
}

/** The scrolling part: the settings of the shooting mode and the last shot. */
@Composable
private fun CaptureCard(info: CameraInfo, shot: ShotChoice, changer: SettingChanger) {
    val controller = LocalActions.current.app.controller
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val last by controller.lastCapture.collectAsStateWithLifecycle()
    val v = captureView(info, changer)
    val caps = v.caps
    val mode = shot.mode(caps)
    val quality = shot.quality(caps)

    Card {
        Label(stringResource(R.string.capture_title), size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
        if (caps.settings.isNotEmpty()) SettingRows(caps.settings, v.settings, v.shootingMode, changer)
        if (caps.captureModes.size > 1 && !v.recordMode) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                caps.captureModes.forEach { m ->
                    Chip(Format.mode(resources, m), selected = m == mode) { shot.modeWire = m.wire }
                }
            }
        }
        // Quality applies to the JPEG: not offered for RAW alone
        if (caps.photoQualities.size > 1 && mode != CaptureMode.RAW && !v.recordMode) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                caps.photoQualities.forEach { q ->
                    Chip(Format.quality(resources, q), selected = q == quality) { shot.quality = q }
                }
            }
        }
        val files = last?.files.orEmpty()
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            LastCapture(files)
        }
    }
}

/** Seconds since the camera last reported [reported], counted on between status polls. */
@Composable
private fun elapsedSeconds(reported: Long?): Long {
    var base by remember { mutableStateOf(0L to 0L) }   // (reported seconds, local time it arrived)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(reported) {
        base = (reported ?: 0L) to System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return base.first + (now - base.second) / 1_000
}

/**
 * Changes to the camera's settings (protocol §5.12), one at a time: the camera answers the values in
 * force. Nothing changes while [locked].
 */
private class SettingChanger(
    private val scope: CoroutineScope,
    private val send: suspend (String, String) -> Unit,
    private val failed: (Exception) -> Unit,
) {
    /** The setting id being changed */
    var pending by mutableStateOf<String?>(null)
        private set
    var locked = false

    fun change(values: SettingValues?, id: String, token: String) {
        if (locked || pending != null || values?.get(id) == token) return
        pending = id
        scope.launch {
            try {
                send(id, token)
            } catch (e: Exception) {
                failed(e)
            } finally {
                pending = null
            }
        }
    }
}

/** The shooting mode chips, in the fixed bar above the shutter */
@Composable
private fun ModeChips(def: SettingDef, settings: CameraSettings?, changer: SettingChanger) {
    val resources = LocalResources.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        def.values.forEach { token ->
            Chip(Format.settingValue(resources, def.id, token), selected = token == settings?.values?.get(def.id)) {
                changer.change(settings?.values, def.id, token)
            }
        }
    }
}

/**
 * The settings that matter in the shooting [mode], as the camera offers them now: the shooting
 * ones, then the picture and camera ones behind "More settings".
 */
@Composable
private fun SettingRows(defs: List<SettingDef>, settings: CameraSettings?, mode: String?, changer: SettingChanger) {
    val colors = LocalAppColors.current
    var more by rememberSaveable { mutableStateOf(false) }
    val video = mode == ShootingModes.VIDEO || mode == ShootingModes.LOOPING

    // What matters per mode; unknown ids are shown everywhere
    val shown = defs.filter { def ->
        when (def.id) {
            SettingIds.MODE -> false
            SettingIds.PHOTO_SIZE, SettingIds.TIMER, SettingIds.HDR, SettingIds.RAW -> mode == ShootingModes.PHOTO
            SettingIds.VIDEO_SIZE -> ShootingModes.records(mode)
            SettingIds.TIMELAPSE_INTERVAL -> mode == ShootingModes.TIMELAPSE
            SettingIds.LOOP_TIME -> mode == ShootingModes.LOOPING
            SettingIds.WIND_CUT, SettingIds.AUDIO -> video
            else -> true
        }
    }
    val (advanced, basic) = shown.partition { it.id in SettingIds.ADVANCED }
    basic.forEach { CameraSettingRow(it, settings, changer) }
    if (advanced.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().pressable(label = stringResource(R.string.settings_more)) { more = !more },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(stringResource(if (more) R.string.settings_less else R.string.settings_more), Modifier.weight(1f),
            size = 14.sp, weight = FontWeight.Bold, color = colors.accent)
        Icon(AppIcons.Chevron, colors.accent, Modifier.rotate(if (more) 90f else 0f), size = 18.dp)
    }
    if (more) advanced.forEach { CameraSettingRow(it, settings, changer) }
}

/** One setting: its title and a chip per value the camera offers now (and the value in force). */
@Composable
private fun CameraSettingRow(full: SettingDef, settings: CameraSettings?, changer: SettingChanger) {
    val values = settings?.values
    val resources = LocalResources.current
    val colors = LocalAppColors.current
    val now = settings?.offered(full) ?: full.values
    val def = full.copy(values = full.values.filter { it in now || it == values?.get(full.id) })
    // A long ordered scale (the EV steps) is a slider: the value goes to the camera when the finger lifts
    val slider = def.id == SettingIds.EV && def.values.size > 2
    var dragged by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(changer.pending) { if (changer.pending == null) dragged = null }
    val current = def.values.indexOf(values?.get(def.id))
    val shownIndex = dragged ?: current
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(Format.settingTitle(resources, def.id) ?: def.id, size = 13.sp, color = colors.text2)
        if (changer.pending == def.id) {
            Spacer(Modifier.width(8.dp))
            Spinner(size = 14.dp)
        }
        if (slider && shownIndex >= 0) {
            Spacer(Modifier.weight(1f))
            Label(Format.settingValue(resources, def.id, def.values[shownIndex]), size = 14.sp, weight = FontWeight.Bold, mono = true)
        }
    }
    Spacer(Modifier.height(6.dp))
    if (slider) {
        ValueSlider(
            value = shownIndex.coerceAtLeast(0),
            range = 0..def.values.lastIndex,
            onChange = { dragged = it },
            onDone = { dragged?.let { changer.change(values, def.id, def.values[it]) } },
        )
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        def.values.forEach { token ->
            Chip(Format.settingValue(resources, def.id, token), selected = token == values?.get(def.id)) {
                changer.change(values, def.id, token)
            }
        }
    }
    Format.settingHint(resources, def.id)?.let {
        Spacer(Modifier.height(6.dp))
        Label(it, size = 12.sp, color = colors.text2)
    }
}

@Composable
private fun RecordButton(active: Boolean, busy: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val ring by animateColorAsState(if (busy) colors.warn else colors.bad, tween(200), label = "ring")
    val corner by animateFloatAsState(if (active) 8f else 40f, tween(200), label = "corner")
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .border(4.dp, ring, CircleShape)
            .pressable(
                enabled = !busy,
                label = stringResource(if (active) R.string.record_stop else R.string.record_start),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Spinner(size = 28.dp)
        } else {
            // A round dot to start, a rounded square to stop
            val inner = if (active) 30.dp else 56.dp
            Box(Modifier.size(inner).clip(RoundedCornerShape(corner.dp)).background(colors.bad))
        }
    }
}

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val ring by animateColorAsState(if (busy) colors.warn else colors.accent, tween(200), label = "ring")
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .border(4.dp, ring, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (busy) colors.surface2 else colors.accent)
            .pressable(enabled = !busy, label = stringResource(R.string.capture_action), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) Spinner(size = 28.dp) else Icon(AppIcons.Shutter, colors.onAccent, size = 30.dp)
    }
}

/** The gallery, showing the newest file's picture: the camera roll button of a camera app. */
@Composable
private fun GalleryButton() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val files by nav.app.controller.files.collectAsStateWithLifecycle()
    val newest = files?.firstOrNull()
    var thumb by remember(newest?.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(newest?.path) { thumb = newest?.let { nav.app.controller.thumbnail(it) } }
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface2)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .pressable(label = stringResource(R.string.gallery_title)) { nav.navigate(Screen.Gallery) },
        contentAlignment = Alignment.Center,
    ) {
        val image = thumb
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
        } else {
            Icon(AppIcons.Gallery, colors.text2, size = 24.dp)
        }
    }
}

@Composable
private fun LastCapture(files: List<CameraFile>) {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val first = files.first()
    var thumb by remember(first.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(first.path) {
        // The JPG of a RAW+JPG pair has the thumbnail
        val withThumb = files.firstOrNull { it.kind == FileKind.PHOTO } ?: first
        thumb = nav.app.controller.thumbnail(withThumb)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable { nav.navigate(Screen.File(first.path)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp, 40.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface2), contentAlignment = Alignment.Center) {
            val image = thumb
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(40.dp))
            } else {
                Icon(AppIcons.Photo, colors.text2, size = 20.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(stringResource(R.string.capture_last), size = 13.sp, color = colors.text2)
            Label(files.joinToString(" + ") { it.name }, weight = FontWeight.Bold, maxLines = 1)
        }
        Icon(AppIcons.Chevron, colors.text2, size = 20.dp)
    }
}
