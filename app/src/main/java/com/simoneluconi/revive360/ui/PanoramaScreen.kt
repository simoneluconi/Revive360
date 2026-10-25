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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
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
import com.simoneluconi.revive360.stitch.Framing
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
    val key = source?.key
    var fetched by remember { mutableFloatStateOf(-1f) }
    var original by remember { mutableStateOf<File?>(null) }
    var picture by remember { mutableStateOf<Panorama?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(false) }
    // What the picture keeps: its corrections, its framing, and which of the two it was last
    var edit by remember(key) { mutableStateOf(key?.let { edits.get(it) } ?: PanoramaEdit()) }
    var framing by remember(key) {
        mutableStateOf(key?.let { edits.framing(it) } ?: Framing(style = ViewMode.NORMAL.name, ratio = edits.lastRatio))
    }
    // A stitched copy has nothing to save as 360: it is framed only
    var flat by remember(key) {
        mutableStateOf(source?.stitched == true || (key?.let { edits.flat(it) } ?: edits.lastFlat))
    }
    val mode = ViewMode.entries.firstOrNull { it.name == framing.style } ?: ViewMode.NORMAL
    // Straightening (360): the slider turns from where the drag started, then springs back to 0
    var straighten by remember { mutableIntStateOf(0) }
    var straightenFrom by remember { mutableStateOf<Pair<PanoramaEdit, ViewState>?>(null) }
    var frame by remember { mutableStateOf<Rect?>(null) }
    var viewer by remember { mutableStateOf(Size.Zero) }
    var panorama by remember { mutableStateOf<PanoramaView?>(null) }

    fun keep(next: PanoramaEdit) {
        edit = next
        key?.let { edits.put(it, next) }
    }

    fun firstView(): ViewState = ViewState(yaw = edit.heading, pitch = edit.pitch, level = edit.level)

    fun framedView(): ViewState = ViewState(
        yaw = framing.yaw, pitch = framing.pitch, fov = framing.fov, roll = framing.roll,
        projection = mode.projection, level = edit.level,
    )

    /** The frame where the framing keeps it, on a viewer of [size]. */
    fun placedFrame(size: Size): Rect? {
        if (size.width <= 0f || size.height <= 0f) return null
        val f = framing
        if (f.frameHeight <= 0f) return centredFrame(size, f.ratio)
        val h = (f.frameHeight * size.height).coerceAtMost(minOf(size.height, size.width / f.ratio))
        val w = h * f.ratio
        val cx = (f.frameX * size.width).coerceIn(w / 2, size.width - w / 2)
        val cy = (f.frameY * size.height).coerceIn(h / 2, size.height - h / 2)
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** The framing with the view and the frame as they are now. */
    fun currentFraming(): Framing {
        val v = panorama?.view
        val f = frame
        return framing.copy(
            yaw = v?.yaw ?: framing.yaw, pitch = v?.pitch ?: framing.pitch,
            fov = v?.fov ?: framing.fov, roll = v?.roll ?: framing.roll,
            frameX = if (f != null && viewer.width > 0f) f.center.x / viewer.width else framing.frameX,
            frameY = if (f != null && viewer.height > 0f) f.center.y / viewer.height else framing.frameY,
            frameHeight = if (f != null && viewer.height > 0f) f.height / viewer.height else framing.frameHeight,
        )
    }

    fun keepFraming() {
        if (!flat) return
        framing = currentFraming()
        key?.let { edits.putFraming(it, framing) }
    }

    fun showFlat(on: Boolean) {
        if (on == flat) return
        if (!on) keepFraming()
        flat = on
        key?.let { edits.putFlat(it, on) }
        val p = panorama ?: return
        if (on) {
            frame = placedFrame(viewer)
            p.home = mode.preset(firstView())
            p.setView(framedView())
        } else {
            frame = null
            p.home = firstView()
            p.setView(firstView())
        }
    }

    BackHandler(enabled = full) { full = false }
    DisposableEffect(full) {
        systemBars(view, show = !full)
        onDispose { systemBars(view, show = true) }
    }
    // Leaving keeps the framing as it was left
    DisposableEffect(key) { onDispose { keepFraming() } }
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

    /** Runs [work] off the main thread with the save buttons busy, then says how it went. */
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

    /** Publishes what [write] puts into a cache file as [name]. */
    fun publish(name: String, write: (File) -> Unit) {
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
                .background(Color.Black)
                .onSizeChanged {
                    val size = Size(it.width.toFloat(), it.height.toFloat())
                    if (size != viewer) {
                        if (flat && frame != null) framing = currentFraming()
                        viewer = size
                        frame = if (flat) placedFrame(size) else null
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = {
                    PanoramaView(it, model).apply {
                        home = if (flat) mode.preset(firstView()) else firstView()
                        setView(if (flat) framedView() else firstView())
                        panorama = this
                    }
                },
                update = { it.show(picture) },
                modifier = Modifier.fillMaxSize(),
            )
            val shown = frame
            if (flat && shown != null && picture != null) FrameOverlay(shown, viewer) { frame = it }
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
        Label(
            stringResource(if (flat) R.string.pano_hint_flat else R.string.pano_hint),
            Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2, align = TextAlign.Center,
        )
        val ready = picture != null && !saving

        // What to make of it: a 360 photo, or an ordinary one framed out of the sphere
        if (source?.stitched != true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.pano_kind_360), selected = !flat) { showFlat(false) }
                Chip(stringResource(R.string.pano_kind_flat), selected = flat) { showFlat(true) }
            }
        }

        // The same height for both: switching does not resize the picture or move the buttons
        Column(
            Modifier.fillMaxWidth().height(CONTROLS_HEIGHT).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!flat) {
                // Straighten: turns the picture about the middle of the screen, kept with the picture
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
                    PillButton(stringResource(R.string.pano_first_view), Modifier.weight(1f), enabled = picture != null) {
                        val v = panorama?.view ?: return@PillButton
                        keep(edit.copy(heading = v.yaw, pitch = v.pitch))
                        panorama?.home = firstView()
                        nav.message(resources.getString(R.string.pano_first_view_done), MessageKind.GOOD)
                    }
                    PillButton(stringResource(R.string.pano_reset), Modifier.weight(1f), enabled = picture != null && edit != PanoramaEdit()) {
                        keep(PanoramaEdit())
                        panorama?.home = firstView()
                        panorama?.setView(firstView())
                    }
                }
                Spacer(Modifier.weight(1f))
                PillButton(
                    stringResource(if (saving) R.string.pano_saving else R.string.pano_save),
                    Modifier.fillMaxWidth(),
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
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ViewMode.entries.forEach { m ->
                        Chip(stringResource(m.label), selected = mode == m) {
                            val p = panorama ?: return@Chip
                            framing = currentFraming().copy(style = m.name)
                            val next = m.preset(p.view).copy(roll = p.view.roll)
                            p.home = m.preset(firstView())
                            p.setView(next)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label(stringResource(R.string.pano_format), size = 14.sp, color = colors.text2)
                    FRAMES.forEach { (label, ratio) ->
                        Chip(label, selected = framing.ratio == ratio) {
                            framing = currentFraming().copy(ratio = ratio, frameX = -1f, frameY = -1f, frameHeight = -1f)
                            edits.lastRatio = ratio
                            frame = placedFrame(viewer)
                        }
                    }
                }
                // Rotate: turns this framing only (the 360 photo keeps its own level)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val roll = Math.toDegrees((panorama?.view?.roll ?: framing.roll).toDouble()).toFloat()
                    Label(stringResource(R.string.pano_rotate_value, roll), Modifier.width(118.dp), size = 14.sp, color = colors.text2)
                    var rollSteps by remember(key) { mutableIntStateOf((roll * 2).toInt()) }
                    ValueSlider(
                        value = rollSteps,
                        range = -ROTATE_STEPS..ROTATE_STEPS,
                        onChange = { v ->
                            rollSteps = v
                            panorama?.let { p -> p.setView(p.view.copy(roll = Math.toRadians(v / 2.0).toFloat())) }
                        },
                        onDone = { keepFraming() },
                        modifier = Modifier.weight(1f),
                        enabled = picture != null,
                    )
                }
                Spacer(Modifier.weight(1f))
                PillButton(
                    stringResource(if (saving) R.string.pano_saving_flat else R.string.pano_save_flat),
                    Modifier.fillMaxWidth(),
                    icon = AppIcons.Photo,
                    style = ButtonStyle.PRIMARY,
                    enabled = ready && frame != null,
                ) {
                    val image = picture ?: return@PillButton
                    val v = panorama?.view ?: return@PillButton
                    val f = frame ?: return@PillButton
                    val local = original
                    val from = source ?: return@PillButton
                    keepFraming()
                    val window = frameWindow(f, viewer)
                    val (w, h) = frameSize(image, v, window)
                    val stamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.ROOT).format(java.util.Date())
                    save("Framing ${w}x$h") {
                        val picked = ViewRenderer.render(image, model, v, w, h, window)
                        try {
                            publish("${from.baseName}_${mode.fileTag}_$stamp.jpg") { Jpeg360.writeFlat(picked, local, it) }
                        } finally {
                            picked.recycle()
                        }
                    }
                }
            }
        }
    }
}

/** The ways of framing the sphere as an ordinary photo, and where each starts. */
private enum class ViewMode(val label: Int, val fileTag: String, val projection: Projection) {
    NORMAL(R.string.pano_mode_normal, "view", Projection.RECTILINEAR),
    PLANET(R.string.pano_mode_planet, "planet", Projection.STEREOGRAPHIC),
    TUNNEL(R.string.pano_mode_tunnel, "tunnel", Projection.STEREOGRAPHIC);

    fun preset(from: ViewState): ViewState = when (this) {
        NORMAL -> from.copy(projection = projection, pitch = 0f, fov = 90f)
        // Straight down (positive pitch looks down) through a wide stereographic lens
        PLANET -> from.copy(projection = projection, pitch = HALF_PI, fov = 260f)
        TUNNEL -> from.copy(projection = projection, pitch = -HALF_PI, fov = 260f)
    }
}

private const val HALF_PI = (Math.PI / 2).toFloat()

/** Half degrees each way. */
private const val STRAIGHTEN_STEPS = 60
private const val ROTATE_STEPS = 360

/** Room for the taller set of controls (the normal photo's) with its bottom margin. */
private val CONTROLS_HEIGHT = 232.dp

private val FRAMES = listOf("16:9" to 16f / 9f, "4:3" to 4f / 3f, "1:1" to 1f, "9:16" to 9f / 16f)

/**
 * The frame [f], on a viewer of size [viewer], as the renderer's window: centre and half size in
 * units of the view's half height, y up.
 */
private fun frameWindow(f: Rect, viewer: Size): FloatArray {
    val h = viewer.height
    return floatArrayOf(
        (f.center.x / viewer.width * 2f - 1f) * viewer.width / h,
        -(f.center.y / h * 2f - 1f),
        f.width / h,
        f.height / h,
    )
}

/**
 * A framing's size: as much detail as the picture has in the middle of the view (its pixels per
 * radian there), at most [MAX_FRAME] on the long side.
 */
private fun frameSize(picture: Panorama, view: ViewState, window: FloatArray): Pair<Int, Int> {
    val perRadian = when (picture) {
        is DualFisheyeImage -> picture.side * 2 / (2 * Math.PI)
        is EquirectImage -> picture.bitmap.width / (2 * Math.PI)
    }
    var w = 2 * window[2] * view.scale() * perRadian
    var h = 2 * window[3] * view.scale() * perRadian
    val longest = maxOf(w, h)
    if (longest > MAX_FRAME) {
        w *= MAX_FRAME / longest
        h *= MAX_FRAME / longest
    }
    return w.toInt().coerceAtLeast(320) to h.toInt().coerceAtLeast(320)
}

private const val MAX_FRAME = 4096.0

/** Halves bigger than this are viewed at half size (well under any phone GPU's texture limit). */
private const val MAX_SIDE = 8192
