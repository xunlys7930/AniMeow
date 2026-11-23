package com.animeow.app.data.remote

enum class RemoteCatalogSource(
    val storageKey: String,
    val displayName: String,
) {
    BANGUMI("bangumi", "Bangumi"),
    ANILIST("anilist", "AniList"),
    KITSU("kitsu", "Kitsu"),
    TVMAZE("tvmaze", "TVmaze"),
    SERVER("server", "资料库服务器"),
}

enum class DiscoverySourceFilter(val displayName: String) {
    ALL("全部来源"),
    BANGUMI("Bangumi"),
    ANILIST("AniList"),
    SERVER("服务器"),
}

enum class DiscoveryFeedMode(val displayName: String) {
    FEATURED("推荐"),
    RANKING("排行榜"),
    SEASONAL("新番总览"),
}

enum class DiscoverySort(val displayName: String) {
    TRENDING("趋势"),
    RANK("综合排名"),
    SCORE("评分"),
    NEWEST("最新放送"),
    POPULARITY("人气"),
}

data class DiscoveryFilters(
    val year: Int? = null,
    val seasonMonth: Int? = null,
    val format: String? = null,
    val tag: String? = null,
    val minimumScore: Int? = null,
    val sort: DiscoverySort = DiscoverySort.TRENDING,
) {
    val activeCount: Int
        get() = listOf(year, seasonMonth, format, tag, minimumScore).count { it != null }
}

data class RemoteAnime(
    val source: RemoteCatalogSource,
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val coverUrl: String? = null,
    val summary: String? = null,
    val score: Double? = null,
    val ratingCount: Int? = null,
    val sourceRank: Int? = null,
    val popularity: Int? = null,
    val episodes: Int? = null,
    val airDate: String? = null,
    val studio: String? = null,
    val tags: List<String> = emptyList(),
    val format: String? = null,
    val subjectType: String = "anime",
    val siteUrl: String? = null,
    val originSource: String? = null,
    val originId: String? = null,
    val broadcastDay: Int? = null,
    val catalogUpdatedAt: String? = null,
    val cacheStale: Boolean = false,
    val cacheUpdatedAt: String? = null,
    val coverUrls: Map<String, String> = emptyMap(),
    val retrievalRoute: String? = null,
    val metadataReferences: Set<MetadataReference> = emptySet(),
    val localizedTitles: Map<String, String> = emptyMap(),
    val localizedSummaries: Map<String, String> = emptyMap(),
    val titleLanguage: String? = null,
    val summaryLanguage: String? = null,
    val ratingDistribution: Map<Int, Int> = emptyMap(),
) {
    val uniqueKey: String get() = "${source.storageKey}:$id"
    val importSource: String get() = originSource?.takeIf(String::isNotBlank) ?: source.storageKey
    val importId: String get() = originId?.takeIf(String::isNotBlank) ?: id
    val sourceLabel: String get() {
        val additions = metadataReferences.mapNotNull { reference ->
            RemoteCatalogSource.entries.find { it.storageKey == reference.source }
        }.filter { it != source }.map { it.displayName }.distinct()
        return (listOf(source.displayName) + additions).joinToString(" + ") + if (retrievalRoute == "proxy") " · 代理" else ""
    }
}

data class MetadataReference(val source: String, val id: String)
data class MetadataAttempt(val source: String, val elapsedMillis: Long, val result: String)

data class RemoteFetchResult(
    val items: List<RemoteAnime>,
    val warnings: List<String> = emptyList(),
    val attempts: List<MetadataAttempt> = emptyList(),
    val page: Int = 1,
    val totalPages: Int? = null,
    val hasMore: Boolean = false,
    val totalCount: Int = 0,
    val totalCountExact: Boolean = false,
    val resultScope: String? = null,
)
