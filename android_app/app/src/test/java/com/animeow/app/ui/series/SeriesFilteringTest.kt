package com.animeow.app.ui.series

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.preferences.SeriesSort
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesFilteringTest {
    @Test
    fun statusAndSearchWorkTogetherIncludingCustomStatusesAndOriginalTitles() {
        val items = listOf(
            AnimeEntity(id = 1, title = "第一季", originalTitle = "Summer", status = "在看"),
            AnimeEntity(id = 2, title = "第二季", originalTitle = "Summer 2", status = "重温"),
            AnimeEntity(id = 3, title = "剧场版", status = "看完"),
        )
        assertEquals(listOf(1L), filterSeriesAnimes(items, "在看", " summer ").map { it.id })
        assertEquals(listOf(2L), filterSeriesAnimes(items, "重温", "summer").map { it.id })
        assertEquals(emptyList<AnimeEntity>(), filterSeriesAnimes(items, "在看", "不存在"))
        assertEquals(items, filterSeriesAnimes(items, null, ""))
    }

    @Test fun mixedSeriesFiltersKeepIndependentBookProgress() {
        val book = AnimeEntity(id = 1, title = "原作", subjectType = "book", bookReadVolumes = 3, bookReadChapters = 17, watchedEpisodes = 99)
        val anime = AnimeEntity(id = 2, title = "改编异名", watchedEpisodes = 6, totalEpisodes = 12)
        assertEquals(listOf(book), filterSeriesAnimes(listOf(book, anime), null, "", "book"))
        assertEquals(listOf(anime), filterSeriesAnimes(listOf(book, anime), null, "", "anime"))
        assertEquals("已读 3 卷 · 已读 17 话", seriesProgressText(book))
        assertEquals("阅读进度未记录", seriesProgressText(book.copy(bookReadVolumes = null, bookReadChapters = null)))
        assertEquals("6/12 集", seriesProgressText(anime))
    }

    @Test
    fun unknownDatesAndRatingsRemainLastInEitherDirection() {
        val items = listOf(
            AnimeEntity(id = 1, title = "没有资料"),
            AnimeEntity(id = 2, title = "较早", airDate = "2025-01-01", rating = 70),
            AnimeEntity(id = 3, title = "较新", airDate = "2026-01-01", rating = 90),
        )
        listOf(SeriesSort.AIR_DATE, SeriesSort.RATING).forEach { sort ->
            assertEquals(listOf(2L, 3L, 1L), sortSeriesAnimes(items, sort, true).map { it.id })
            assertEquals(listOf(3L, 2L, 1L), sortSeriesAnimes(items, sort, false).map { it.id })
        }
    }
}
