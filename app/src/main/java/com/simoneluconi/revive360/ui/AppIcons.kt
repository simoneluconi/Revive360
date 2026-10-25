package com.simoneluconi.revive360.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line icons on a 24x24 grid with round 2-unit strokes; the launcher icon uses the same [Camera360]. */
object AppIcons {
    /** The Gear 360 itself: a ball with its two lenses. */
    val Camera360 = icon(
        circle(12f, 10f, 7f),
        circle(12f, 10f, 2.5f),
        "M9 17l-1 4h8l-1-4",
    )
    val Shutter = icon(circle(12f, 12f, 8f), circle(12f, 12f, 4f))
    val Photo = icon(
        "M5 5h14a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
        "M4 16l5-5 4 4 2-2 5 5",
        circle(15.5f, 9f, 1.2f),
    )
    val Raw = icon("M5 5h14a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", "M8 9v6M8 9h2.5a1.5 1.5 0 0 1 0 3H8M10.5 12l1.5 3", "M15 9v6")
    val Video = icon("M4 7h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M16 10.5l5-3v9l-5-3")
    val File = icon("M7 3h7l5 5v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M14 3v5h5")
    val Gallery = icon(
        "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z",
    )
    val Wifi = icon(
        "M2.5 9a14 14 0 0 1 19 0", "M5.5 12.5a9.5 9.5 0 0 1 13 0", "M8.5 16a5 5 0 0 1 7 0",
        circle(12f, 19f, 0.6f),
    )
    val SdCard = icon("M8 3h9a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6z", "M9 3v4M12 3v4M15 3v4")
    val Battery = icon("M4 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M22 11v2")
    val Charging = icon("M4 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M22 11v2", "M11 9l-2 3h3l-2 3")
    val Led = icon(circle(12f, 10f, 4f), "M12 2v1.5M5 4.5l1 1M19 4.5l-1 1M3 10h1.5M19.5 10H21", "M10 16h4M10.5 19h3")
    val Download = icon("M12 3v12", "M8 11l4 4 4-4", "M5 19h14")
    val Trash = icon("M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val Settings = icon(
        "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12",
        circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f),
    )
    val Back = icon("M15 6l-6 6 6 6")
    val Chevron = icon("M9 6l6 6-6 6")
    val Close = icon("M6 6l12 12M18 6L6 18")
    val Check = icon("M5 12.5l4.5 4.5L19 7.5")
    val SelectAll = icon("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M8 12.5l3 3 5-6")
    val SelectNone = icon("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    val Refresh = icon("M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val List = icon("M9 6h11M9 12h11M9 18h11", circle(4.5f, 6f, 0.8f), circle(4.5f, 12f, 0.8f), circle(4.5f, 18f, 0.8f))
    val Info = icon(circle(12f, 12f, 9f), "M12 11v5", circle(12f, 8f, 0.4f))
    val Warning = icon("M12 3.5L21.5 20h-19z", "M12 10v4.5", circle(12f, 17.2f, 0.4f))
    val Key = icon(circle(8f, 15f, 4f), "M11 12l8.5-8.5", "M16 7l2 2", "M18.5 4.5l1.5 1.5")
    val Tool = icon("M14.5 6.5a4 4 0 0 0 5 5l-8 8a2.1 2.1 0 0 1-3-3z", "M14.5 6.5L17 4l3 3-2.5 2.5")
    val Copy = icon("M10 9h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z", "M5 15V5a1 1 0 0 1 1-1h10")

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(vararg strokes: String): ImageVector {
        val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in strokes) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}

@Composable
fun Icon(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(color))
}
