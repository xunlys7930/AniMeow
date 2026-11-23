package com.animeow.app.data.community

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersonalActivityPagerTest {
    @Test fun sparseLikesContinueBeyondTheFirstFeedPage() = runBlocking {
        val cursors = mutableListOf<Long?>()
        val batch = scanPersonalActivity(null, matches = { it.likedByViewer }) { cursor ->
            cursors += cursor
            when (cursor) {
                null -> page(listOf(post(100)), 90)
                90L -> page(emptyList(), 80) // A page can be empty after blocked authors are removed.
                else -> page(listOf(post(75, liked = true)), 70)
            }
        }
        assertEquals(listOf(null, 90L, 80L), cursors)
        assertEquals(listOf(75L), batch.items.map { it.id })
        assertEquals(70L, batch.nextBeforeId)
        assertFalse(batch.endReached)
    }

    @Test fun boundedEmptyBatchIsNotAnEmptyHistory() = runBlocking {
        var calls = 0
        val first = scanPersonalActivity(null, matches = { it.likedByViewer }) {
            calls++
            page(emptyList(), 100L - calls)
        }
        assertEquals(3, calls)
        assertFalse(first.endReached)
        val next = scanPersonalActivity(first.nextBeforeId, matches = { it.likedByViewer }) { cursor ->
            assertEquals(97L, cursor)
            page(listOf(post(60, liked = true)), null)
        }
        assertTrue(next.endReached)
        assertEquals(60L, next.items.single().id)
    }

    @Test fun repeatedPinnedPostsDoNotEndTheScanBeforeAnUnseenFavorite() = runBlocking {
        var calls = 0
        val batch = scanPersonalActivity(null, knownIds = setOf(1), matches = { it.isFavorited }) {
            calls++
            if (calls == 1) page(listOf(post(1, favorite = true)), 50)
            else page(listOf(post(1, favorite = true), post(40, favorite = true)), null)
        }
        assertEquals(2, calls)
        assertEquals(setOf(1L, 40L), batch.items.map { it.id }.toSet())
    }

    @Test fun exhaustedUnmatchedPagesAreReportedAsEmpty() = runBlocking {
        val batch = scanPersonalActivity(null, matches = { it.likedByViewer }) { page(listOf(post(2)), null) }
        assertTrue(batch.endReached)
        assertNull(batch.nextBeforeId)
        assertTrue(batch.items.isEmpty())
    }

    @Test fun nonAdvancingCursorFailsInsteadOfLoopingOrSilentlyTruncating() = runBlocking {
        try {
            scanPersonalActivity(50, matches = { true }) { page(listOf(post(49)), 50) }
            fail("A stuck cursor must be surfaced as a retryable failure")
        } catch (_: IllegalStateException) { }
    }

    @Test fun failuresAndCancellationPropagateWithoutBecomingEmptyResults() = runBlocking {
        val failure = java.io.IOException("offline")
        try {
            scanPersonalActivity(null, matches = { true }) { throw failure }
            fail("Network failure must not look like empty history")
        } catch (error: java.io.IOException) { assertSame(failure, error) }
        try {
            scanPersonalActivity(null, matches = { true }) { throw CancellationException("left screen") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun newerSnapshotsReplaceDuplicatesAndPublicationOrderIsStable() {
        val old = post(10, liked = true)
        val fresh = old.copy(likedByViewer = false, content = "edited")
        val merged = mergePersonalActivity(listOf(old, post(1)), listOf(fresh, post(12)))
        assertEquals(listOf(12L, 10L, 1L), merged.map { it.id })
        assertEquals("edited", merged[1].content)
        assertFalse(merged[1].likedByViewer)
    }

    private fun page(posts: List<CommunityPost>, cursor: Long?) = CommunityFeedPage(
        CommunityGroup(1, "test", "", "", true, true, null), posts, cursor,
    )

    private fun post(id: Long, liked: Boolean = false, favorite: Boolean = false) = CommunityPost(
        id, 1, "post $id", emptyList(),
        CommunityProfile(7, "test", "Test", "", "members", false, false, false, null, false, emptyMap(), null),
        "visible", false, false, null, false, null, 1, 0, liked, favorite, false, true, "2026-09-08", "2026-09-08",
    )
}
