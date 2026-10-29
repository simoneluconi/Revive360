package com.simoneluconi.revive360.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.BleSession
import com.simoneluconi.revive360.link.CameraBle
import com.simoneluconi.revive360.link.CompanionWifi
import com.simoneluconi.revive360.protocol.Protocol
import com.simoneluconi.revive360.settings.CameraModels
import com.simoneluconi.revive360.settings.Requirement
import com.simoneluconi.revive360.settings.Support
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First-time setup. Over Bluetooth: the app finds the camera, which (in its first minutes after
 * power-on) hands over its Wi-Fi's name and passphrase and switches it on; then the system's
 * chooser for that network, once. Cameras without Bluetooth: the passphrase typed by hand.
 */
@Composable
fun PairScreen(replaceId: String? = null) {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<CameraBle.Seen>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }

    fun scan() {
        scanning = true
        problem = null
        scope.launch {
            found = CameraBle.scan(context, SCAN_MS)
            scanning = false
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) scan() else problem = resources.getString(R.string.pair_permission)
    }

    /** Pairing over Bluetooth: the Wi-Fi details, the Wi-Fi on, then the system's chooser. */
    fun pairWith(camera: CameraBle.Seen) {
        problem = null
        busy = resources.getString(R.string.pair_connecting)
        scope.launch {
            try {
                val wifi = BleSession.open(context, camera.address).use { ble ->
                    val info = ble.info()
                    val details = ble.wifi()
                    if (!info.pairing || details == null) {
                        problem = resources.getString(R.string.pair_closed)
                        busy = null
                        return@launch
                    }
                    busy = resources.getString(R.string.pair_wifi_on)
                    var state = info.wifi
                    if (state != "on") {
                        ble.setWifi(true)
                        repeat(WIFI_WAIT_S) {
                            if (state == "on" || state == "failed") return@repeat
                            delay(1_000)
                            state = ble.info().wifi
                        }
                    }
                    EventLog.log("Pairing: ${details.ssid} over Bluetooth, its Wi-Fi $state")
                    details
                }
                busy = resources.getString(R.string.pair_pick_network, wifi.ssid)
                nav.findCamera { picked ->
                    busy = null
                    // Without the chooser's pick the app joins by name, the system then asks each time
                    nav.app.controller.pair(wifi.passphrase, picked, replaceId, ssid = wifi.ssid, bluetooth = camera.address)
                    nav.message(resources.getString(R.string.pair_done), MessageKind.GOOD)
                    nav.home()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log("Pairing over Bluetooth failed: ${e.message}")
                problem = resources.getString(R.string.pair_failed, e.message ?: e.javaClass.simpleName)
                busy = null
            }
        }
    }

    ScreenScaffold(title = stringResource(R.string.pair_title)) {
        BeforeYouStart()
        Card {
            Step(1, stringResource(R.string.pair_step_sd))
            Spacer(Modifier.height(12.dp))
            Step(2, stringResource(R.string.pair_step_power))
            Spacer(Modifier.height(12.dp))
            Step(3, stringResource(R.string.pair_step_find))
        }
        Card {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    stringResource(if (scanning) R.string.pair_searching else R.string.pair_find),
                    icon = AppIcons.Camera360,
                    style = ButtonStyle.PRIMARY,
                    enabled = !scanning && busy == null,
                ) { permissions.launch(CameraBle.permissions()) }
                if (scanning) Spinner()
            }
            val list = found
            if (list != null && !scanning) {
                Spacer(Modifier.height(14.dp))
                if (list.isEmpty()) {
                    Label(stringResource(R.string.pair_none), size = 14.sp, color = colors.text2)
                } else {
                    Label(stringResource(R.string.pair_choose), size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
                    list.forEach { camera ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Label(camera.name ?: "Gear 360", size = 15.sp, weight = FontWeight.Bold)
                                Label(camera.address, size = 12.sp, color = colors.text2, mono = true)
                            }
                            PillButton(stringResource(R.string.pair_use), enabled = busy == null) { pairWith(camera) }
                        }
                    }
                }
            }
            busy?.let {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Spacer(Modifier.width(10.dp))
                    Label(it, size = 14.sp)
                }
            }
            problem?.let {
                Spacer(Modifier.height(14.dp))
                Label(it, size = 14.sp, color = colors.bad)
            }
        }
        if (!manual) {
            PillButton(stringResource(R.string.pair_manual), Modifier.fillMaxWidth()) { manual = true }
        } else {
            ManualPairing(replaceId)
        }
    }
}

/** Which cameras work and what each one needs, before the user looks for one ([CameraModels]). */
@Composable
private fun BeforeYouStart() {
    val colors = LocalAppColors.current
    val uri = LocalUriHandler.current
    Card {
        Label(stringResource(R.string.models_title), size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
        CameraModels.all.forEach { model ->
            Spacer(Modifier.height(10.dp))
            Label("${model.name} · ${model.code}", size = 15.sp, weight = FontWeight.Bold)
            val need = when {
                model.support == Support.COMING -> R.string.models_coming
                model.requirement == Requirement.CUSTOM_FIRMWARE -> R.string.models_custom_firmware
                model.requirement == Requirement.STOCK_FIRMWARE -> R.string.models_stock_firmware
                else -> R.string.models_coming
            }
            Label(stringResource(need), size = 14.sp, color = colors.text2)
        }
        Spacer(Modifier.height(14.dp))
        PillButton(stringResource(R.string.models_guide)) { uri.openUri(CameraModels.INSTALL_GUIDE) }
    }
}

/** A camera without the Bluetooth side: the passphrase typed by hand, then the system's chooser. */
@Composable
private fun ManualPairing(replaceId: String?) {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val existing = nav.app.store.cameras.value.firstOrNull { it.id == replaceId }
    var code by rememberSaveable { mutableStateOf(existing?.passphrase ?: "") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Offered after the chooser failed: join by the SSID prefix, the system asks each time
    var offerFallback by rememberSaveable { mutableStateOf(false) }
    val valid = Protocol.isValidPassphrase(code)

    fun paired(found: CompanionWifi.Found?) {
        nav.app.controller.pair(code, found, replaceId)
        nav.message(resources.getString(R.string.pair_done), MessageKind.GOOD)
        nav.home()
    }

    Card {
        Label(stringResource(R.string.pair_code_label), size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        TextInput(
            value = code,
            onChange = { code = it.filter { c -> c.code in 0x20..0x7E }.take(Protocol.PASSPHRASE_MAX) },
            placeholder = stringResource(R.string.pair_code_placeholder),
            mono = true,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (code.isNotEmpty() && !valid) {
            Spacer(Modifier.height(8.dp))
            Label(stringResource(R.string.pair_code_invalid, Protocol.PASSPHRASE_MIN), size = 13.sp, color = colors.bad)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                stringResource(R.string.pair_find_wifi),
                icon = AppIcons.Wifi,
                enabled = valid && !searching,
            ) {
                searching = true
                nav.findCamera { found ->
                    searching = false
                    if (found != null) {
                        paired(found)
                    } else {
                        offerFallback = true
                        nav.message(resources.getString(R.string.pair_not_found), MessageKind.BAD)
                    }
                }
            }
            if (searching) Spinner()
        }
    }
    if (offerFallback) {
        Card(color = colors.surface2) {
            Label(stringResource(R.string.pair_fallback_text), size = 14.sp, color = colors.text2)
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.pair_fallback_action), enabled = valid) { paired(null) }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Label("$number", size = 14.sp, color = colors.accent, weight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Label(text, size = 15.sp) }
    }
}

private const val SCAN_MS = 6_000L
private const val WIFI_WAIT_S = 30
