package com.animeow.app.data.reminder

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeReminderSchedulerTest {
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
}
