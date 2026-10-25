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
import androidx.compose.foundation.layout.width
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
import com.simoneluconi.revive360.stitch.PanoramaEdit
import com.simoneluconi.revive360.stitch.PanoramaEdits
import com.simoneluconi.revive360.stitch.Projection
import com.simoneluconi.revive360.stitch.ViewRenderer
import com.simoneluconi.revive360.stitch.ViewState
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
) {
    /** Names the picture for its kept edits: a camera file and its copy on the phone share it. */
    val key: String get() = "$baseName|$time"
}

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
    val edits = remember { PanoramaEdits(context) }
    var fetched by remember { mutableFloatStateOf(-1f) }
    var original by remember { mutableStateOf<File?>(null) }
    var picture by remember { mutableStateOf<Panorama?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(false) }
    var edit by remember(source) { mutableStateOf(source?.let { edits.get(it.key) } ?: PanoramaEdit()) }
    var mode by remember { mutableStateOf(ViewMode.NORMAL) }
    // Straightening: the slider turns from where the drag started, then springs back to 0
    var straighten by remember { mutableIntStateOf(0) }
    var straightenFrom by remember { mutableStateOf<Pair<PanoramaEdit, ViewState>?>(null) }
    var framing by remember { mutableStateOf(false) }
    var panorama by remember { mutableStateOf<PanoramaView?>(null) }

    fun keep(next: PanoramaEdit) {
        edit = next
        source?.let { edits.put(it.key, next) }
    }

    fun firstView(): ViewState = ViewState(yaw = edit.heading, pitch = edit.pitch, level = edit.level)

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

    /** Runs [work] off the main thread with the save button busy, then says how it went. */
    fun save(label: String, work: suspend () -> Unit) {
        saving = true
        scope.launch {
            try {
                val started = System.currentTimeMillis()
                withContext(Dispatchers.Default) { work() }
                EventLog.log("$label saved in ${System.currentTimeMillis() - started} ms")
                nav.message(resources.getString(R.string.pano_saved, MediaSaver.FOLDER), MessageKind.GOOD)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log("$label: failed (${e.message})")
                nav.message(Format.error(resources, e), MessageKind.BAD)
            } finally {
                saving = false
            }
        }
    }

    /** Publishes [bitmap] as [name], made by [write] into a cache file first. */
    suspend fun publish(name: String, write: (File) -> Unit) {
        val from = source ?: return
        val out = File(context.cacheDir, name)
        try {
            write(out)
            MediaSaver.publish(context, name, FileKind.PHOTO, "image/jpeg", from.time, out)
        } finally {
            out.delete()
        }
    }

    ScreenScaffold(title = source?.title ?: fallbackTitle, scroll = false, bare = full) {
        Box(
            (if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = {
                    PanoramaView(it, model).apply {
                        home = firstView()
                        setView(firstView())
                        panorama = this
                    }
                },
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
        val ready = picture != null && !saving

        // How to look at it
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewMode.entries.forEach { m ->
                Chip(stringResource(m.label), selected = mode == m) {
                    mode = m
                    panorama?.let { p -> p.setView(m.preset(p.view)) }
                }
            }
        }

        // Straighten: turns the picture about the middle of the screen
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(
                if (straightenFrom != null) stringResource(R.string.pano_straighten_value, straighten / 2f)
                else stringResource(R.string.pano_straighten),
                Modifier.width(118.dp), size = 14.sp, color = colors.text2,
            )
            ValueSlider(
                value = straighten,
                range = -STRAIGHTEN_STEPS..STRAIGHTEN_STEPS,
                onChange = { v ->
                    val p = panorama ?: return@ValueSlider
                    val from = straightenFrom ?: (edit to p.view).also { straightenFrom = it }
                    straighten = v
                    val next = from.first.rolled(from.first.level, from.second, Math.toRadians(v / 2.0).toFloat())
                    edit = next
                    p.setView(p.view.copy(level = next.level))
                },
                onDone = {
                    if (straightenFrom != null) keep(edit)
                    straightenFrom = null
                    straighten = 0
                },
                modifier = Modifier.weight(1f),
                enabled = picture != null,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.pano_first_view), Modifier.weight(1f), enabled = picture != null && mode == ViewMode.NORMAL) {
                val v = panorama?.view ?: return@PillButton
                keep(edit.copy(heading = v.yaw, pitch = v.pitch))
                panorama?.home = firstView()
                nav.message(resources.getString(R.string.pano_first_view_done), MessageKind.GOOD)
            }
            PillButton(stringResource(R.string.pano_reset), Modifier.weight(1f), enabled = picture != null && edit != PanoramaEdit()) {
                keep(PanoramaEdit())
                mode = ViewMode.NORMAL
                panorama?.home = firstView()
                panorama?.setView(firstView())
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.pano_frame), Modifier.weight(1f), icon = AppIcons.Photo, enabled = ready) { framing = true }
            if (source?.stitched != true) {
                PillButton(
                    stringResource(if (saving) R.string.pano_saving else R.string.pano_save),
                    Modifier.weight(1f),
                    icon = AppIcons.Sphere,
                    style = ButtonStyle.PRIMARY,
                    enabled = ready && picture is DualFisheyeImage,
                ) {
                    val image = picture as? DualFisheyeImage ?: return@PillButton
                    val local = original ?: return@PillButton
                    val from = source ?: return@PillButton
                    val e = edit
                    save("360 photo") {
                        val stitched = Stitcher.equirect(image, model, image.side * 2, e.exportOrientation())
                        try {
                            publish("${from.baseName}${PhoneItem.STITCHED_SUFFIX}.jpg") {
                                // Our pitch looks down when positive, the metadata's up
                                Jpeg360.write(stitched, local, it, -Math.toDegrees(e.pitch.toDouble()).toFloat())
                            }
                        } finally {
                            stitched.recycle()
                        }
                    }
                }
            }
        }
    }

    if (framing) {
        AppDialog(
            title = stringResource(R.string.pano_frame_title),
            onDismiss = { framing = false },
            buttons = { PillButton(stringResource(R.string.action_cancel)) { framing = false } },
        ) {
            Label(stringResource(R.string.pano_frame_message), size = 14.sp, color = colors.text2)
            FRAMES.forEach { (label, ratio) ->
                PillButton(label, Modifier.fillMaxWidth()) {
                    framing = false
                    val image = picture ?: return@PillButton
                    val v = panorama?.view ?: return@PillButton
                    val local = original
                    val from = source ?: return@PillButton
                    val (w, h) = frameSize(image, v, ratio)
                    val stamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.ROOT).format(java.util.Date())
                    save("Framing ${w}x$h") {
                        val flat = ViewRenderer.render(image, model, v, w, h)
                        try {
                            publish("${from.baseName}_${mode.fileTag}_$stamp.jpg") { Jpeg360.writeFlat(flat, local, it) }
                        } finally {
                            flat.recycle()
                        }
                    }
                }
            }
        }
    }
}

/** The ways of looking at the sphere, and where each starts. */
private enum class ViewMode(val label: Int, val fileTag: String) {
    NORMAL(R.string.pano_mode_normal, "view"),
    PLANET(R.string.pano_mode_planet, "planet"),
    TUNNEL(R.string.pano_mode_tunnel, "tunnel");

    fun preset(from: ViewState): ViewState = when (this) {
        NORMAL -> from.copy(projection = Projection.RECTILINEAR, pitch = 0f, fov = 90f)
        // Straight down (positive pitch looks down) through a wide stereographic lens
        PLANET -> from.copy(projection = Projection.STEREOGRAPHIC, pitch = HALF_PI, fov = 260f)
        TUNNEL -> from.copy(projection = Projection.STEREOGRAPHIC, pitch = -HALF_PI, fov = 260f)
    }
}

private const val HALF_PI = (Math.PI / 2).toFloat()

/** Half degrees each way. */
private const val STRAIGHTEN_STEPS = 60

private val FRAMES = listOf("16:9" to 16f / 9f, "4:3" to 4f / 3f, "1:1" to 1f, "9:16" to 9f / 16f)

/**
 * A framing's size: as much detail as the picture has in the middle of the view (its pixels per
 * radian there), at most [MAX_FRAME] on the long side.
 */
private fun frameSize(picture: Panorama, view: ViewState, ratio: Float): Pair<Int, Int> {
    val perRadian = when (picture) {
        is DualFisheyeImage -> picture.side * 2 / (2 * Math.PI)
        is EquirectImage -> picture.bitmap.width / (2 * Math.PI)
    }
    var h = 2 * view.scale() * perRadian
    var w = h * ratio
    val longest = maxOf(w, h)
    if (longest > MAX_FRAME) {
        h *= MAX_FRAME / longest
        w *= MAX_FRAME / longest
    }
    return w.toInt().coerceAtLeast(320) to h.toInt().coerceAtLeast(320)
}

private const val MAX_FRAME = 4096.0

/** Halves bigger than this are viewed at half size (well under any phone GPU's texture limit). */
private const val MAX_SIDE = 8192
