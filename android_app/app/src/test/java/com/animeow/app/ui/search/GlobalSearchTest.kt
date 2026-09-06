package com.animeow.app.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {
    @Test
    fun normalizationIgnoresCommonTitleSeparators() {
        assertTrue(matches(normalizeSearchText("Re Zero"), "Re:Zero — Starting Life"))
        assertTrue(matches(normalizeSearchText("葬送的芙莉莲"), "葬送的 芙莉莲"))
    }

    @Test
    fun emptyQueryNeverMatchesEverything() {
        assertFalse(matches(normalizeSearchText("   "), "任意作品"))
    }
}
