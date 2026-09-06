package com.animeow.app.data

import androidx.room.withTransaction
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.withNormalizedSubjectType
import com.animeow.app.data.local.simplifyTagName
import com.animeow.app.data.local.normalizeTagKey
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.data.remote.RemoteCatalogSource
import com.animeow.app.data.remote.ServerCatalogService
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalTime
import com.animeow.app.data.diagnostics.OperationLogService

data class ProgressChange(
    val animeId: Long,
    val previous: Int,
    val current: Int,
    val previousStatus: String? = null,
    val currentStatus: String? = null,
    val previousFinishDate: String? = null,
    val currentFinishDate: String? = null,
    val watchRecordId: Long? = null,
    val previousStartDate: String? = null,
    val currentStartDate: String? = null,
)

data class StatusChange(
    val animeId: Long,
    val previous: String,
    val current: String,
    val previousStartDate: String? = null,
    val previousFinishDate: String? = null,
    val currentStartDate: String? = null,
    val currentFinishDate: String? = null,
)

sealed interface RemoteImportResult {
    data class Added(val animeId: Long) : RemoteImportResult
    data class Duplicate(val existing: AnimeEntity) : RemoteImportResult
}

data class CoverSyncSummary(
    val success: Int,
    val skipped: Int,
    val failed: Int,
)

data class GroupedAnimeSaveResult(
    val animeId: Long,
    val seriesId: Long,
)

data class AnimeMergeResult(
    val target: AnimeEntity,
    val sourceIds: Set<Long>,
)

data class DeletedTagSnapshot(
    val name: String,
    val color: Long?,
    val blocked: Boolean,
    val animeIds: List<Long>,
)

data class RemoteAnimeRatingMerge(
    val rating: Int?,
    val ratingGrade: String?,
)

data class RemoteMetadataFields(
    val cover: Boolean = true,
    val rating: Boolean = true,
    val episodes: Boolean = true,
    val studio: Boolean = true,
    val synopsis: Boolean = true,
    val airDate: Boolean = true,
    val externalLink: Boolean = true,
    val tags: Boolean = false,
) {
    val hasSelection: Boolean
        get() = cover || rating || episodes || studio || synopsis || airDate || externalLink || tags
}

class LibraryRepository(
    private val database: AniMeowDatabase,
    private val operationLog: OperationLogService? = null,
) {
    private val dao = database.libraryDao()
    private val serverCatalog = ServerCatalogService()

    fun observeAnimes(): Flow<List<AnimeEntity>> = dao.observeAnimes()

    fun observeDeletedAnimes(): Flow<List<AnimeEntity>> = dao.observeDeletedAnimes()

    fun observeAnime(animeId: Long): Flow<AnimeEntity?> = dao.observeAnime(animeId)

    fun observeTags(): Flow<List<TagEntity>> = dao.observeTags()

    fun observeAllTags(): Flow<List<TagEntity>> = dao.observeAllTags()

    fun observeAnimeTags(): Flow<List<AnimeTagEntity>> = dao.observeAnimeTags()

    fun observeWatchStatuses(): Flow<List<WatchStatusEntity>> = dao.observeWatchStatuses()

    fun observeWatchRecords(): Flow<List<WatchRecordEntity>> = dao.observeWatchRecords()

    fun observeSeries(): Flow<List<SeriesEntity>> = dao.observeSeries()

    suspend fun getActiveAnimesSnapshot(): List<AnimeEntity> = dao.getActiveAnimes()

    suspend fun getWatchRecordsSnapshot(): List<WatchRecordEntity> = dao.getWatchRecords()

    suspend fun getTagIdsForAnime(animeId: Long): List<Long> = dao.getTagIdsForAnime(animeId)

    suspend fun applyRemoteMetadata(
        animeId: Long,
        remote: RemoteAnime,
        fields: RemoteMetadataFields,
        overwriteExisting: Boolean,
    ): Boolean {
        if (!fields.hasSelection) return false
        val current = dao.getAnime(animeId) ?: return false
        val remoteRating = remote.score
            ?.let { score -> if (score <= 10.0) score * 10 else score }
            ?.toInt()
            ?.takeIf { it in 1..100 }
        val ratingMerge = mergeRemoteAnimeRating(
            currentRating = current.rating,
            currentGrade = current.ratingGrade,
            remoteRating = remoteRating,
            overwriteExisting = overwriteExisting,
        )
        val next = normalizeCompletionProgress(current.copy(
            coverUrl = if (fields.cover) mergeRemoteText(current.coverUrl, remote.coverUrl, overwriteExisting) else current.coverUrl,
            rating = if (fields.rating) ratingMerge.rating else current.rating,
            ratingGrade = if (fields.rating) ratingMerge.ratingGrade else current.ratingGrade,
            totalEpisodes = if (fields.episodes) {
                mergeRemoteNumber(
                    current.totalEpisodes.takeIf { it > 0 },
                    remote.episodes?.takeIf { it > 0 },
                    overwriteExisting,
                ) ?: current.totalEpisodes
            } else {
                current.totalEpisodes
            },
            studio = if (fields.studio) mergeRemoteText(current.studio, remote.studio, overwriteExisting) else current.studio,
            synopsis = if (fields.synopsis) mergeRemoteText(current.synopsis, remote.summary, overwriteExisting) else current.synopsis,
            airDate = if (fields.airDate) mergeRemoteText(current.airDate, remote.airDate, overwriteExisting) else current.airDate,
            externalSource = if (fields.externalLink) {
                mergeRemoteText(current.externalSource, remote.importSource, overwriteExisting)
            } else {
                current.externalSource
            },
            externalId = if (fields.externalLink) {
                mergeRemoteText(current.externalId, remote.importId, overwriteExisting)
            } else {
                current.externalId
            },
            externalUrl = if (fields.externalLink) {
                mergeRemoteText(current.externalUrl, remote.siteUrl, overwriteExisting)
            } else {
                current.externalUrl
            },
            originalTitle = if (fields.externalLink) {
                mergeRemoteText(current.originalTitle, remote.originalTitle, overwriteExisting)
            } else {
                current.originalTitle
            },
            mediaFormat = if (fields.externalLink) {
                mergeRemoteText(current.mediaFormat, remote.format, overwriteExisting)
            } else {
                current.mediaFormat
            },
        ).withNormalizedSubjectType())
        if (next == current && !fields.tags) return false
        if (next != current) {
            dao.updateAnime(next)
        }
        // 标签同步：从云端匹配结果中同步标签到本地
        var tagsChanged = false
        if (fields.tags && remote.tags.isNotEmpty()) {
            val existingTags = dao.getAllTags().associateBy { normalizeTagKey(it.name) }.toMutableMap()
            val remoteTagIds = remote.tags.mapNotNull { rawName ->
                val name = simplifyTagName(rawName)
                if (name.isEmpty()) return@mapNotNull null
                val key = normalizeTagKey(name)
                existingTags[key]?.let { tag ->
                    if (tag.blocked) dao.setTagBlocked(tag.id, false)
                    tag.id
                } ?: dao.insertTag(TagEntity(name = name)).also { id ->
                    existingTags[key] = TagEntity(id = id, name = name)
                }
            }.distinct()
            if (overwriteExisting) {
                // 覆盖模式：先清除旧标签，再添加新标签
                dao.clearAnimeTagsForAnime(animeId)
                if (remoteTagIds.isNotEmpty()) {
                    dao.insertAnimeTags(remoteTagIds.map { tagId -> AnimeTagEntity(animeId, tagId) })
                    tagsChanged = true
                }
            } else {
                // 合并模式：只添加不存在的标签
                val currentTagIds = dao.getAnimeTagsByAnimeId(animeId).map { it.tagId }.toSet()
                val newTagIds = remoteTagIds.filterNot { it in currentTagIds }
                if (newTagIds.isNotEmpty()) {
                    dao.insertAnimeTags(newTagIds.map { tagId -> AnimeTagEntity(animeId, tagId) })
                    tagsChanged = true
                }
            }
        }
        if (next == current && !tagsChanged) return false
        operationLog?.record(
            "批量联网补全资料",
            "首页",
            mapOf(
                "animeId" to animeId,
                "source" to remote.importSource,
                "overwrite" to overwriteExisting,
            ),
        )
        return true
    }

    /**
     * Fills only currently blank calendar dates. Existing user-entered values are never
     * overwritten, which keeps automatic repair safe to retry.
     */
    suspend fun fillMissingCalendarDates(
        animeId: Long,
        airDate: String? = null,
        watchStartDate: String? = null,
        watchFinishDate: String? = null,
    ): Int = database.withTransaction {
        val anime = dao.getAnime(animeId) ?: return@withTransaction 0
        val nextAirDate = anime.airDate?.takeIf(String::isNotBlank) ?: airDate?.takeIf(String::isNotBlank)
        val nextStartDate = anime.watchStartDate?.takeIf(String::isNotBlank) ?: watchStartDate?.takeIf(String::isNotBlank)
        val nextFinishDate = anime.watchFinishDate?.takeIf(String::isNotBlank) ?: watchFinishDate?.takeIf(String::isNotBlank)
        val changed = listOf(
            anime.airDate to nextAirDate,
            anime.watchStartDate to nextStartDate,
            anime.watchFinishDate to nextFinishDate,
        ).count { (before, after) -> before.isNullOrBlank() && !after.isNullOrBlank() }
        if (changed > 0) {
            dao.updateAnime(
                anime.copy(
                    airDate = nextAirDate,
                    watchStartDate = nextStartDate,
                    watchFinishDate = nextFinishDate,
                ),
            )
            operationLog?.record(
                "补全日历日期",
                "日历",
                mapOf("animeId" to animeId, "fieldCount" to changed),
            )
        }
        changed
    }

    suspend fun saveAnime(anime: AnimeEntity, tagIds: Set<Long>): Long =
        database.withTransaction { saveAnimeRecord(anime, tagIds) }

    suspend fun importBroadcastSchedules(items: List<RemoteAnime>): BroadcastImportSummary = database.withTransaction {
        var added = 0
        var updated = 0
        var skipped = 0
        val library = dao.getActiveAnimes().toMutableList()
        items.distinctBy { "${it.importSource}:${it.importId}" }.forEach { remote ->
            val day = remote.broadcastDay?.takeIf { it in 1..7 }
            val match = matchBroadcastAnime(remote, library)
            if (day == null || match.ambiguous || normalizeSubjectType(remote.subjectType) != "anime") {
                skipped += 1
                return@forEach
            }
            var created = false
            val anime = match.anime ?: when (val result = importRemoteAnime(remote, forceDuplicate = true)) {
                is RemoteImportResult.Added -> {
                    created = true
                    dao.getAnime(result.animeId)
                }
                is RemoteImportResult.Duplicate -> result.existing
            }
            if (anime == null || anime.deletedAt != null || anime.broadcastDay in 1..7) {
                skipped += 1
                return@forEach
            }
            val next = anime.copy(broadcastDay = day)
            dao.updateAnime(next)
            library.removeAll { it.id == next.id }
            library.add(next)
            if (created) added += 1 else updated += 1
        }
        BroadcastImportSummary(added, updated, skipped)
    }

    suspend fun updateBroadcastSchedule(animeId: Long, day: Int?, time: String?) = database.withTransaction {
        require(day == null || day in 1..7) { "请选择有效的更新星期" }
        val normalizedTime = time?.trim()?.takeIf(String::isNotEmpty)?.let {
            require(Regex("\\d{2}:\\d{2}").matches(it)) { "时间格式应为 HH:mm" }
            LocalTime.parse(it).toString()
        }
        val anime = dao.getAnime(animeId)?.takeIf { it.deletedAt == null } ?: error("作品已不存在")
        dao.updateAnime(anime.copy(broadcastDay = day, broadcastTime = normalizedTime.takeIf { day != null }))
    }

    suspend fun saveAnimeAndGroupDuplicates(
        anime: AnimeEntity,
        tagIds: Set<Long>,
        duplicateAnimeIds: Set<Long>,
        existingSeriesId: Long?,
        seriesName: String,
    ): GroupedAnimeSaveResult = database.withTransaction {
        val seriesId = existingSeriesId ?: dao.insertSeriesItem(
            SeriesEntity(
                name = seriesName.trim().ifBlank { "未命名系列" },
                createdAt = Instant.now().toString(),
            ),
        )
        if (duplicateAnimeIds.isNotEmpty()) {
            dao.assignSeriesToAnimes(duplicateAnimeIds.toList(), seriesId)
        }
        GroupedAnimeSaveResult(
            animeId = saveAnimeRecord(anime.copy(seriesId = seriesId), tagIds),
            seriesId = seriesId,
        )
    }

    private suspend fun saveAnimeRecord(anime: AnimeEntity, tagIds: Set<Long>): Long {
        val normalizedAnime = normalizeCompletionProgress(anime.withNormalizedSubjectType())
        val id = if (normalizedAnime.id > 0L) {
            requireNotNull(dao.getAnime(normalizedAnime.id)) { "要编辑的作品已不存在" }
            dao.updateAnime(normalizedAnime)
            normalizedAnime.id
        } else {
            dao.insertAnime(normalizedAnime)
        }
        dao.clearAnimeTagsForAnime(id)
        if (tagIds.isNotEmpty()) {
            dao.insertAnimeTags(tagIds.map { tagId -> AnimeTagEntity(id, tagId) })
        }
        operationLog?.record("保存作品", "资料库", mapOf("animeId" to id, "tagCount" to tagIds.size))
        return id
    }

    suspend fun deleteAnime(animeId: Long): AnimeEntity? {
        val deletedAt = Instant.now().toString()
        val moved = database.withTransaction {
            val current = dao.getAnime(animeId)?.takeIf { it.deletedAt == null }
                ?: return@withTransaction null
            dao.moveAnimeToTrash(animeId, deletedAt)
            current.copy(deletedAt = deletedAt)
        }
        if (moved != null) {
            operationLog?.record("移入回收站", "资料库", mapOf("animeId" to animeId))
        }
        return moved
    }

    suspend fun updateStatuses(animeIds: Set<Long>, status: String) {
        if (animeIds.isEmpty()) return
        database.withTransaction {
            dao.getAnimes(animeIds.toList()).filter { it.deletedAt == null }.forEach { anime ->
                dao.updateAnime(anime.withWatchStatusDates(status))
            }
        }
        operationLog?.record("批量修改状态", "资料库", mapOf("count" to animeIds.size, "status" to status))
    }

    suspend fun cycleStatus(animeId: Long): StatusChange? = database.withTransaction {
        val anime = dao.getAnime(animeId) ?: return@withTransaction null
        val statuses = dao.getWatchStatuses().map { it.name }.filter(String::isNotBlank)
        if (statuses.isEmpty()) return@withTransaction null
        val index = statuses.indexOf(anime.status)
        val next = statuses[(index + 1).mod(statuses.size)]
        val updated = anime.withWatchStatusDates(next)
        dao.updateAnime(updated)
        StatusChange(animeId, anime.status, next, anime.watchStartDate, anime.watchFinishDate, updated.watchStartDate, updated.watchFinishDate)
    }

    suspend fun restoreStatus(change: StatusChange) {
        database.withTransaction {
            val anime = dao.getAnime(change.animeId) ?: return@withTransaction
            dao.updateAnime(anime.copy(
                status = change.previous,
                watchStartDate = if (anime.watchStartDate == change.currentStartDate) change.previousStartDate else anime.watchStartDate,
                watchFinishDate = if (anime.watchFinishDate == change.currentFinishDate) change.previousFinishDate else anime.watchFinishDate,
            ))
        }
    }

    suspend fun deleteAnimes(animeIds: Set<Long>): Set<Long> {
        if (animeIds.isEmpty()) return emptySet()
        val deletedAt = Instant.now().toString()
        val movedIds = database.withTransaction {
            val activeIds = dao.getAnimes(animeIds.toList())
                .filter { it.deletedAt == null }
                .mapTo(linkedSetOf(), AnimeEntity::id)
            if (activeIds.isNotEmpty()) {
                dao.moveAnimesToTrash(activeIds.toList(), deletedAt)
            }
            activeIds
        }
        if (movedIds.isNotEmpty()) {
            operationLog?.record("批量移入回收站", "资料库", mapOf("count" to movedIds.size))
        }
        return movedIds
    }

    suspend fun restoreAnime(animeId: Long): AnimeEntity? {
        val restored = database.withTransaction {
            val current = dao.getAnime(animeId) ?: return@withTransaction null
            if (current.deletedAt == null) return@withTransaction current
            dao.restoreAnime(animeId)
            current.copy(deletedAt = null)
        }
        if (restored != null) {
            operationLog?.record("恢复作品", "回收站", mapOf("animeId" to animeId))
        }
        return restored
    }

    suspend fun permanentlyDeleteAnime(animeId: Long): AnimeEntity? {
        val deleted = database.withTransaction {
            val current = dao.getAnime(animeId) ?: return@withTransaction null
            dao.clearAnimeTagsForAnime(animeId)
            dao.clearWatchRecordsForAnime(animeId)
            dao.clearAnimeCharactersForAnime(animeId)
            dao.clearCharacterGroupWorksForAnime(animeId)
            dao.deleteAnime(animeId)
            current
        }
        if (deleted != null) {
            operationLog?.record("永久删除作品", "回收站", mapOf("animeId" to animeId))
        }
        return deleted
    }

    suspend fun emptyTrash(): Set<Long> {
        val deletedIds = database.withTransaction {
            val ids = dao.getAllAnimesIncludingDeleted()
                .filter { it.deletedAt != null }
                .mapTo(linkedSetOf(), AnimeEntity::id)
            if (ids.isEmpty()) return@withTransaction emptySet()
            dao.clearAnimeTagsForTrash()
            dao.clearWatchRecordsForTrash()
            dao.clearAnimeCharactersForTrash()
            dao.clearCharacterGroupWorksForTrash()
            dao.deleteTrashAnimes()
            ids
        }
        if (deletedIds.isNotEmpty()) {
            operationLog?.record("清空回收站", "回收站", mapOf("count" to deletedIds.size))
        }
        return deletedIds
    }

    suspend fun findTrashedByExternalId(source: String, externalId: String): AnimeEntity? {
        val match = dao.getAnimeByExternalIdIncludingDeleted(source, externalId) ?: return null
        return if (match.deletedAt != null) match else null
    }

    suspend fun swapSynopsisAndReview(animeId: Long) {
        dao.swapSynopsisAndReview(animeId)
        operationLog?.record("互换简介与评价", "资料库", mapOf("animeId" to animeId))
    }

    suspend fun swapSynopsisAndReviewBatch(animeIds: Set<Long>): Int {
        if (animeIds.isEmpty()) return 0
        val ids = animeIds.toList()
        database.withTransaction { dao.swapSynopsisAndReviewBatch(ids) }
        operationLog?.record("批量互换简介与评价", "资料库", mapOf("count" to ids.size))
        return ids.size
    }

    suspend fun swapSynopsisAndReviewBatchForAll(): Int {
        val ids = dao.getAllAnimesIncludingDeleted().filter { it.deletedAt == null }.map { it.id }
        if (ids.isEmpty()) return 0
        database.withTransaction { dao.swapSynopsisAndReviewBatch(ids) }
        operationLog?.record("全部互换简介与评价", "资料库", mapOf("count" to ids.size))
        return ids.size
    }

    suspend fun addTagToAnimes(animeIds: Set<Long>, tagId: Long) {
        if (animeIds.isEmpty()) return
        dao.insertAnimeTags(animeIds.map { animeId -> AnimeTagEntity(animeId, tagId) })
    }

    suspend fun removeTagFromAnimes(animeIds: Set<Long>, tagId: Long) {
        if (animeIds.isEmpty()) return
        dao.removeTagFromAnimes(animeIds.toList(), tagId)
    }

    suspend fun createTagAndAddToAnimes(
        animeIds: Set<Long>,
        name: String,
        color: Long,
    ): Long {
        require(animeIds.isNotEmpty()) { "请先选择至少一部作品" }
        val normalizedName = simplifyTagName(name)
        require(normalizedName.isNotEmpty()) { "标签名称不能为空" }
        val tagId = database.withTransaction {
            val existing = dao.getAllTags().firstOrNull { normalizeTagKey(it.name) == normalizeTagKey(normalizedName) }
            val resolvedId = existing?.id ?: dao.insertTag(
                TagEntity(name = normalizedName, color = color),
            )
            if (existing?.blocked == true) dao.setTagBlocked(existing.id, false)
            dao.insertAnimeTags(animeIds.map { animeId -> AnimeTagEntity(animeId, resolvedId) })
            resolvedId
        }
        operationLog?.record(
            "新建并批量添加标签",
            "资料库",
            mapOf("tagId" to tagId, "count" to animeIds.size),
        )
        return tagId
    }

    suspend fun syncCoverUrlToServer(title: String, coverUrl: String): Boolean {
        val normalizedTitle = title.trim()
        val normalizedUrl = coverUrl.trim()
        if (normalizedTitle.isEmpty() ||
            (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) ||
            !serverCatalog.isConfigured
        ) {
            return false
        }
        return runCatchingCancellable { serverCatalog.updateCover(normalizedTitle, normalizedUrl) }
            .onSuccess {
                operationLog?.record(
                    "自动同步封面至公共资料库",
                    "作品编辑",
                    mapOf("title" to normalizedTitle),
                )
            }
            .isSuccess
    }

    suspend fun syncCoverUrlsToServer(animeIds: Set<Long>): CoverSyncSummary {
        if (animeIds.isEmpty()) return CoverSyncSummary(0, 0, 0)
        var success = 0
        var skipped = 0
        var failed = 0
        dao.getAnimes(animeIds.toList()).forEach { anime ->
            val coverUrl = anime.coverUrl?.trim().orEmpty()
            if (!coverUrl.startsWith("http://") && !coverUrl.startsWith("https://")) {
                skipped += 1
            } else {
                runCatchingCancellable { serverCatalog.updateCover(anime.title, coverUrl) }
                    .onSuccess { success += 1 }
                    .onFailure { failed += 1 }
            }
        }
        operationLog?.record(
            "批量同步封面至公共资料库",
            "首页",
            mapOf("count" to animeIds.size, "success" to success, "skipped" to skipped, "failed" to failed),
        )
        return CoverSyncSummary(success, skipped, failed)
    }

    suspend fun importRemoteAnime(
        remote: RemoteAnime,
        forceDuplicate: Boolean = false,
    ): RemoteImportResult = database.withTransaction {
        val normalizedSubjectType = normalizeSubjectType(remote.subjectType)
        val externalMatch = dao.getAnimeByExternalIdIncludingDeleted(remote.importSource, remote.importId)
        if (externalMatch != null) return@withTransaction RemoteImportResult.Duplicate(externalMatch)
        if (!forceDuplicate) {
            val titleKey = normalizedMatchTitle(remote.title)
            val titleMatch = dao.getActiveAnimes().firstOrNull { anime ->
                normalizeSubjectType(anime.subjectType) == normalizedSubjectType &&
                    normalizedMatchTitle(anime.title) == titleKey
            }
            if (titleMatch != null) return@withTransaction RemoteImportResult.Duplicate(titleMatch)
        }

        val rating = when (remote.source) {
            RemoteCatalogSource.BANGUMI -> remote.score?.times(10)?.toInt()
            RemoteCatalogSource.ANILIST -> remote.score?.toInt()
            RemoteCatalogSource.SERVER -> when (remote.importSource) {
                "bangumi" -> remote.score?.times(10)?.toInt()
                else -> remote.score?.takeIf { it <= 10.0 }?.times(10)?.toInt() ?: remote.score?.toInt()
            }
        }?.coerceIn(0, 100)
        val animeId = dao.insertAnime(
            AnimeEntity(
                title = remote.title,
                coverUrl = remote.coverUrl,
                status = "想看",
                rating = rating,
                createdAt = Instant.now().toString(),
                airDate = remote.airDate,
                studio = remote.studio,
                totalEpisodes = remote.episodes ?: 0,
                tvEpisodes = remote.episodes ?: 0,
                subjectType = normalizedSubjectType,
                externalSource = remote.importSource,
                externalId = remote.importId,
                externalUrl = remote.siteUrl,
                originalTitle = remote.originalTitle,
                synopsis = remote.summary,
                mediaFormat = remote.format,
            ),
        )
        val existingTags = dao.getAllTags().associateBy { normalizeTagKey(it.name) }.toMutableMap()
        val tagIds = remote.tags.mapNotNull { rawName ->
            val name = simplifyTagName(rawName)
            if (name.isEmpty()) return@mapNotNull null
            val key = normalizeTagKey(name)
            existingTags[key]?.let { tag ->
                if (tag.blocked) dao.setTagBlocked(tag.id, false)
                tag.id
            } ?: dao.insertTag(TagEntity(name = name)).also { id ->
                existingTags[key] = TagEntity(id = id, name = name)
            }
        }.distinct()
        if (tagIds.isNotEmpty()) {
            dao.insertAnimeTags(tagIds.map { tagId -> AnimeTagEntity(animeId, tagId) })
        }
        operationLog?.record("从在线数据源添加作品", "发现", mapOf("animeId" to animeId, "source" to remote.importSource))
        RemoteImportResult.Added(animeId)
    }

    suspend fun mergeAnimes(targetId: Long, sourceIds: Set<Long>): AnimeMergeResult? {
        val duplicates = sourceIds.filterNot { it == targetId }
        if (duplicates.isEmpty()) return null
        val result = database.withTransaction {
            val items = dao.getAnimes(listOf(targetId) + duplicates)
            val target = items.firstOrNull { it.id == targetId && it.deletedAt == null }
                ?: return@withTransaction null
            val sources = items.filter { it.id != targetId && it.deletedAt == null }
            if (sources.isEmpty()) return@withTransaction null
            val actualSourceIds = sources.map(AnimeEntity::id)
            val merged = mergeAnimeMetadata(target, sources).withNormalizedSubjectType()
            dao.updateAnime(merged)
            dao.copyAnimeTags(targetId, actualSourceIds)
            dao.copyAnimeCharacters(targetId, actualSourceIds)
            dao.copyCharacterGroupWorks(targetId, actualSourceIds)
            dao.copyWatchRecords(targetId, actualSourceIds)
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

    suspend fun saveTag(tag: TagEntity): Long = database.withTransaction {
        val normalized = tag.copy(name = simplifyTagName(tag.name).ifBlank { error("标签名称不能为空") })
        val sameName = dao.getAllTags().firstOrNull { normalizeTagKey(it.name) == normalizeTagKey(normalized.name) }
        if (normalized.id == 0L) {
            if (sameName != null) {
                if (sameName.blocked) dao.setTagBlocked(sameName.id, false)
                return@withTransaction sameName.id
            }
            return@withTransaction dao.insertTag(normalized)
        }
        val current = dao.getTag(normalized.id) ?: error("要编辑的标签已不存在")
        if (sameName != null && sameName.id != current.id) {
            dao.copyAnimeTagLinks(sameName.id, current.id)
            dao.clearAnimeTagsForTag(current.id)
            dao.deleteTag(current.id)
            return@withTransaction sameName.id
        }
        dao.updateTag(normalized.copy(blocked = current.blocked))
        normalized.id
    }

    suspend fun deleteTag(tagId: Long) {
        database.withTransaction {
            dao.clearAnimeTagsForTag(tagId)
            dao.deleteTag(tagId)
        }
    }

    suspend fun setTagBlocked(tagId: Long, blocked: Boolean) {
        val tag = dao.getTag(tagId) ?: return
        if (tag.blocked == blocked) return
        dao.setTagBlocked(tagId, blocked)
        operationLog?.record(
            if (blocked) "屏蔽标签" else "取消屏蔽标签",
            "标签管理",
            mapOf("tagId" to tagId, "name" to tag.name),
        )
    }

    /**
     * 把多个同义/近义标签合并到 targetId。源标签的关联会全部复制到目标标签后删除。
     */
    suspend fun mergeTags(sourceIds: Set<Long>, targetId: Long): Int {
        val sources = sourceIds.filterNot { it == targetId }.distinct()
        if (sources.isEmpty()) return 0
        val count = database.withTransaction {
            var merged = 0
            sources.forEach { sourceId ->
                val source = dao.getTag(sourceId) ?: return@forEach
                val target = dao.getTag(targetId) ?: return@forEach
                if (source.id == target.id) return@forEach
                dao.copyAnimeTagLinks(target.id, source.id)
                dao.clearAnimeTagsForTag(source.id)
                dao.deleteTag(source.id)
                merged += 1
            }
            merged
        }
        if (count > 0) {
            operationLog?.record(
                "合并同义标签",
                "标签管理",
                mapOf("targetId" to targetId, "mergedCount" to count),
            )
        }
        return count
    }

    /**
     * 批量删除标签，并返回每个被删除标签的快照（名称、颜色、关联作品 ID），
     * 供调用方在撤销时通过 [restoreTags] 恢复标签及其关联。
     */
    suspend fun deleteTagsWithSnapshot(tagIds: Set<Long>): List<DeletedTagSnapshot> = database.withTransaction {
        if (tagIds.isEmpty()) {
            emptyList()
        } else {
            tagIds.mapNotNull { tagId ->
                val tag = dao.getTag(tagId) ?: return@mapNotNull null
                val animeIds = dao.getAnimeIdsForTag(tagId)
                dao.clearAnimeTagsForTag(tagId)
                dao.deleteTag(tagId)
                DeletedTagSnapshot(tag.name, tag.color, tag.blocked, animeIds)
            }
        }
    }

    /**
     * 根据快照恢复标签及其与作品的关联。若已存在同名标签则复用之。
     */
    suspend fun restoreTags(snapshots: List<DeletedTagSnapshot>) = database.withTransaction {
        snapshots.forEach { snapshot ->
            val restoredName = simplifyTagName(snapshot.name)
            val existing = dao.getAllTags().firstOrNull { normalizeTagKey(it.name) == normalizeTagKey(restoredName) }
            val tagId = existing?.id ?: dao.insertTag(
                TagEntity(name = restoredName, color = snapshot.color, blocked = snapshot.blocked),
            )
            if (existing != null && existing.blocked != snapshot.blocked) {
                dao.setTagBlocked(existing.id, snapshot.blocked)
            }
            if (snapshot.animeIds.isNotEmpty()) {
                dao.insertAnimeTags(snapshot.animeIds.map { AnimeTagEntity(it, tagId) })
            }
        }
    }

    suspend fun saveSeries(series: SeriesEntity): Long = database.withTransaction {
        val normalized = series.copy(name = series.name.trim().ifBlank { error("系列名称不能为空") })
        if (normalized.id == 0L) {
            dao.insertSeriesItem(normalized)
        } else {
            requireNotNull(dao.getSeries(normalized.id)) { "要编辑的系列已不存在" }
            dao.updateSeriesItem(normalized)
            normalized.id
        }
    }

    suspend fun createSeriesAndAssignAnimes(
        name: String,
        animeIds: Set<Long>,
    ): Long = database.withTransaction {
        val seriesId = dao.insertSeriesItem(
            SeriesEntity(
                name = name.trim().ifBlank { error("系列名称不能为空") },
                createdAt = Instant.now().toString(),
            ),
        )
        if (animeIds.isNotEmpty()) {
            dao.assignSeriesToAnimes(animeIds.toList(), seriesId)
        }
        operationLog?.record(
            "创建并归纳系列",
            "资料库",
            mapOf("seriesId" to seriesId, "animeCount" to animeIds.size),
        )
        seriesId
    }

    suspend fun deleteSeries(seriesId: Long) {
        database.withTransaction {
            dao.clearSeriesFromAnimes(seriesId)
            dao.deleteSeries(seriesId)
        }
    }

    suspend fun updateSeriesCover(seriesId: Long, coverUrl: String?) {
        val series = dao.getSeries(seriesId) ?: return
        dao.updateSeriesItem(series.copy(customCoverUrl = coverUrl?.takeIf(String::isNotBlank)))
        operationLog?.record(
            "修改系列封面",
            "系列",
            mapOf("seriesId" to seriesId, "customCover" to !coverUrl.isNullOrBlank()),
        )
    }

    suspend fun assignSeriesToAnimes(animeIds: Set<Long>, seriesId: Long) {
        if (animeIds.isEmpty()) return
        dao.assignSeriesToAnimes(animeIds.toList(), seriesId)
    }

    suspend fun removeSeriesFromAnimes(animeIds: Set<Long>) {
        if (animeIds.isEmpty()) return
        dao.clearSeriesFromAnimes(animeIds.toList())
    }

    suspend fun saveWatchStatus(status: WatchStatusEntity): Long =
        database.withTransaction {
            val normalized = status.copy(name = status.name.trim().ifBlank { error("状态名称不能为空") })
            val sameName = dao.getWatchStatusByName(normalized.name)
            if (normalized.id == 0L) {
                return@withTransaction sameName?.id ?: dao.insertWatchStatus(normalized)
            }
            val old = dao.getWatchStatus(normalized.id) ?: error("要编辑的状态已不存在")
            if (sameName != null && sameName.id != old.id) {
                dao.renameAnimeStatus(old.name, sameName.name)
                dao.deleteWatchStatus(old.id)
                return@withTransaction sameName.id
            }
            dao.updateWatchStatus(normalized)
            if (old.name != normalized.name) dao.renameAnimeStatus(old.name, normalized.name)
            normalized.id
        }

    suspend fun deleteWatchStatus(statusId: Long, replacementStatusId: Long? = null) {
        val result = database.withTransaction {
            val status = dao.getWatchStatus(statusId) ?: return@withTransaction null
            val usageCount = dao.countAnimesUsingStatus(status.name)
            val replacement = if (usageCount > 0) {
                requireNotNull(replacementStatusId) { "该状态仍有作品使用，请先选择迁移目标" }
                    .let { dao.getWatchStatus(it) }
                    ?.takeIf { it.id != status.id }
                    ?: error("迁移目标状态无效")
            } else {
                null
            }
            replacement?.let { dao.renameAnimeStatus(status.name, it.name) }
            dao.deleteWatchStatus(statusId)
            Triple(status.name, usageCount, replacement?.name)
        } ?: return
        operationLog?.record(
            "删除观看状态",
            "资料库",
            mapOf(
                "status" to result.first,
                "migratedCount" to result.second,
                "replacement" to result.third,
            ),
        )
    }

    suspend fun reorderWatchStatuses(statusIds: List<Long>) {
        if (statusIds.isEmpty()) return
        database.withTransaction {
            statusIds.distinct().forEachIndexed { index, statusId ->
                dao.updateWatchStatusSortOrder(statusId, index)
            }
        }
        operationLog?.record("调整状态顺序", "资料库", mapOf("count" to statusIds.size))
    }

    suspend fun clearReminder(animeId: Long) {
        dao.clearAnimeReminder(animeId)
        operationLog?.record("取消追番提醒", "提醒", mapOf("animeId" to animeId))
    }

    suspend fun incrementProgress(
        animeId: Long,
        autoCompleteStatus: Boolean = false,
        completionStatus: String = "看完",
    ): ProgressChange? =
        database.withTransaction {
            val anime = dao.getAnime(animeId) ?: return@withTransaction null
            if (anime.totalEpisodes > 0 && anime.watchedEpisodes >= anime.totalEpisodes) {
                return@withTransaction null
            }

            val current = anime.watchedEpisodes + 1
            val reachedEnd = anime.totalEpisodes > 0 && current >= anime.totalEpisodes
            val nextStatus = if (autoCompleteStatus && reachedEnd) completionStatus else anime.status
            val nextStartDate = anime.watchStartDate?.takeIf(String::isNotBlank)
                ?: java.time.LocalDate.now().toString().takeIf { anime.watchedEpisodes == 0 }
            val nextFinishDate = if (autoCompleteStatus && reachedEnd) {
                java.time.LocalDate.now().toString()
            } else {
                anime.watchFinishDate
            }
            dao.updateAnime(
                anime.copy(
                    watchedEpisodes = current,
                    status = nextStatus,
                    watchFinishDate = nextFinishDate,
                    watchStartDate = nextStartDate,
                ),
            )
            val watchRecordId = dao.insertWatchRecord(
                WatchRecordEntity(
                    animeId = animeId,
                    episode = current,
                    status = if (reachedEnd) "completed" else "watched",
                    recordDate = java.time.OffsetDateTime.now().toString(),
                ),
            )
            ProgressChange(
                animeId = animeId,
                previous = anime.watchedEpisodes,
                current = current,
                previousStatus = anime.status,
                currentStatus = nextStatus,
                previousFinishDate = anime.watchFinishDate,
                currentFinishDate = nextFinishDate,
                watchRecordId = watchRecordId,
                previousStartDate = anime.watchStartDate,
                currentStartDate = nextStartDate,
            )
        }

    suspend fun restoreProgress(change: ProgressChange) {
        database.withTransaction {
            val anime = dao.getAnime(change.animeId) ?: return@withTransaction
            dao.updateAnime(
                anime.copy(
                    watchedEpisodes = change.previous,
                    status = change.previousStatus ?: anime.status,
                    watchFinishDate = change.previousFinishDate,
                    watchStartDate = if (anime.watchStartDate == change.currentStartDate) change.previousStartDate else anime.watchStartDate,
                ),
            )
            change.watchRecordId?.let { dao.deleteWatchRecord(it) }
        }
    }

    suspend fun deleteWatchRecord(recordId: Long) {
        dao.deleteWatchRecord(recordId)
        operationLog?.record("删除观看记录", "日历", mapOf("recordId" to recordId))
    }

    suspend fun setProgress(animeId: Long, progress: Int) {
        database.withTransaction {
            val anime = dao.getAnime(animeId) ?: return@withTransaction
            val bounded = progress.coerceAtLeast(0).let { value ->
                if (anime.totalEpisodes > 0) value.coerceAtMost(anime.totalEpisodes) else value
            }
            dao.updateAnime(normalizeCompletionProgress(anime.copy(watchedEpisodes = bounded)))
        }
    }

    suspend fun setFunRatingTier(animeId: Long, tier: String?) {
        dao.updateAnimeFunTier(animeId, tier)
    }

    suspend fun clearFunRatingTiers() {
        dao.clearAnimeFunTiers()
    }
}

internal fun normalizeCompletionProgress(anime: AnimeEntity): AnimeEntity =
    if (anime.status.trim() == "看完" && anime.totalEpisodes > 0 && anime.watchedEpisodes != anime.totalEpisodes) {
        anime.copy(watchedEpisodes = anime.totalEpisodes)
    } else {
        anime
    }

private fun mergeRemoteText(current: String?, remote: String?, overwrite: Boolean): String? {
    val candidate = remote?.trim()?.takeIf(String::isNotEmpty) ?: return current
    return if (overwrite || current.isNullOrBlank()) candidate else current
}

private fun <T> mergeRemoteNumber(current: T?, remote: T?, overwrite: Boolean): T? =
    if (remote != null && (overwrite || current == null)) remote else current

internal fun mergeAnimeMetadata(
    target: AnimeEntity,
    sources: List<AnimeEntity>,
): AnimeEntity {
    val all = listOf(target) + sources
    val progressWinner = all.maxWithOrNull(
        compareBy<AnimeEntity>(AnimeEntity::watchedEpisodes)
            .thenBy { mergeStatusPriority(it.status) }
            .thenBy(AnimeEntity::totalEpisodes),
    ) ?: target
    val externalIdentity = all.firstOrNull(AnimeEntity::hasCompleteExternalIdentity)
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
        review = all.mapNotNull(AnimeEntity::review).filter(String::isNotBlank).maxByOrNull(String::length),
        seriesId = target.seriesId ?: all.firstNotNullOfOrNull(AnimeEntity::seriesId),
        status = progressWinner.status,
        createdAt = earliest(AnimeEntity::createdAt),
        airDate = earliest(AnimeEntity::airDate),
        studio = text(AnimeEntity::studio),
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
        externalSource = externalIdentity?.externalSource ?: target.externalSource,
        externalId = externalIdentity?.externalId ?: target.externalId,
        externalUrl = externalUrl ?: if (externalIdentity == null) target.externalUrl else null,
        originalTitle = text(AnimeEntity::originalTitle),
        synopsis = all.mapNotNull(AnimeEntity::synopsis).filter(String::isNotBlank).maxByOrNull(String::length),
        mediaFormat = text(AnimeEntity::mediaFormat),
        deletedAt = null,
    ))
}

private fun AnimeEntity.hasCompleteExternalIdentity(): Boolean =
    !externalSource.isNullOrBlank() && !externalId.isNullOrBlank()

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

private fun normalizedMatchTitle(value: String): String = value
    .trim()
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("[\\s　]+"), "")

internal fun mergeRemoteAnimeRating(
    currentRating: Int?,
    currentGrade: String?,
    remoteRating: Int?,
    overwriteExisting: Boolean,
): RemoteAnimeRatingMerge {
    val normalizedRemote = remoteRating?.takeIf { it in 1..100 }
        ?: return RemoteAnimeRatingMerge(currentRating, currentGrade)
    val hasCurrentRating = currentRating in 1..100 || normalizeAnimeRatingGrade(currentGrade) != null
    return if (!overwriteExisting && hasCurrentRating) {
        RemoteAnimeRatingMerge(currentRating, currentGrade)
    } else {
        RemoteAnimeRatingMerge(normalizedRemote, null)
    }
}
