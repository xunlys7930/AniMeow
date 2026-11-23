package com.animeow.app.ui.discovery

import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoverySort
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import com.animeow.app.data.remote.applyDiscoveryFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SeasonalDiscoveryTest {
    @Test fun defaultsFollowTheExistingSeasonBoundaryAndCrossYear() {
        val september = seasonalDiscoveryFilters(LocalDate.of(2026, 9, 24))
        assertEquals(7, september.seasonMonth)
        val october = seasonalDiscoveryFilters(LocalDate.of(2026, 9, 25))
        assertEquals(2026, october.year)
        assertEquals(10, october.seasonMonth)
        val january = seasonalDiscoveryFilters(LocalDate.of(2026, 12, 25))
        assertEquals(2027, january.year)
        assertEquals(1, january.seasonMonth)
        assertEquals(DiscoverySort.POPULARITY, january.sort)
        assertNull(january.minimumScore)
    }

    @Test fun clearingOptionalFiltersKeepsTheSelectedSeason() {
        val current = DiscoveryFilters(year = 2026, seasonMonth = 10, tag = "日常", minimumScore = 70)
        assertEquals(DiscoveryFilters(year = 2026, seasonMonth = 10), requiredSeasonFilters(DiscoveryFilters(), current))
        val next = current.copy(year = 2027, seasonMonth = 1)
        assertEquals(next, requiredSeasonFilters(next, current))
        assertEquals(current, requiredSeasonFilters(current.copy(year = 0, seasonMonth = 2), current))
    }

    @Test fun upcomingUnratedWorksAreIncludedAndOtherSeasonsAreExcluded() {
        val filters = seasonalDiscoveryFilters(LocalDate.of(2090, 10, 1))
        val upcoming = RemoteAnime(RemoteCatalogSource.BANGUMI, "1", "未开播作品", airDate = "2090-10-08")
        val early = upcoming.copy(id = "2", airDate = "2090-09-25")
        val previous = upcoming.copy(id = "3", airDate = "2090-09-24")
        val next = upcoming.copy(id = "4", airDate = "2090-12-25")
        assertEquals(listOf(upcoming, early), applyDiscoveryFilters(listOf(upcoming, early, previous, next), filters, RemoteCatalogSource.BANGUMI))
    }
}
