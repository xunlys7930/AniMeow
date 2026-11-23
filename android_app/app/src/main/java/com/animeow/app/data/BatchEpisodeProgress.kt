package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.isBookSubjectType

data class EpisodeProgressEdit(val before: AnimeEntity, val progress: Int)

data class BatchEpisodeProgress(val edits: List<EpisodeProgressEdit>, val eligibleCount: Int)

internal fun previewEpisodeProgress(rows: List<AnimeEntity>, progress: Int): BatchEpisodeProgress {
    require(progress >= 0) { "请输入有效的已看集数" }
    val eligible = rows.filter { it.deletedAt == null && !it.subjectType.isBookSubjectType() }
    val edits = eligible.mapNotNull { anime ->
        val bounded = if (anime.totalEpisodes > 0) progress.coerceAtMost(anime.totalEpisodes) else progress
        val updated = normalizeCompletionProgress(anime.copy(watchedEpisodes = bounded))
        if (updated.watchedEpisodes == anime.watchedEpisodes) null else EpisodeProgressEdit(anime, updated.watchedEpisodes)
    }
    return BatchEpisodeProgress(edits, eligible.size)
}
