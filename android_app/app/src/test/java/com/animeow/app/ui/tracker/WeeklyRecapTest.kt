package com.animeow.app.ui.tracker

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.WatchRecordEntity
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WeeklyRecapTest {
    @Test fun excludesFutureDeletedAndPreviousWeekRecordsAndDeduplicatesEpisodes() {
        val anime = AnimeEntity(id = 1, title = "旅人")
        val deleted = AnimeEntity(id = 2, title = "回收站", deletedAt = "2026-09-09")
        val records = listOf(
            WatchRecordEntity(1, 1, 1, recordDate = "2026-09-07"), WatchRecordEntity(2, 1, 1, recordDate = "2026-09-07"),
            WatchRecordEntity(3, 1, 2, recordDate = "2026-09-09"), WatchRecordEntity(4, 1, 3, recordDate = "2026-09-12"),
            WatchRecordEntity(5, 1, 4, recordDate = "2026-09-06"), WatchRecordEntity(6, 2, 1, recordDate = "2026-09-08"),
            WatchRecordEntity(7, 1, 5, recordDate = "invalid"))
        val result = buildWeeklyRecap(listOf(anime, deleted), records, LocalDate.of(2026, 9, 11))
        assertEquals(2, result.episodeCount)
        assertEquals(2, result.activeDays)
        assertEquals(1, result.workCount)
        assertEquals(listOf("旅人"), result.titles)
    }
}
