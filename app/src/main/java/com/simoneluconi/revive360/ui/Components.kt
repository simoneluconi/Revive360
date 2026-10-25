package com.simoneluconi.revive360.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.simoneluconi.revive360.ui.theme.BaseText
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlin.math.roundToInt

/** Whether buttons give haptic feedback (a setting). */
val LocalHaptics = compositionLocalOf { true }

enum class Haptic { TAP, CONFIRM, REJECT }

@Composable
fun rememberHaptics(): (Haptic) -> Unit {
    val view = LocalView.current
    val enabled = LocalHaptics.current
    return remember(view, enabled) {
        { kind ->
            if (enabled) {
                // CONFIRM and REJECT are Android 11+: a plain tap and a long press before
                val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                view.performHapticFeedback(
                    when (kind) {
                        Haptic.TAP -> HapticFeedbackConstants.VIRTUAL_KEY
                        Haptic.CONFIRM -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                        Haptic.REJECT -> if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                    }
                )
            }
        }
    }
}

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = LocalAppColors.current.text,
    weight: FontWeight = FontWeight.SemiBold,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Unspecified,
    mono: Boolean = false,
    tabular: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = BaseText.copy(
            color = color,
            fontSize = size,
            fontWeight = weight,
            textAlign = align,
            lineHeight = size * 1.3f,
            fontFamily = if (mono) FontFamily.Monospace else BaseText.fontFamily,
            fontFeatureSettings = if (tabular) "tnum" else null,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Tap (and optional long press) with a press-in scale and haptic tick: every interactive element
 * uses it, so the whole app feels the same.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    label: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    val haptic = rememberHaptics()
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    this
        .scale(scale)
        .semantics {
            role = Role.Button
            if (label != null) contentDescription = label
        }
        // Restarted when a long press appears or disappears: detectTapGestures reads it once
        .pointerInput(enabled, onLongClick != null) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = {
                    haptic(Haptic.TAP)
                    click()
                },
                onLongPress = longClick?.let { action -> { haptic(Haptic.CONFIRM); action() } },
            )
        }
}

/** Calls [onDown] when the finger lands and [onUp] when it lifts (or slides off): the remote's hold buttons. */
fun Modifier.holdable(enabled: Boolean = true, onDown: () -> Unit, onUp: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "hold")
    val haptic = rememberHaptics()
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    this
        .scale(scale)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = {
                pressed = true
                haptic(Haptic.CONFIRM)
                down()
                try {
                    tryAwaitRelease()
                } finally {
                    pressed = false
                    haptic(Haptic.TAP)
                    up()
                }
            })
        }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    color: Color = LocalAppColors.current.surface,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Label(text.uppercase(), modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp), size = 13.sp,
        color = LocalAppColors.current.text2, weight = FontWeight.Bold)
}

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER }

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val bg = when (style) {
        ButtonStyle.PRIMARY -> colors.accent
        ButtonStyle.SECONDARY -> colors.surface2
        ButtonStyle.DANGER -> colors.soft(colors.bad)
    }
    val fg = when (style) {
        ButtonStyle.PRIMARY -> colors.onAccent
        ButtonStyle.SECONDARY -> colors.text
        ButtonStyle.DANGER -> colors.bad
    }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (enabled) bg else colors.surface2)
            .pressable(enabled = enabled, label = text, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) fg else colors.text2
        if (icon != null) {
            Icon(icon, color, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Label(text, color = color, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    background: Color = LocalAppColors.current.surface,
    tint: Color = LocalAppColors.current.text,
    size: Dp = 48.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .pressable(label = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tint, size = size * 0.46f)
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = LocalAppColors.current
    val track by animateColorAsState(if (checked) colors.accent else colors.line, tween(180), label = "track")
    val knob by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "knob")
    Box(
        modifier
            .size(52.dp, 32.dp)
            .clip(CircleShape)
            .background(if (enabled) track else colors.surface2)
            .pressable(enabled = enabled) { onChange(!checked) }
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset((knob * 20.dp.toPx()).roundToInt(), 0) }
                .size(24.dp)
                .clip(CircleShape)
                .background(colors.knob)
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    // Selected = solid accent: a soft tint alone was too close to the unselected chips.
    // pressable (its scale layer) goes before the background: after it, a background colour
    // changed by a long press was not redrawn and the selected chip looked transparent.
    Box(
        modifier
            .pressable(label = text, onClick = onClick)
            .clip(CircleShape)
            .background(if (selected) colors.accent else colors.surface2)
            .border(1.dp, if (selected) colors.accent else colors.line, CircleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Label(text, color = if (selected) colors.onAccent else colors.text, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** A row inside a card: icon, title and subtitle on the left, [trailing] on the right. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalAppColors.current.text,
    titleColor: Color = LocalAppColors.current.text,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.pressable(label = title, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surface2), contentAlignment = Alignment.Center) {
                Icon(icon, iconTint, size = 20.dp)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Label(title, color = titleColor, weight = FontWeight.Bold)
            if (subtitle != null) Label(subtitle, size = 14.sp, color = colors.text2)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Icon(AppIcons.Chevron, colors.text2, size = 20.dp)
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalAppColors.current.line))
}

/** Label on the left, value on the right. */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.weight(1f), color = colors.text2)
        Label(value, color = colors.text, weight = FontWeight.Bold, mono = mono, maxLines = 1)
    }
}

/**
 * A slider drawn like the rest of the app (foundation has none). [onChange] follows the finger,
 * [onDone] comes when it lifts (a tap or the end of a drag): for a value to send once.
 */
@Composable
fun ValueSlider(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val fraction = ((value - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)
    BoxWithConstraints(modifier.fillMaxWidth().height(36.dp)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val knobPx = with(LocalDensity.current) { 24.dp.toPx() }
        fun valueAt(x: Float): Int {
            val f = ((x - knobPx / 2) / (widthPx - knobPx)).coerceIn(0f, 1f)
            return (range.first + f * (range.last - range.first)).roundToInt()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    detectTapGestures {
                        change(valueAt(it.x))
                        done?.invoke()
                    }
                }
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = { done?.invoke() },
                        onDragCancel = { done?.invoke() },
                    ) { pointer, _ -> change(valueAt(pointer.position.x)) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.line))
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).height(6.dp).clip(CircleShape)
                .background(if (enabled) colors.accent else colors.text2))
            Box(
                Modifier
                    .offset { IntOffset((fraction * (widthPx - knobPx)).roundToInt(), 0) }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (enabled) colors.accent else colors.text2)
                    .border(3.dp, colors.surface, CircleShape)
            )
        }
    }
}

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = LocalAppColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = keyboard,
        cursorBrush = SolidColor(colors.accent),
        textStyle = BaseText.copy(color = colors.text, fontSize = 17.sp,
            fontFamily = if (mono) FontFamily.Monospace else BaseText.fontFamily),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface2)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                if (value.isEmpty()) Label(placeholder, color = colors.text2, size = 17.sp, maxLines = 1)
                field()
            }
        },
    )
}

/** A centered dialog card with a title, content and buttons in a row. */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surface)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Label(title, size = 21.sp, weight = FontWeight.ExtraBold)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) { buttons() }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    cancel: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppDialog(title = title, onDismiss = onDismiss, buttons = {
        PillButton(cancel, onClick = onDismiss)
        PillButton(confirm, style = if (danger) ButtonStyle.DANGER else ButtonStyle.PRIMARY) {
            onDismiss()
            onConfirm()
        }
    }) {
        Label(message, color = LocalAppColors.current.text2)
    }
}

/** An arc that spins: work in progress. */
@Composable
fun Spinner(color: Color = LocalAppColors.current.accent, size: Dp = 20.dp) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle",
    )
    Canvas(Modifier.size(size).rotate(turn)) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = color, startAngle = 0f, sweepAngle = 270f, useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** Small round status dot. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** A rounded shape for big tiles. */
val TileShape: Shape = RoundedCornerShape(26.dp)
