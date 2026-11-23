package com.animeow.app.ui.icons

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.theme.LocalMotionLevel
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

/**
 * The single renderer for application icons. VectorDrawable XML is also usable outside Compose.
 * Tint preserves tonal alpha. Style overrides cannot change the meaning or the active state.
 */
@Composable
fun AppIcon(
    icon: IconGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    style: IconStyle? = null,
    variant: IconVariant? = null,
    animationTrigger: Int = 0,
    running: Boolean = false,
    preserveTint: Boolean = false,
) {
    val settings = LocalIconSettings.current
    val resolved = settings.resolve(icon.id)
    val resource = IconDrawables.resource(icon.id, style ?: resolved.style, variant ?: resolved.variant, icon.active)
    val allowed = iconAnimationsEnabled(settings, LocalMotionLevel.current, LocalIconSystemAnimationsEnabled.current)
    val scheme = MaterialTheme.colorScheme
    val accent = com.animeow.app.ui.theme.LocalAccentPaint.current
    val accentColors = com.animeow.app.ui.theme.LocalAccentColorScheme.current
    val linkedTheme = com.animeow.app.ui.theme.LocalIndependentThemeColor.current == null
    // Inverse button content and error feedback retain their contrast/meaning.
    val protectedTint = preserveTint || tint == scheme.error || tint == scheme.onError ||
        tint == scheme.onErrorContainer || tint == scheme.onPrimary || tint == scheme.onSecondary || tint == scheme.onTertiary
    val colorMode = if (protectedTint) IconColorMode.CONTENT else resolved.color.mode
    val usesAccent = !protectedTint && (colorMode == IconColorMode.ACCENT ||
        (colorMode == IconColorMode.CONTENT && linkedTheme && tint == scheme.primary))
    val gradient = usesAccent && accent.settings.mode != com.animeow.app.ui.theme.AccentMode.SOLID
    val iconTint = when {
        gradient -> Color.White.copy(alpha = tint.alpha)
        colorMode == IconColorMode.ACCENT -> accentColors.primary.copy(alpha = tint.alpha)
        colorMode == IconColorMode.CUSTOM -> Color(resolved.color.customColor).copy(alpha = tint.alpha)
        else -> tint
    }
    val progress = remember(icon.id) { Animatable(1f) }
    var previousActive by remember(icon.id) { mutableStateOf(icon.active) }
    var previousTrigger by remember(icon.id) { mutableIntStateOf(animationTrigger) }

    LaunchedEffect(icon.id, icon.active, animationTrigger, running, allowed) {
        val shouldPlay = (icon.active && !previousActive) || previousTrigger != animationTrigger
        previousActive = icon.active
        previousTrigger = animationTrigger
        if (!allowed) {
            progress.snapTo(1f)
        } else if (running && icon.id.motion == IconMotion.ROTATE) {
            // The system check also guards a duration scale change during a running animation.
            while (isActive && ValueAnimator.areAnimatorsEnabled()) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(900, easing = LinearEasing))
            }
        } else if (shouldPlay) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if (icon.id.motion == IconMotion.ROTATE) 600 else 360, easing = LinearEasing))
        } else {
            progress.snapTo(1f)
        }
    }

    androidx.compose.material3.Icon(
        painter = painterResource(resource),
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer {
            compositingStrategy = if (gradient) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            scaleX = 1f
            scaleY = 1f
            rotationZ = 0f
            translationX = 0f
            transformOrigin = TransformOrigin.Center
            if (allowed) {
                val phase = progress.value
                val pulse = sin(phase * PI).toFloat()
                when (icon.id.motion) {
                    IconMotion.PULSE -> {
                        scaleX = 1f + pulse * 0.12f
                        scaleY = scaleX
                    }
                    IconMotion.ROTATE -> rotationZ = phase * 360f
                    IconMotion.BELL -> {
                        transformOrigin = TransformOrigin(0.5f, 0.2f)
                        rotationZ = sin(phase * PI * 4).toFloat() * (1f - phase) * 14f
                    }
                    IconMotion.NUDGE -> translationX = pulse * 2.dp.toPx()
                }
            }
        }.then(if (gradient) Modifier.drawWithCache {
            onDrawWithContent {
                drawContent()
                drawRect(accent.brush(size, animate = allowed), blendMode = BlendMode.SrcIn)
            }
        } else Modifier),
        tint = iconTint,
    )
}

/** Observe the Android animation switch once at the theme boundary, not once per icon. */
@Composable
internal fun rememberIconSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}
