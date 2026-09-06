package com.animeow.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.MotionLevel

@Composable
fun Modifier.animatedPressClick(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val motionLevel = LocalMotionLevel.current
    val duration = scaledMotionDurationMillis(140, motionLevel)
    val pressedScale = when (motionLevel) {
        MotionLevel.FULL -> 0.978f
        MotionLevel.REDUCED -> 0.99f
        MotionLevel.NONE -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) pressedScale else 1f,
        animationSpec = tween(
            durationMillis = duration,
            easing = AniMeowStandardEasing,
        ),
        label = "press_scale",
    )
    val animated = graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    return if (onLongClick == null) {
        animated.clickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        )
    } else {
        animated.combinedClickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClickLabel = onLongClickLabel,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}
