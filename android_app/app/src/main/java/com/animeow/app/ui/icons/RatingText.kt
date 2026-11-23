package com.animeow.app.ui.icons

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.animeow.app.ui.theme.RatingIconStyle

val RatingIconStyle.glyph: IconGlyph?
    get() = when (this) {
        RatingIconStyle.PAW -> AppIcons.Paw
        RatingIconStyle.CAT -> AppIcons.Cat
        RatingIconStyle.STAR -> AppIcons.StarFilled
        RatingIconStyle.GLOWING_STAR -> AppIcons.GlowingStar
        RatingIconStyle.HEART -> AppIcons.HeartFilled
        RatingIconStyle.FIRE -> AppIcons.Fire
        RatingIconStyle.DIAMOND -> AppIcons.Diamond
        RatingIconStyle.SPARKLES -> AppIcons.Sparkles
        RatingIconStyle.THUMBS_UP -> AppIcons.ThumbsUp
        RatingIconStyle.CLOVER -> AppIcons.Clover
        RatingIconStyle.BLOSSOM -> AppIcons.Blossom
        RatingIconStyle.TROPHY -> AppIcons.Trophy
        RatingIconStyle.NONE -> null
    }

/** Vector rating marks scale with the text and inherit both tint and icon customization. */
@Composable
fun RatingText(
    text: String,
    ratingIcon: RatingIconStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    preserveIconTint: Boolean = false,
) {
    val glyph = ratingIcon.glyph
    val tint = when {
        color.isSpecified -> color
        style.color.isSpecified -> style.color
        else -> LocalContentColor.current
    }
    val content = buildAnnotatedString {
        if (glyph != null) {
            appendInlineContent("rating", ratingIcon.displayName)
            append(" ")
        }
        append(text)
    }
    Text(
        text = content,
        modifier = modifier,
        color = color,
        style = style,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = if (glyph == null) emptyMap() else mapOf(
            "rating" to InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
                AppIcon(glyph, null, Modifier.fillMaxSize(), tint = tint, preserveTint = preserveIconTint)
            },
        ),
    )
}
