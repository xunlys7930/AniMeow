package com.animeow.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteImageUrlTest {
    private val proxy = "https://img-bgm.example.com"

    @Test
    fun originalBangumiImageUsesProxyOnlyAsFallback() {
        val original = "https://lain.bgm.tv/pic/cover/l/aa/bb/123.jpg"
        assertEquals(original, canonicalRemoteImageUrl(original, proxy))
        assertEquals(
            "https://img-bgm.example.com/pic/cover/l/aa/bb/123.jpg",
            bangumiImageProxyFallbackUrl(original, proxy),
        )
    }

    @Test
    fun storedProxyUrlCanReturnToOriginalBeforeRetryingProxy() {
        val proxied = "https://img-bgm.example.com/pic/cover/l/aa/bb/123.jpg"
        assertEquals(
            "https://lain.bgm.tv/pic/cover/l/aa/bb/123.jpg",
            canonicalRemoteImageUrl(proxied, proxy),
        )
    }

    @Test
    fun nonBangumiImageDoesNotConsumeProxy() {
        assertNull(bangumiImageProxyFallbackUrl("https://example.com/cover.jpg", proxy))
    }
}
