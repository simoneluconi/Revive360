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
import com.simoneluconi.revive360.media.PhoneItem
import com.simoneluconi.revive360.media.PhoneMedia
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.DualFisheyeImage
import com.simoneluconi.revive360.stitch.EquirectImage
import com.simoneluconi.revive360.stitch.Panorama
import com.simoneluconi.revive360.stitch.Jpeg360
import com.simoneluconi.revive360.stitch.Stitcher
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the 360 screen's picture comes from, and what it is. */
class PanoramaSource(
    val title: String,
    /** The stitched copy is saved as this plus "_360.jpg". */
    val baseName: String,
    /** Unix seconds, when it was shot. */
    val time: Long,
    /** Already stitched (equirectangular): shown as it is, nothing to save. */
    val stitched: Boolean,
    /** The picture as a local file; reports the fraction fetched. */
    val fetch: suspend (progress: (Float) -> Unit) -> File,
)

/** A camera photo as a sphere to look around, and its stitched 360 copy saved to the phone. */
@Composable
fun PanoramaScreen(path: String) {
    val controller = LocalActions.current.app.controller
    val files by controller.files.collectAsStateWithLifecycle()
    val file = files?.firstOrNull { it.path == path }
    val source = file?.let { f ->
        remember(f) {
            PanoramaSource(f.name, f.baseName, f.mtime, stitched = false) { progress ->
                controller.original(f) { done, total -> progress(if (total > 0) done.toFloat() / total else 0f) }
            }
        }
    }
    PanoramaContent(source, path.substringAfterLast('/'))
}

/** A picture saved on the phone: a camera original (stitched on the fly) or a stitched copy. */
@Composable
fun PhonePanoramaScreen(item: PhoneItem) {
    val context = LocalContext.current
    val source = remember(item) {
        PanoramaSource(item.name, item.baseName, item.time, item.isStitched) { progress ->
            withContext(Dispatchers.IO) { PhoneMedia.local(context, item) }.also { progress(1f) }
        }
    }
    PanoramaContent(source, item.name)
}

@Composable
private fun PanoramaContent(source: PanoramaSource?, fallbackTitle: String) {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val model = remember { DualFisheye() }
    var fetched by remember { mutableFloatStateOf(-1f) }
    var original by remember { mutableStateOf<File?>(null) }
    var picture by remember { mutableStateOf<Panorama?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = full) { full = false }
    DisposableEffect(full) {
        systemBars(view, show = !full)
        onDispose { systemBars(view, show = true) }
    }
    LaunchedEffect(source, attempt) {
        val target = source ?: return@LaunchedEffect
        if (picture != null) return@LaunchedEffect
        error = null
        try {
            val local = target.fetch { fetched = it }
            original = local
            fetched = 1f
            picture = withContext(Dispatchers.Default) {
                if (target.stitched) EquirectImage.load(local, MAX_SIDE) else DualFisheyeImage.load(local, model, MAX_SIDE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EventLog.log("360 view: ${target.title} failed (${e.message})")
            error = Format.error(resources, e)
        }
    }

    ScreenScaffold(title = source?.title ?: fallbackTitle, scroll = false, bare = full) {
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
        if (source?.stitched == true) return@ScreenScaffold
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            PillButton(
                stringResource(if (saving) R.string.pano_saving else R.string.pano_save),
                icon = AppIcons.Download,
                style = ButtonStyle.PRIMARY,
                enabled = picture is DualFisheyeImage && !saving,
            ) {
                val image = picture as? DualFisheyeImage ?: return@PillButton
                val local = original ?: return@PillButton
                val from = source ?: return@PillButton
                saving = true
                scope.launch {
                    try {
                        val name = "${from.baseName}${PhoneItem.STITCHED_SUFFIX}.jpg"
                        withContext(Dispatchers.Default) {
                            val started = System.currentTimeMillis()
                            val stitched = Stitcher.equirect(image, model, image.side * 2)
                            val out = File(context.cacheDir, name)
                            try {
                                Jpeg360.write(stitched, local, out)
                                stitched.recycle()
                                MediaSaver.publish(context, name, FileKind.PHOTO, "image/jpeg", from.time, out)
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
