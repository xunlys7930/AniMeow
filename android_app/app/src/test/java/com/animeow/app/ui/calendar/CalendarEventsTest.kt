package com.animeow.app.ui.calendar

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventsTest {
    @Test
    fun importedBroadcastsStartAtPremiereAndRemainSeparateFromReminders() {
        val anime = AnimeEntity(id = 1, title = "新番", broadcastDay = 1, airDate = "2026-09-09")
        val events = buildCalendarEvents(listOf(anime), emptyList(), YearMonth.of(2026, 9))
        val broadcasts = events.filter { it.type == CalendarEventType.BROADCAST && it.date.monthValue == 9 }
        assertEquals(listOf(14, 21, 28), broadcasts.map { it.date.dayOfMonth })
        assertTrue(broadcasts.all { it.time == null && "时间待定" in it.subtitle })
        assertTrue(events.none { it.type == CalendarEventType.REMINDER })
        assertTrue(buildCalendarEvents(listOf(anime.copy(status = "看完")), emptyList(), YearMonth.of(2026, 9)).none { it.type == CalendarEventType.BROADCAST })
    }

    @Test
    fun completionDateAndItsCheckInProduceOneCompletionEvent() {
        val anime = AnimeEntity(id = 1, title = "作品", watchFinishDate = "2026-09-06", totalEpisodes = 12)
        val record = WatchRecordEntity(id = 2, animeId = 1, episode = 12, status = "completed", recordDate = "2026-09-06T20:00:00+08:00")
        val events = buildCalendarEvents(listOf(anime), listOf(record), YearMonth.of(2026, 9))
        assertEquals(1, events.count { it.type == CalendarEventType.WATCH_FINISH })
        assertEquals(2L, events.single().watchRecordId)
    }

    @Test
    fun buildsDatesRecordsAndWeeklyReminders() {
        val anime = AnimeEntity(
            id = 9,
            title = "测试作品",
            airDate = "2026-08-03",
            watchStartDate = "2026-08-04",
            reminderDay = 5,
            reminderTime = "20:30",
        )
        val records = listOf(
            WatchRecordEntity(id = 1, animeId = 9, episode = 3, recordDate = "2026-08-07T12:00:00"),
            WatchRecordEntity(id = 2, animeId = 9, episode = 12, status = "completed", recordDate = "2026-08-08"),
            WatchRecordEntity(id = 3, animeId = 9, episode = 12, status = "completed", recordDate = "2026-08-09"),
        )

        val events = buildCalendarEvents(listOf(anime), records, YearMonth.of(2026, 8))

        assertTrue(events.any { it.type == CalendarEventType.AIR_DATE && it.date == LocalDate.of(2026, 8, 3) })
        assertTrue(
            events.any {
                it.type == CalendarEventType.WATCH_RECORD &&
                    it.subtitle == "打卡第 3 集" &&
                    it.watchRecordId == 1L
            },
        )
        assertTrue(
            events.any {
                it.type == CalendarEventType.WATCH_FINISH &&
                    it.subtitle == "完成观看（第 2 次）" &&
                    it.watchRecordId == 3L
            },
        )
        assertEquals(
            listOf(7, 14, 21, 28),
            events.filter { it.type == CalendarEventType.REMINDER && it.date.monthValue == 8 }
                .map { it.date.dayOfMonth },
        )
    }

    @Test
    fun findsOnlyDatesRequiredByCurrentStatus() {
        val items = buildMissingDateItems(
            listOf(
                AnimeEntity(id = 1, title = "在看作品", status = "在看"),
                AnimeEntity(id = 2, title = "完成作品", status = "看完", airDate = "2024-01-01"),
                AnimeEntity(id = 3, title = "未看作品", status = "未看", airDate = "2025-01-01"),
            ),
        )

        assertEquals(setOf(MissingDateKind.AIR_DATE, MissingDateKind.WATCH_START), items.first { it.anime.id == 1L }.missing)
        assertEquals(setOf(MissingDateKind.WATCH_FINISH), items.first { it.anime.id == 2L }.missing)
        assertTrue(items.none { it.anime.id == 3L })
    }

    @Test
    fun remoteDateRepairPrefersExactExternalId() {
        val anime = AnimeEntity(
            id = 8,
            title = "相同标题",
            externalSource = "bangumi",
            externalId = "42",
        )
        val wrong = RemoteAnime(
            source = RemoteCatalogSource.BANGUMI,
            id = "41",
            title = "相同标题",
            airDate = "2020-01-01",
        )
        val exact = RemoteAnime(
            source = RemoteCatalogSource.BANGUMI,
            id = "42",
            title = "其他标题",
            airDate = "2021-02-03",
        )

        assertEquals(exact, selectRemoteMatch(anime, listOf(wrong, exact)))
    }

    @Test
    fun usesReadingLanguageForBookRecords() {
        val book = AnimeEntity(id = 20, title = "测试小说", subjectType = "novel", totalEpisodes = 10)
        val events = buildCalendarEvents(
            listOf(book),
            listOf(
                WatchRecordEntity(id = 7, animeId = 20, episode = 4, status = "watched", recordDate = "2026-08-01"),
                WatchRecordEntity(id = 8, animeId = 20, episode = 10, status = "completed", recordDate = "2026-08-02"),
            ),
            YearMonth.of(2026, 8),
        )

        assertTrue(events.any { it.subtitle == "阅读第 4 话/页" })
        assertTrue(events.any { it.subtitle == "完成阅读" && it.type == CalendarEventType.WATCH_FINISH })
    }
}
