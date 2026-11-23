package com.animeow.app.data.reminder

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeReminderSchedulerTest {
    @Test fun reminderWithinOneMinuteIsNotSkipped() {
        assertEquals(Duration.ofSeconds(20), nextReminderDelay(4, LocalTime.of(18, 1), LocalDateTime.of(2026, 9, 10, 18, 0, 40)))
    }

    @Test fun daylightSavingGapUsesActualElapsedTime() {
        val zone = java.time.ZoneId.of("America/New_York")
        val now = java.time.ZonedDateTime.of(2026, 3, 8, 1, 50, 0, 0, zone)
        val due = nextReminderOccurrence(7, LocalTime.of(3, 10), now)
        assertEquals(Duration.ofMinutes(20), Duration.between(now, due))
    }

    @Test fun nextWeekRetainsWallTimeAcrossDstChange() {
        val zone = java.time.ZoneId.of("America/New_York")
        val now = java.time.ZonedDateTime.of(2026, 3, 1, 18, 0, 0, 0, zone)
        assertEquals(Duration.ofDays(7).minusHours(1), Duration.between(now, nextReminderOccurrence(7, LocalTime.of(18, 0), now)))
    }
    @Test fun advanceNoticeCanCrossMidnightAndWeekBoundary() {
        val now = LocalDateTime.of(2026, 9, 13, 23, 30) // Sunday
        assertEquals(Duration.ofMinutes(10), nextReminderDelay(1, LocalTime.of(0, 10), now, leadMinutes = 30))
    }

    @Test fun elapsedAdvanceWindowSchedulesNextWeek() {
        val now = LocalDateTime.of(2026, 9, 14, 0, 5)
        assertEquals(Duration.ofDays(7).minusMinutes(25), nextReminderDelay(1, LocalTime.of(0, 10), now, leadMinutes = 30))
    }
    @Test
    fun futureTimeOnSameWeekdayUsesToday() {
        val now = LocalDateTime.of(2026, 8, 10, 18, 0) // Monday

        assertEquals(
            Duration.ofHours(2),
            nextReminderDelay(day = 1, time = LocalTime.of(20, 0), now = now),
        )
    }

    @Test
    fun elapsedTimeOnSameWeekdayMovesToNextWeek() {
        val now = LocalDateTime.of(2026, 8, 10, 20, 0) // Monday

        assertEquals(
            Duration.ofDays(7),
            nextReminderDelay(day = 1, time = LocalTime.of(20, 0), now = now),
        )
    }

    @Test
    fun countLimitOnlyStopsWhenItReachesZero() {
        // 旧测试在测试体里自己写了一遍 count -= 1，没有碰任何生产代码；
        // 这里改为直接调用生产用的判定与扣减函数。
        assertEquals(null, remainingAfterDelivery(null))
        assertEquals(2, remainingAfterDelivery(3))
        assertEquals(0, remainingAfterDelivery(1))
        assertEquals(0, remainingAfterDelivery(0))

        val today = java.time.LocalDate.of(2026, 9, 10)
        org.junit.Assert.assertFalse(shouldStopForLimit(null, null, today))
        org.junit.Assert.assertFalse(shouldStopForLimit(3, null, today))
        org.junit.Assert.assertTrue(shouldStopForLimit(0, null, today))
    }

    @Test
    fun untilDateStopsAfterTheDeadlineButNotOnIt() {
        val today = java.time.LocalDate.of(2026, 9, 10)
        val deadline = java.time.LocalDate.of(2026, 9, 10)
        org.junit.Assert.assertFalse("截止当天仍应提醒", shouldStopForLimit(null, deadline, today))
        org.junit.Assert.assertTrue(
            "截止日之后应停止",
            shouldStopForLimit(null, java.time.LocalDate.of(2026, 9, 9), today),
        )
        org.junit.Assert.assertFalse("尚未到期不应停止", shouldStopForLimit(null, java.time.LocalDate.of(2026, 9, 11), today))
    }

    @Test
    fun threeDeliveriesExhaustACountOfThree() {
        // 覆盖线上故障场景：次数不会被任何一次同步重置，只会因送达而递减。
        var remaining: Int? = 3
        var deliveries = 0
        while (!shouldStopForLimit(remaining, null, java.time.LocalDate.of(2026, 9, 10))) {
            deliveries += 1
            remaining = remainingAfterDelivery(remaining)
        }
        assertEquals(3, deliveries)
        assertEquals(0, remaining)
    }
}
