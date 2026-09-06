package com.animeow.app.data.remote

internal fun applyDiscoveryFilters(
    items: List<RemoteAnime>,
    filters: DiscoveryFilters,
    source: RemoteCatalogSource,
): List<RemoteAnime> = items.asSequence()
    .filter { anime ->
        filters.seasonMonth?.let { seasonMonth ->
            isAirDateInServerDiscoverySeason(
                airDate = anime.airDate,
                seasonMonth = seasonMonth,
                seasonYear = filters.year,
            )
        } ?: (filters.year == null || anime.airDate?.take(4)?.toIntOrNull() == filters.year)
    }
    .filter { anime -> filters.format.isNullOrBlank() || anime.format?.contains(filters.format, true) == true }
    .filter { anime -> filters.tag.isNullOrBlank() || anime.tags.any { it.contains(filters.tag, true) } }
    .filter { anime ->
        val minimum = filters.minimumScore ?: return@filter true
        normalizedDiscoveryScore(anime, source)?.let { it >= minimum } ?: false
    }
    .let { sequence ->
        when (filters.sort) {
            DiscoverySort.SCORE, DiscoverySort.RANK -> sequence.sortedByDescending {
                normalizedDiscoveryScore(it, source) ?: Double.NEGATIVE_INFINITY
            }
            DiscoverySort.NEWEST -> sequence.sortedByDescending(RemoteAnime::airDate)
            DiscoverySort.TRENDING, DiscoverySort.POPULARITY -> sequence
        }
    }
    .toList()

private fun normalizedDiscoveryScore(anime: RemoteAnime, source: RemoteCatalogSource): Double? {
    val score = anime.score ?: return null
    return when {
        source == RemoteCatalogSource.BANGUMI || anime.importSource.equals("bangumi", true) -> score * 10.0
        score <= 10.0 -> score * 10.0
        else -> score
    }
}
