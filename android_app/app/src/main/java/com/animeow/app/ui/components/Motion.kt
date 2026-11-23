package com.animeow.app.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.MotionLevel
import kotlin.math.roundToInt

val AniMeowStandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
val AniMeowEnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val AniMeowExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

fun scaledMotionDurationMillis(baseDurationMillis: Int, motion: MotionLevel): Int =
    (baseDurationMillis * motion.durationScale).roundToInt().coerceAtLeast(0)

@Composable
fun motionDurationMillis(baseDurationMillis: Int): Int =
    scaledMotionDurationMillis(baseDurationMillis, LocalMotionLevel.current)

@Composable
fun <T> motionAnimationSpec(
    baseDurationMillis: Int = 220,
    easing: Easing = AniMeowStandardEasing,
): FiniteAnimationSpec<T> {
    val motion = LocalMotionLevel.current
    return if (motion == MotionLevel.NONE) snap()
    else tween(motionDurationMillis(baseDurationMillis), easing = easing)
}

@Composable
fun motionFadeIn(baseDurationMillis: Int = 180): EnterTransition =
    if (LocalMotionLevel.current == MotionLevel.NONE) EnterTransition.None
    else fadeIn(tween(motionDurationMillis(baseDurationMillis), easing = AniMeowEnterEasing))

@Composable
fun motionFadeOut(baseDurationMillis: Int = 140): ExitTransition =
    if (LocalMotionLevel.current == MotionLevel.NONE) ExitTransition.None
    else fadeOut(tween(motionDurationMillis(baseDurationMillis), easing = AniMeowExitEasing))

@Composable
fun Modifier.motionAnimateContentSize(baseDurationMillis: Int = 220): Modifier =
    if (LocalMotionLevel.current == MotionLevel.NONE) this
    else animateContentSize(
        animationSpec = tween(
            durationMillis = motionDurationMillis(baseDurationMillis),
            easing = AniMeowStandardEasing,
        ),
    )

@Composable
fun motionExpandIn(baseDurationMillis: Int = 220): EnterTransition =
    if (LocalMotionLevel.current == MotionLevel.NONE) EnterTransition.None
    else {
        val duration = motionDurationMillis(baseDurationMillis)
        fadeIn(tween(duration, easing = AniMeowEnterEasing)) +
            expandVertically(tween(duration, easing = AniMeowStandardEasing))
    }

@Composable
fun motionShrinkOut(baseDurationMillis: Int = 180): ExitTransition =
    if (LocalMotionLevel.current == MotionLevel.NONE) ExitTransition.None
    else {
        val duration = motionDurationMillis(baseDurationMillis)
        fadeOut(tween(duration, easing = AniMeowExitEasing)) +
            shrinkVertically(tween(duration, easing = AniMeowStandardEasing))
    }

@Composable
fun motionBarEnter(baseDurationMillis: Int = 220): EnterTransition {
    val motion = LocalMotionLevel.current
    if (motion == MotionLevel.NONE) return EnterTransition.None
    val duration = scaledMotionDurationMillis(baseDurationMillis, motion)
    val base = fadeIn(tween(duration, easing = AniMeowEnterEasing)) +
        expandVertically(tween(duration, easing = AniMeowStandardEasing))
    return if (motion == MotionLevel.FULL) {
        base + slideInVertically(tween(duration, easing = AniMeowStandardEasing)) { it / 2 }
    } else {
        base
    }
}

@Composable
fun motionBarExit(baseDurationMillis: Int = 180): ExitTransition {
    val motion = LocalMotionLevel.current
    if (motion == MotionLevel.NONE) return ExitTransition.None
    val duration = scaledMotionDurationMillis(baseDurationMillis, motion)
    val base = fadeOut(tween(duration, easing = AniMeowExitEasing)) +
        shrinkVertically(tween(duration, easing = AniMeowStandardEasing))
    return if (motion == MotionLevel.FULL) {
        base + slideOutVertically(tween(duration, easing = AniMeowStandardEasing)) { it / 2 }
    } else {
        base
    }
}

fun motionFadeThroughSpec(
    motion: MotionLevel,
    enterMillis: Int = 200,
    exitMillis: Int = 140,
): ContentTransform {
    if (motion == MotionLevel.NONE) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    val enterDuration = scaledMotionDurationMillis(enterMillis, motion)
    val exitDuration = scaledMotionDurationMillis(exitMillis, motion)
    val enter = if (motion == MotionLevel.FULL) {
        fadeIn(tween(enterDuration, easing = AniMeowEnterEasing)) +
            scaleIn(tween(enterDuration, easing = AniMeowEnterEasing), initialScale = 0.985f)
    } else {
        fadeIn(tween(enterDuration, easing = AniMeowEnterEasing))
    }
    return enter togetherWith fadeOut(tween(exitDuration, easing = AniMeowExitEasing))
}

fun motionSlideContentSpec(
    motion: MotionLevel,
    forward: Boolean = true,
    durationMillis: Int = 220,
): ContentTransform {
    if (motion == MotionLevel.NONE) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    val duration = scaledMotionDurationMillis(durationMillis, motion)
    val fraction = if (motion == MotionLevel.FULL) 0.22f else 0.06f
    val enterSign = if (forward) 1 else -1
    val exitSign = -enterSign
    return (slideInHorizontally(tween(duration, easing = AniMeowStandardEasing)) { (it * fraction * enterSign).roundToInt() } +
        fadeIn(tween(duration, easing = AniMeowEnterEasing))) togetherWith
        (slideOutHorizontally(tween(duration, easing = AniMeowStandardEasing)) { (it * fraction * exitSign).roundToInt() } +
            fadeOut(tween((duration * 0.72f).roundToInt(), easing = AniMeowExitEasing)))
}

@Composable
fun motionDragReorderPlacementSpec(
    moving: Boolean,
    baseDurationMillis: Int = 200,
): FiniteAnimationSpec<IntOffset>? {
    if (moving) return null
    return when (val motion = LocalMotionLevel.current) {
        MotionLevel.NONE -> null
        MotionLevel.REDUCED -> tween(
            durationMillis = scaledMotionDurationMillis(baseDurationMillis, motion),
            easing = AniMeowStandardEasing,
        )
        MotionLevel.FULL -> spring(
            stiffness = 600f,
            visibilityThreshold = IntOffset.VisibilityThreshold,
        )
    }
}

internal data class MotionItemSpecs(
    val fadeInSpec: FiniteAnimationSpec<Float>?,
    val placementSpec: FiniteAnimationSpec<IntOffset>?,
    val fadeOutSpec: FiniteAnimationSpec<Float>?,
)

internal fun buildMotionItemSpecs(
    motion: MotionLevel,
    enableFade: Boolean,
    enablePlacement: Boolean,
): MotionItemSpecs {
    if (motion == MotionLevel.NONE) {
        return MotionItemSpecs(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    }
    val fadeInSpec: FiniteAnimationSpec<Float>? = if (enableFade) {
        tween(scaledMotionDurationMillis(180, motion), easing = AniMeowEnterEasing)
    } else null
    val fadeOutSpec: FiniteAnimationSpec<Float>? = if (enableFade) {
        tween(scaledMotionDurationMillis(120, motion), easing = AniMeowExitEasing)
    } else null
    val placementSpec: FiniteAnimationSpec<IntOffset>? = if (enablePlacement) {
        when (motion) {
            MotionLevel.FULL -> spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            )
            MotionLevel.REDUCED -> tween(
                durationMillis = scaledMotionDurationMillis(220, motion),
                easing = AniMeowStandardEasing,
            )
            MotionLevel.NONE -> null
        }
    } else null
    return MotionItemSpecs(fadeInSpec = fadeInSpec, placementSpec = placementSpec, fadeOutSpec = fadeOutSpec)
}

@Composable
fun LazyItemScope.motionAnimateItem(
    modifier: Modifier = Modifier,
    enableFade: Boolean = true,
    enablePlacement: Boolean = true,
): Modifier {
    val motion = LocalMotionLevel.current
    if (motion == MotionLevel.NONE || (!enableFade && !enablePlacement)) return modifier
    val specs = buildMotionItemSpecs(motion, enableFade, enablePlacement)
    return modifier.animateItem(
        fadeInSpec = specs.fadeInSpec,
        placementSpec = specs.placementSpec,
        fadeOutSpec = specs.fadeOutSpec,
    )
}

@Composable
fun LazyGridItemScope.motionAnimateItem(
    modifier: Modifier = Modifier,
    enableFade: Boolean = true,
    enablePlacement: Boolean = true,
): Modifier {
    val motion = LocalMotionLevel.current
    if (motion == MotionLevel.NONE || (!enableFade && !enablePlacement)) return modifier
    val specs = buildMotionItemSpecs(motion, enableFade, enablePlacement)
    return modifier.animateItem(
        fadeInSpec = specs.fadeInSpec,
        placementSpec = specs.placementSpec,
        fadeOutSpec = specs.fadeOutSpec,
    )
}

@Composable
fun LazyStaggeredGridItemScope.motionAnimateItem(
    modifier: Modifier = Modifier,
    enableFade: Boolean = true,
    enablePlacement: Boolean = true,
): Modifier {
    val motion = LocalMotionLevel.current
    if (motion == MotionLevel.NONE || (!enableFade && !enablePlacement)) return modifier
    val specs = buildMotionItemSpecs(motion, enableFade, enablePlacement)
    return modifier.animateItem(
        fadeInSpec = specs.fadeInSpec,
        placementSpec = specs.placementSpec,
        fadeOutSpec = specs.fadeOutSpec,
    )
}
