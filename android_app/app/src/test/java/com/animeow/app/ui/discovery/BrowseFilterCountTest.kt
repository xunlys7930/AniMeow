package com.animeow.app.ui.discovery

import com.animeow.app.data.remote.*
import com.animeow.app.ui.screens.discoveryFilterCount
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseFilterCountTest {
    @Test fun seasonalContextDoesNotPretendTheDefaultPageHasExtraFilters() {
        val seasonal = DiscoveryFilters(year = 2026, seasonMonth = 10, sort = DiscoverySort.POPULARITY)
        assertEquals(0, discoveryFilterCount(seasonal, DiscoverySourceFilter.BANGUMI, DiscoveryFeedMode.SEASONAL))
        assertEquals(2, discoveryFilterCount(seasonal.copy(tag = "日常", minimumScore = 70), DiscoverySourceFilter.BANGUMI, DiscoveryFeedMode.SEASONAL))
    }
    @Test fun sourceAndDateRestrictionsAreCountedOutsideSeasonalOverview() {
        val filtered = DiscoveryFilters(year = 2026, tag = "小说")
        assertEquals(2, discoveryFilterCount(filtered, DiscoverySourceFilter.ALL, DiscoveryFeedMode.FEATURED))
        assertEquals(3, discoveryFilterCount(filtered, DiscoverySourceFilter.BANGUMI, DiscoveryFeedMode.FEATURED))
    }
}
