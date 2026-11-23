package com.animeow.app.ui.screens

import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoverySort
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFilterPresentationTest {
    @Test fun resultFactsKeepEpisodesAndDatesIndependentOfTheOtherMetadata() {
        val anime = RemoteAnime(RemoteCatalogSource.BANGUMI, "1", "示例",
            ratingCount = 123456, airDate = "1998-10-01", episodes = 24)
        assertEquals(listOf("24 集", "1998-10-01"), discoveryScheduleFacts(anime, true))
        assertEquals(listOf("24 集"), discoveryScheduleFacts(anime, false))
        assertEquals(listOf("集数暂无", "日期暂无"), discoveryScheduleFacts(anime.copy(episodes = 0, airDate = null), true))
        assertEquals(listOf("1998-10-01"), discoveryScheduleFacts(anime.copy(subjectType = "book"), true))
    }

    @Test fun yearAcceptsOlderWorksAndRejectsInvalidInput() {
        listOf("", " ", "1998", " 1920 ", "1", "9999").forEach { assertTrue(it, isValidDiscoveryYear(it)) }
        listOf("0", "-1", "10000", "2026.5", "abcd", "999999999999").forEach { assertFalse(it, isValidDiscoveryYear(it)) }
    }

    @Test fun removingConditionPreservesOtherConditionsAndSort() {
        val filters = DiscoveryFilters(1998, 4, "TV", "校园", 75, DiscoverySort.RANK)
        val chips = discoveryFilterChips(filters)
        assertEquals(listOf("1998 年", "春季", "TV", "标签：校园", "≥ 7.5 分"), chips.map { it.label })
        assertEquals(listOf(filters.copy(year = null), filters.copy(seasonMonth = null), filters.copy(format = null), filters.copy(tag = null), filters.copy(minimumScore = null)), chips.map { it.withoutFilter })
        assertTrue(discoveryFilterChips(DiscoveryFilters()).isEmpty())
    }
}
