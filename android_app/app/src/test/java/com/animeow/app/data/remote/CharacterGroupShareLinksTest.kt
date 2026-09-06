package com.animeow.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterGroupShareLinksTest {
    @Test
    fun extractsPrefixedAndStandaloneShareCodes() {
        assertEquals("A9K3M8PX", extractCharacterGroupShareCode("cg#A9K3M8PX"))
        assertEquals("A9K3M8PX", extractCharacterGroupShareCode("角色组分享码：a9k3m8px，欢迎导入"))
        assertEquals("A9K3M8PX", extractCharacterGroupShareCode(" A9K3M8PX "))
    }

    @Test
    fun rejectsAmbiguousClipboardTextAndExcludedCharacters() {
        assertNull(extractCharacterGroupShareCode("PASSWORD"))
        assertNull(extractCharacterGroupShareCode("普通文本 A9K3M8PX 没有分享码标签"))
        assertNull(extractCharacterGroupShareCode("cg#A1K3M8PX"))
    }

    @Test
    fun parsesAnimeAndCommunityDeepLinks() {
        assertEquals(42L, parseAniMeowDeepLink("animeow://anime/42")?.animeId)
        assertEquals(
            "A9K3M8PX",
            parseAniMeowDeepLink("animeow://community/share/a9k3m8px")?.community?.shareCode,
        )
        assertEquals(
            "cg_a1b2c3d4e5f67890",
            parseAniMeowDeepLink("animeow://community/group/cg_a1b2c3d4e5f67890")?.community?.communityId,
        )
    }

    @Test
    fun ignoresForeignOrMalformedLinks() {
        assertNull(parseAniMeowDeepLink("https://example.com/anime/42"))
        assertNull(parseAniMeowDeepLink("animeow://anime/not-a-number"))
        assertNull(parseAniMeowDeepLink("animeow://community/share/PASSWORD"))
    }
}
