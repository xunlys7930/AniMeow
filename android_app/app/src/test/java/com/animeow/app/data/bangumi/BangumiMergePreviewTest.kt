package com.animeow.app.data.bangumi

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.BangumiCollectionItem
import org.junit.Assert.assertEquals
import org.junit.Test

class BangumiMergePreviewTest {
    private fun local(id: Long, subjectId: String? = null, deleted: Boolean = false) = AnimeEntity(
        id = id, title = "同名作品", externalSource = subjectId?.let { "bangumi" }, externalId = subjectId,
        deletedAt = if (deleted) "2026-01-01" else null,
    )

    private fun remote(id: Long) = BangumiCollectionItem(
        subjectId = id, collectionType = 3, title = "同名作品", originalTitle = "同名作品",
    )

    @Test fun exactIdentityMatchesButSameTitleDoesNot() {
        val rows = buildBangumiMergePreview(listOf(local(1, "42"), local(2)), listOf(remote(42), remote(43)), true)
        assertEquals(listOf(BangumiMergeMatch.MATCHED, BangumiMergeMatch.UNLINKED, BangumiMergeMatch.REMOTE_ONLY), rows.map { it.match })
    }

    @Test fun partialRemoteSnapshotNeverProvesAbsence() {
        val row = buildBangumiMergePreview(listOf(local(1, "42")), emptyList(), false).single()
        assertEquals(BangumiMergeMatch.REMOTE_UNKNOWN, row.match)
    }

    @Test fun completeSnapshotIdentifiesLocalOnlyWithoutMatchingSameName() {
        val rows = buildBangumiMergePreview(listOf(local(1, "42")), listOf(remote(43)), true)
        assertEquals(listOf(BangumiMergeMatch.LOCAL_ONLY, BangumiMergeMatch.REMOTE_ONLY), rows.map { it.match })
    }

    @Test fun otherSourceAndInvalidBangumiIdAreUnlinked() {
        val otherSource = local(1, "42").copy(externalSource = "anilist")
        val invalidId = local(2, "0")
        val rows = buildBangumiMergePreview(listOf(otherSource, invalidId), emptyList(), true)
        assertEquals(listOf(BangumiMergeMatch.UNLINKED, BangumiMergeMatch.UNLINKED), rows.map { it.match })
    }

    @Test fun deletedIdentityIsNotTreatedAsRemoteOnly() {
        val row = buildBangumiMergePreview(listOf(local(1, "42", deleted = true)), listOf(remote(42)), true).single()
        assertEquals(BangumiMergeMatch.DELETED, row.match)
    }

    @Test fun duplicateIdentitiesRequireReview() {
        val rows = buildBangumiMergePreview(listOf(local(1, "42"), local(2, "42")), listOf(remote(42)), true)
        assertEquals(listOf(BangumiMergeMatch.AMBIGUOUS, BangumiMergeMatch.AMBIGUOUS), rows.map { it.match })
        assertEquals(BangumiMergeMatch.AMBIGUOUS, buildBangumiMergePreview(emptyList(), listOf(remote(43), remote(43)), true).first().match)
    }
}
