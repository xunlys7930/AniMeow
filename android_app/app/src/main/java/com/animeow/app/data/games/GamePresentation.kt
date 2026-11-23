package com.animeow.app.data.games

import java.text.Normalizer
import java.util.Locale

fun gameScore(score: Double): String = if (score.isFinite() && score > 0) String.format(Locale.ROOT, "%.2f", score).trimEnd('0').trimEnd('.') else ""

fun gamePlatforms(value: String): String = value.split('/').joinToString(" / ") { part ->
    when (part.trim().lowercase(Locale.ROOT)) {
        "win" -> "Windows"; "and" -> "Android"; "swi" -> "Nintendo Switch"; "lin" -> "Linux"; "mac" -> "macOS"
        else -> part.trim()
    }
}

private fun normalizedGameTitle(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("[\\p{P}\\p{S}\\s]"), "")

fun sameCatalogGame(a: GameEntity, b: GameEntity): Boolean = a.catalogKey == b.catalogKey ||
    ((a.vndbId != null) != (b.vndbId != null) && a.releaseDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
        a.releaseDate == b.releaseDate && a.originalTitle.isNotBlank() &&
        (normalizedGameTitle(a.originalTitle) == normalizedGameTitle(b.originalTitle) ||
            (a.title.isNotBlank() && normalizedGameTitle(a.title) == normalizedGameTitle(b.title) &&
                gameScriptTitle(a.originalTitle).isNotBlank() && gameScriptTitle(a.originalTitle) == gameScriptTitle(b.originalTitle))))

private fun gameScriptTitle(value: String): String {
    val at = Regex("[\u3040-\u30ff\u3400-\u9fff]").find(value)?.range?.first ?: return ""
    return normalizedGameTitle(value.substring(at))
}

fun gameSynopsisNotice(game: GameEntity): String? = if (game.synopsis.isNotBlank() &&
    !game.synopsis.any { it in '\u4e00'..'\u9fff' } && game.synopsis.any { it in 'a'..'z' })
    "来源仅提供英文原文，暂无可核实的中文简介" else null
