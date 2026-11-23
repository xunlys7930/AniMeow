package com.animeow.app.data.community

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CursorPageIndexTest {
    @Test fun jumpingVisitsOnlyUnknownBoundariesAndBackNavigationNeedsOneRequest() = runBlocking {
        val index = CursorPageIndex()
        val requested = mutableListOf<Long?>()
        val fetch: suspend (Long?) -> CursorBatch<Int> = { cursor ->
            requested += cursor
            val number = ((cursor ?: 0) / 30).toInt() + 1
            CursorBatch(listOf(number), (number * 30).toLong().takeIf { number < 6 })
        }
        val third = index.load(3, fetch = fetch)
        assertEquals(listOf(null, 30L, 60L), requested)
        assertEquals(3, third.number)
        assertEquals(listOf(3), third.items)
        requested.clear()
        assertEquals(2, index.load(2, fetch = fetch).number)
        assertEquals(listOf(30L), requested)
        requested.clear()
        assertEquals(4, index.load(4, fetch = fetch).number)
        assertEquals(listOf(90L), requested)
    }

    @Test fun jumpingPastEndReturnsRealLastPageWithoutInventingATotal() = runBlocking {
        val index = CursorPageIndex()
        val fetch: suspend (Long?) -> CursorBatch<Int> = { cursor ->
            if (cursor == null) CursorBatch(listOf(1), 100) else CursorBatch(listOf(2), null)
        }
        assertNull(index.load(1, fetch = fetch).lastPage)
        val last = index.load(999, fetch = fetch)
        assertEquals(2, last.number)
        assertEquals(2, last.lastPage)
        assertFalse(last.hasNext)
    }

    @Test fun failedOrCancelledFetchDoesNotCacheAnUnseenPage() = runBlocking {
        val index = CursorPageIndex()
        val failure = runCatching { index.load(2) { cursor ->
            if (cursor == null) CursorBatch(listOf(1), 30) else throw CancellationException("fixture")
        } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        val requested = mutableListOf<Long?>()
        val result = index.load(2) { cursor -> requested += cursor; CursorBatch(listOf(2), null) }
        assertEquals(listOf(30L), requested)
        assertEquals(2, result.number)
    }

    @Test fun aDeletedLastPageFallsBackAndRepeatedCursorCannotLoopForever() = runBlocking {
        val index = CursorPageIndex()
        index.load(1) { CursorBatch(listOf(1), 30) }
        val result = index.load(2) { cursor -> if (cursor == null) CursorBatch(listOf(1), null) else CursorBatch(emptyList<Int>(), null) }
        assertEquals(1, result.number)
        assertEquals(listOf(1), result.items)
        val failure = runCatching { CursorPageIndex().load(5) { CursorBatch(listOf(1), 10) } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
