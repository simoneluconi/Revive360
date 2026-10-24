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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.simoneluconi.revive360.protocol.CameraInfo
import com.simoneluconi.revive360.protocol.CaptureMode
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.protocol.Storage
import com.simoneluconi.revive360.ui.theme.LocalAppColors
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

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        showBack = false,
        actions = {
            RoundIconButton(AppIcons.Settings, label = stringResource(R.string.settings_title)) { nav.navigate(Screen.Settings) }
        },
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
        val info = (state as? LinkState.Connected)?.info ?: return@ScreenScaffold
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
        if (caps.canCapture) CaptureCard(info)
        if (caps.listFiles) {
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
        LinkState.Joining, LinkState.Handshake -> colors.warn
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
                LinkState.Joining, LinkState.Handshake -> Spinner()
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

@Composable
private fun CaptureCard(info: CameraInfo) {
    val nav = LocalActions.current
    val controller = nav.app.controller
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val caps = info.capabilities
    val capturing by controller.capturing.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val last by controller.lastCapture.collectAsStateWithLifecycle()
    var modeWire by rememberSaveable { mutableStateOf(caps.captureModes.first().wire) }
    val mode = caps.captureModes.firstOrNull { it.wire == modeWire } ?: caps.captureModes.first()
    var quality by rememberSaveable { mutableStateOf(caps.photoQualities.firstOrNull()) }
    val busy = capturing || status?.busy == true

    Card {
        Label(stringResource(R.string.capture_title), size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
        if (caps.captureModes.size > 1) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                caps.captureModes.forEach { m ->
                    Chip(Format.mode(resources, m), selected = m == mode) { modeWire = m.wire }
                }
            }
        }
        // Quality applies to the JPEG: not offered for RAW alone
        if (caps.photoQualities.size > 1 && mode != CaptureMode.RAW) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                caps.photoQualities.forEach { q ->
                    Chip(Format.quality(resources, q), selected = q == quality) { quality = q }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            ShutterButton(busy = busy) {
                scope.launch {
                    try {
                        val result = controller.capture(mode, if (mode == CaptureMode.RAW) null else quality)
                        haptic(Haptic.CONFIRM)
                        nav.message(resources.getString(R.string.capture_done, result.files.size), MessageKind.GOOD)
                    } catch (e: Exception) {
                        haptic(Haptic.REJECT)
                        nav.message(Format.error(resources, e), MessageKind.BAD)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Label(
            if (busy) stringResource(R.string.capture_busy) else stringResource(R.string.capture_hint),
            Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2, align = TextAlign.Center,
        )
        val shot = last?.files.orEmpty()
        if (shot.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            LastCapture(shot)
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
