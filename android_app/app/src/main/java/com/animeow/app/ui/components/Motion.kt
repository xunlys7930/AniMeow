package com.animeow.app.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
