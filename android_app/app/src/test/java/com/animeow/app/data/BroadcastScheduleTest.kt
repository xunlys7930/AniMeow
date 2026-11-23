package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastScheduleTest {
    private val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "42", "夏日故事", originalTitle = "Summer Story", broadcastDay = 1)

    @Test
    fun externalIdentityWinsAndDifferentKnownSeasonsNeverMatchByTitle() {
        val exact = AnimeEntity(id = 1, title = "我的自定义标题", externalSource = "bangumi", externalId = "42")
        val sequel = AnimeEntity(id = 2, title = remote.title, externalSource = "bangumi", externalId = "43")
        assertEquals(exact, matchBroadcastAnime(remote, listOf(sequel, exact)).anime)
        assertNull(matchBroadcastAnime(remote, listOf(sequel)).anime)
    }

    @Test
    fun uniqueOriginalTitleCanMatchAcrossSourcesButAmbiguousMatchesAreFlagged() {
        val original = AnimeEntity(id = 1, title = "SUMMER STORY", externalSource = "anilist", externalId = "100")
        assertEquals(original, matchBroadcastAnime(remote, listOf(original)).anime)
        val ambiguous = matchBroadcastAnime(remote, listOf(original, original.copy(id = 2)))
        assertTrue(ambiguous.ambiguous)
        assertNull(ambiguous.anime)
    }

    @Test
    fun deletedWorksAndBooksCannotBeMatchedOrScheduled() {
        val book = AnimeEntity(id = 1, title = remote.title, subjectType = "book", broadcastDay = 1)
        val deleted = book.copy(id = 2, subjectType = "anime", deletedAt = "2026-09-06")
        assertNull(matchBroadcastAnime(remote, listOf(book, deleted)).anime)
        assertFalse(book.hasActiveBroadcast())
        assertFalse(deleted.hasActiveBroadcast())
    }

    @Test
    fun finishingOrPausingStopsRecurringEventsWhileCustomActiveStatusesStillWork() {
        val anime = AnimeEntity(title = "作品", broadcastDay = 7, status = "一起追")
        assertTrue(anime.hasActiveBroadcast())
        listOf("看完", "弃坑", "搁置", "暂停").forEach { assertFalse(anime.copy(status = it).hasActiveBroadcast()) }
    }
}
