package com.animeow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.icons.RatingText
import com.animeow.app.ui.theme.AccentMode
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.LocalAccentPaint
import com.animeow.app.ui.theme.LocalIndependentThemeColor
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.theme.contrastingText

/** The same badge is used on library covers, series members, and the live settings preview. */
@Composable
internal fun CoverRatingBadge(
    text: String,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val style = settings.ratingBadgeColorStyle
    val followsAccent = style == RatingBadgeColorStyle.ACCENT ||
        (style == RatingBadgeColorStyle.THEME_PRIMARY && LocalIndependentThemeColor.current == null)
    val paint = LocalAccentPaint.current
    val visibleColors = if (paint.settings.mode == AccentMode.SOLID) paint.colors.take(1) else paint.colors
    val contrast = remember(visibleColors) { ratingGradientContrast(visibleColors) }
    val background = when (style) {
        RatingBadgeColorStyle.ORANGE -> Color(0xFFFF6B00).copy(alpha = 0.9f)
        RatingBadgeColorStyle.BLACK_WHITE -> Color.Black.copy(alpha = 0.85f)
        RatingBadgeColorStyle.THEME_PRIMARY, RatingBadgeColorStyle.ACCENT -> MaterialTheme.colorScheme.primary
        RatingBadgeColorStyle.TRANSPARENT_DARK -> Color.Black.copy(alpha = 0.5f)
        RatingBadgeColorStyle.CUSTOM -> Color(settings.ratingBadgeCustomColor)
    }
    val foreground = when {
        followsAccent -> contrast.foreground
        style == RatingBadgeColorStyle.THEME_PRIMARY || style == RatingBadgeColorStyle.CUSTOM -> contrastingText(background)
        else -> Color.White
    }
    RatingText(
        text = text,
        ratingIcon = settings.ratingIconStyle,
        modifier = modifier.clip(RoundedCornerShape(trackerSettings.infoCornerDp.dp))
            .then(if (followsAccent) Modifier.drawWithCache {
                onDrawBehind {
                    // Read the shared phase only in drawing, so visible badges do not recompose every frame.
                    drawRect(paint.brush(size))
                    if (contrast.scrimAlpha > 0f) drawRect(Color.Black, alpha = contrast.scrimAlpha)
                }
            } else Modifier.background(background))
            .padding(PaddingValues(horizontal = (7 * trackerSettings.infoScale).dp, vertical = (3 * trackerSettings.infoScale).dp)),
        style = textStyle.copy(fontWeight = FontWeight.Bold),
        color = foreground,
        maxLines = 1,
        preserveIconTint = true,
    )
}

internal data class RatingGradientContrast(val foreground: Color, val scrimAlpha: Float = 0f)

/** Contrast correction is local to this badge; source sRGB colors and saved settings remain intact. */
internal fun ratingGradientContrast(colors: List<Color>): RatingGradientContrast {
    val samples = if (colors.size == 1) colors else colors.zipWithNext().flatMap { (a, b) ->
        (0..32).map { step ->
            val t = step / 32f
            Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)
        }
    }
    val darkTextContrast = samples.minOf { (it.luminance() + 0.05f) / 0.05f }
    val lightTextContrast = samples.minOf { 1.05f / (it.luminance() + 0.05f) }
    if (darkTextContrast >= 4.6f && darkTextContrast >= lightTextContrast) return RatingGradientContrast(Color.Black)
    if (lightTextContrast >= 4.5f) return RatingGradientContrast(Color.White)
    if (darkTextContrast >= 4.6f) return RatingGradientContrast(Color.Black)
    val alpha = (1..16).first { step ->
        colors.all { 1.05f / (Color.Black.copy(alpha = step / 20f).compositeOver(it).luminance() + 0.05f) >= 4.5f }
    } / 20f
    return RatingGradientContrast(Color.White, alpha)
}
