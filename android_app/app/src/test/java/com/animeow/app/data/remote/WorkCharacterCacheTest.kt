package com.animeow.app.data.remote

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkCharacterCacheTest {
    @Test fun warmLoadsAvoidRequestsAndExplicitRetryBypassesTheCache() = runBlocking {
        var calls = 0
        var clock = 0L
        val cache = WorkCharacterCache { clock }
        suspend fun get(force: Boolean = false) = cache.get(listOf("bangumi", "42"), force) {
            calls++; WorkCharacterCatalog(subjectId = 42, subjectTitle = "revision $calls")
        }
        assertEquals(get(), get()); assertEquals(1, calls)
        get(force = true); assertEquals(2, calls)
        clock = 300_001; get(); assertEquals(3, calls)
    }
    @Test fun concurrentRequestsForOneWorkShareTheCompletedResult() = runBlocking {
        var calls = 0
        val cache = WorkCharacterCache()
        val results = (1..5).map { async { cache.get(listOf("same"), false) { calls++; delay(30); WorkCharacterCatalog(subjectId = 42) } } }.awaitAll()
        assertEquals(1, calls); assertTrue(results.all { it.subjectId == 42L })
    }
    @Test fun failuresAndCancellationDoNotPoisonTheNextAttempt() = runBlocking {
        val cache = WorkCharacterCache()
        try { cache.get(listOf("failed"), false) { error("offline") }; fail() } catch (_: IllegalStateException) { }
        assertEquals(1L, cache.get(listOf("failed"), false) { WorkCharacterCatalog(subjectId = 1) }.subjectId)
        val pending = launch { cache.get(listOf("cancelled"), false) { awaitCancellation() } }
        yield(); pending.cancelAndJoin()
        assertEquals(2L, cache.get(listOf("cancelled"), false) { WorkCharacterCatalog(subjectId = 2) }.subjectId)
    }
    @Test fun differentWorksDoNotWaitBehindEachOther() = runBlocking {
        val cache = WorkCharacterCache()
        val release = CompletableDeferred<Unit>()
        val slow = launch { cache.get(listOf("slow"), false) { release.await(); WorkCharacterCatalog(subjectId = 1) } }
        yield()
        assertEquals(2L, withTimeout(1000) { cache.get(listOf("fast"), false) { WorkCharacterCatalog(subjectId = 2) } }.subjectId)
        release.complete(Unit); slow.join()
    }
}
