package com.animeow.app.data

import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.local.encodeAnimeSourceReferences
import androidx.room.withTransaction
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.withNormalizedSubjectType
import com.animeow.app.data.local.simplifyTagName
import com.animeow.app.data.local.normalizeTagKey
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.sourceRatingSnapshot
import com.animeow.app.data.diagnostics.OperationLogService

internal class LibraryMetadataOperations(
    private val database: AniMeowDatabase,
    private val operationLog: OperationLogService?,
    private val onPublicMetadata: (RemoteAnime) -> Unit,
) {
    private val dao = database.libraryDao()

    suspend fun applyRemoteMetadata(
        animeId: Long,
        remote: RemoteAnime,
        fields: RemoteMetadataFields,
        overwriteExisting: Boolean,
        preservePersonalProgress: Boolean = false,
    ): Boolean {
        if (!fields.hasSelection) return false
        runCatching { onPublicMetadata(remote) }
        return database.withTransaction {
            val current = requireNotNull(dao.getAnime(animeId)?.takeIf { it.deletedAt == null }) { "作品已被移除，请重新选择" }
            val remoteOwner = if (fields.externalLink) dao.getAnimeByExternalIdIncludingDeleted(remote.importSource, remote.importId) else null
            val canSetRemoteIdentity = remoteOwner == null || remoteOwner.id == current.id
            val replaceRemoteIdentity = fields.externalLink && canSetRemoteIdentity &&
                (overwriteExisting || !current.hasCompleteExternalIdentity())
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
            val next = current.copy(
                sourceRatingJson = if (fields.rating || replaceRemoteIdentity ||
                    (current.externalSource == remote.importSource && current.externalId == remote.importId)) remote.sourceRatingSnapshot() ?: current.sourceRatingJson else current.sourceRatingJson,
                coverUrl = if (fields.cover && !com.animeow.app.data.media.isLocalCustomCoverUrl(current.coverUrl)) {
                    mergeRemoteText(current.coverUrl, remote.coverUrl, overwriteExisting)
                } else {
                    current.coverUrl
                },
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
                externalSource = if (replaceRemoteIdentity) {
                    remote.importSource
                } else {
                    current.externalSource
                },
                externalId = if (replaceRemoteIdentity) {
                    remote.importId
                } else {
                    current.externalId
                },
                externalUrl = if (replaceRemoteIdentity && (current.externalSource != remote.importSource || current.externalId != remote.importId)) {
                    remote.siteUrl
                } else if (replaceRemoteIdentity ||
                    (fields.externalLink && current.externalSource == remote.importSource && current.externalId == remote.importId)) {
                    mergeRemoteText(current.externalUrl, remote.siteUrl, overwriteExisting)
                } else {
                    current.externalUrl
                },
                mergedSourcesJson = if (fields.externalLink) encodeAnimeSourceReferences(
                    current.sourceReferences() + com.animeow.app.data.local.AnimeSourceReference(
                        remote.title, remote.originalTitle, remote.importSource, remote.importId, remote.siteUrl,
                    ),
                ) else current.mergedSourcesJson,
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
            ).withNormalizedSubjectType().let { if (preservePersonalProgress) it else normalizeCompletionProgress(it) }
            if (next == current && !fields.tags) return@withTransaction false
            if (next != current) {
                dao.updateAnime(next)
            }
            // 标签同步：从云端匹配结果中同步标签到本地
            var tagsChanged = false
            if (fields.tags && remote.tags.isNotEmpty()) {
                val existingTags = dao.getAllTags().associateBy { normalizeTagKey(it.name) }.toMutableMap()
                val blockedKeys = dao.getAllTags().filter { it.blocked }.map { normalizeTagKey(it.name) }.toSet()
                val remoteTagIds = remote.tags.mapNotNull { rawName ->
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
                val currentTagIds = dao.getAnimeTagsByAnimeId(animeId).map { it.tagId }.toSet()
                val newTagIds = remoteTagIds.filterNot { it in currentTagIds }
                if (newTagIds.isNotEmpty()) {
                    dao.insertAnimeTags(newTagIds.map { tagId -> AnimeTagEntity(animeId, tagId) })
                    tagsChanged = true
                }
            }
            if (next == current && !tagsChanged) return@withTransaction false
            operationLog?.record(
                "批量联网补全资料",
                "首页",
                mapOf(
                    "animeId" to animeId,
                    "source" to remote.importSource,
                    "overwrite" to overwriteExisting,
                ),
            )
            true
        }
    }
}

private fun mergeRemoteText(current: String?, remote: String?, overwrite: Boolean): String? {
    val candidate = remote?.trim()?.takeIf(String::isNotEmpty) ?: return current
    return if (overwrite || current.isNullOrBlank()) candidate else current
}

private fun <T> mergeRemoteNumber(current: T?, remote: T?, overwrite: Boolean): T? =
    if (remote != null && (overwrite || current == null)) remote else current

internal fun AnimeEntity.hasCompleteExternalIdentity(): Boolean =
    !externalSource.isNullOrBlank() && !externalId.isNullOrBlank()

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
