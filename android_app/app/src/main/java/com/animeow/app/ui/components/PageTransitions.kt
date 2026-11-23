package com.animeow.app.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import com.animeow.app.ui.theme.PageTransitionStyle
import kotlin.math.roundToInt

fun pageSwitchEnterTransition(
    style: PageTransitionStyle,
    durationMillis: Int,
    direction: Int,
    reduced: Boolean = false,
): EnterTransition = when (style) {
    PageTransitionStyle.REFINED_SLIDE -> slideEnter(durationMillis, direction, scaledFraction(1f, reduced))
    PageTransitionStyle.PARALLAX -> slideEnter(durationMillis, direction, scaledFraction(0.76f, reduced))
    PageTransitionStyle.CARD_STACK -> slideEnter(durationMillis, direction, scaledFraction(0.62f, reduced)) +
        scaleIn(tween(durationMillis, easing = AniMeowEnterEasing), initialScale = if (reduced) 0.985f else 0.94f)
    PageTransitionStyle.SOFT_FADE -> fadeIn(tween(durationMillis, easing = AniMeowEnterEasing)) +
        scaleIn(tween(durationMillis, easing = AniMeowEnterEasing), initialScale = 0.985f)
}

fun pageSwitchExitTransition(
    style: PageTransitionStyle,
    durationMillis: Int,
    direction: Int,
    reduced: Boolean = false,
): ExitTransition = when (style) {
    PageTransitionStyle.REFINED_SLIDE -> slideExit(durationMillis, -direction, scaledFraction(1f, reduced))
    PageTransitionStyle.PARALLAX -> slideExit(durationMillis, -direction, scaledFraction(0.28f, reduced))
    PageTransitionStyle.CARD_STACK -> slideExit(durationMillis, -direction, scaledFraction(0.12f, reduced)) +
        scaleOut(tween(durationMillis, easing = AniMeowExitEasing), targetScale = if (reduced) 0.995f else 0.98f)
    PageTransitionStyle.SOFT_FADE -> fadeOut(tween(durationMillis, easing = AniMeowExitEasing)) +
        scaleOut(tween(durationMillis, easing = AniMeowExitEasing), targetScale = 0.99f)
}

fun pageForwardEnterTransition(
    style: PageTransitionStyle,
    durationMillis: Int,
    reduced: Boolean = false,
): EnterTransition = pageSwitchEnterTransition(style, durationMillis, direction = 1, reduced = reduced)

fun pageForwardExitTransition(
    style: PageTransitionStyle,
    durationMillis: Int,
    reduced: Boolean = false,
): ExitTransition = when (style) {
    PageTransitionStyle.REFINED_SLIDE -> slideExit(durationMillis, -1, scaledFraction(0.24f, reduced))
    PageTransitionStyle.PARALLAX -> slideExit(durationMillis, -1, scaledFraction(0.16f, reduced))
    PageTransitionStyle.CARD_STACK -> slideExit(durationMillis, -1, scaledFraction(0.08f, reduced)) +
        scaleOut(tween(durationMillis, easing = AniMeowExitEasing), targetScale = if (reduced) 0.995f else 0.98f)
    PageTransitionStyle.SOFT_FADE -> fadeOut(tween(durationMillis, easing = AniMeowExitEasing))
}

fun pagePopEnterTransition(
    style: PageTransitionStyle,
    durationMillis: Int,
    reduced: Boolean = false,
): EnterTransition = when (style) {
    PageTransitionStyle.REFINED_SLIDE -> slideEnter(durationMillis, -1, scaledFraction(0.24f, reduced))
    PageTransitionStyle.PARALLAX -> slideEnter(durationMillis, -1, scaledFraction(0.16f, reduced))
    PageTransitionStyle.CARD_STACK -> slideEnter(durationMillis, -1, scaledFraction(0.08f, reduced)) +
        scaleIn(tween(durationMillis, easing = AniMeowEnterEasing), initialScale = if (reduced) 0.995f else 0.98f)
    PageTransitionStyle.SOFT_FADE -> fadeIn(tween(durationMillis, easing = AniMeowEnterEasing))
}

fun pagePopExitTransition(
    style: PageTransitionStyle,
    durationMillis: Int,
    reduced: Boolean = false,
): ExitTransition = when (style) {
    PageTransitionStyle.REFINED_SLIDE -> slideExit(durationMillis, 1, scaledFraction(1f, reduced))
    PageTransitionStyle.PARALLAX -> slideExit(durationMillis, 1, scaledFraction(0.76f, reduced))
    PageTransitionStyle.CARD_STACK -> slideExit(durationMillis, 1, scaledFraction(0.62f, reduced)) +
        scaleOut(tween(durationMillis, easing = AniMeowExitEasing), targetScale = if (reduced) 0.985f else 0.94f)
    PageTransitionStyle.SOFT_FADE -> fadeOut(tween(durationMillis, easing = AniMeowExitEasing)) +
        scaleOut(tween(durationMillis, easing = AniMeowExitEasing), targetScale = 0.985f)
}

private fun slideEnter(durationMillis: Int, direction: Int, fraction: Float): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(durationMillis, easing = AniMeowStandardEasing),
        initialOffsetX = { size -> (size * fraction * direction).roundToInt() },
    )

private fun slideExit(durationMillis: Int, direction: Int, fraction: Float): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(durationMillis, easing = AniMeowStandardEasing),
        targetOffsetX = { size -> (size * fraction * direction).roundToInt() },
    )

private fun scaledFraction(value: Float, reduced: Boolean): Float =
    if (reduced) value * 0.35f else value
