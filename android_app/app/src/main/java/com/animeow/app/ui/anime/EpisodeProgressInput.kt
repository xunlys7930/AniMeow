package com.animeow.app.ui.anime

import com.animeow.app.data.bangumi.EpisodeNumbering

/** Converts only explicit global-number input; existing callers continue saving season counts. */
internal fun resolveEpisodeProgressInput(text: String, total: Int, continuous: Boolean, numbering: EpisodeNumbering?): Int? {
    val value = text.toIntOrNull() ?: return null
    if (continuous) return numbering?.takeIf { it.totalEpisodes == total }?.watchedCountForNumber(value)
    return value.takeIf { it >= 0 && (total <= 0 || it <= total) }
}
