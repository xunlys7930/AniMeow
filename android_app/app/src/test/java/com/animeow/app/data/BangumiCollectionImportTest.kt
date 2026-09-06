package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.BangumiCollectionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiCollectionImportTest {
    @Test
    fun activeExternalMatchWinsOverTrashedDuplicate() {
        val item = collectionItem(subjectId = 42, title = "测试作品")
        val trashed = AnimeEntity(
            id = 1,
            title = "旧回收站条目",
            externalSource = "bangumi",
            externalId = "42",
            deletedAt = "2026-01-01T00:00:00Z",
        )
        val active = AnimeEntity(
            id = 2,
            title = "活动条目",
            externalSource = "Bangumi",
            externalId = "42",
        )

        val candidate = buildBangumiImportCandidates(listOf(item), listOf(trashed, active)).single()

        assertEquals(BangumiImportMatchKind.EXTERNAL_ID, candidate.matchKind)
        assertEquals(2L, candidate.existingAnimeId)
    }

    @Test
    fun mergeNeverReducesLocalProgress() {
        val existing = AnimeEntity(
            id = 7,
            title = "测试作品",
            status = "在看",
            watchedEpisodes = 8,
            totalEpisodes = 12,
            externalSource = "bangumi",
            externalId = "42",
        )

        val merged = mergeBangumiCollection(
            existing = existing,
            item = collectionItem(subjectId = 42, title = "测试作品", watched = 3, total = 12),
            now = "2026-01-01T00:00:00Z",
        )

        assertEquals(8, merged.watchedEpisodes)
        assertEquals("在看", merged.status)
        assertFalse(merged.deletedAt != null)
    }

    @Test
    fun completedCollectionFillsKnownTotalEpisodes() {
        val completed = collectionItem(
            subjectId = 42,
            title = "测试作品",
            watched = 3,
            total = 12,
            collectionType = 2,
        ).toAnimeEntity("2026-01-01T00:00:00Z")

        assertEquals("看完", completed.status)
        assertEquals(12, completed.watchedEpisodes)
    }

    @Test
    fun restoredItemsCountAsChanges() {
        val summary = BangumiImportSummary(
            addedCount = 1,
            mergedCount = 2,
            restoredCount = 3,
            skippedCount = 4,
            importedTagCount = 5,
        )

        assertEquals(6, summary.changedCount)
        assertTrue(normalizedBangumiTitle("  A　B  ") == "ab")
    }

    @Test
    fun mergeKeepsLetterRatingExclusiveAndMatchesSourceCaseInsensitively() {
        val existing = AnimeEntity(
            id = 7,
            title = "测试作品",
            rating = 80,
            ratingGrade = "A",
            externalSource = "Bangumi",
            externalId = "42",
        )

        val merged = mergeBangumiCollection(
            existing = existing,
            item = collectionItem(subjectId = 42, title = "测试作品", score = 6.5),
            now = "2026-01-01T00:00:00Z",
        )

        assertEquals("A", merged.ratingGrade)
        assertNull(merged.rating)
    }

    @Test
    fun titleMergeDoesNotMixBangumiUrlIntoAnotherExternalIdentity() {
        val existing = AnimeEntity(
            id = 7,
            title = "测试作品",
            externalSource = "anilist",
            externalId = "999",
            externalUrl = null,
        )

        val merged = mergeBangumiCollection(
            existing = existing,
            item = collectionItem(subjectId = 42, title = "测试作品"),
            now = "2026-01-01T00:00:00Z",
        )

        assertEquals("anilist", merged.externalSource)
        assertEquals("999", merged.externalId)
        assertNull(merged.externalUrl)
    }

    @Test
    fun titleMergeReplacesAnIncompleteExternalIdentityAsOneUnit() {
        val existing = AnimeEntity(
            id = 7,
            title = "测试作品",
            externalSource = "legacy",
            externalUrl = "https://legacy.invalid/item",
        )

        val merged = mergeBangumiCollection(
            existing = existing,
            item = collectionItem(subjectId = 42, title = "测试作品"),
            now = "2026-01-01T00:00:00Z",
        )

        assertEquals("bangumi", merged.externalSource)
        assertEquals("42", merged.externalId)
        assertEquals("https://bgm.tv/subject/42", merged.externalUrl)
    }

    private fun collectionItem(
        subjectId: Long,
        title: String,
        watched: Int = 0,
        total: Int = 12,
        score: Double? = null,
        collectionType: Int = 3,
    ) = BangumiCollectionItem(
        subjectId = subjectId,
        collectionType = collectionType,
        title = title,
        originalTitle = title,
        watchedEpisodes = watched,
        totalEpisodes = total,
        subjectScore = score,
    )
}
