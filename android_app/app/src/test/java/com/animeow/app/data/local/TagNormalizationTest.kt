package com.animeow.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TagNormalizationTest {
    @Test fun upstreamPunctuationAndWidthVariantsShareOneIdentity() {
        assertEquals(normalizeTagKey("轻百合"), normalizeTagKey("  輕百。 "))
        assertEquals(normalizeTagKey("校园"), normalizeTagKey("校园..."))
        assertEquals(normalizeTagKey("OVA"), normalizeTagKey("ＯＶＡ！"))
        assertEquals(normalizeTagKey("Slice of Life"), normalizeTagKey("Ｓｌｉｃｅ－ｏｆ－Ｌｉｆｅ"))
    }

    @Test fun distinctMeaningfulSymbolsAreNotMerged() {
        assertNotEquals(normalizeTagKey("C"), normalizeTagKey("C++"))
        assertNotEquals(normalizeTagKey("2.5次元"), normalizeTagKey("25次元"))
        assertNotEquals(normalizeTagKey("科幻"), normalizeTagKey("奇幻"))
        assertEquals("", normalizeTagKey("。…"))
    }
}
