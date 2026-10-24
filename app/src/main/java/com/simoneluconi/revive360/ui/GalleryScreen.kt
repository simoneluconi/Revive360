package com.simoneluconi.revive360.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.simoneluconi.revive360.link.DownloadProgress
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.protocol.CameraFile
import com.simoneluconi.revive360.protocol.FileKind
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
    val connected = state is LinkState.Connected
    var refreshing by remember { mutableStateOf(false) }

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

    ScreenScaffold(
        title = stringResource(R.string.gallery_title),
        scroll = false,
        actions = {
            if (refreshing) {
                Spinner()
            } else if (connected) {
                RoundIconButton(AppIcons.Refresh, label = stringResource(R.string.action_refresh)) { refresh() }
            }
        },
    ) {
        val list = files
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
                    FileTile(
                        file = file,
                        progress = downloads[file.path],
                        saved = controller.savedKey(file) in saved,
                        connected = connected,
                    ) { nav.navigate(Screen.File(file.path)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Label(text, color = LocalAppColors.current.text2)
    }
}

@Composable
private fun FileTile(file: CameraFile, progress: DownloadProgress?, saved: Boolean, connected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .pressable(label = file.name, onClick = onClick),
    ) {
        Thumbnail(file, connected, Modifier.fillMaxSize())
        // Name and badges on a strip at the bottom, readable over any picture
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(colors.bg.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Label(file.name, size = 12.sp, weight = FontWeight.Bold, maxLines = 1, mono = true)
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
    val wantsThumb = file.kind == FileKind.PHOTO || file.kind == FileKind.RAW
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
