package com.animeow.app.data.reminder

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ReminderCalendarSyncTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun weekdayTokenMapsMondayThroughSunday() {
        assertEquals(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), (1..7).map { CalendarReminderPlanner.weekdayToken(it) })
    }

    @Test fun weekdayTokenRejectsOutOfRange() {
        for (day in listOf(0, 8, -1)) {
            try {
                CalendarReminderPlanner.weekdayToken(day)
                fail("星期取值 $day 应被拒绝")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun eventTitleWrapsAnimeTitle() {
        assertEquals("《葬送的芙莉莲》追番提醒", CalendarReminderPlanner.eventTitle("葬送的芙莉莲"))
    }

    @Test fun buildRruleWithoutLimitsIsPlainWeekly() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", CalendarReminderPlanner.buildRrule(1, null, null))
    }

    @Test fun buildRruleUsesRemainingCount() {
        assertEquals("FREQ=WEEKLY;BYDAY=SU;COUNT=5", CalendarReminderPlanner.buildRrule(7, 5, null))
    }

    @Test fun buildRruleUsesUntilDate() {
        assertEquals(
            "FREQ=WEEKLY;BYDAY=TU;UNTIL=20261231T235959",
            CalendarReminderPlanner.buildRrule(2, null, LocalDate.of(2026, 12, 31)),
        )
    }

    @Test fun buildRruleAppliesCountAndUntilTogether() {
        assertEquals(
            "FREQ=WEEKLY;BYDAY=TU;COUNT=3;UNTIL=20261231T235959",
            CalendarReminderPlanner.buildRrule(2, 3, LocalDate.of(2026, 12, 31)),
        )
    }

    @Test fun buildRruleSkipsExhaustedReminder() {
        assertNull(CalendarReminderPlanner.buildRrule(4, 0, null))
    }

    @Test fun nextStartPicksLaterTodayWhenTimeNotPassed() {
        // 2026-09-23 是周三（day=3），12:00 还没到
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 23, 10, 0), zone)
        assertEquals(
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 23, 12, 0), zone),
            CalendarReminderPlanner.nextStart(3, LocalTime.of(12, 0), now),
        )
    }

    @Test fun nextStartRollsToNextWeekWhenTimeAlreadyPassed() {
        // 同为周三但 09:00 已过，落到下周三
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 23, 10, 0), zone)
        assertEquals(
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 30, 9, 0), zone),
            CalendarReminderPlanner.nextStart(3, LocalTime.of(9, 0), now),
        )
    }

    @Test fun nextStartPicksSameWeekFriday() {
        // 2026-09-25 是周五（day=5）
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 23, 10, 0), zone)
        assertEquals(
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 25, 20, 0), zone),
            CalendarReminderPlanner.nextStart(5, LocalTime.of(20, 0), now),
        )
    }

    @Test fun nextStartPicksNextMondayAfterWeekend() {
        // 周三之后下一个周一是 2026-09-28
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 23, 10, 0), zone)
        assertEquals(
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 28, 8, 30), zone),
            CalendarReminderPlanner.nextStart(1, LocalTime.of(8, 30), now),
        )
    }
}
