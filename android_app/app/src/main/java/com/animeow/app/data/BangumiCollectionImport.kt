package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.remote.BangumiCollectionItem
import java.util.Locale
import kotlin.math.roundToInt

enum class BangumiImportConflictStrategy(
    val displayName: String,
    val description: String,
) {
    SKIP(
        displayName = "跳过已有",
        description = "只新增资料库中不存在的作品，现有条目保持不变",
    ),
    MERGE(
        displayName = "安全合并",
        description = "合并状态、进度与标签，同时保留本地封面和自定义内容",
    ),
}

enum class BangumiImportMatchKind(val displayName: String) {
    NEW("新增"),
    EXTERNAL_ID("Bangumi ID 已存在"),
    TITLE("同名条目"),
    TRASHED("回收站中"),
}

data class BangumiImportCandidate(
    val item: BangumiCollectionItem,
    val matchKind: BangumiImportMatchKind,
    val existingAnimeId: Long? = null,
    val existingTitle: String? = null,
) {
    val isDuplicate: Boolean get() = matchKind != BangumiImportMatchKind.NEW
}

data class BangumiImportSummary(
    val addedCount: Int,
    val mergedCount: Int,
    val restoredCount: Int,
    val skippedCount: Int,
    val importedTagCount: Int,
) {
    val changedCount: Int get() = addedCount + mergedCount + restoredCount
}

internal fun buildBangumiImportCandidates(
    items: List<BangumiCollectionItem>,
    existingAnimes: List<AnimeEntity>,
): List<BangumiImportCandidate> {
    val externalMatches = existingAnimes.asSequence()
        .filter { it.externalSource.equals(BANGUMI_SOURCE, ignoreCase = true) && !it.externalId.isNullOrBlank() }
        .groupBy { it.externalId.orEmpty() }
        .mapValues { (_, matches) ->
            matches.filter { it.deletedAt == null }.maxByOrNull(AnimeEntity::id)
                ?: matches.maxBy(AnimeEntity::id)
        }
    val activeTitleMatches = buildMap<String, AnimeEntity> {
        existingAnimes.asSequence().filter { it.deletedAt == null }.sortedByDescending(AnimeEntity::id).forEach { anime ->
            animeMatchKeys(anime).forEach { key -> putIfAbsent(key, anime) }
        }
    }

    return items.distinctBy(BangumiCollectionItem::subjectId).map { item ->
        val external = externalMatches[item.subjectId.toString()]
        if (external != null) {
            BangumiImportCandidate(
                item = item,
                matchKind = if (external.deletedAt == null) {
                    BangumiImportMatchKind.EXTERNAL_ID
                } else {
                    BangumiImportMatchKind.TRASHED
                },
                existingAnimeId = external.id,
                existingTitle = external.title,
            )
        } else {
            val titleMatch = collectionMatchKeys(item).firstNotNullOfOrNull(activeTitleMatches::get)
            BangumiImportCandidate(
                item = item,
                matchKind = if (titleMatch == null) BangumiImportMatchKind.NEW else BangumiImportMatchKind.TITLE,
                existingAnimeId = titleMatch?.id,
                existingTitle = titleMatch?.title,
            )
        }
    }
}

internal fun BangumiCollectionItem.toAnimeEntity(createdAt: String): AnimeEntity = normalizeCompletionProgress(AnimeEntity(
    title = title,
    coverUrl = coverUrl,
    status = status,
    rating = subjectScore?.times(10)?.roundToInt()?.coerceIn(0, 100),
    review = importedReview,
    createdAt = createdAt,
    airDate = airDate,
    watchedEpisodes = cappedWatchedEpisodes,
    totalEpisodes = totalEpisodes,
    tvEpisodes = totalEpisodes,
    subjectType = "anime",
    externalSource = BANGUMI_SOURCE,
    externalId = subjectId.toString(),
    externalUrl = "https://bgm.tv/subject/$subjectId",
    originalTitle = originalTitle.takeUnless { it.equals(title, ignoreCase = true) },
    synopsis = summary,
    mediaFormat = format,
))

internal fun mergeBangumiCollection(
    existing: AnimeEntity,
    item: BangumiCollectionItem,
    now: String,
): AnimeEntity {
    val imported = item.toAnimeEntity(existing.createdAt ?: now)
    val importedHasAtLeastAsMuchProgress = imported.watchedEpisodes >= existing.watchedEpisodes
    val mergedStatus = when {
        imported.watchedEpisodes > existing.watchedEpisodes -> imported.status
        imported.watchedEpisodes < existing.watchedEpisodes -> existing.status
        statusPriority(imported.status) > statusPriority(existing.status) -> imported.status
        else -> existing.status
    }
    val sameBangumiItem = existing.externalSource.equals(BANGUMI_SOURCE, ignoreCase = true) &&
        existing.externalId == item.subjectId.toString()
    val existingHasCompleteIdentity = !existing.externalSource.isNullOrBlank() && !existing.externalId.isNullOrBlank()
    val useImportedIdentity = !existingHasCompleteIdentity
    val existingGrade = normalizeAnimeRatingGrade(existing.ratingGrade)

    return normalizeCompletionProgress(existing.copy(
        coverUrl = existing.coverUrl?.takeIf(String::isNotBlank) ?: imported.coverUrl,
        status = if (importedHasAtLeastAsMuchProgress) mergedStatus else existing.status,
        rating = if (existingGrade != null) {
            null
        } else if (sameBangumiItem) {
            imported.rating ?: existing.rating
        } else {
            existing.rating ?: imported.rating
        },
        ratingGrade = existingGrade,
        review = mergeBangumiReview(existing.review, imported.review),
        createdAt = existing.createdAt ?: now,
        airDate = existing.airDate?.takeIf(String::isNotBlank) ?: imported.airDate,
        watchedEpisodes = maxOf(existing.watchedEpisodes, imported.watchedEpisodes),
        totalEpisodes = maxOf(existing.totalEpisodes, imported.totalEpisodes),
        tvEpisodes = maxOf(existing.tvEpisodes, imported.tvEpisodes),
        subjectType = "anime",
        deletedAt = null,
        externalSource = if (useImportedIdentity) imported.externalSource else existing.externalSource,
        externalId = if (useImportedIdentity) imported.externalId else existing.externalId,
        externalUrl = when {
            useImportedIdentity -> imported.externalUrl
            sameBangumiItem && existing.externalUrl.isNullOrBlank() -> imported.externalUrl
            else -> existing.externalUrl
        },
        originalTitle = existing.originalTitle?.takeIf(String::isNotBlank) ?: imported.originalTitle,
        synopsis = existing.synopsis?.takeIf(String::isNotBlank) ?: imported.synopsis,
        mediaFormat = existing.mediaFormat?.takeIf(String::isNotBlank) ?: imported.mediaFormat,
    ))
}

internal fun mergeBangumiReview(existing: String?, imported: String?): String? {
    val current = existing?.trim().orEmpty()
    val incoming = imported?.trim().orEmpty()
    return when {
        current.isEmpty() -> incoming.takeIf(String::isNotEmpty)
        incoming.isEmpty() || current == incoming || current.contains(incoming) -> current
        else -> "$current\n$incoming"
    }
}

internal fun collectionMatchKeys(item: BangumiCollectionItem): List<String> = listOf(
    item.title,
    item.originalTitle,
).map(::normalizedBangumiTitle).filter(String::isNotEmpty).distinct()

private fun animeMatchKeys(anime: AnimeEntity): List<String> = listOfNotNull(
    anime.title,
    anime.originalTitle,
).map(::normalizedBangumiTitle).filter(String::isNotEmpty).distinct()

internal fun normalizedBangumiTitle(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\s　]+"), "")

private fun statusPriority(status: String): Int = when (status) {
    "看完" -> 5
    "在看" -> 4
    "弃坑" -> 3
    "想看" -> 2
    "未看" -> 1
    else -> 0
}

internal const val BANGUMI_SOURCE = "bangumi"
