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
    .filter { anime -> filters.format.isNullOrBlank() || (discoveryFormatMatches(anime.format, filters.format) || anime.tags.any { discoveryFormatMatches(it, filters.format) }) }
    .filter { anime -> filters.tag.isNullOrBlank() || anime.tags.any { discoveryTagMatches(it, filters.tag) } }
    .filter { anime ->
        val minimum = filters.minimumScore ?: return@filter true
        normalizedDiscoveryScore(anime, source)?.let { it >= minimum } ?: false
    }
    .let { sequence ->
        when (filters.sort) {
            DiscoverySort.SCORE -> sequence.sortedWith(
                compareByDescending<RemoteAnime> { isAirDateReleased(it.airDate) }
                    .thenByDescending { normalizedDiscoveryScore(it, source) ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.airDate.orEmpty() }
            )
            DiscoverySort.NEWEST -> sequence.sortedByDescending(RemoteAnime::airDate)
            DiscoverySort.TRENDING, DiscoverySort.POPULARITY, DiscoverySort.RANK -> sequence
        }
    }
    .toList()

internal fun isAirDateReleased(airDate: String?): Boolean {
    val date = parseServerDiscoveryAirDate(airDate) ?: return true
    return !date.isAfter(java.time.LocalDate.now())
}

private fun normalizedDiscoveryScore(anime: RemoteAnime, source: RemoteCatalogSource): Double? {
    val score = anime.score ?: return null
    return when {
        source == RemoteCatalogSource.BANGUMI || anime.importSource.equals("bangumi", true) -> score * 10.0
        score <= 10.0 -> score * 10.0
        else -> score
    }
}

internal fun normalizedDiscoveryFormat(value: String): String = when (value.trim().substringBefore(" 共").uppercase(java.util.Locale.ROOT)) {
    "剧场版", "MOVIE" -> "MOVIE"
    "WEB", "ONA" -> "ONA"
    "SP", "SPECIAL", "特别篇" -> "SPECIAL"
    else -> value.trim().substringBefore(" 共").uppercase(java.util.Locale.ROOT)
}
internal fun discoveryFormatMatches(actual: String?, selected: String): Boolean =
    actual != null && normalizedDiscoveryFormat(actual) == normalizedDiscoveryFormat(selected)

internal val discoveryAniListGenres = mapOf(
    "动作" to "Action", "战斗" to "Action", "冒险" to "Adventure", "喜剧" to "Comedy", "搞笑" to "Comedy",
    "剧情" to "Drama", "奇幻" to "Fantasy", "恐怖" to "Horror", "音乐" to "Music",
    "悬疑" to "Mystery", "推理" to "Mystery", "恋爱" to "Romance", "爱情" to "Romance", "科幻" to "Sci-Fi",
    "日常" to "Slice of Life", "运动" to "Sports", "体育" to "Sports", "机战" to "Mecha",
    "心理" to "Psychological", "超自然" to "Supernatural", "魔法少女" to "Mahou Shoujo",
)
internal val discoveryAniListTags = mapOf("校园" to "School", "治愈" to "Iyashikei", "异世界" to "Isekai", "热血" to "Shounen")
internal fun discoveryTagMatches(actual: String, selected: String): Boolean {
    val translated = discoveryAniListGenres[selected] ?: discoveryAniListTags[selected] ?: selected
    return actual.contains(selected, true) || actual.equals(translated, true)
}

/** Preserve the existing late-month quarter boundary. */
internal fun discoveryDateRange(filters: DiscoveryFilters): Pair<java.time.LocalDate, java.time.LocalDate>? {
    val year = filters.year ?: return null
    return if (filters.seasonMonth in SERVER_DISCOVERY_SEASON_MONTHS) {
        serverDiscoverySeasonStart(year, filters.seasonMonth!!) to serverDiscoveryNextSeasonStart(year, filters.seasonMonth)
    } else java.time.LocalDate.of(year, 1, 1) to java.time.LocalDate.of(year + 1, 1, 1)
}

internal val discoveryAniListSources = mapOf(
    "原创" to listOf("ORIGINAL"), "漫画改" to listOf("MANGA"),
    "小说改" to listOf("LIGHT_NOVEL", "NOVEL"), "游戏改" to listOf("VISUAL_NOVEL", "VIDEO_GAME"),
)
