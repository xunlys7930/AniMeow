package com.animeow.app.ui.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchTimelineTest {
    @Test
    fun durationIncludesBothRecordedDatesAndHandlesLeapYears() {
        assertEquals(1L, watchDurationDays("2026-09-06", "2026-09-06"))
        assertEquals(3L, watchDurationDays("2024-02-28", "2024-03-01"))
        assertNull(watchDurationDays("2026-09-06", "2026-09-01"))
        assertNull(watchDurationDays(null, "2026-09-01"))
        assertNull(watchDurationDays("invalid", "2026-09-01"))
    }
}
