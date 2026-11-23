package com.animeow.app.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFeedLoaderTest {
    private val serverWork = RemoteAnime(RemoteCatalogSource.SERVER, "1", "服务器作品", originSource = "bangumi", originId = "10")

    @Test fun publishesServerBeforeStartingFallbacksAndNeverWaitsForTheSlowestToDisplay() = runBlocking {
        val releaseServer = CompletableDeferred<Unit>()
        val releaseBangumi = CompletableDeferred<Unit>()
        val serverShown = CompletableDeferred<Unit>()
        val aniListShown = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val frames = mutableListOf<List<RemoteAnime>>()
        val task = async {
            loadDiscoverySources(
                DiscoveryRequest("服务器") { requests += "server"; releaseServer.await(); listOf(serverWork) },
                listOf(DiscoveryRequest("Bangumi") { requests += "bangumi"; releaseBangumi.await(); emptyList() },
                    DiscoveryRequest("AniList") { requests += "anilist"; listOf(RemoteAnime(RemoteCatalogSource.ANILIST, "20", "官方作品")) }),
                onProgress = { result, _ ->
                    frames += result.items
                    if (result.items.size == 1) serverShown.complete(Unit)
                    if (result.items.size == 2) aniListShown.complete(Unit)
                },
            )
        }
        yield()
        assertEquals(listOf("server"), requests)
        releaseServer.complete(Unit)
        withTimeout(2_000) { serverShown.await(); aniListShown.await() }
        assertFalse(task.isCompleted)
        assertEquals(listOf(serverWork), frames.first())
        assertEquals(serverWork, frames.last().first())
        releaseBangumi.complete(Unit)
        assertEquals(2, task.await().items.size)
    }

    @Test fun slowServerAndFailedSourcesAreBoundedWithoutDroppingTheSuccessfulSource() = runBlocking {
        var serverCancelled = false
        val result = withTimeout(2_000) {
            loadDiscoverySources(DiscoveryRequest("服务器") { try { awaitCancellation() } finally { serverCancelled = true } },
                listOf(DiscoveryRequest("Bangumi") { listOf(serverWork) }, DiscoveryRequest("AniList") { awaitCancellation() }),
                serverTimeoutMillis = 20, sourceTimeoutMillis = 30)
        }
        assertTrue(serverCancelled)
        assertEquals(listOf(serverWork), result.items)
        assertEquals(2, result.warnings.size)
    }

    @Test fun addsMissingServerCoversWithoutReorderingOrMixingBooksAndAnimation() {
        val official = RemoteAnime(RemoteCatalogSource.BANGUMI, "10", "官方标题", coverUrl = "https://example.com/cover.jpg")
        val merged = mergeDiscoveryItems(listOf(serverWork, official, official.copy(subjectType = "book")))
        assertEquals(2, merged.size)
        assertEquals(RemoteCatalogSource.SERVER, merged.first().source)
        assertEquals(official.coverUrl, merged.first().coverUrl)
        assertEquals("book", merged.last().subjectType)
    }

    @Test fun validEmptyResultsAreDifferentFromEverySourceFailing() = runBlocking {
        assertTrue(loadDiscoverySources(null, listOf(DiscoveryRequest("Bangumi") { emptyList() })).items.isEmpty())
        val failure = runCatching { loadDiscoverySources(null, listOf(DiscoveryRequest("Bangumi") { error("offline") })) }
        assertTrue(failure.isFailure)
    }
}
