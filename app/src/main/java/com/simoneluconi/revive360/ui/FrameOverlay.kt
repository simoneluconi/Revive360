package com.simoneluconi.revive360.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The framing to save, over the picture: outside it is dimmed. Its corners resize it (its shape
 * stays), its edges move it; inside, touches go on to the picture, which pans and zooms under it.
 */
@Composable
fun FrameOverlay(frame: Rect, bounds: Size, onChange: (Rect) -> Unit) {
    // Its own top-left origin: the frame's offsets are from the viewer's corner, whatever the
    // parent's alignment
    Box(Modifier.fillMaxSize()) { Overlay(frame, bounds, onChange) }
}

@Composable
private fun Overlay(frame: Rect, bounds: Size, onChange: (Rect) -> Unit) {
    val current by rememberUpdatedState(frame)
    val change by rememberUpdatedState(onChange)
    val area by rememberUpdatedState(bounds)
    Canvas(Modifier.fillMaxSize()) {
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, Offset.Zero, Size(size.width, frame.top))
        drawRect(dim, Offset(0f, frame.bottom), Size(size.width, size.height - frame.bottom))
        drawRect(dim, Offset(0f, frame.top), Size(frame.left, frame.height))
        drawRect(dim, Offset(frame.right, frame.top), Size(size.width - frame.right, frame.height))
        drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(1.5.dp.toPx()))
        // Thirds, as on a camera screen
        for (i in 1..2) {
            val x = frame.left + frame.width * i / 3
            val y = frame.top + frame.height * i / 3
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, frame.top), Offset(x, frame.bottom), 0.75.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f), Offset(frame.left, y), Offset(frame.right, y), 0.75.dp.toPx())
        }
        // The corner handles
        val arm = minOf(22.dp.toPx(), frame.width / 3, frame.height / 3)
        val thick = 4.dp.toPx()
        for (corner in Corner.entries) {
            val p = corner.of(frame)
            val sx = if (corner.left) 1f else -1f
            val sy = if (corner.top) 1f else -1f
            drawLine(Color.White, p, Offset(p.x + sx * arm, p.y), thick, StrokeCap.Round)
            drawLine(Color.White, p, Offset(p.x, p.y + sy * arm), thick, StrokeCap.Round)
        }
    }
    val density = LocalDensity.current
    val grip = with(density) { GRIP.toPx() }
    // While a drag lasts its touch areas stay where it started: moving under the finger they
    // would eat into the finger's own movement
    var held by remember { mutableStateOf<Rect?>(null) }
    val at = held ?: frame

    /** Follows a drag from its start: [to] makes the frame from the start one and the total drag. */
    suspend fun PointerInputScope.follow(to: (Rect, Offset) -> Rect) {
        var start = Rect.Zero
        var total = Offset.Zero
        detectDragGestures(
            onDragStart = { start = current; total = Offset.Zero; held = start },
            onDragEnd = { held = null },
            onDragCancel = { held = null },
        ) { pointer, drag ->
            pointer.consume()
            total += drag
            change(to(start, total))
        }
    }

    // The edges: a band along each side (between the corners) moves the whole frame
    for (edge in Edge.entries) {
        TouchArea(edge.band(at, grip)) { follow { start, total -> moved(start, area, total) } }
    }
    // The corners: dragging one resizes about the opposite corner
    for (corner in Corner.entries) {
        val p = corner.of(at)
        TouchArea(Rect(p.x - grip, p.y - grip, p.x + grip, p.y + grip)) {
            follow { start, total -> resized(start, area, corner, total) }
        }
    }
}

/** A transparent area at [r] (viewer pixels) taking the touches it gets. */
@Composable
private fun TouchArea(r: Rect, gestures: suspend PointerInputScope.() -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            .pointerInput(Unit, gestures),
    )
}

private enum class Corner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true), TOP_RIGHT(false, true), BOTTOM_LEFT(true, false), BOTTOM_RIGHT(false, false);

    fun of(r: Rect): Offset = Offset(if (left) r.left else r.right, if (top) r.top else r.bottom)
}

private enum class Edge {
    TOP, BOTTOM, LEFT, RIGHT;

    /** The band of this side, [grip] each way of the line, short of the corner handles. */
    fun band(r: Rect, grip: Float): Rect = when (this) {
        TOP -> Rect(r.left + grip, r.top - grip, r.right - grip, r.top + grip)
        BOTTOM -> Rect(r.left + grip, r.bottom - grip, r.right - grip, r.bottom + grip)
        LEFT -> Rect(r.left - grip, r.top + grip, r.left + grip, r.bottom - grip)
        RIGHT -> Rect(r.right - grip, r.top + grip, r.right + grip, r.bottom - grip)
    }
}

/** [frame] moved by [drag], kept inside [bounds]. */
private fun moved(frame: Rect, bounds: Size, drag: Offset): Rect {
    val dx = (frame.left + drag.x).coerceIn(0f, bounds.width - frame.width) - frame.left
    val dy = (frame.top + drag.y).coerceIn(0f, bounds.height - frame.height) - frame.top
    return frame.translate(dx, dy)
}

/** [frame] with [corner] dragged by [drag]: the opposite corner stays, so does the shape. */
private fun resized(frame: Rect, bounds: Size, corner: Corner, drag: Offset): Rect {
    val ratio = frame.width / frame.height
    val anchor = Corner.entries.first { it.left != corner.left && it.top != corner.top }.of(frame)
    // The height follows whichever way the finger went further, in the frame's shape
    val grow = maxOf(
        (if (corner.left) -drag.x else drag.x) / ratio,
        if (corner.top) -drag.y else drag.y,
    )
    val room = minOf(
        (if (corner.left) anchor.x else bounds.width - anchor.x) / ratio,
        if (corner.top) anchor.y else bounds.height - anchor.y,
    )
    val h = (frame.height + grow).coerceIn(minOf(MIN_SIDE, room), room)
    val w = h * ratio
    val left = if (corner.left) anchor.x - w else anchor.x
    val top = if (corner.top) anchor.y - h else anchor.y
    return Rect(left, top, left + w, top + h)
}

/** The largest frame of [ratio] (width / height) in [fill] of [bounds], centred. */
fun centredFrame(bounds: Size, ratio: Float, fill: Float = 0.85f): Rect {
    val h = minOf(bounds.width * fill / ratio, bounds.height * fill)
    val w = h * ratio
    return Rect((bounds.width - w) / 2, (bounds.height - h) / 2, (bounds.width + w) / 2, (bounds.height + h) / 2)
}

private val GRIP: Dp = 22.dp
private const val MIN_SIDE = 120f
