package com.simoneluconi.revive360.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/** One camera file: preview, details, download to the phone, delete from the card. */
@Composable
fun FileScreen(path: String) {
    val nav = LocalActions.current
    val controller = nav.app.controller
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    val files by controller.files.collectAsStateWithLifecycle()
    val downloads by controller.downloads.collectAsStateWithLifecycle()
    val saved by controller.saved.keys.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val info = (state as? LinkState.Connected)?.info
    val caps = info?.capabilities
    val file = files?.firstOrNull { it.path == path }

    ScreenScaffold(title = file?.name ?: path.substringAfterLast('/')) {
        if (file == null) {
            Card(Modifier.fillMaxWidth()) { Label(stringResource(R.string.file_gone), color = colors.text2) }
            return@ScreenScaffold
        }
        Thumbnail(file, info != null, Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(24.dp)))
        Card {
            InfoRow(stringResource(R.string.file_kind), when (file.kind) {
                FileKind.PHOTO -> stringResource(R.string.kind_photo)
                FileKind.RAW -> stringResource(R.string.kind_raw)
                FileKind.VIDEO -> stringResource(R.string.kind_video)
                FileKind.OTHER -> stringResource(R.string.kind_other)
            })
            Hairline()
            InfoRow(stringResource(R.string.file_size), Format.bytes(file.size))
            Hairline()
            InfoRow(stringResource(R.string.file_date), Format.date(file.mtime))
            Hairline()
            InfoRow(stringResource(R.string.file_path), file.path, mono = true)
        }

        if (file.kind == FileKind.PHOTO && (caps?.download == true)) {
            PillButton(
                stringResource(R.string.pano_open),
                Modifier.fillMaxWidth(),
                icon = AppIcons.Sphere,
                enabled = info != null,
            ) { nav.navigate(Screen.Panorama(file.path)) }
        }

        val progress = downloads[file.path]
        val isSaved = controller.savedKey(file) in saved
        if (caps?.download == true || progress != null) {
            Card(Modifier.fillMaxWidth()) {
                when {
                    progress != null && progress.failed == null -> {
                        Label(stringResource(R.string.download_running, (progress.fraction * 100).toInt()), size = 15.sp)
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.line)) {
                            Box(Modifier.fillMaxWidth(progress.fraction.coerceAtLeast(0.02f)).height(6.dp).clip(CircleShape).background(colors.accent))
                        }
                        Spacer(Modifier.height(4.dp))
                        Label("${Format.bytes(progress.done)} / ${Format.bytes(progress.total)}", size = 13.sp, color = colors.text2, tabular = true)
                        Spacer(Modifier.height(12.dp))
                        PillButton(stringResource(R.string.action_cancel), icon = AppIcons.Close) { controller.cancelDownload(file) }
                    }
                    else -> {
                        if (progress?.failed != null) {
                            Label(stringResource(R.string.download_failed), size = 14.sp, color = colors.bad)
                            Spacer(Modifier.height(10.dp))
                        } else if (isSaved) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.Check, colors.good, size = 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Label(stringResource(R.string.download_saved, MediaSaver.FOLDER), size = 14.sp, color = colors.text2)
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        PillButton(
                            stringResource(if (progress?.failed != null) R.string.download_resume else if (isSaved) R.string.download_again else R.string.download_action),
                            icon = AppIcons.Download,
                            style = ButtonStyle.PRIMARY,
                            enabled = info != null,
                        ) { controller.download(file) }
                    }
                }
            }
        }

        if (caps?.delete == true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(
                    stringResource(R.string.delete_action),
                    icon = AppIcons.Trash,
                    style = ButtonStyle.DANGER,
                    enabled = !deleting && progress == null,
                ) { confirmDelete = true }
            }
        }
    }

    if (confirmDelete && file != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_title),
            message = stringResource(R.string.delete_message, file.name),
            confirm = stringResource(R.string.delete_action),
            cancel = stringResource(R.string.action_cancel),
            danger = true,
            onDismiss = { confirmDelete = false },
        ) {
            deleting = true
            scope.launch {
                try {
                    controller.delete(file)
                    nav.message(resources.getString(R.string.delete_done, file.name), MessageKind.GOOD)
                    nav.back()
                } catch (e: Exception) {
                    nav.message(Format.error(resources, e), MessageKind.BAD)
                } finally {
                    deleting = false
                }
            }
        }
    }
}
