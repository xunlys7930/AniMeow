package com.animeow.app.data.bangumi

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.BangumiCollectionItem

/** A read-only comparison; no proposed row is an instruction to write either library. */
enum class BangumiMergeMatch {
    MATCHED, LOCAL_ONLY, REMOTE_ONLY, UNLINKED, DELETED, AMBIGUOUS, REMOTE_UNKNOWN,
}

data class BangumiMergeRow(
    val local: AnimeEntity?,
    val remote: BangumiCollectionItem?,
    val match: BangumiMergeMatch,
)

/**
 * Identity comes only from an existing Bangumi ID, never a title. A partial remote
 * snapshot cannot establish that an item is absent, so it must not create uploads.
 */
internal fun buildBangumiMergePreview(
    localItems: List<AnimeEntity>,
    remoteItems: List<BangumiCollectionItem>,
    remoteComplete: Boolean,
): List<BangumiMergeRow> {
    val localById = localItems.mapNotNull { item -> bangumiSubjectIdOf(item)?.let { it to item } }
        .groupBy({ it.first }, { it.second })
    val remoteById = remoteItems.groupBy(BangumiCollectionItem::subjectId)
    val rows = mutableListOf<BangumiMergeRow>()

    localItems.forEach { local ->
        val subjectId = bangumiSubjectIdOf(local)
        val matches = subjectId?.let { remoteById[it] }.orEmpty()
        val remote = matches.singleOrNull()
        val match = when {
            subjectId != null && (localById[subjectId].orEmpty().size > 1 || matches.size > 1) -> BangumiMergeMatch.AMBIGUOUS
            local.deletedAt != null -> BangumiMergeMatch.DELETED
            subjectId == null -> BangumiMergeMatch.UNLINKED
            remote != null -> BangumiMergeMatch.MATCHED
            !remoteComplete -> BangumiMergeMatch.REMOTE_UNKNOWN
            else -> BangumiMergeMatch.LOCAL_ONLY
        }
        rows += BangumiMergeRow(local, remote, match)
    }
    remoteById.forEach { (subjectId, matches) ->
        if (subjectId !in localById) {
            matches.forEach { remote ->
                rows += BangumiMergeRow(null, remote, if (matches.size == 1) BangumiMergeMatch.REMOTE_ONLY else BangumiMergeMatch.AMBIGUOUS)
            }
        }
    }
    return rows
}
