package com.animeow.app.ui.search

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {
    @Test
    fun relatedWorksDoNotRepeatVisibleResultsOrOtherSeriesSuggestions() {
        val first = AnimeEntity(id = 1, title = "旅人 第一季", seriesId = 9)
        val second = AnimeEntity(id = 2, title = "旅人 第二季", seriesId = 9)
        val extra = AnimeEntity(id = 3, title = "剧场版", seriesId = 9)
        val suggestions = searchSeriesSuggestions(listOf(first, second), mapOf(9L to listOf(first, second, extra, extra)))
        assertEquals(setOf(1L), suggestions.keys)
        assertEquals(listOf(3L), suggestions.getValue(1L).map { it.id })
    }

    @Test
    fun sameTitlesWithDifferentIdsStayAccessibleAndLongSeriesAreBounded() {
        val result = AnimeEntity(id = 1, title = "同名作品", seriesId = 9)
        val members = (1L..20L).map { AnimeEntity(id = it, title = "同名作品", seriesId = 9) }
        assertEquals((2L..7L).toList(), searchSeriesSuggestions(listOf(result), mapOf(9L to members)).getValue(1L).map { it.id })
        assertTrue(searchSeriesSuggestions(listOf(result.copy(seriesId = null)), emptyMap()).isEmpty())
    }

    @Test
    fun normalizationIgnoresCommonTitleSeparators() {
        assertEquals(1, SearchTerms("Re Zero").rank("Re:Zero — Starting Life"))
        assertEquals(0, SearchTerms("葬送的芙莉莲").rank("葬送的 芙莉莲"))
    }

    @Test
    fun emptyQueryNeverMatchesEverything() {
        assertEquals(null, SearchTerms("   ").rank("任意作品"))
    }

    @Test
    fun ranksTitlesBeforeSecondaryFieldsAndKeepsAllMatches() {
        val results = (1..20).map { "旅人 第 $it 季" } + "旅人"
        val terms = SearchTerms("旅人")
        assertEquals(listOf("旅人") + results.dropLast(1), results.rankedSearch { terms.rank(it) })
        assertEquals(3, terms.rank("漫长的旅途", "别名旅人"))
    }

    @Test
    fun whitespaceSeparatedWordsCanMatchAcrossTitleAndSearchMetadata() {
        val terms = SearchTerms("Bangumi 重试")
        assertEquals(4, terms.rank("Bangumi 同步状态", "立即重试"))
        assertEquals(null, terms.rank("Bangumi 同步设置", "绑定账号"))
        assertEquals(0, SearchTerms("RJ 123456").rank("rj123456"))
        assertTrue(SearchTerms("  — ").isEmpty)
    }

    @Test
    fun onlineResultsUseSeparateKeysForBooksAndAnimation() {
        val animation = RemoteAnime(RemoteCatalogSource.ANILIST, "42", "动画")
        val book = animation.copy(title = "书籍", subjectType = "book")
        assertEquals(animation.uniqueKey, book.uniqueKey)
        assertFalse(animation.globalSearchKey == book.globalSearchKey)
        assertTrue(shouldSearchOnline(GlobalSearchScope.ONLINE, "小说"))
        assertFalse(shouldSearchOnline(GlobalSearchScope.SETTINGS, "小说"))
        assertFalse(shouldSearchOnline(GlobalSearchScope.LOCAL, "小说"))
    }
}
