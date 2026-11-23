package com.animeow.app.ui.statistics

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.WatchStatusEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsCalculationTest {
    @Test fun booksNeverBecomeAnimationMinutesAndOverflowIsClamped() {
        val book = AnimeEntity(id = 1, title = "书籍", subjectType = "book", watchedEpisodes = 30)
        val animation = AnimeEntity(id = 2, title = "动画", watchedEpisodes = 2)
        val state = calculateStatistics(listOf(book, animation), emptyList(), emptyList(), emptyList(),
            episodeDetails = listOf(EpisodeDetailEntity(1, 1, durationMinutes = 60), EpisodeDetailEntity(2, 1, durationMinutes = 10)))
        assertEquals(2, state.watchedEpisodes)
        assertEquals(34L, state.watchMinutes)
        assertEquals(1, state.bookCount)
        assertEquals(0L, estimateWatchMinutes(listOf(book), listOf(EpisodeDetailEntity(1, 1, durationMinutes = 60))))
        assertEquals(Int.MAX_VALUE, calculateStatistics(listOf(animation.copy(watchedEpisodes = Int.MAX_VALUE), animation.copy(id = 3)), emptyList(), emptyList(), emptyList()).watchedEpisodes)
    }

    @Test fun dateChangesRefreshStreakAndMonthWindowAndIgnoreOrphanRecords() {
        val source = listOf(AnimeEntity(id = 1, title = "记录"))
        val records = listOf(WatchRecordEntity(1, 1, 1, recordDate = "2026-10-30"), WatchRecordEntity(2, 9, 1, recordDate = "2026-11-01"))
        val before = calculateStatistics(source, emptyList(), emptyList(), records, today = LocalDate.of(2026, 10, 31))
        val after = calculateStatistics(source, emptyList(), emptyList(), records, today = LocalDate.of(2026, 11, 1))
        assertEquals(1, before.currentStreakDays); assertEquals(0, after.currentStreakDays)
        assertEquals("10月", before.monthlyActivity.last().name); assertEquals("11月", after.monthlyActivity.last().name)
        assertEquals(0, after.monthlyActivity.last().count)
        assertEquals(LocalDate.of(2026, 11, 1), com.animeow.app.data.local.watchRecordDate("2026-10-31T18:00:00Z", java.time.ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun usesFilledDurationsOnlyForWatchedEpisodesAndEstimatesMissingOnes() {
        val state = calculateStatistics(
            animes = listOf(AnimeEntity(id = 1, title = "A", watchedEpisodes = 3)),
            tags = emptyList(), links = emptyList(), records = emptyList(),
            episodeDetails = listOf(
                EpisodeDetailEntity(1, 1, durationMinutes = 45),
                EpisodeDetailEntity(1, 2, durationMinutes = 5),
                EpisodeDetailEntity(1, 4, durationMinutes = 120),
                EpisodeDetailEntity(2, 1, durationMinutes = 120),
            ),
        )
        assertEquals(74L, state.watchMinutes)
        assertEquals(1, state.watchHours)
        assertEquals("1 小时 14 分钟", formatEstimatedWatchTime(state.watchMinutes))
    }

    @Test
    fun editingDurationClearingItAndRollingBackProgressRecalculatesEstimate() {
        val anime = AnimeEntity(id = 1, title = "A", watchedEpisodes = 2)
        val detail = EpisodeDetailEntity(1, 2, durationMinutes = 90)
        assertEquals(114L, estimateWatchMinutes(listOf(anime), listOf(detail)))
        assertEquals(69L, estimateWatchMinutes(listOf(anime), listOf(detail.copy(durationMinutes = 45))))
        assertEquals(48L, estimateWatchMinutes(listOf(anime), listOf(detail.copy(durationMinutes = null))))
        assertEquals(24L, estimateWatchMinutes(listOf(anime.copy(watchedEpisodes = 1)), listOf(detail)))
        assertEquals(0L, estimateWatchMinutes(listOf(anime.copy(watchedEpisodes = 0)), listOf(detail)))
    }

    @Test
    fun invalidDurationsFallBackAndDeletedOrInvalidEpisodesDoNotCount() {
        val details = listOf(
            EpisodeDetailEntity(1, 1, durationMinutes = 0),
            EpisodeDetailEntity(1, 2, durationMinutes = -1),
            EpisodeDetailEntity(1, 3, durationMinutes = 1441),
            EpisodeDetailEntity(1, 0, durationMinutes = 120),
            EpisodeDetailEntity(2, 1, durationMinutes = 120),
        )
        assertEquals(72L, estimateWatchMinutes(listOf(
            AnimeEntity(id = 1, title = "A", watchedEpisodes = 3),
            AnimeEntity(id = 2, title = "回收站", watchedEpisodes = 1, deletedAt = "2026-10-10"),
        ), details))
        assertEquals(0L, estimateWatchMinutes(emptyList(), details))
    }

    @Test
    fun largeProgressDoesNotOverflowOrEnumerateAllEpisodes() {
        assertEquals(Int.MAX_VALUE.toLong() * 24 + 1416, estimateWatchMinutes(
            listOf(AnimeEntity(id = 1, title = "大集数", watchedEpisodes = Int.MAX_VALUE)),
            listOf(EpisodeDetailEntity(1, Int.MAX_VALUE, durationMinutes = 1440)),
        ))
    }

    @Test
    fun displaysMinutesInsteadOfDiscardingPartialHours() {
        assertEquals("0 分钟", formatEstimatedWatchTime(0))
        assertEquals("45 分钟", formatEstimatedWatchTime(45))
        assertEquals("2 小时", formatEstimatedWatchTime(120))
        assertEquals("2 小时 1 分钟", formatEstimatedWatchTime(121))
    }

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
        assertEquals(10, state.watchedEpisodes)
        assertEquals(240L, state.watchMinutes)
        assertEquals(4, state.watchHours)
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
