package com.animeow.app.data

import com.animeow.app.data.local.allTitles
import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.local.matchesExternalReference
import androidx.room.withTransaction
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.data.local.episodeDetailOrNull
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.withNormalizedSubjectType
import com.animeow.app.data.local.simplifyTagName
import com.animeow.app.data.local.normalizeTagKey
import com.animeow.app.data.bangumi.BangumiSyncQueue
import com.animeow.app.data.bangumi.bangumiCollectionType
import com.animeow.app.data.bangumi.bangumiRate
import com.animeow.app.data.bangumi.bangumiSubjectIdOf
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.sourceRatingSnapshot
import com.animeow.app.util.withDataTransferLock
import com.animeow.app.data.remote.RemoteCatalogSource
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalTime
import com.animeow.app.data.diagnostics.OperationLogService

data class BatchTagDraft(val name: String, val color: Long)

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
    val previousStatusChangedAt: Long? = null,
    val currentStatusChangedAt: Long? = null,
)

data class StatusChange(
    val animeId: Long,
    val previous: String,
    val current: String,
    val previousStartDate: String? = null,
    val previousFinishDate: String? = null,
    val currentStartDate: String? = null,
    val currentFinishDate: String? = null,
    val previousStatusChangedAt: Long? = null,
    val currentStatusChangedAt: Long? = null,
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
    private val bangumiSync: BangumiSyncQueue? = null,
    private val onPublicMetadata: (RemoteAnime) -> Unit = {},
) {
    private val dao = database.libraryDao()
    private val metadata = LibraryMetadataOperations(database, operationLog, onPublicMetadata)
    private val merging = LibraryMergeOperations(database, operationLog)
    private val publicCovers = PublicCoverSync(database.libraryDao(), operationLog)

    suspend fun applyRemoteMetadata(
        animeId: Long,
        remote: RemoteAnime,
        fields: RemoteMetadataFields,
        overwriteExisting: Boolean,
        preservePersonalProgress: Boolean = false,
    ): Boolean = metadata.applyRemoteMetadata(animeId, remote, fields, overwriteExisting, preservePersonalProgress)

    suspend fun mergeAnimes(targetId: Long, sourceIds: Set<Long>): AnimeMergeResult? =
        merging.mergeAnimes(targetId, sourceIds)

    suspend fun syncCoverUrlToServer(title: String, coverUrl: String): Boolean =
        publicCovers.syncCoverUrlToServer(title, coverUrl)

    suspend fun syncCoverUrlsToServer(animeIds: Set<Long>): CoverSyncSummary =
        publicCovers.syncCoverUrlsToServer(animeIds)

    fun observeAnimes(): Flow<List<AnimeEntity>> = dao.observeAnimes()

    fun observeDeletedAnimes(): Flow<List<AnimeEntity>> = dao.observeDeletedAnimes()

    fun observeAnime(animeId: Long): Flow<AnimeEntity?> = dao.observeAnime(animeId)

    fun observeTags(): Flow<List<TagEntity>> = dao.observeTags()

    fun observeAllTags(): Flow<List<TagEntity>> = dao.observeAllTags()

    fun observeAnimeTags(): Flow<List<AnimeTagEntity>> = dao.observeAnimeTags()

    fun observeWatchStatuses(): Flow<List<WatchStatusEntity>> = dao.observeWatchStatuses()

    fun observeWatchRecords(): Flow<List<WatchRecordEntity>> = dao.observeWatchRecords()
    fun observeLatestWatchRecords() = dao.observeLatestWatchRecords()
    fun observeWatchRecordsSince(since: String) = dao.observeWatchRecordsSince(since)

    fun observeEpisodeDetails(animeId: Long): Flow<List<EpisodeDetailEntity>> = dao.observeEpisodeDetails(animeId)

    fun observeEpisodeDetailsWithDuration(): Flow<List<EpisodeDetailEntity>> = dao.observeEpisodeDetailsWithDuration()

    suspend fun getEpisodeDetails(animeId: Long): List<EpisodeDetailEntity> = dao.getEpisodeDetails(animeId)

    /**
     * 保存单集资料。字段全部留空时删除该集记录，不留空行。
     * 集号只要求从 1 开始，不强制落在总集数内：总集数有误时仍可先记录，之后再修正。
     */
    suspend fun saveEpisodeDetail(
        animeId: Long,
        episode: Int,
        title: String,
        durationMinutes: Int?,
        synopsis: String,
        review: String,
        rating: Int?,
    ): EpisodeDetailEntity? = database.withTransaction {
        require(episode >= 1) { "集号必须从 1 开始" }
        val detail = episodeDetailOrNull(
            animeId = animeId,
            episode = episode,
            title = title,
            durationMinutes = durationMinutes,
            synopsis = synopsis,
            review = review,
            rating = rating,
            updatedAt = Instant.now().toString(),
        )
        if (detail == null) {
            dao.deleteEpisodeDetail(animeId, episode)
        } else {
            dao.upsertEpisodeDetail(detail)
        }
        detail
    }

    fun observeSeries(): Flow<List<SeriesEntity>> = dao.observeSeries()

    fun observeSeries(seriesId: Long): Flow<SeriesEntity?> = dao.observeSeries(seriesId)

    fun observeSeriesMembers(seriesId: Long, excludedId: Long): Flow<List<AnimeEntity>> =
        dao.observeSeriesMembers(seriesId, excludedId)

    internal suspend fun updatePublicRating(animeId: Long, remote: RemoteAnime) = database.withTransaction {
        val current = dao.getAnime(animeId)?.takeIf { it.deletedAt == null } ?: return@withTransaction
        val matches = (current.externalSource == remote.importSource && current.externalId == remote.importId) ||
            current.sourceReferences().any { it.source == remote.importSource && it.externalId == remote.importId }
        if (matches) remote.sourceRatingSnapshot()?.let { dao.updateAnime(current.copy(sourceRatingJson = it)) }
    }

    suspend fun getActiveAnimesSnapshot(): List<AnimeEntity> = dao.getActiveAnimes()

    suspend fun getWatchRecordsSnapshot(): List<WatchRecordEntity> = dao.getWatchRecords()

    suspend fun getTagIdsForAnime(animeId: Long): List<Long> = dao.getTagIdsForAnime(animeId)

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

    suspend fun saveAnime(anime: AnimeEntity, tagIds: Set<Long>): Long {
        val id = database.withTransaction { saveAnimeRecord(anime, tagIds) }
        // 评分变更可能刚入队；保存是低频操作，总是排空一次队列（3 秒防抖会合并）
        bangumiSync?.schedulePending()
        return id
    }

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
    }.also { bangumiSync?.schedulePending() }

    private suspend fun saveAnimeRecord(anime: AnimeEntity, tagIds: Set<Long>): Long {
        val normalizedAnime = normalizeCompletionProgress(
            anime.copy(memo = com.animeow.app.data.local.normalizeAnimeMemo(anime.memo)).withNormalizedSubjectType(),
        )
        val now = System.currentTimeMillis()
        val id = if (normalizedAnime.id > 0L) {
            val stored = requireNotNull(dao.getAnime(normalizedAnime.id)) { "要编辑的作品已不存在" }
            dao.updateAnime(normalizedAnime.copy(
                seriesRole = stored.seriesRole?.takeIf { stored.seriesId == normalizedAnime.seriesId && stored.seriesId != null },
                pinnedAt = stored.pinnedAt,
                statusChangedAt = if (stored.status != normalizedAnime.status) now else stored.statusChangedAt,
                updatedAt = now,
            ))
            // 评分真的变了才同步（导入/元数据刷新不走这里，避免无意义回写）
            if (stored.rating != normalizedAnime.rating) {
                enqueueBangumiRating(normalizedAnime)
            }
            // 编辑页改状态、改进度同样上报，与详情页快捷操作一致
            if (stored.status != normalizedAnime.status) {
                enqueueBangumiStatus(normalizedAnime, normalizedAnime.status)
            }
            val before = stored.watchedEpisodes
            val after = normalizedAnime.watchedEpisodes
            when {
                after > before -> enqueueBangumiEpisodes(normalizedAnime, ((before + 1)..after).toSet(), emptySet())
                after < before -> enqueueBangumiEpisodes(normalizedAnime, emptySet(), ((after + 1)..before).toSet())
            }
            normalizedAnime.id
        } else {
            dao.insertAnime(normalizedAnime.copy(updatedAt = normalizedAnime.updatedAt ?: now))
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
        var pendingBangumiSync = false
        database.withTransaction {
            dao.getAnimes(animeIds.toList()).filter { it.deletedAt == null }.forEach { anime ->
                // 必须和新增/编辑路径一样补全进度：状态改成"看完"却把进度留在 0，
                // 会让详情页显示 0/12，统计的"估算时长"也按 0 集计算，看起来像没生效。
                val updated = normalizeCompletionProgress(anime.withWatchStatusDates(status))
                dao.updateAnime(updated)
                pendingBangumiSync = enqueueBangumiStatus(updated, updated.status) || pendingBangumiSync
            }
        }
        if (pendingBangumiSync) bangumiSync?.schedulePending()
        operationLog?.record("批量修改状态", "资料库", mapOf("count" to animeIds.size, "status" to status))
    }

    suspend fun setPinned(animeIds: Set<Long>, pinned: Boolean): PinChange = database.withTransaction {
        val changed = dao.getAnimes(animeIds.toList()).filter {
            it.deletedAt == null && (it.pinnedAt != null) != pinned
        }
        val timestamp = if (pinned) System.currentTimeMillis() else null
        changed.forEach { dao.updateAnimePin(it.id, timestamp) }
        PinChange(changed.associate { it.id to it.pinnedAt }, timestamp)
    }

    suspend fun restorePins(change: PinChange) = database.withTransaction {
        change.before.forEach { (id, previous) ->
            val current = dao.getAnime(id)
            if (current != null && current.deletedAt == null && current.pinnedAt == change.applied) dao.updateAnimePin(id, previous)
        }
    }

    suspend fun cycleStatus(animeId: Long): StatusChange? {
        var pendingBangumiSync = false
        val change = database.withTransaction {
            val anime = dao.getAnime(animeId) ?: return@withTransaction null
            val statuses = dao.getWatchStatuses().map { it.name }.filter(String::isNotBlank)
            if (statuses.isEmpty()) return@withTransaction null
            val index = statuses.indexOf(anime.status)
            val next = statuses[(index + 1).mod(statuses.size)]
            // 循环切换状态同样要补全进度，否则滑到"看完"会留下 0/N 的进度。
            val updated = normalizeCompletionProgress(anime.withWatchStatusDates(next))
            dao.updateAnime(updated)
            pendingBangumiSync = enqueueBangumiStatus(updated, next) || pendingBangumiSync
            StatusChange(animeId, anime.status, next, anime.watchStartDate, anime.watchFinishDate,
                updated.watchStartDate, updated.watchFinishDate, anime.statusChangedAt, updated.statusChangedAt)
        }
        if (pendingBangumiSync) bangumiSync?.schedulePending()
        return change
    }

    suspend fun restoreStatus(change: StatusChange) {
        database.withTransaction {
            val anime = dao.getAnime(change.animeId) ?: return@withTransaction
            if (anime.status != change.current || anime.statusChangedAt != change.currentStatusChangedAt) return@withTransaction
            dao.updateAnime(anime.copy(
                status = change.previous,
                statusChangedAt = change.previousStatusChangedAt,
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
            dao.clearEpisodeDetailsForAnime(animeId)
            dao.clearAnimeCharactersForAnime(animeId)
            dao.clearCharacterGroupWorksForAnime(animeId)
            database.bookReadingDao().deleteStateForAnime(animeId)
            database.bookReadingDao().deleteBookmarksForAnime(animeId)
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
            dao.clearEpisodeDetailsForTrash()
            dao.clearAnimeCharactersForTrash()
            dao.clearCharacterGroupWorksForTrash()
            database.bookReadingDao().deleteStatesForTrash()
            database.bookReadingDao().deleteBookmarksForTrash()
            dao.deleteTrashAnimes()
            ids
        }
        if (deletedIds.isNotEmpty()) {
            operationLog?.record("清空回收站", "回收站", mapOf("count" to deletedIds.size))
        }
        return deletedIds
    }

    suspend fun findTrashedByExternalId(source: String, externalId: String): AnimeEntity? {
        if (dao.getActiveAnimes().any { it.matchesExternalReference(source, externalId) }) return null
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

    suspend fun updateTagsForAnimes(
        animeIds: Set<Long>,
        tagIds: Set<Long>,
        remove: Boolean,
        newTags: List<BatchTagDraft> = emptyList(),
    ) = withDataTransferLock {
        require(animeIds.isNotEmpty()) { "请先选择至少一部作品" }
        require(tagIds.isNotEmpty() || newTags.isNotEmpty()) { "请至少选择一个标签" }
        require(!remove || newTags.isEmpty()) { "移除标签时不能新建标签" }
        database.withTransaction {
            val animes = dao.getAnimes(animeIds.toList())
            require(animes.size == animeIds.size && animes.none { it.deletedAt != null }) {
                "所选作品已变化，请重新选择"
            }
            val currentTags = dao.getAllTags().associateBy(TagEntity::id)
            require(tagIds.all { currentTags[it]?.blocked == false }) {
                "所选标签已删除或屏蔽，请重新选择"
            }
            val resolved = tagIds.toMutableSet()
            newTags.forEach { draft ->
                resolved += saveTag(TagEntity(name = draft.name, color = draft.color))
            }
            if (remove) {
                resolved.forEach { dao.removeTagFromAnimes(animeIds.toList(), it) }
            } else {
                dao.insertAnimeTags(animeIds.flatMap { animeId -> resolved.map { AnimeTagEntity(animeId, it) } })
            }
        }
        operationLog?.record(
            if (remove) "批量移除标签" else "批量添加标签",
            "资料库",
            mapOf("works" to animeIds.size, "tags" to tagIds.size, "newTags" to newTags.size),
        )
    }

    suspend fun importBatchItem(remote: RemoteAnime, seriesName: String?, initialStatus: String? = null): Pair<Long, Boolean> =
        importBatchItem(remote, BatchSeriesDestination(newSeriesName = seriesName), BatchMetadataMode.KEEP, initialStatus)

    suspend fun importBatchItem(remote: RemoteAnime, destination: BatchSeriesDestination,
        metadataMode: BatchMetadataMode, initialStatus: String? = null): Pair<Long, Boolean> = database.withTransaction {
        val name = destination.newSeriesName?.trim()?.takeIf(String::isNotBlank)
        require(destination.existingSeriesId == null || name == null) { "请选择已有系列或新建系列中的一种" }
        require(name == null || name.length <= 100) { "系列名称不能超过 100 字" }
        val target = destination.existingSeriesId?.let { id ->
            requireNotNull(dao.getSeries(id)) { "所选系列已不存在，请重新选择" }
        } ?: name?.let { dao.getSeriesByName(it) }
        val imported = importRemoteAnime(remote)
        val added = imported is RemoteImportResult.Added
        val anime = when (imported) {
            is RemoteImportResult.Added -> checkNotNull(dao.getAnime(imported.animeId))
            is RemoteImportResult.Duplicate -> imported.existing.also {
                require(it.deletedAt == null) { "作品在回收站中，请先恢复后再添加" }
                require(it.matchesExternalReference(remote.importSource, remote.importId)) { "存在同名作品，请单独确认后添加" }
            }
        }
        val cleanInitialStatus = initialStatus?.trim()?.takeIf(String::isNotEmpty)
        if (added && cleanInitialStatus != null && cleanInitialStatus != anime.status) {
            dao.updateAnime(normalizeCompletionProgress(anime.withWatchStatusDates(cleanInitialStatus)))
        }
        if (name != null || target != null) {
            val currentSeries = anime.seriesId?.let { dao.getSeries(it) }
            require(currentSeries == null || currentSeries.id == target?.id) { "作品已属于其他系列，已保留原系列，请手动确认" }
            val seriesId = target?.id ?: dao.insertSeriesItem(SeriesEntity(name = requireNotNull(name), createdAt = Instant.now().toString()))
            dao.assignSeriesToAnimes(listOf(anime.id), seriesId)
        }
        if (!added && metadataMode != BatchMetadataMode.KEEP) {
            applyRemoteMetadata(anime.id, remote,
                RemoteMetadataFields(rating = false, tags = false,
                    episodes = remote.episodes?.let { it >= anime.watchedEpisodes } ?: false,
                    externalLink = !anime.hasCompleteExternalIdentity() ||
                        (anime.externalSource == remote.importSource && anime.externalId == remote.importId)),
                overwriteExisting = metadataMode == BatchMetadataMode.OVERWRITE, preservePersonalProgress = true)
        }
        anime.id to added
    }

    suspend fun importBatchItems(remotes: List<RemoteAnime>, destination: BatchSeriesDestination,
        metadataMode: BatchMetadataMode): BatchImportSummary {
        require(remotes.isNotEmpty() && remotes.size <= 60) { "每次请选择 1～60 部作品" }
        val succeeded = linkedSetOf<String>()
        val failures = linkedMapOf<String, String>()
        remotes.distinctBy { it.uniqueKey }.forEach { remote ->
            try {
                importBatchItem(remote, destination, metadataMode)
                succeeded += remote.uniqueKey
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                failures[remote.uniqueKey] = if (error is IllegalArgumentException) error.message ?: "保存失败，请重试" else "保存失败，请重试"
            }
        }
        return BatchImportSummary(succeeded, failures)
    }

    suspend fun importRemoteAnime(
        remote: RemoteAnime,
        forceDuplicate: Boolean = false,
    ): RemoteImportResult = database.withTransaction {
        runCatching { onPublicMetadata(remote) }
        val normalizedSubjectType = normalizeSubjectType(remote.subjectType)
        val active = dao.getActiveAnimes()
        val externalMatch = dao.getAnimeByExternalId(remote.importSource, remote.importId)
            ?: active.firstOrNull { it.matchesExternalReference(remote.importSource, remote.importId) }
            ?: dao.getAnimeByExternalIdIncludingDeleted(remote.importSource, remote.importId)
        if (externalMatch != null) return@withTransaction RemoteImportResult.Duplicate(externalMatch)
        if (!forceDuplicate) {
            val titleKey = normalizedMatchTitle(remote.title)
            val titleMatch = active.firstOrNull { anime ->
                normalizeSubjectType(anime.subjectType) == normalizedSubjectType &&
                    anime.allTitles().any { normalizedMatchTitle(it) == titleKey }
            }
            if (titleMatch != null) return@withTransaction RemoteImportResult.Duplicate(titleMatch)
        }

        val rating = when (remote.source) {
            RemoteCatalogSource.BANGUMI, RemoteCatalogSource.KITSU, RemoteCatalogSource.TVMAZE -> remote.score?.times(10)?.toInt()
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
                sourceRatingJson = remote.sourceRatingSnapshot(),
            ),
        )
        val existingTags = dao.getAllTags().associateBy { normalizeTagKey(it.name) }.toMutableMap()
        val blockedKeys = dao.getAllTags().filter { it.blocked }.map { normalizeTagKey(it.name) }.toSet()
        val tagIds = remote.tags.mapNotNull { rawName ->
            val name = simplifyTagName(rawName)
            if (name.isEmpty()) return@mapNotNull null
            val key = normalizeTagKey(name)
            if (key.isBlank() || key in blockedKeys) return@mapNotNull null
            existingTags[key]?.let { tag ->
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

    suspend fun saveTag(tag: TagEntity): Long = database.withTransaction {
        val normalized = tag.copy(name = simplifyTagName(tag.name).ifBlank { error("标签名称不能为空") })
        require(normalizeTagKey(normalized.name).isNotEmpty()) { "标签名称不能只有标点" }
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
        dao.updateTag(normalized.copy(blocked = current.blocked, sortOrder = current.sortOrder))
        normalized.id
    }

    suspend fun reorderTags(tagIds: List<Long>) {
        database.withTransaction {
            val current = dao.getTags().map(TagEntity::id)
            require(tagIds.size == tagIds.distinct().size && tagIds.toSet() == current.toSet()) {
                "标签列表已变化，请重新打开排序"
            }
            tagIds.forEachIndexed { index, id -> dao.updateTagSortOrder(id, index) }
        }
        operationLog?.record("调整标签顺序", "资料库", mapOf("count" to tagIds.size))
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
        val normalized = series.copy(
            name = series.name.trim().ifBlank { error("系列名称不能为空") },
            linkedMediaJson = com.animeow.app.data.local.normalizeSeriesLinkedMediaJson(series.linkedMediaJson),
        )
        if (normalized.id == 0L) {
            dao.insertSeriesItem(normalized)
        } else {
            val stored = requireNotNull(dao.getSeries(normalized.id)) { "要编辑的系列已不存在" }
            dao.updateSeriesItem(
                normalized.copy(
                    linkedMediaJson = normalized.linkedMediaJson
                        ?: com.animeow.app.data.local.normalizeSeriesLinkedMediaJson(stored.linkedMediaJson),
                ),
            )
            normalized.id
        }
    }

    suspend fun updateAnimeMemo(animeId: Long, memo: String?) = database.withTransaction {
        val anime = dao.getAnime(animeId)?.takeIf { it.deletedAt == null } ?: error("作品已不存在")
        dao.updateAnimeMemo(anime.id, com.animeow.app.data.local.normalizeAnimeMemo(memo))
    }

    suspend fun addSeriesLinkedMedia(
        seriesId: Long,
        items: List<com.animeow.app.data.local.SeriesLinkedMediaItem>,
    ) = database.withTransaction {
        if (items.isEmpty()) return@withTransaction
        val series = requireNotNull(dao.getSeries(seriesId)) { "系列已不存在" }
        val existing = com.animeow.app.data.local.parseSeriesLinkedMedia(series.linkedMediaJson)
        val merged = com.animeow.app.data.local.encodeSeriesLinkedMedia(existing + items)
        dao.updateSeriesItem(series.copy(linkedMediaJson = merged))
    }

    suspend fun removeSeriesLinkedMedia(seriesId: Long, keys: Set<String>) = database.withTransaction {
        if (keys.isEmpty()) return@withTransaction
        val series = requireNotNull(dao.getSeries(seriesId)) { "系列已不存在" }
        val filtered = com.animeow.app.data.local.parseSeriesLinkedMedia(series.linkedMediaJson)
            .filterNot { it.key in keys }
        dao.updateSeriesItem(series.copy(linkedMediaJson = com.animeow.app.data.local.encodeSeriesLinkedMedia(filtered)))
    }

    suspend fun setSeriesLinkedMediaRole(seriesId: Long, keys: Set<String>, role: String?) = database.withTransaction {
        require(role == null || role in com.animeow.app.data.local.SERIES_ROLES) { "请选择有效的系列关系" }
        require(keys.isNotEmpty()) { "请选择作品" }
        val series = requireNotNull(dao.getSeries(seriesId)) { "系列已不存在" }
        val current = com.animeow.app.data.local.parseSeriesLinkedMedia(series.linkedMediaJson)
        require(keys.all { key -> current.any { it.key == key } }) { "作品或系列归属已变化，请重新选择" }
        val updated = current.map { item -> if (item.key in keys) item.copy(role = role) else item }
        dao.updateSeriesItem(series.copy(linkedMediaJson = com.animeow.app.data.local.encodeSeriesLinkedMedia(updated)))
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

    suspend fun setSeriesRole(animeIds: Set<Long>, seriesId: Long, role: String?) = database.withTransaction {
        require(role == null || role in com.animeow.app.data.local.SERIES_ROLES) { "请选择有效的系列关系" }
        require(animeIds.isNotEmpty()) { "请选择作品" }
        val rows = dao.getAnimes(animeIds.toList())
        require(rows.size == animeIds.size && rows.all { it.deletedAt == null && it.seriesId == seriesId }) { "作品或系列归属已变化，请重新选择" }
        rows.forEach { dao.updateAnime(it.copy(seriesRole = role)) }
    }

    suspend fun groupDraggedAnimes(expectedSeries: Map<Long, Long?>, seriesId: Long?, name: String): Long = database.withTransaction {
        require(expectedSeries.isNotEmpty()) { "请选择要归组的作品" }
        val current = dao.getAnimes(expectedSeries.keys.toList())
        require(current.size == expectedSeries.size && current.all { it.deletedAt == null && it.seriesId == expectedSeries[it.id] }) {
            "作品或系列归属已变化，请重新拖动确认"
        }
        val target = if (seriesId != null) {
            requireNotNull(dao.getSeries(seriesId)) { "目标系列已不存在，请重新选择" }.id
        } else {
            val title = name.trim()
            require(title.isNotEmpty() && title.length <= 100) { "系列名称需为 1～100 字" }
            require(dao.getSeriesByName(title) == null) { "已有同名系列，请改名或拖入已有系列" }
            dao.insertSeriesItem(SeriesEntity(name = title, createdAt = Instant.now().toString()))
        }
        dao.assignSeriesToAnimes(expectedSeries.keys.toList(), target)
        operationLog?.record("拖动归入系列", "资料库", mapOf("seriesId" to target, "animeCount" to expectedSeries.size))
        target
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

    suspend fun setReminder(animeId: Long, day: Int?, time: String?): AnimeEntity {
        require((day == null && time == null) || (day in 1..7 && !time.isNullOrBlank())) { "请选择提醒星期和时间" }
        val normalizedTime = time?.let { java.time.LocalTime.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }
        val updated = database.withTransaction {
            check(dao.setAnimeReminder(animeId, day, normalizedTime) == 1) { "作品已被移动或不存在" }
            checkNotNull(dao.getAnime(animeId))
        }
        operationLog?.record(if (day == null) "取消追番提醒" else "设置追番提醒", "提醒", mapOf("animeId" to animeId))
        return updated
    }

    suspend fun incrementProgress(
        animeId: Long,
        autoCompleteStatus: Boolean = false,
        completionStatus: String = "看完",
        expectedProgress: Int? = null,
    ): ProgressChange? {
        var pendingBangumiSync = false
        val change = database.withTransaction {
            val anime = dao.getAnime(animeId) ?: return@withTransaction null
            if (anime.deletedAt != null || (expectedProgress != null && anime.watchedEpisodes != expectedProgress)) return@withTransaction null
            if (anime.totalEpisodes > 0 && anime.watchedEpisodes >= anime.totalEpisodes) {
                return@withTransaction null
            }

            val current = anime.watchedEpisodes + 1
            val reachedEnd = anime.totalEpisodes > 0 && current >= anime.totalEpisodes
            val nextStatus = when {
                autoCompleteStatus && reachedEnd -> completionStatus
                anime.status.trim() in setOf("想看", "未看") -> "在看"
                else -> anime.status
            }
            val nextStartDate = anime.watchStartDate?.takeIf(String::isNotBlank)
                ?: java.time.LocalDate.now().toString().takeIf { anime.watchedEpisodes == 0 }
            val nextFinishDate = if (autoCompleteStatus && reachedEnd) {
                java.time.LocalDate.now().toString()
            } else {
                anime.watchFinishDate
            }
            val now = System.currentTimeMillis()
            val nextStatusChangedAt = if (nextStatus != anime.status) now else anime.statusChangedAt
            dao.updateAnime(
                anime.copy(
                    watchedEpisodes = current,
                    status = nextStatus,
                    statusChangedAt = nextStatusChangedAt,
                    updatedAt = now,
                    watchFinishDate = nextFinishDate,
                    watchStartDate = nextStartDate,
                ),
            )
            pendingBangumiSync = enqueueBangumiEpisodes(anime, add = setOf(current), remove = emptySet()) ||
                pendingBangumiSync
            if (nextStatus != anime.status) {
                pendingBangumiSync = enqueueBangumiStatus(anime, nextStatus) || pendingBangumiSync
            }
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
                previousStatusChangedAt = anime.statusChangedAt,
                currentStatusChangedAt = nextStatusChangedAt,
            )
        }
        if (pendingBangumiSync) bangumiSync?.schedulePending()
        return change
    }

    suspend fun restoreProgress(change: ProgressChange, syncBangumi: Boolean = false): Boolean {
        var pendingBangumiSync = false
        val restored = database.withTransaction {
            val anime = dao.getAnime(change.animeId) ?: return@withTransaction false
            if (anime.watchedEpisodes != change.current || anime.status != change.currentStatus ||
                anime.statusChangedAt != change.currentStatusChangedAt) return@withTransaction false
            dao.updateAnime(
                anime.copy(
                    watchedEpisodes = change.previous,
                    status = change.previousStatus ?: anime.status,
                    statusChangedAt = change.previousStatusChangedAt,
                    watchFinishDate = change.previousFinishDate,
                    watchStartDate = if (anime.watchStartDate == change.currentStartDate) change.previousStartDate else anime.watchStartDate,
                ),
            )
            change.watchRecordId?.let { dao.deleteWatchRecord(it) }
            val startedWatching = change.previousStatus?.trim() in setOf("想看", "未看") && change.currentStatus == "在看"
            if (syncBangumi || startedWatching) {
                pendingBangumiSync = enqueueBangumiEpisodes(anime, add = emptySet(), remove = setOf(change.current))
                if (change.previousStatus != null && change.previousStatus != change.currentStatus) {
                    val previousStatus = if (change.previousStatus.trim() in setOf("想看", "未看")) "想看" else change.previousStatus
                    pendingBangumiSync = enqueueBangumiStatus(anime, previousStatus) || pendingBangumiSync
                }
            }
            true
        }
        if (pendingBangumiSync) bangumiSync?.schedulePending()
        return restored
    }

    suspend fun deleteWatchRecord(recordId: Long) {
        dao.deleteWatchRecord(recordId)
        operationLog?.record("删除观看记录", "日历", mapOf("recordId" to recordId))
    }

    suspend fun updateSelectedEpisodeProgress(animeIds: Set<Long>, progress: Int): Int {
        require(progress >= 0) { "请输入有效的已看集数" }
        return database.withTransaction {
            val rows = animeIds.toList().chunked(500).flatMap { dao.getAnimes(it) }
            updateEpisodeCounts(rows, progress)
        }
    }

    suspend fun resetUnwatchedEpisodeProgress(): Int = database.withTransaction {
        updateEpisodeCounts(dao.getUnwatchedAnimesWithProgress(), 0)
    }

    suspend fun previewBatchEpisodeProgress(animeIds: Set<Long>?, progress: Int): BatchEpisodeProgress = database.withTransaction {
        val rows = if (animeIds == null) dao.getUnwatchedAnimesWithProgress()
            else animeIds.toList().chunked(500).flatMap { dao.getAnimes(it) }
        previewEpisodeProgress(rows, if (animeIds == null) 0 else progress)
    }

    suspend fun applyBatchEpisodeProgress(preview: BatchEpisodeProgress): Long = database.withTransaction {
        // Validate the whole preview before writing so a stale confirmation cannot partly apply.
        for (edit in preview.edits) {
            check(dao.getAnime(edit.before.id) == edit.before) { "作品已发生变化，请重新打开预览后保存" }
        }
        val appliedAt = System.currentTimeMillis()
        preview.edits.forEach { dao.updateAnimeProgress(it.before.id, it.progress, appliedAt) }
        appliedAt
    }

    suspend fun undoBatchEpisodeProgress(change: BatchEpisodeProgress, appliedAt: Long): Int = database.withTransaction {
        var restored = 0
        for (edit in change.edits) {
            val applied = edit.before.copy(watchedEpisodes = edit.progress, updatedAt = appliedAt)
            if (dao.getAnime(edit.before.id) != applied) continue
            dao.updateAnimeProgress(edit.before.id, edit.before.watchedEpisodes)
            restored++
        }
        restored
    }

    // Explicit corrections are local and retain past watch records and other fields.
    private suspend fun updateEpisodeCounts(rows: List<AnimeEntity>, progress: Int): Int {
        val preview = previewEpisodeProgress(rows, progress)
        preview.edits.forEach { dao.updateAnimeProgress(it.before.id, it.progress) }
        return preview.edits.size
    }

    suspend fun setProgress(animeId: Long, progress: Int) {
        var pendingBangumiSync = false
        database.withTransaction {
            val anime = dao.getAnime(animeId) ?: return@withTransaction
            val bounded = progress.coerceAtLeast(0).let { value ->
                if (anime.totalEpisodes > 0) value.coerceAtMost(anime.totalEpisodes) else value
            }
            val updated = normalizeCompletionProgress(
                anime.copy(watchedEpisodes = bounded, updatedAt = System.currentTimeMillis()),
            )
            dao.updateAnime(updated)
            // 直接设置进度：把前后差集上报为「看过 / 取消」
            val before = anime.watchedEpisodes
            val after = updated.watchedEpisodes
            pendingBangumiSync = when {
                after > before -> enqueueBangumiEpisodes(anime, add = ((before + 1)..after).toSet(), remove = emptySet())
                after < before -> enqueueBangumiEpisodes(anime, add = emptySet(), remove = ((after + 1)..before).toSet())
                else -> false
            }
        }
        if (pendingBangumiSync) bangumiSync?.schedulePending()
    }

    suspend fun setFunRatingTier(animeId: Long, tier: String?) {
        dao.updateAnimeFunTier(animeId, tier)
    }

    suspend fun clearFunRatingTiers() {
        dao.clearAnimeFunTiers()
    }

    // 以下三个入口只在对应开关打开且作品挂了 Bangumi 条目时才入队（Local-First）。
    // 既有撤销默认不上报；播放器撤销通过 syncBangumi 显式排入取消观看/还原状态。
    private suspend fun enqueueBangumiEpisodes(anime: AnimeEntity, add: Set<Int>, remove: Set<Int>): Boolean {
        val sync = bangumiSync ?: return false
        val subjectId = bangumiSubjectIdOf(anime) ?: return false
        return sync.enqueueEpisodes(anime.id, subjectId, add, remove)
    }

    private suspend fun enqueueBangumiStatus(anime: AnimeEntity, status: String): Boolean {
        val sync = bangumiSync ?: return false
        val type = bangumiCollectionType(status) ?: return false
        val subjectId = bangumiSubjectIdOf(anime) ?: return false
        return sync.enqueueStatus(anime.id, subjectId, type)
    }

    private suspend fun enqueueBangumiRating(anime: AnimeEntity): Boolean {
        val sync = bangumiSync ?: return false
        val subjectId = bangumiSubjectIdOf(anime) ?: return false
        return sync.enqueueRating(anime.id, subjectId, bangumiRate(anime.rating))
    }
}

internal fun normalizeCompletionProgress(anime: AnimeEntity): AnimeEntity =
    if (anime.status.trim() in COMPLETED_STATUSES && anime.totalEpisodes > 0 && anime.watchedEpisodes != anime.totalEpisodes) {
        anime.copy(watchedEpisodes = anime.totalEpisodes)
    } else {
        anime
    }

private fun normalizedMatchTitle(value: String): String = value
    .trim()
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("[\\s　]+"), "")
