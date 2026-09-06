package com.animeow.app.ui.statistics

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.WatchStatusEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsCalculationTest {
    @Test
    fun calculatesOverviewTagsAndStreak() {
        val state = calculateStatistics(
            animes = listOf(
                AnimeEntity(id = 1, title = "A", status = "在看", watchedEpisodes = 10, rating = 90),
                AnimeEntity(id = 2, title = "B", status = "看完", watchedEpisodes = 20, rating = 80, subjectType = "book"),
            ),
            tags = listOf(TagEntity(id = 7, name = "治愈")),
            links = listOf(AnimeTagEntity(1, 7), AnimeTagEntity(2, 7)),
            records = listOf(
                WatchRecordEntity(id = 1, animeId = 1, episode = 9, recordDate = "2026-08-10"),
                WatchRecordEntity(id = 2, animeId = 1, episode = 10, recordDate = "2026-08-11"),
            ),
            statuses = listOf(
                WatchStatusEntity(id = 1, name = "在看", color = 0xFF112233, sortOrder = 0),
                WatchStatusEntity(id = 2, name = "看完", color = 0xFF445566, sortOrder = 1),
                WatchStatusEntity(id = 3, name = "搁置", color = 0xFF778899, sortOrder = 2),
            ),
            today = LocalDate.of(2026, 8, 11),
        )

        assertEquals(2, state.totalAnime)
        assertEquals(1, state.animeCount)
        assertEquals(1, state.bookCount)
        assertEquals(30, state.watchedEpisodes)
        assertEquals(8.5, state.averageRating!!, 0.001)
        assertEquals(2, state.currentStreakDays)
        assertEquals(NamedCount("治愈", 2, id = 7), state.tagCounts.single())
        assertEquals("在看", state.statusCounts.first { it.name == "在看" }.filterValue)
        assertEquals(0xFF112233, state.statusCounts.first { it.name == "在看" }.color)
        assertEquals(0, state.statusCounts.first { it.name == "搁置" }.count)
    }

    @Test
    fun statusCollectionSearchesAndSortsWithoutChangingSource() {
        val source = listOf(
            AnimeEntity(id = 1, title = "Beta", originalTitle = "原名乙", rating = 90, watchedEpisodes = 3, totalEpisodes = 12),
            AnimeEntity(id = 2, title = "Alpha", originalTitle = "原名甲", rating = 70, watchedEpisodes = 8, totalEpisodes = 8),
        )

        assertEquals(listOf(2L, 1L), filterStatusAnime(source, "", StatusCollectionSort.RECENT).map { it.id })
        assertEquals(listOf(2L, 1L), filterStatusAnime(source, "", StatusCollectionSort.TITLE).map { it.id })
        assertEquals(listOf(2L), filterStatusAnime(source, "原名甲", StatusCollectionSort.RATING).map { it.id })
        assertEquals(listOf(2L, 1L), filterStatusAnime(source, "", StatusCollectionSort.PROGRESS).map { it.id })
        assertEquals(2, source.size)
    }

    @Test
    fun ignoresLegacyZeroRatingsInsteadOfCountingThemAsPointOne() {
        val state = calculateStatistics(
            animes = listOf(
                AnimeEntity(id = 1, title = "未评分 A", rating = 0),
                AnimeEntity(id = 2, title = "未评分 B", rating = 0),
                AnimeEntity(id = 3, title = "有效评分", rating = 85),
            ),
            tags = emptyList(),
            links = emptyList(),
            records = emptyList(),
        )

        assertEquals(8.5, state.averageRating!!, 0.001)
        assertEquals(1, state.ratingCounts.sumOf(NamedCount::count))
        assertEquals(0, state.ratingCounts.last().count)
    }

    @Test
    fun countsLegacyReadingValuesAsBooks() {
        val state = calculateStatistics(
            animes = listOf(
                AnimeEntity(id = 1, title = "动画", subjectType = "anime"),
                AnimeEntity(id = 2, title = "旧漫画值", subjectType = "manga"),
                AnimeEntity(id = 3, title = "旧小说值", subjectType = "novel"),
            ),
            tags = emptyList(),
            links = emptyList(),
            records = emptyList(),
        )

        assertEquals(1, state.animeCount)
        assertEquals(2, state.bookCount)
    }
}
