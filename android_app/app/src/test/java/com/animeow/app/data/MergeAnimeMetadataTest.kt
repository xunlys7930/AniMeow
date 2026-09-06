package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MergeAnimeMetadataTest {
    @Test
    fun mergePreservesTargetIdentityAndMostCompleteMetadata() {
        val target = AnimeEntity(id = 1, title = "作品", watchedEpisodes = 3, totalEpisodes = 0)
        val source = AnimeEntity(
            id = 2,
            title = "作品 ",
            coverUrl = "cover",
            review = "更完整的长评价",
            watchedEpisodes = 8,
            totalEpisodes = 12,
            rating = 90,
            status = "看完",
        )

        val merged = mergeAnimeMetadata(target, listOf(source))

        assertEquals(1L, merged.id)
        assertEquals("作品", merged.title)
        assertEquals("cover", merged.coverUrl)
        assertEquals(12, merged.watchedEpisodes)
        assertEquals(12, merged.totalEpisodes)
        assertEquals(90, merged.rating)
        assertEquals("看完", merged.status)
    }

    @Test
    fun mergeKeepsExternalSourceAndIdFromTheSameRecord() {
        val target = AnimeEntity(id = 1, title = "作品", externalSource = "partial")
        val source = AnimeEntity(
            id = 2,
            title = "作品",
            externalSource = "bangumi",
            externalId = "123",
            externalUrl = "https://bgm.tv/subject/123",
        )

        val merged = mergeAnimeMetadata(target, listOf(source))

        assertEquals("bangumi", merged.externalSource)
        assertEquals("123", merged.externalId)
        assertEquals("https://bgm.tv/subject/123", merged.externalUrl)
    }

    @Test
    fun mergeKeepsTheTargetsRatingModeAtomic() {
        val target = AnimeEntity(id = 1, title = "作品", rating = 90)
        val source = AnimeEntity(id = 2, title = "作品", ratingGrade = "A")

        val merged = mergeAnimeMetadata(target, listOf(source))

        assertEquals(90, merged.rating)
        assertNull(merged.ratingGrade)
    }

    @Test
    fun mergeUsesOneCompleteReminderInsteadOfCombiningPartialValues() {
        val target = AnimeEntity(id = 1, title = "作品", reminderDay = 2)
        val timeOnly = AnimeEntity(id = 2, title = "作品", reminderTime = "21:00")
        val complete = AnimeEntity(id = 3, title = "作品", reminderDay = 5, reminderTime = "22:30")

        val merged = mergeAnimeMetadata(target, listOf(timeOnly, complete))

        assertEquals(5, merged.reminderDay)
        assertEquals("22:30", merged.reminderTime)
    }

    @Test
    fun mergeNeverBorrowsExternalUrlFromAnotherIdentity() {
        val target = AnimeEntity(
            id = 1,
            title = "作品",
            externalSource = "bangumi",
            externalId = "123",
        )
        val differentIdentity = AnimeEntity(
            id = 2,
            title = "作品",
            externalSource = "anilist",
            externalId = "999",
            externalUrl = "https://anilist.co/anime/999",
        )
        val sameIdentity = AnimeEntity(
            id = 3,
            title = "作品",
            externalSource = "BANGUMI",
            externalId = "123",
            externalUrl = "https://bgm.tv/subject/123",
        )

        val merged = mergeAnimeMetadata(target, listOf(differentIdentity, sameIdentity))

        assertEquals("bangumi", merged.externalSource)
        assertEquals("123", merged.externalId)
        assertEquals("https://bgm.tv/subject/123", merged.externalUrl)
    }

    @Test
    fun remoteRatingDoesNotOverwriteLetterGradeByDefault() {
        val merged = mergeRemoteAnimeRating(
            currentRating = null,
            currentGrade = "A",
            remoteRating = 86,
            overwriteExisting = false,
        )

        assertNull(merged.rating)
        assertEquals("A", merged.ratingGrade)
    }

    @Test
    fun remoteRatingReplacesLetterGradeAtomicallyWhenOverwriteIsEnabled() {
        val merged = mergeRemoteAnimeRating(
            currentRating = null,
            currentGrade = "B",
            remoteRating = 86,
            overwriteExisting = true,
        )

        assertEquals(86, merged.rating)
        assertNull(merged.ratingGrade)
    }
}
