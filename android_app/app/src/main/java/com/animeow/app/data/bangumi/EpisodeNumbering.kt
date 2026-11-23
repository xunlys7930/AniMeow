package com.animeow.app.data.bangumi

/** Display-only metadata. Persisted progress, backups and sync always remain season counts. */
internal class EpisodeNumbering private constructor(
    val firstNumber: Int,
    val totalEpisodes: Int,
) {
    val lastNumber: Int get() = firstNumber + totalEpisodes - 1
    val usesContinuousNumbering: Boolean get() = firstNumber > 1

    fun numberForWatchedCount(count: Int): Int? =
        if (count in 1..totalEpisodes) firstNumber + count - 1 else null

    /** Zero is deliberately not a global episode number; zero progress stays in count mode. */
    fun watchedCountForNumber(number: Int): Int? =
        if (number in firstNumber..lastNumber) number - firstNumber + 1 else null

    companion object {
        /** Refuse partial lists, SPs, duplicate IDs/numbers, gaps and fractional numbering. */
        fun fromEpisodes(episodes: List<EpisodeNumberMetadata>, expectedTotal: Int): EpisodeNumbering? {
            if (expectedTotal <= 0 || episodes.size != expectedTotal) return null
            if (episodes.any { it.id <= 0 || it.type != 0 } ||
                episodes.map { it.id }.toSet().size != expectedTotal
            ) return null
            val numbers = episodes.map { row ->
                val number = row.sort
                if (!number.isFinite() || number < 1 || number > Int.MAX_VALUE || number % 1.0 != 0.0) return null
                number.toInt()
            }.sorted()
            val first = numbers.first()
            if (numbers.withIndex().any { (index, number) -> number.toLong() != first.toLong() + index }) return null
            return EpisodeNumbering(first, expectedTotal)
        }
    }
}

/** Bangumi sort is the public episode label; ep can remain season-local (1, 2, ...). */
internal data class EpisodeNumberMetadata(val id: Long, val type: Int, val sort: Double)
