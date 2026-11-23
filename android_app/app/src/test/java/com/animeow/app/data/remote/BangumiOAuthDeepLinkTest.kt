package com.animeow.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BangumiOAuthDeepLinkTest {
    @Test
    fun parsesDirectCodeStateCallback() {
        val callback = parseAniMeowDeepLink("animeow://oauth/bangumi?code=abc123&state=st-1")?.bangumiOAuth
        assertNotNull(callback)
        assertEquals("abc123", callback?.code)
        assertEquals("st-1", callback?.state)
        assertNull(callback?.ticket)
        assertNull(callback?.error)
    }

    @Test
    fun parsesTicketRelayCallback() {
        val callback = parseAniMeowDeepLink("animeow://oauth/bangumi?ticket=9f8e7d6c5b4a3210")?.bangumiOAuth
        assertEquals("9f8e7d6c5b4a3210", callback?.ticket)
        assertNull(callback?.code)
    }

    @Test
    fun parsesErrorCallbacksIncludingAccessDenied() {
        assertEquals(
            "access_denied",
            parseAniMeowDeepLink("animeow://oauth/bangumi?error=access_denied")?.bangumiOAuth?.error,
        )
        assertEquals(
            "state_invalid",
            parseAniMeowDeepLink("animeow://oauth/bangumi?error=state_invalid")?.bangumiOAuth?.error,
        )
    }

    @Test
    fun rejectsIncompletePayloads() {
        // code 缺 state 不构成有效回跳；空 query 与非 bangumi 路径都不是回跳
        assertNull(parseAniMeowDeepLink("animeow://oauth/bangumi?code=abc")?.bangumiOAuth)
        assertNull(parseAniMeowDeepLink("animeow://oauth/bangumi")?.bangumiOAuth)
        assertNull(parseAniMeowDeepLink("animeow://oauth/other?code=abc&state=st")?.bangumiOAuth)
    }

    @Test
    fun callbackKeyDedupsRotationReplays() {
        val first = parseAniMeowDeepLink("animeow://oauth/bangumi?code=abc&state=st")?.bangumiOAuth
        val replay = parseAniMeowDeepLink("animeow://oauth/bangumi?code=abc&state=st")?.bangumiOAuth
        assertEquals(first?.key, replay?.key)
        val other = parseAniMeowDeepLink("animeow://oauth/bangumi?code=xyz&state=st")?.bangumiOAuth
        assertNotEquals(first?.key, other?.key)
        // 同一 error 文案可安全去重（重放不再换票），不同载荷不冲突
        val denied1 = parseAniMeowDeepLink("animeow://oauth/bangumi?error=access_denied")?.bangumiOAuth
        val denied2 = parseAniMeowDeepLink("animeow://oauth/bangumi?error=access_denied")?.bangumiOAuth
        assertEquals(denied1?.key, denied2?.key)
        assertNotEquals(first?.key, denied1?.key)
    }

    @Test
    fun existingRoutesStayUntouched() {
        assertEquals(42L, parseAniMeowDeepLink("animeow://anime/42")?.animeId)
        assertNull(parseAniMeowDeepLink("animeow://anime/42")?.bangumiOAuth)
        assertNull(parseAniMeowDeepLink("animeow://oauth/bangumi?code=abc&state=st")?.animeId)
    }
}
