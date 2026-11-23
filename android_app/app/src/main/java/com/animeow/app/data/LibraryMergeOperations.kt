package com.animeow.app.data

import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.local.encodeAnimeSourceReferences
import com.animeow.app.data.local.parseAnimeSourceReferences
import androidx.room.withTransaction
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.withNormalizedSubjectType
import java.time.Instant
import java.time.LocalTime
import com.animeow.app.data.diagnostics.OperationLogService

internal class LibraryMergeOperations(
    private val database: AniMeowDatabase,
    private val operationLog: OperationLogService?,
) {
    private val dao = database.libraryDao()

    suspend fun mergeAnimes(targetId: Long, sourceIds: Set<Long>): AnimeMergeResult? {
        val duplicates = sourceIds.filterNot { it == targetId }
        if (duplicates.isEmpty()) return null
        val result = database.withTransaction {
            val items = dao.getAnimes(listOf(targetId) + duplicates)
            val target = items.firstOrNull { it.id == targetId && it.deletedAt == null }
                ?: return@withTransaction null
            val sources = items.filter { it.id != targetId && it.deletedAt == null }
            require(sources.size == duplicates.size) { "所选作品已发生变化，请重新选择后合并" }
            require(sources.all { normalizeSubjectType(it.subjectType) == normalizeSubjectType(target.subjectType) }) {
                "动画和书籍不能合并为同一个条目"
            }
            val actualSourceIds = sources.map(AnimeEntity::id)
            val merged = mergeAnimeMetadata(target, sources).withNormalizedSubjectType()
            dao.updateAnime(merged)
            dao.copyAnimeTags(targetId, actualSourceIds)
            dao.copyAnimeCharacters(targetId, actualSourceIds)
            dao.fillMergedCharacterRoles(targetId, actualSourceIds)
            dao.fillMergedCharacterVoiceActors(targetId, actualSourceIds)
            dao.copyCharacterGroupWorks(targetId, actualSourceIds)
            dao.copyWatchRecords(targetId, actualSourceIds)
            dao.copyEpisodeDetails(targetId, actualSourceIds)
            mergeBookReadingData(targetId, actualSourceIds)
            dao.moveAnimesToTrash(actualSourceIds, Instant.now().toString())
            AnimeMergeResult(merged, actualSourceIds.toSet())
        }
        if (result != null) {
            operationLog?.record(
                "合并重复作品",
                "查重",
                mapOf("targetId" to targetId, "sourceCount" to result.sourceIds.size),
            )
        }
        return result
    }

    private suspend fun mergeBookReadingData(targetId: Long, sourceIds: List<Long>) {
        if (sourceIds.isEmpty()) return
        val bookDao = database.bookReadingDao()
        val states = bookDao.getStates(listOf(targetId) + sourceIds).associateBy { it.animeId }
        val sourceStates = sourceIds.mapNotNull(states::get)
        if (sourceStates.isNotEmpty()) {
            val targetState = states[targetId]
            val localWinner = (listOfNotNull(targetState) + sourceStates)
                .filter { !it.localFileUri.isNullOrBlank() || it.progressPermille > 0 || it.chapterIndex > 0 }
                .maxWithOrNull(compareBy({ !it.localFileUri.isNullOrBlank() }, { it.progressPermille }, { it.chapterIndex }))
                ?: targetState
                ?: sourceStates.first()
            val legadoWinner = (listOfNotNull(targetState) + sourceStates)
                .filter { !it.legadoBookUrl.isNullOrBlank() }
                .maxByOrNull { it.legadoChapterIndex ?: -1 }
            val mergedState = (targetState ?: localWinner).copy(
                animeId = targetId,
                localFileUri = targetState?.localFileUri?.takeIf(String::isNotBlank) ?: localWinner.localFileUri,
                localFileName = targetState?.localFileName?.takeIf(String::isNotBlank) ?: localWinner.localFileName,
                fileFormat = targetState?.fileFormat?.takeIf(String::isNotBlank) ?: localWinner.fileFormat,
                fileFingerprint = targetState?.fileFingerprint?.takeIf(String::isNotBlank) ?: localWinner.fileFingerprint,
                charsetName = targetState?.charsetName?.takeIf(String::isNotBlank) ?: localWinner.charsetName,
                chapterIndex = maxOf(targetState?.chapterIndex ?: 0, localWinner.chapterIndex),
                chapterTitle = if ((localWinner.chapterIndex) >= (targetState?.chapterIndex ?: 0)) localWinner.chapterTitle ?: targetState?.chapterTitle else targetState?.chapterTitle,
                totalChapters = maxOf(targetState?.totalChapters ?: 0, localWinner.totalChapters),
                blockIndex = if (localWinner.progressPermille > (targetState?.progressPermille ?: -1)) localWinner.blockIndex else (targetState?.blockIndex ?: 0),
                charOffset = if (localWinner.progressPermille > (targetState?.progressPermille ?: -1)) localWinner.charOffset else (targetState?.charOffset ?: 0),
                progressPermille = maxOf(targetState?.progressPermille ?: 0, localWinner.progressPermille),
                lastReadAt = listOfNotNull(targetState?.lastReadAt, localWinner.lastReadAt).maxOrNull(),
                legadoBookUrl = targetState?.legadoBookUrl?.takeIf(String::isNotBlank) ?: legadoWinner?.legadoBookUrl,
                legadoOriginName = targetState?.legadoOriginName?.takeIf(String::isNotBlank) ?: legadoWinner?.legadoOriginName,
                legadoAuthor = targetState?.legadoAuthor?.takeIf(String::isNotBlank) ?: legadoWinner?.legadoAuthor,
                legadoChapterIndex = listOfNotNull(targetState?.legadoChapterIndex, legadoWinner?.legadoChapterIndex).maxOrNull(),
                legadoChapterTitle = legadoWinner?.legadoChapterTitle ?: targetState?.legadoChapterTitle,
                legadoTotalChapters = listOfNotNull(targetState?.legadoTotalChapters, legadoWinner?.legadoTotalChapters).maxOrNull(),
                legadoLastReadAt = listOfNotNull(targetState?.legadoLastReadAt, legadoWinner?.legadoLastReadAt).maxOrNull(),
                syncProgressToMain = targetState?.syncProgressToMain ?: localWinner.syncProgressToMain,
                updatedAt = Instant.now().toString(),
            )
            bookDao.upsertState(mergedState)
        }
        val existingBookmarks = bookDao.getBookmarks(targetId)
        val existingKeys = existingBookmarks.map { "${it.chapterIndex}|${it.blockIndex}|${it.charOffset}" }.toMutableSet()
        val copiedBookmarks = bookDao.getBookmarksForAnimes(sourceIds).mapNotNull { bookmark ->
            val key = "${bookmark.chapterIndex}|${bookmark.blockIndex}|${bookmark.charOffset}"
            if (!existingKeys.add(key)) null else bookmark.copy(id = 0, animeId = targetId)
        }
        if (copiedBookmarks.isNotEmpty()) {
            bookDao.insertBookmarks(copiedBookmarks)
        }
    }

}

internal fun mergeAnimeMetadata(
    target: AnimeEntity,
    sources: List<AnimeEntity>,
): AnimeEntity {
    val all = listOf(target) + sources
    require(all.map { normalizeSubjectType(it.subjectType) }.distinct().size == 1) { "动画和书籍不能合并为同一个条目" }
    val progressWinner = all.maxWithOrNull(
        compareBy<AnimeEntity>(AnimeEntity::watchedEpisodes)
            .thenBy { mergeStatusPriority(it.status) }
            .thenBy(AnimeEntity::totalEpisodes),
    ) ?: target
    // Sources retain their identity in the trash, so only the target may own its unique DB key.
    // All other identities are stored as references for search, re-import detection and role lookup.
    val externalIdentity = target.takeIf(AnimeEntity::hasCompleteExternalIdentity)
    val externalUrl = externalIdentity?.let { owner ->
        owner.externalUrl?.trim()?.takeIf(String::isNotEmpty)
            ?: all.firstNotNullOfOrNull { candidate ->
                candidate.takeIf { it.hasSameExternalIdentity(owner) }
                    ?.externalUrl
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }
    }
    val targetGrade = normalizeAnimeRatingGrade(target.ratingGrade)
    val targetScore = target.rating?.takeIf { it in 1..100 }
    val sourceGrade = sources.firstNotNullOfOrNull { normalizeAnimeRatingGrade(it.ratingGrade) }
    val mergedGrade = when {
        targetGrade != null -> targetGrade
        targetScore != null -> null
        else -> sourceGrade
    }
    val mergedScore = when {
        targetGrade != null -> null
        targetScore != null -> targetScore
        sourceGrade != null -> null
        else -> sources.mapNotNull(AnimeEntity::rating).filter { it in 1..100 }.maxOrNull()
    }
    val reminder = all.firstNotNullOfOrNull(AnimeEntity::completeReminder)
    fun text(selector: (AnimeEntity) -> String?): String? =
        all.asSequence().mapNotNull(selector).firstOrNull { it.isNotBlank() }
    fun earliest(selector: (AnimeEntity) -> String?): String? =
        all.mapNotNull(selector).filter(String::isNotBlank).minOrNull()
    fun latest(selector: (AnimeEntity) -> String?): String? =
        all.mapNotNull(selector).filter(String::isNotBlank).maxOrNull()

    return normalizeCompletionProgress(target.copy(
        coverUrl = text(AnimeEntity::coverUrl),
        rating = mergedScore,
        ratingGrade = mergedGrade,
        funRatingTier = text(AnimeEntity::funRatingTier),
        review = mergeReviewText(all),
        memo = mergeMemoText(all),
        seriesId = target.seriesId ?: all.firstNotNullOfOrNull(AnimeEntity::seriesId),
        seriesRole = all.firstOrNull { it.seriesId != null && it.seriesId == (target.seriesId ?: all.firstNotNullOfOrNull(AnimeEntity::seriesId)) && it.seriesRole != null }?.seriesRole,
        status = progressWinner.status,
        createdAt = earliest(AnimeEntity::createdAt),
        airDate = earliest(AnimeEntity::airDate),
        studio = text(AnimeEntity::studio),
        voiceActors = text(AnimeEntity::voiceActors),
        coverOrientation = text(AnimeEntity::coverOrientation),
        sourceRatingJson = text(AnimeEntity::sourceRatingJson),
        bookReadVolumes = target.bookReadVolumes ?: sources.firstNotNullOfOrNull { it.bookReadVolumes },
        bookReadChapters = target.bookReadChapters ?: sources.firstNotNullOfOrNull { it.bookReadChapters },
        watchStartDate = earliest(AnimeEntity::watchStartDate),
        watchFinishDate = latest(AnimeEntity::watchFinishDate),
        watchedEpisodes = all.maxOf(AnimeEntity::watchedEpisodes),
        totalEpisodes = all.maxOf(AnimeEntity::totalEpisodes),
        tvEpisodes = all.maxOf(AnimeEntity::tvEpisodes),
        spEpisodes = all.maxOf(AnimeEntity::spEpisodes),
        reminderDay = reminder?.first,
        reminderTime = reminder?.second,
        broadcastDay = all.firstOrNull { it.broadcastDay in 1..7 }?.broadcastDay,
        broadcastTime = all.firstOrNull { it.broadcastDay in 1..7 }?.broadcastTime,
        pinnedAt = all.mapNotNull(AnimeEntity::pinnedAt).maxOrNull(),
        externalSource = externalIdentity?.externalSource ?: target.externalSource,
        externalId = externalIdentity?.externalId ?: target.externalId,
        externalUrl = externalUrl ?: if (externalIdentity == null) target.externalUrl else null,
        mergedSourcesJson = encodeAnimeSourceReferences(
            parseAnimeSourceReferences(target.mergedSourcesJson) + sources.flatMap { it.sourceReferences() },
        ),
        originalTitle = text(AnimeEntity::originalTitle),
        synopsis = all.mapNotNull(AnimeEntity::synopsis).filter(String::isNotBlank).maxByOrNull(String::length),
        mediaFormat = text(AnimeEntity::mediaFormat),
        deletedAt = null,
    ))
}

private fun mergeReviewText(items: List<AnimeEntity>): String? {
    val collected = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    items.forEach { anime ->
        val review = anime.review?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
        if (!seen.add(review)) return@forEach
        collected += if (collected.isEmpty()) review else "《${anime.title}》的评价\n$review"
    }
    return collected.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

private fun mergeMemoText(items: List<AnimeEntity>): String? {
    val collected = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    items.forEach { anime ->
        val memo = com.animeow.app.data.local.normalizeAnimeMemo(anime.memo) ?: return@forEach
        if (!seen.add(memo)) return@forEach
        collected += if (collected.isEmpty()) memo else "《${anime.title}》的备注\n$memo"
    }
    return com.animeow.app.data.local.normalizeAnimeMemo(collected.takeIf { it.isNotEmpty() }?.joinToString("\n\n"))
}

private fun AnimeEntity.hasSameExternalIdentity(other: AnimeEntity): Boolean =
    externalSource?.trim()?.lowercase(java.util.Locale.ROOT) ==
        other.externalSource?.trim()?.lowercase(java.util.Locale.ROOT) &&
        externalId?.trim()?.lowercase(java.util.Locale.ROOT) ==
        other.externalId?.trim()?.lowercase(java.util.Locale.ROOT)

private fun AnimeEntity.completeReminder(): Pair<Int, String>? {
    val day = reminderDay?.takeIf { it in 1..7 } ?: return null
    val time = reminderTime?.trim()?.let { value ->
        runCatching { LocalTime.parse(value) }.getOrNull()
    } ?: return null
    return day to time.toString()
}

private fun mergeStatusPriority(status: String): Int = when (status) {
    "看完" -> 5
    "在看" -> 4
    "弃坑" -> 3
    "想看" -> 2
    "未看" -> 1
    else -> 2
}
