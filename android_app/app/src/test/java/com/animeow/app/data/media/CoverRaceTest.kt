package com.animeow.app.data.media

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test

class CoverRaceTest {
    @Test fun firstDecodableCoverWinsAndCancelsSlowerDownloads() = runBlocking {
        val started = mutableSetOf<String>()
        val cancelled = mutableSetOf<String>()
        val ready = CompletableDeferred<Unit>()
        val result = withTimeout(5_000) { firstValidCover(listOf("slow", "broken", "fast")) { source ->
            started += source
            if (started.size == 3) ready.complete(Unit)
            try {
                ready.await()
                if (source == "slow") awaitCancellation()
                delay(if (source == "broken") 1 else 20)
                if (source == "broken") null else source
            } finally { if (source == "slow") cancelled += source }
        } }
        assertEquals("fast", result)
        assertEquals(setOf("slow", "broken", "fast"), started)
        assertEquals(setOf("slow"), cancelled)
    }

    @Test fun allFailuresFinishWithoutHangingOrBecomingAValidCover() = runBlocking {
        assertNull(withTimeout(5_000) { firstValidCover<String>(listOf("bad", "error")) { source ->
            if (source == "error") error("connection failed") else null
        } })
    }
}
