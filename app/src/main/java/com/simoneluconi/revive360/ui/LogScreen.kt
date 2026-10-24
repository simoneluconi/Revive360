package com.simoneluconi.revive360.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/** The phone's event log and, when the camera offers it, the camera controller's own log. */
@Composable
fun LogScreen() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val lines by EventLog.lines.collectAsStateWithLifecycle()
    val state by nav.app.controller.state.collectAsStateWithLifecycle()
    val cameraLog = (state as? LinkState.Connected)?.info?.capabilities?.debugLog == true
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var cameraText by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }

    fun loadCamera() {
        loading = true
        scope.launch {
            cameraText = try {
                nav.app.controller.cameraLog()
            } catch (e: Exception) {
                Format.error(resources, e)
            }
            loading = false
        }
    }

    LaunchedEffect(showCamera) { if (showCamera && cameraText == null) loadCamera() }

    val text = if (showCamera) cameraText.orEmpty() else lines.joinToString("\n")
    ScreenScaffold(
        title = stringResource(R.string.log_title),
        actions = {
            if (showCamera && loading) Spinner()
            RoundIconButton(AppIcons.Copy, label = stringResource(R.string.log_copy)) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Revive360 log", text))
                nav.message(resources.getString(R.string.log_copied), MessageKind.GOOD)
            }
        },
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.log_phone), selected = !showCamera) { showCamera = false }
            if (cameraLog) Chip(stringResource(R.string.log_camera), selected = showCamera) { showCamera = true }
            if (showCamera) Chip(stringResource(R.string.action_refresh), selected = false) { loadCamera() }
            if (!showCamera) Chip(stringResource(R.string.log_clear), selected = false) { EventLog.clearScreen() }
        }
        Card(Modifier.fillMaxWidth()) {
            Label(text.ifEmpty { stringResource(R.string.log_empty) }, size = 12.sp, mono = true,
                color = if (text.isEmpty()) colors.text2 else colors.text)
        }
    }
}
