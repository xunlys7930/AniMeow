package com.animeow.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BangumiCalendarParsingTest {
    @Test
    fun retainsWeekdayAndLegacyAirDateWithoutInventingClockTimes() {
        val result = BangumiService().parseCalendarResponse("""
            [
              {"weekday":{"id":1},"items":[{"id":42,"name":"Monday","air_date":"2026-09-07"}]},
              {"weekday":{"id":7},"items":[{"id":43,"name_cn":"周日作品","date":"2026-09-06"}]}
            ]
        """.trimIndent())
        assertEquals(listOf(1, 7), result.map { it.broadcastDay })
        assertEquals(listOf("2026-09-07", "2026-09-06"), result.map { it.airDate })
    }

    @Test
    fun invalidDaysStayUnscheduledAndDuplicateSubjectsAppearOnce() {
        val result = BangumiService().parseCalendarResponse("""
            [{"weekday":{"id":8},"items":[{"id":42,"name":"Unknown"},{"id":42,"name":"Duplicate"}]}]
        """.trimIndent())
        assertEquals(1, result.size)
        assertNull(result.single().broadcastDay)
    }
}
