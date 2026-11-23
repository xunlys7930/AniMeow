package com.animeow.app.ui.tracker

import com.animeow.app.data.local.AnimeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerPinOrderingTest {
    private val source = listOf(
        AnimeEntity(id = 10, title = "A", status = "在看", rating = 9),
        AnimeEntity(id = 2, title = "Z", status = "在看", rating = 3, pinnedAt = 20),
        AnimeEntity(id = 3, title = "M", status = "看完", rating = 5, pinnedAt = 30),
    )
    @Test fun pinnedWorksLeadInEverySortDirection() {
        for (sort in LibrarySort.entries) for (ascending in listOf(false, true)) {
            val result = filterAndSortAnimes(source, "", "全部", sort, ascending = ascending)
            assertEquals(listOf(3L, 2L, 10L), result.map { it.id })
        }
    }
    @Test fun pinningDoesNotBypassSearchOrStatusFilters() {
        assertEquals(listOf(2L, 10L), filterAndSortAnimes(source, "", "在看", LibrarySort.TITLE).map { it.id })
        assertEquals(listOf(10L), filterAndSortAnimes(source, "A", "全部", LibrarySort.TITLE).map { it.id })
    }
}
