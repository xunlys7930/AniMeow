package com.animeow.app.data.playback

import com.animeow.app.data.remote.PlaybackStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun missingOfflineEpisodeFailsWithoutAnyOnlineResolutionButOnlineChoiceCanResolve() = runBlocking {
        var requests = 0
        val stream = PlaybackStream("https://cdn.example.test/video.mp4", "video/mp4", emptyMap())
        val resolve: suspend () -> PlaybackStream = { requests++; stream }
        try { resolvePlaybackStream(null, true, resolve); fail("offline must not connect") } catch (failure: IllegalStateException) {
            assertTrue(failure.message!!.contains("缓存已被移除"))
        }
        assertEquals(0, requests)
        assertEquals(stream, resolvePlaybackStream(null, false, resolve))
        assertEquals(1, requests)
        assertEquals(stream, resolvePlaybackStream(stream, true, resolve))
        assertEquals(1, requests)
    }
}
