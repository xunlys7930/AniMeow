package com.animeow.app.data.remote

enum class RemoteCatalogSource(
    val storageKey: String,
    val displayName: String,
) {
    BANGUMI("bangumi", "Bangumi"),
    ANILIST("anilist", "AniList"),
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
) {
    val uniqueKey: String get() = "${source.storageKey}:$id"
    val importSource: String get() = originSource?.takeIf(String::isNotBlank) ?: source.storageKey
    val importId: String get() = originId?.takeIf(String::isNotBlank) ?: id
}

data class RemoteFetchResult(
    val items: List<RemoteAnime>,
    val warnings: List<String> = emptyList(),
)
