package com.simoneluconi.revive360.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.settings.Accent
import com.simoneluconi.revive360.settings.ThemeMode

/** The palette every screen draws with; nothing else hard-codes colors. */
@Immutable
data class AppColors(
    val bg: Color,
    val surface: Color,
    /** Cards inside a surface, chips, inactive buttons. */
    val surface2: Color,
    /** Hairlines between rows. */
    val line: Color,
    val text: Color,
    /** Labels and secondary text. */
    val text2: Color,
    val accent: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    /** A switch's knob, on the track whatever its color. */
    val knob: Color,
    val dark: Boolean,
) {
    /** Readable content on [accent]. */
    val onAccent: Color get() = if (accent.luminance() > 0.45f) Color(0xFF0F1318) else Color.White

    /** A soft tint of the accent for selected cards and chips. */
    val accentSoft: Color get() = lerp(surface, accent, if (dark) 0.22f else 0.14f)

    fun soft(color: Color): Color = lerp(surface, color, if (dark) 0.22f else 0.14f)
}

private val Day = AppColors(
    bg = Color(0xFFE7EBF0),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF1F3F6),
    line = Color(0xFFD3D9E1),
    text = Color(0xFF0F1318),
    text2 = Color(0xFF4A5462),
    accent = Color(0xFF0B63CE),
    good = Color(0xFF1E8E3E),
    warn = Color(0xFFB26A00),
    bad = Color(0xFFC62828),
    knob = Color(0xFFFFFFFF),
    dark = false,
)

private val Night = AppColors(
    bg = Color(0xFF090B0E),
    surface = Color(0xFF14171C),
    surface2 = Color(0xFF1C2128),
    line = Color(0xFF2A303A),
    text = Color(0xFFF3F5F8),
    text2 = Color(0xFFA9B1BE),
    accent = Color(0xFF5EA8FF),
    good = Color(0xFF6FD08C),
    warn = Color(0xFFFFB74D),
    bad = Color(0xFFFF7A7A),
    knob = Color(0xFFF3F5F8),
    dark = true,
)

val LocalAppColors = staticCompositionLocalOf { Day }

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(500), manrope(600), manrope(700), manrope(800))

val BaseText = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AppTheme(mode: ThemeMode, accent: Accent, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val base = if (dark) Night else Day
    val accentColor by animateColorAsState(Color(if (dark) accent.night else accent.day), tween(400), label = "accent")
    CompositionLocalProvider(LocalAppColors provides base.copy(accent = accentColor), content = content)
}
