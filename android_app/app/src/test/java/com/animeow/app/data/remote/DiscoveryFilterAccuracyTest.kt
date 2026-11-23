package com.animeow.app.data.remote

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class DiscoveryFilterAccuracyTest {
    @Test fun rankPreservesProviderOrderRatherThanReorderingByScore() {
        val items = listOf(item("rank1", 7.0), item("rank2", 9.0))
        assertEquals(items, applyDiscoveryFilters(items, DiscoveryFilters(sort = DiscoverySort.RANK), RemoteCatalogSource.BANGUMI))
        assertEquals(listOf("rank2", "rank1"), applyDiscoveryFilters(items, DiscoveryFilters(sort = DiscoverySort.SCORE), RemoteCatalogSource.BANGUMI).map { it.id })
    }
    @Test fun formatAliasesMatchExactlyWithoutTreatingTvShortAsTv() {
        assertTrue(discoveryFormatMatches("MOVIE", "剧场版"))
        assertTrue(discoveryFormatMatches("TV 共 12 集", "TV"))
        assertTrue(discoveryFormatMatches("ONA", "WEB"))
        assertTrue(discoveryFormatMatches("SPECIAL", "SP"))
        assertFalse(discoveryFormatMatches("TV_SHORT", "TV"))
        assertFalse(discoveryFormatMatches(null, "TV"))
    }
    @Test fun chineseGenreAndTagSelectionsMatchAnilistMetadata() {
        assertTrue(discoveryTagMatches("Romance", "恋爱"))
        assertTrue(discoveryTagMatches("School", "校园"))
        assertTrue(discoveryTagMatches("Slice of Life", "日常"))
        assertFalse(discoveryTagMatches("Horror", "恋爱"))
    }
    @Test fun adaptationTagsUseSourcePredicatesRatherThanNonexistentGenres() {
        assertEquals(listOf("ORIGINAL"), discoveryAniListSources["原创"])
        assertEquals(listOf("LIGHT_NOVEL", "NOVEL"), discoveryAniListSources["小说改"])
        assertEquals(listOf("VISUAL_NOVEL", "VIDEO_GAME"), discoveryAniListSources["游戏改"])
    }
    @Test fun winterRemoteRangeIncludesPreviousDecemberAndExcludesNextQuarter() {
        assertEquals(LocalDate.of(2025, 12, 25) to LocalDate.of(2026, 3, 25), discoveryDateRange(DiscoveryFilters(year = 2026, seasonMonth = 1)))
        assertEquals(LocalDate.of(2026, 1, 1) to LocalDate.of(2027, 1, 1), discoveryDateRange(DiscoveryFilters(year = 2026)))
        assertNull(discoveryDateRange(DiscoveryFilters(seasonMonth = 1)))
    }
    @Test fun anilistPageInfoDrivesContinuationWithoutInventingTotals() {
        val result = AniListService().parseDiscoveryPage("""{"data":{"Page":{"pageInfo":{"currentPage":2,"hasNextPage":true},"media":[]}}}""", DiscoveryFilters(), 2)
        assertEquals(2, result.page)
        assertTrue(result.hasMore)
        assertFalse(result.totalCountExact)
        assertNull(result.totalPages)
    }
    @Test fun oldProxyCannotRepeatFirstPageForever() {
        val response = """{"data":{"Page":{"media":[{"id":1,"title":{"native":"测试"}}]}}}"""
        val first = AniListService().parseDiscoveryPage(response, DiscoveryFilters(), 1)
        assertEquals(1, first.items.size)
        assertFalse(first.hasMore)
        val next = AniListService().parseDiscoveryPage(response, DiscoveryFilters(), 2)
        assertTrue(next.items.isEmpty())
        assertFalse(next.hasMore)
        assertNotNull(next.resultScope)
    }
    @Test fun serverCountFailureDoesNotTurnFilteredRowsIntoAnExactTotal() {
        val page = parseServerDiscoveryPageInfo(org.json.JSONObject("""{"total":null,"totalKnown":false,"filtersApplied":true,"hasMore":true}"""), 2, 24, 24)
        assertTrue(page.filtersApplied)
        assertFalse(page.totalKnown)
        assertNull(page.totalPages)
        assertTrue(page.hasMore)
    }
    @Test fun serverExplicitUnknownWinsEvenWhenFallbackNumberExists() {
        val page = parseServerDiscoveryPageInfo(org.json.JSONObject("""{"total":24,"totalKnown":false,"filtersApplied":true}"""), 1, 24, 24)
        assertFalse(page.totalKnown)
        assertNull(page.totalPages)
        assertTrue(page.hasMore)
    }
    @Test fun serverVerifiedTotalSupportsRealPagination() {
        val page = parseServerDiscoveryPageInfo(org.json.JSONObject("""{"total":52,"totalKnown":true,"filtersApplied":true}"""), 2, 24, 24)
        assertTrue(page.totalKnown)
        assertEquals(3, page.totalPages)
        assertTrue(page.hasMore)
    }
    private fun item(id: String, score: Double) = RemoteAnime(RemoteCatalogSource.BANGUMI, id, id, score = score)
}
