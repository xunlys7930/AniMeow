package com.animeow.app.data.remote

data class BangumiCollectionItem(
    val subjectId: Long,
    val collectionType: Int,
    val title: String,
    val originalTitle: String,
    val coverUrl: String? = null,
    val airDate: String? = null,
    val watchedEpisodes: Int = 0,
    val totalEpisodes: Int = 0,
    val subjectScore: Double? = null,
    val userRate: Int? = null,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    val updatedAt: String? = null,
    val summary: String? = null,
    val format: String? = null,
) {
    val status: String
        get() = when (collectionType) {
            2 -> "看完"
            3 -> "在看"
            5 -> "弃坑"
            else -> "未看"
        }

    val cappedWatchedEpisodes: Int
        get() = if (totalEpisodes > 0) {
            watchedEpisodes.coerceIn(0, totalEpisodes)
        } else {
            watchedEpisodes.coerceAtLeast(0)
        }

    val progressText: String
        get() = when {
            totalEpisodes > 0 -> "$cappedWatchedEpisodes/$totalEpisodes 集"
            watchedEpisodes > 0 -> "已看 ${watchedEpisodes.coerceAtLeast(0)} 集"
            else -> "集数未知"
        }

    val importedReview: String?
        get() = buildList {
            comment?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            userRate?.takeIf { it > 0 }?.let { add("Bangumi 个人评分：$it") }
        }.joinToString("\n").takeIf(String::isNotBlank)
}

data class BangumiCollectionFetchResult(
    val items: List<BangumiCollectionItem>,
    val reportedTotal: Int,
    val truncated: Boolean,
)

internal data class BangumiCollectionPage(
    val items: List<BangumiCollectionItem>,
    val total: Int,
    val limit: Int,
)

internal fun normalizeBangumiImageUrl(value: String?): String? {
    val clean = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return when {
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("http://lain.bgm.tv/") -> clean.replaceFirst("http://", "https://")
        else -> clean
    }
}
