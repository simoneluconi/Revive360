package com.simoneluconi.revive360.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.DngBatch
import com.simoneluconi.revive360.link.DownloadProgress
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.protocol.CameraFile
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.protocol.RawPairs
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun GalleryScreen() {
    val nav = LocalActions.current
    val controller = nav.app.controller
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    val files by controller.files.collectAsStateWithLifecycle()
    val downloads by controller.downloads.collectAsStateWithLifecycle()
    val saved by controller.saved.keys.collectAsStateWithLifecycle()
    val batch by controller.dngBatch.collectAsStateWithLifecycle()
    val connected = state is LinkState.Connected
    val canDelete = (state as? LinkState.Connected)?.info?.capabilities?.delete == true
    var refreshing by remember { mutableStateOf(false) }
    // The camera's card or what is already on the phone
    var onPhone by rememberSaveable { mutableStateOf(!connected) }
    // Selection: a long press starts it, taps then toggle; paths, so a refresh keeps it
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // The RAW dumps of a photo are not files of their own here: the photo carries a mark
    val pairs = remember(files) { files?.let { RawPairs.of(it) } }
    val list = pairs?.shown
    // Files gone from the card leave the selection
    val chosen = selected?.let { sel -> list.orEmpty().filter { it.path in sel } }
    // DNGs to save together: the chosen photos with RAW, else every one not saved yet
    val dngTargets = pairs?.let { p ->
        (chosen ?: list.orEmpty().filter { controller.dngSavedKey(it) !in saved })
            .mapNotNull { photo -> p.of(photo)?.let { photo to it } }
    }.orEmpty()
    var confirmDngs by remember { mutableStateOf(false) }

    BackHandler(enabled = selected != null) { selected = null }
    LaunchedEffect(onPhone) { if (onPhone) selected = null }

    fun deleteChosen(targets: List<CameraFile>) {
        deleting = true
        scope.launch {
            var done = 0
            var lastError: Exception? = null
            for (file in targets) {
                try {
                    controller.delete(file)
                    done++
                } catch (e: Exception) {
                    lastError = e
                }
            }
            deleting = false
            selected = null
            val failed = targets.size - done
            if (failed == 0) {
                nav.message(resources.getString(R.string.delete_many_done, done), MessageKind.GOOD)
            } else {
                nav.message(
                    resources.getString(R.string.delete_many_partial, done, failed, lastError?.let { Format.error(resources, it) } ?: ""),
                    MessageKind.BAD,
                )
            }
        }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                controller.refreshFiles()
            } catch (e: Exception) {
                nav.message(Format.error(resources, e), MessageKind.BAD)
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(connected) { if (connected) refresh() }
    // The outcome of a batch, once
    LaunchedEffect(batch?.finished) {
        val b = batch?.takeIf { it.finished } ?: return@LaunchedEffect
        val ok = b.done - b.failed
        when {
            b.done < b.total -> nav.message(resources.getString(R.string.dng_all_stopped, ok), MessageKind.INFO)
            b.failed == 0 -> nav.message(resources.getString(R.string.dng_all_done, ok, MediaSaver.FOLDER), MessageKind.GOOD)
            else -> nav.message(resources.getString(R.string.dng_all_partial, ok, b.failed), MessageKind.BAD)
        }
        controller.dismissDngBatch()
    }

    ScreenScaffold(
        title = chosen?.let { stringResource(R.string.gallery_selected, it.size) } ?: stringResource(R.string.gallery_title),
        scroll = false,
        actions = {
            if (onPhone) {
                // Nothing to act on: the phone's list has no selection
            } else if (chosen != null) {
                val all = chosen.size == list.orEmpty().size
                RoundIconButton(
                    if (all) AppIcons.SelectNone else AppIcons.SelectAll,
                    label = stringResource(if (all) R.string.select_none else R.string.select_all),
                ) { selected = if (all) emptySet() else list.orEmpty().map { it.path }.toSet() }
                Spacer(Modifier.width(8.dp))
                if (connected && batch == null && dngTargets.isNotEmpty()) {
                    RoundIconButton(AppIcons.Raw, label = stringResource(R.string.dng_all_action)) { confirmDngs = true }
                    Spacer(Modifier.width(8.dp))
                }
                if (deleting) {
                    Spinner()
                } else if (canDelete && chosen.isNotEmpty()) {
                    RoundIconButton(AppIcons.Trash, label = stringResource(R.string.delete_action), tint = colors.bad) {
                        confirmDelete = true
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoundIconButton(AppIcons.Close, label = stringResource(R.string.selection_close)) { selected = null }
            } else if (refreshing) {
                Spinner()
            } else if (connected) {
                if (!onPhone && batch == null && dngTargets.isNotEmpty()) {
                    RoundIconButton(AppIcons.Raw, label = stringResource(R.string.dng_all_action)) { confirmDngs = true }
                    Spacer(Modifier.width(8.dp))
                }
                RoundIconButton(AppIcons.Refresh, label = stringResource(R.string.action_refresh)) { refresh() }
            }
        },
    ) {
        if (chosen == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.gallery_tab_camera), selected = !onPhone) { onPhone = false }
                Chip(stringResource(R.string.gallery_tab_phone), selected = onPhone) { onPhone = true }
            }
        }
        if (onPhone) {
            PhoneGallery()
            return@ScreenScaffold
        }
        batch?.takeIf { !it.finished }?.let { b ->
            DngBatchCard(b, downloads[b.current?.let { "$it#dng" }]?.fraction ?: 0f) { controller.cancelDngs() }
        }
        when {
            list == null && !connected -> EmptyNote(stringResource(R.string.gallery_not_connected))
            list == null -> EmptyNote(stringResource(R.string.gallery_loading))
            list.isEmpty() -> EmptyNote(stringResource(R.string.gallery_empty))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.path }) { file ->
                    val sel = selected
                    FileTile(
                        file = file,
                        progress = downloads[file.path] ?: downloads[controller.dngKey(file)],
                        saved = controller.savedKey(file) in saved,
                        raw = when {
                            pairs?.of(file) == null -> RawMark.NONE
                            controller.dngSavedKey(file) in saved -> RawMark.SAVED
                            else -> RawMark.ON_CAMERA
                        },
                        connected = connected,
                        selected = sel?.let { file.path in it },
                        onLongClick = { selected = (sel ?: emptySet()) + file.path },
                    ) {
                        if (sel == null) nav.navigate(Screen.File(file.path))
                        else selected = if (file.path in sel) sel - file.path else sel + file.path
                    }
                }
            }
        }
    }
    if (confirmDngs && dngTargets.isNotEmpty()) {
        val targets = dngTargets
        ConfirmDialog(
            title = stringResource(R.string.dng_all_title, targets.size),
            message = stringResource(R.string.dng_all_message, Format.bytes(targets.sumOf { it.second.size }), MediaSaver.FOLDER),
            confirm = stringResource(R.string.dng_action),
            cancel = stringResource(R.string.action_cancel),
            onDismiss = { confirmDngs = false },
        ) {
            controller.saveDngs(targets)
            selected = null
        }
    }
    if (confirmDelete && chosen != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_many_title, chosen.size),
            message = stringResource(R.string.delete_many_message),
            confirm = stringResource(R.string.delete_action),
            cancel = stringResource(R.string.action_cancel),
            danger = true,
            onDismiss = { confirmDelete = false },
        ) { deleteChosen(chosen) }
    }
}

/** A photo's RAW in the grid: none, on the camera only, or saved as a DNG too. */
private enum class RawMark { NONE, ON_CAMERA, SAVED }

/** The DNGs being saved together: which one, overall progress, a way to stop. */
@Composable
private fun DngBatchCard(batch: DngBatch, current: Float, onCancel: () -> Unit) {
    val colors = LocalAppColors.current
    val fraction = ((batch.done + current) / batch.total).coerceIn(0f, 1f)
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(stringResource(R.string.dng_all_running, minOf(batch.done + 1, batch.total), batch.total), Modifier.weight(1f), size = 15.sp)
            PillButton(stringResource(R.string.action_cancel), icon = AppIcons.Close, onClick = onCancel)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.line)) {
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).height(6.dp).clip(CircleShape).background(colors.accent))
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Label(text, color = LocalAppColors.current.text2)
    }
}

/** A file of the grid; [selected] is null outside selection, else whether this one is chosen. */
@Composable
private fun FileTile(
    file: CameraFile,
    progress: DownloadProgress?,
    saved: Boolean,
    raw: RawMark,
    connected: Boolean,
    selected: Boolean?,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(shape)
            .background(colors.surface)
            .then(if (selected == true) Modifier.border(3.dp, colors.accent, shape) else Modifier)
            .pressable(label = file.name, onLongClick = onLongClick, onClick = onClick),
    ) {
        Thumbnail(file, connected, Modifier.fillMaxSize())
        if (selected != null) {
            // Selection: the others dimmed, a check mark in the corner
            if (!selected) Box(Modifier.fillMaxSize().background(colors.bg.copy(alpha = 0.35f)))
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (selected) colors.accent else colors.bg.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(AppIcons.Check, colors.bg, size = 16.dp)
                }
            }
        }
        // Name and badges on a strip at the bottom, readable over any picture
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(colors.bg.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(file.name, Modifier.weight(1f), size = 12.sp, weight = FontWeight.Bold, maxLines = 1, mono = true)
                if (raw != RawMark.NONE) {
                    Label("RAW", size = 11.sp, weight = FontWeight.ExtraBold, color = if (raw == RawMark.SAVED) colors.good else colors.accent)
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            when {
                saved -> Badge { Icon(AppIcons.Check, colors.good, size = 14.dp) }
                file.kind == FileKind.RAW -> Badge { Label("RAW", size = 11.sp, weight = FontWeight.ExtraBold) }
                file.kind == FileKind.VIDEO -> Badge { Icon(AppIcons.Video, colors.text, size = 14.dp) }
            }
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(4.dp).background(colors.line)) {
                Box(Modifier.fillMaxWidth(progress.fraction.coerceAtLeast(0.02f)).height(4.dp)
                    .background(if (progress.failed != null) colors.bad else colors.accent))
            }
        }
    }
}

@Composable
private fun Badge(content: @Composable () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(LocalAppColors.current.bg.copy(alpha = 0.8f)).padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The camera file's thumbnail, loaded through the cache; an icon for its kind when there is none. */
@Composable
fun Thumbnail(file: CameraFile, connected: Boolean, modifier: Modifier = Modifier) {
    val controller = LocalActions.current.app.controller
    val colors = LocalAppColors.current
    var image by remember(file.path, file.size, file.mtime) { mutableStateOf<ImageBitmap?>(null) }
    val wantsThumb = file.kind != FileKind.OTHER
    LaunchedEffect(file.path, file.size, file.mtime, connected) {
        if (wantsThumb && image == null) image = controller.thumbnail(file)
    }
    Box(modifier.background(colors.surface2), contentAlignment = Alignment.Center) {
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                when (file.kind) {
                    FileKind.PHOTO -> AppIcons.Photo
                    FileKind.RAW -> AppIcons.Raw
                    FileKind.VIDEO -> AppIcons.Video
                    FileKind.OTHER -> AppIcons.File
                },
                colors.text2,
                size = 28.dp,
            )
        }
    }
}
