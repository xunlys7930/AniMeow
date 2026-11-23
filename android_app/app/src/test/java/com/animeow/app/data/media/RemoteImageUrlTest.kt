package com.animeow.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteImageUrlTest {
    @Test
    fun serverImagesEncodeExternalUrlsAndKeepOwnMediaPaths() {
        val base = "https://www.xunlys.top"
        val external = "https://s4.anilist.co/file/a.jpg?x=1&y=2"
        assertEquals("$base/api/catalog/images?url=https%3A%2F%2Fs4.anilist.co%2Ffile%2Fa.jpg%3Fx%3D1%26y%3D2", publicServerImageUrl(external, base))
        val own = "$base/api/community/images/42"
        assertEquals(own, publicServerImageUrl(own, base))
        assertNull(publicServerImageUrl("file:///cover.jpg", base))
        assertNull(publicServerImageUrl("https://user:secret@example.com/a.jpg", base))
        assertNull(publicServerImageUrl(external, ""))
    }
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
