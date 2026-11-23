package com.animeow.app.data.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteLanguagesTest {
    @Test fun verifiedChineseWinsWithoutLosingTheOriginalLanguageOrChangingIdentity() {
        val original = RemoteAnime(RemoteCatalogSource.ANILIST, "123", "English title", titleLanguage = "en", localizedTitles = mapOf("en" to "English title"))
        val update = RemoteAnime(RemoteCatalogSource.SERVER, "999", "中文标题", originSource = "anilist", originId = "123",
            localizedTitles = mapOf("zh" to "中文标题", "ja" to "日本語"), localizedSummaries = mapOf("zh" to "中文简介"))
        val merged = localizedDiscoveryItem(original, update)
        assertEquals("中文标题", merged.title)
        assertEquals("English title", merged.localizedTitles["en"])
        assertEquals(original.uniqueKey, merged.uniqueKey)
        assertEquals(original, localizedDiscoveryItem(original, update.copy(originId = "124")))
        assertEquals("中文标题", localizedDiscoveryItem(merged, original).title)
    }
}
