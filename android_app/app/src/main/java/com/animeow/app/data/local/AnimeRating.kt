package com.animeow.app.data.local

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Numeric anime ratings are stored as tenths (0..100) so a 0..10 score keeps one decimal place.
 * All user-facing values remain on the original app's 0..10 scale.
 */
fun formatAnimeRating(storedRating: Int?): String {
    val normalized = storedRating?.coerceIn(0, 100)?.takeIf { it > 0 } ?: return ""
    return String.format(Locale.ROOT, "%.1f", normalized / 10.0)
}

fun parseAnimeRatingInput(value: String): Int? {
    val score = value.trim().replace('，', '.').toDoubleOrNull() ?: return null
    if (!score.isFinite() || score <= 0.0) return null
    return (score.coerceIn(0.0, 10.0) * 10.0).roundToInt().coerceIn(1, 100)
}

fun sanitizeAnimeRatingInput(value: String): String {
    val normalized = value.replace('，', '.')
    val whole = normalized.substringBefore('.').filter(Char::isDigit).take(2)
    if (whole.isEmpty()) return ""
    val boundedWhole = whole.toIntOrNull()?.coerceAtMost(10)?.toString() ?: return ""
    if ('.' !in normalized) return boundedWhole
    val decimal = normalized.substringAfter('.', "").filter(Char::isDigit).take(1)
    return if (boundedWhole == "10") "10${if (decimal.isNotEmpty()) ".0" else "."}" else "$boundedWhole.$decimal"
}

fun normalizeAnimeRatingGrade(value: String?): String? = value.orEmpty().trim().uppercase(Locale.ROOT)
    .takeIf { it in ANIME_RATING_GRADES }

/**
 * Returns the shared 0..100 value used whenever numeric and A-D ratings are compared.
 * This mirrors the legacy app's ordering: A=10, B=8, C=6 and D=4.
 */
fun animeRatingSortValue(anime: AnimeEntity): Int? = when (normalizeAnimeRatingGrade(anime.ratingGrade)) {
    "A" -> 100
    "B" -> 80
    "C" -> 60
    "D" -> 40
    else -> anime.rating?.takeIf { it in 1..100 }
}

fun animeRatingCompactLabel(anime: AnimeEntity): String =
    normalizeAnimeRatingGrade(anime.ratingGrade) ?: formatAnimeRating(anime.rating)

fun animeRatingSummary(anime: AnimeEntity): String =
    normalizeAnimeRatingGrade(anime.ratingGrade)?.let { "$it 级" }
        ?: formatAnimeRating(anime.rating).takeIf(String::isNotBlank)?.let { "$it / 10" }.orEmpty()

fun legacyAnimeRatingToStored(value: Any?): Int? {
    val numeric = when (value) {
        is Number -> value.toDouble()
        else -> value?.toString()?.trim()?.toDoubleOrNull()
    } ?: return null
    if (!numeric.isFinite() || numeric <= 0.0) return null
    val stored = if (numeric <= 10.0) numeric * 10.0 else numeric
    return stored.roundToInt().coerceIn(1, 100)
}

val ANIME_RATING_GRADES = listOf("A", "B", "C", "D")
