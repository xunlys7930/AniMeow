package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchStatusDatesTest {
    private val today = LocalDate.of(2026, 9, 6)

    @Test
    fun newWatchingAndCompletionTransitionsRecordTheirOwnDates() {
        val started = AnimeEntity(title = "作品").withWatchStatusDates("在看", today)
        assertEquals("2026-09-06", started.watchStartDate)
        assertNull(started.watchFinishDate)
        assertEquals("2026-09-07", started.withWatchStatusDates("看完", today.plusDays(1)).watchFinishDate)
    }

    @Test
    fun userDatesArePreservedAndImportedPartialProgressDoesNotInventAStartDate() {
        val dated = AnimeEntity(title = "作品", watchStartDate = "2025-01-01", watchFinishDate = "2025-03-01")
        val completed = dated.withWatchStatusDates("看完", today)
        assertEquals(dated.watchStartDate, completed.watchStartDate)
        assertEquals(dated.watchFinishDate, completed.watchFinishDate)
        assertNull(AnimeEntity(title = "旧资料", watchedEpisodes = 5).withWatchStatusDates("在看", today).watchStartDate)
        assertNull(AnimeEntity(title = "旧资料", status = "在看").withWatchStatusDates("在看", today).watchStartDate)
    }
}
