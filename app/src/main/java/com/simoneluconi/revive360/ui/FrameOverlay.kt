package com.simoneluconi.revive360.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The framing to save, over the picture: outside it is dimmed. Dragging inside moves it, pinching
 * resizes it (its shape stays); touches outside go on to the picture underneath.
 */
@Composable
fun FrameOverlay(frame: Rect, bounds: Size, onChange: (Rect) -> Unit) {
    val current by rememberUpdatedState(frame)
    val change by rememberUpdatedState(onChange)
    val area by rememberUpdatedState(bounds)
    Canvas(Modifier.fillMaxSize()) {
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, Offset.Zero, Size(size.width, frame.top))
        drawRect(dim, Offset(0f, frame.bottom), Size(size.width, size.height - frame.bottom))
        drawRect(dim, Offset(0f, frame.top), Size(frame.left, frame.height))
        drawRect(dim, Offset(frame.right, frame.top), Size(size.width - frame.right, frame.height))
        drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
        // Thirds, as on a camera screen
        val thin = Stroke(0.75.dp.toPx())
        for (i in 1..2) {
            val x = frame.left + frame.width * i / 3
            val y = frame.top + frame.height * i / 3
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, frame.top), Offset(x, frame.bottom), thin.width)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(frame.left, y), Offset(frame.right, y), thin.width)
        }
    }
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
            .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    change(moved(current, area, pan, zoom))
                }
            },
    )
}

/** [frame] scaled by [zoom] about its centre and moved by [pan], kept inside [bounds]. */
private fun moved(frame: Rect, bounds: Size, pan: Offset, zoom: Float): Rect {
    val ratio = frame.width / frame.height
    val fit = minOf(bounds.width / ratio, bounds.height)
    val h = (frame.height * zoom).coerceIn(minOf(MIN_SIDE, fit), fit)
    val w = h * ratio
    val cx = (frame.center.x + pan.x).coerceIn(w / 2, bounds.width - w / 2)
    val cy = (frame.center.y + pan.y).coerceIn(h / 2, bounds.height - h / 2)
    return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
}

/** The largest frame of [ratio] (width / height) in [fill] of [bounds], centred. */
fun centredFrame(bounds: Size, ratio: Float, fill: Float = 0.85f): Rect {
    val h = minOf(bounds.width * fill / ratio, bounds.height * fill)
    val w = h * ratio
    return Rect((bounds.width - w) / 2, (bounds.height - h) / 2, (bounds.width + w) / 2, (bounds.height + h) / 2)
}

private const val MIN_SIDE = 120f
