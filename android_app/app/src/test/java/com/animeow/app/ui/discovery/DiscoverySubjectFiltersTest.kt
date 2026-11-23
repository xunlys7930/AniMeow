package com.animeow.app.ui.discovery

import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoverySort
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverySubjectFiltersTest {
    @Test fun switchingToBooksClearsOnlyAnimationConditions() {
        val filters = DiscoveryFilters(year = 2001, seasonMonth = 4, format = "TV", tag = "恋爱", minimumScore = 70, sort = DiscoverySort.SCORE)
        assertEquals(filters.copy(seasonMonth = null, format = null), discoveryFiltersForSubjectType(filters, "book"))
        assertEquals(filters, discoveryFiltersForSubjectType(filters, "anime"))
    }
}
