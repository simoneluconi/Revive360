package com.simoneluconi.revive360.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.protocol.Protocol
import com.simoneluconi.revive360.ui.theme.LocalAppColors

/**
 * First-time setup: the code the SD preparation tool printed (the camera Wi-Fi's passphrase), then the
 * system's chooser for the camera's network. After that the app joins by itself.
 */
@Composable
fun PairScreen() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val existing = nav.app.store.camera.value
    var code by rememberSaveable { mutableStateOf(existing?.passphrase ?: "") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Offered after the chooser failed: join by the SSID prefix, the system asks each time
    var offerFallback by rememberSaveable { mutableStateOf(false) }
    val valid = Protocol.isValidPassphrase(code)

    fun paired(found: com.simoneluconi.revive360.link.CompanionWifi.Found?) {
        nav.app.controller.pair(code, found)
        nav.message(resources.getString(R.string.pair_done), MessageKind.GOOD)
        nav.home()
    }

    ScreenScaffold(title = stringResource(R.string.pair_title)) {
        Card {
            Step(1, stringResource(R.string.pair_step_sd))
            Spacer(Modifier.height(12.dp))
            Step(2, stringResource(R.string.pair_step_power))
            Spacer(Modifier.height(12.dp))
            Step(3, stringResource(R.string.pair_step_code))
        }
        Card {
            Label(stringResource(R.string.pair_code_label), size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            TextInput(
                value = code,
                onChange = { code = it.filter { c -> c.code in 0x20..0x7E }.take(Protocol.PASSPHRASE_MAX) },
                placeholder = stringResource(R.string.pair_code_placeholder),
                mono = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            if (code.isNotEmpty() && !valid) {
                Spacer(Modifier.height(8.dp))
                Label(stringResource(R.string.pair_code_invalid, Protocol.PASSPHRASE_MIN), size = 13.sp, color = colors.bad)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    stringResource(R.string.pair_find),
                    icon = AppIcons.Wifi,
                    style = ButtonStyle.PRIMARY,
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
