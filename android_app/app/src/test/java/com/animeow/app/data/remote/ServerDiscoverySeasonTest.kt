package com.animeow.app.data.remote

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDiscoverySeasonTest {
    @Test
    fun assignsLateDecemberThroughLateMarchToWinterSeason() {
        assertTrue(isAirDateInServerDiscoverySeason("2025-12-25", 1, 2026))
        assertTrue(isAirDateInServerDiscoverySeason("2026-03-24", 1, 2026))
        assertFalse(isAirDateInServerDiscoverySeason("2026-03-25", 1, 2026))
        assertEquals(LocalDate.of(2025, 12, 25), serverDiscoverySeasonStart(2026, 1))
        assertEquals(LocalDate.of(2026, 3, 25), serverDiscoveryNextSeasonStart(2026, 1))
    }

    @Test
    fun assignsLateMarchThroughLateJuneToSpringSeason() {
        assertTrue(isAirDateInServerDiscoverySeason("2026-03-25", 4, 2026))
        assertTrue(isAirDateInServerDiscoverySeason("2026-06-24", 4, 2026))
        assertFalse(isAirDateInServerDiscoverySeason("2026-06-25", 4, 2026))
    }

    @Test
    fun filtersSeasonMonthAcrossYearsWhenYearIsMissing() {
        assertTrue(isAirDateInServerDiscoverySeason("2025-12-25", 1))
        assertTrue(isAirDateInServerDiscoverySeason("2026-03-25", 4))
        assertFalse(isAirDateInServerDiscoverySeason("2026-03-25", 1))
    }

    @Test
    fun parsesMonthOnlyAndRejectsIncompleteOrInvalidDates() {
        assertTrue(isAirDateInServerDiscoverySeason("2026-03", 1))
        assertFalse(isAirDateInServerDiscoverySeason("2026-07", 1))
        assertFalse(isAirDateInServerDiscoverySeason("2026", 1))
        assertFalse(isAirDateInServerDiscoverySeason(null, 1))
        assertNull(parseServerDiscoveryAirDate("2026-02-30"))
        assertNull(parseServerDiscoveryAirDate("2026-13"))
    }

    @Test
    fun winterServerQueriesIncludePreviousCalendarYear() {
        assertEquals(listOf(2026, 2025), discoveryQueryYears(2026, 1))
        assertEquals(listOf(2026), discoveryQueryYears(2026, 4))
        assertEquals(listOf<Int?>(null), discoveryQueryYears(null, 1))
    }

    @Test
    fun commonClientFilterUsesShiftedSeasonAndNormalizedScores() {
        val items = listOf(
            remote("before", "2025-12-24", 9.5),
            remote("start", "2025-12-25", 8.5),
            remote("end", "2026-03-24", 7.5),
            remote("next", "2026-03-25", 9.8),
        )

        val result = applyDiscoveryFilters(
            items = items,
            filters = DiscoveryFilters(year = 2026, seasonMonth = 1, minimumScore = 80),
            source = RemoteCatalogSource.BANGUMI,
        )

        assertEquals(listOf("start"), result.map(RemoteAnime::id))
    }

    private fun remote(id: String, airDate: String, score: Double) = RemoteAnime(
        source = RemoteCatalogSource.BANGUMI,
        id = id,
        title = id,
        airDate = airDate,
        score = score,
    )
}
