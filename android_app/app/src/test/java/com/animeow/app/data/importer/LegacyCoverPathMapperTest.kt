package com.animeow.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyCoverPathMapperTest {
    @Test
    fun rewritesLegacyAbsoluteCoverPath() {
        val result = LegacyCoverPathMapper.rewrite(
            original = "/data/user/0/app/app_flutter/covers/posters/test.jpg",
            exactPaths = mapOf("posters/test.jpg" to "file:///new/covers/posters/test.jpg"),
            uniqueBaseNames = mapOf("test.jpg" to "file:///new/covers/posters/test.jpg"),
        )

        assertEquals("file:///new/covers/posters/test.jpg", result)
    }

    @Test
    fun leavesRemoteCoverUrlUntouched() {
        val original = "https://example.com/cover.jpg"
        assertEquals(
            original,
            LegacyCoverPathMapper.rewrite(
                original = original,
                exactPaths = emptyMap(),
                uniqueBaseNames = emptyMap(),
            ),
        )
    }
}
