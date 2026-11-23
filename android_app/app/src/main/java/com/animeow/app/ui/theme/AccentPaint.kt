package com.animeow.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AccentMode(val storageKey: String, val label: String) {
    SOLID("solid", "纯色"), GRADIENT("gradient", "渐变"), FLOW("flow", "流动");
    companion object { fun fromStorage(value: String?) = entries.firstOrNull { it.storageKey == value } ?: SOLID }
}

/** Stops store opaque sRGB values. Readability adjustments to UI text never change these values. */
data class AccentGradientSettings(
    val mode: AccentMode = AccentMode.SOLID,
    val colors: List<Long> = emptyList(),
    val angleDegrees: Float = 35f,
    val durationMillis: Int = 8_000,
) {
    fun normalized() = copy(
        colors = colors.take(4).map { (it and 0xFFFFFFL) or 0xFF000000L },
        angleDegrees = if (angleDegrees.isFinite()) angleDegrees.coerceIn(0f, 360f) else 35f,
        durationMillis = durationMillis.coerceIn(4_000, 24_000),
    )
}

class AccentPaint(val colors: List<Color>, val settings: AccentGradientSettings, private val phase: State<Float>) {
    fun withSettings(value: AccentGradientSettings) = AccentPaint(colors, value, phase)
    fun brush(size: Size, animate: Boolean = true): Brush {
        if (settings.mode == AccentMode.SOLID) return SolidColor(colors.first())
        val time = if (animate && settings.mode == AccentMode.FLOW) phase.value else 0f
        val angle = settings.angleDegrees / 180f * PI + sin(time) * 0.3
        val reach = (kotlin.math.abs(cos(angle)) * size.width + kotlin.math.abs(sin(angle)) * size.height).toFloat() / 2f
        val center = Offset(size.width / 2f + sin(time) * size.width * 0.18f, size.height / 2f)
        val vector = Offset(cos(angle).toFloat() * reach, sin(angle).toFloat() * reach)
        return Brush.linearGradient(colors, start = center - vector, end = center + vector)
    }
}

val LocalAccentPaint = staticCompositionLocalOf {
    AccentPaint(listOf(Color(0xFF4C7970)), AccentGradientSettings(), mutableFloatStateOf(0f))
}

fun accentGradientColors(primary: Color, secondary: Color, settings: AccentGradientSettings): List<Color> =
    listOf(primary) + if (settings.colors.isEmpty()) listOf(lerp(primary, secondary, 0.8f)) else settings.colors.map { Color(it) }

internal fun AppearanceSettings.accentSourceColor(themePrimary: Color): Color =
    accentColor?.takeUnless { useDynamicColor }?.let { Color(opaqueAccentColor(it)) } ?: themePrimary

internal fun opaqueAccentColor(color: Long): Long = (color and 0xFFFFFFL) or 0xFF000000L

/** Applying/reversing a palette is one edit, so no intermediate theme color is ever written back. */
internal fun AppearanceSettings.withAccentPalette(colors: List<Long>): AppearanceSettings {
    if (colors.size < 2) return this
    return copy(
        accentColor = opaqueAccentColor(colors.first()),
        useDynamicColor = false,
        randomAccentOnLaunch = false,
        accentGradient = accentGradient.copy(colors = colors.drop(1)).normalized(),
    )
}

@Composable
internal fun ProvideAccentPaint(settings: AppearanceSettings, primary: Color, secondary: Color, systemAnimations: Boolean,
    content: @Composable () -> Unit) {
    val gradient = settings.accentGradient.normalized()
    val phase: State<Float> = if (gradient.mode == AccentMode.FLOW && settings.motionLevel == MotionLevel.FULL && systemAnimations) {
        val transition = rememberInfiniteTransition(label = "accent_flow")
        transition.animateFloat(0f, (PI * 2).toFloat(),
            infiniteRepeatable(tween(gradient.durationMillis, easing = LinearEasing), RepeatMode.Restart), label = "accent_phase")
    } else remember { mutableFloatStateOf(0f) }
    val source = settings.accentSourceColor(primary)
    val colors = remember(source, secondary, gradient.colors) { accentGradientColors(source, secondary, gradient) }
    val paint = remember(colors, gradient, phase) { AccentPaint(colors, gradient, phase) }
    CompositionLocalProvider(LocalAccentPaint provides paint, content = content)
}

/** Read the shared animation phase during drawing, without recomposing an entire screen. */
fun Modifier.accentBackground(paint: AccentPaint, alpha: Float = 1f): Modifier = drawWithCache {
    onDrawBehind { drawRect(paint.brush(size), alpha = alpha) }
}
