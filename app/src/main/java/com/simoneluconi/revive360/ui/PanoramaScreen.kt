package com.simoneluconi.revive360.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.DualFisheyeImage
import com.simoneluconi.revive360.stitch.Jpeg360
import com.simoneluconi.revive360.stitch.Stitcher
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A camera photo as a sphere to look around, and its stitched 360 copy saved to the phone. */
@Composable
fun PanoramaScreen(path: String) {
    val nav = LocalActions.current
    val controller = nav.app.controller
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val files by controller.files.collectAsStateWithLifecycle()
    val file = files?.firstOrNull { it.path == path }
    val model = remember { DualFisheye() }
    var fetched by remember { mutableFloatStateOf(-1f) }
    var original by remember { mutableStateOf<File?>(null) }
    var picture by remember { mutableStateOf<DualFisheyeImage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = full) { full = false }
    DisposableEffect(full) {
        systemBars(view, show = !full)
        onDispose { systemBars(view, show = true) }
    }
    LaunchedEffect(file?.path, attempt) {
        val target = file ?: return@LaunchedEffect
        if (picture != null) return@LaunchedEffect
        error = null
        try {
            val local = controller.original(target) { done, total -> fetched = if (total > 0) done.toFloat() / total else 0f }
            original = local
            fetched = 1f
            picture = withContext(Dispatchers.Default) { DualFisheyeImage.load(local, model, MAX_SIDE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EventLog.log("360 view: ${target.name} failed (${e.message})")
            error = Format.error(resources, e)
        }
    }

    ScreenScaffold(title = file?.name ?: path.substringAfterLast('/'), scroll = false, bare = full) {
        Box(
            (if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { PanoramaView(it, model) },
                update = { it.show(picture) },
                modifier = Modifier.fillMaxSize(),
            )
            if (picture == null && error == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spinner()
                    Spacer(Modifier.height(10.dp))
                    Label(
                        if (fetched in 0f..0.999f) stringResource(R.string.pano_fetching, (fetched * 100).toInt())
                        else stringResource(R.string.pano_preparing),
                        size = 14.sp, color = Color.White,
                    )
                }
            }
            RoundIconButton(
                if (full) AppIcons.Close else AppIcons.Expand,
                label = stringResource(R.string.pano_full),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                background = Color.Black.copy(alpha = 0.45f),
                tint = Color.White,
                size = 40.dp,
            ) { full = !full }
        }
        if (full) return@ScreenScaffold
        val problem = error
        if (problem != null) {
            Card(color = colors.soft(colors.bad)) {
                Label(stringResource(R.string.pano_failed, problem), size = 14.sp)
                Spacer(Modifier.height(10.dp))
                PillButton(stringResource(R.string.action_retry), icon = AppIcons.Refresh) { attempt++ }
            }
        }
        Label(stringResource(R.string.pano_hint), Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2, align = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            PillButton(
                stringResource(if (saving) R.string.pano_saving else R.string.pano_save),
                icon = AppIcons.Download,
                style = ButtonStyle.PRIMARY,
                enabled = picture != null && !saving && file != null,
            ) {
                val image = picture ?: return@PillButton
                val source = original ?: return@PillButton
                val camera = file ?: return@PillButton
                saving = true
                scope.launch {
                    try {
                        val name = "${camera.baseName}_360.jpg"
                        withContext(Dispatchers.Default) {
                            val started = System.currentTimeMillis()
                            val stitched = Stitcher.equirect(image, model, image.side * 2)
                            val out = File(context.cacheDir, name)
                            try {
                                Jpeg360.write(stitched, source, out)
                                stitched.recycle()
                                MediaSaver.publish(context, name, FileKind.PHOTO, "image/jpeg", camera.mtime, out)
                            } finally {
                                out.delete()
                            }
                            EventLog.log("360 photo: $name saved in ${System.currentTimeMillis() - started} ms")
                        }
                        nav.message(resources.getString(R.string.pano_saved, MediaSaver.FOLDER), MessageKind.GOOD)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        EventLog.log("360 photo: failed (${e.message})")
                        nav.message(Format.error(resources, e), MessageKind.BAD)
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}

/** Halves bigger than this are viewed at half size (well under any phone GPU's texture limit). */
private const val MAX_SIDE = 8192
