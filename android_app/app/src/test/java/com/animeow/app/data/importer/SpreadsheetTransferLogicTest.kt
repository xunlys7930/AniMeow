package com.animeow.app.data.importer

import com.animeow.app.data.local.AnimeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpreadsheetTransferLogicTest {
    private val existing = AnimeEntity(
        id = 7,
        title = "旧标题",
        status = "在看",
        watchedEpisodes = 8,
        totalEpisodes = 12,
        tvEpisodes = 12,
        review = "本地短评",
        studio = "本地制作公司",
        coverUrl = "https://example.com/cover.jpg",
        externalSource = "bangumi",
        externalId = "123",
    )

    @Test
    fun replaceOnlyTouchesMappedFields() {
        val result = replaceAnimeFromSpreadsheet(
            existing = existing,
            title = "新标题",
            patch = SpreadsheetAnimePatch(
                mappedFields = setOf(SpreadsheetField.TITLE, SpreadsheetField.REVIEW),
                review = "表格短评",
            ),
        )

        assertEquals("新标题", result.title)
        assertEquals("表格短评", result.review)
        assertEquals("在看", result.status)
        assertEquals(8, result.watchedEpisodes)
        assertEquals(12, result.totalEpisodes)
        assertEquals(12, result.tvEpisodes)
        assertEquals("本地制作公司", result.studio)
        assertEquals(existing.coverUrl, result.coverUrl)
        assertEquals(existing.externalId, result.externalId)
    }

    @Test
    fun mappedBlankTextCanBeClearedWithoutClearingUnmappedText() {
        val result = replaceAnimeFromSpreadsheet(
            existing = existing,
            title = existing.title,
            patch = SpreadsheetAnimePatch(
                mappedFields = setOf(SpreadsheetField.TITLE, SpreadsheetField.REVIEW),
                review = null,
                studio = null,
            ),
        )

        assertNull(result.review)
        assertEquals("本地制作公司", result.studio)
    }

    @Test
    fun safeMergeDoesNotResetUnmappedValues() {
        val result = mergeAnimeFromSpreadsheet(
            existing = existing,
            patch = SpreadsheetAnimePatch(mappedFields = setOf(SpreadsheetField.TITLE)),
        )

        assertEquals(existing, result)
    }

    @Test
    fun safeMergeUsesMoreAdvancedProgressAndStatus() {
        val result = mergeAnimeFromSpreadsheet(
            existing = existing,
            patch = SpreadsheetAnimePatch(
                mappedFields = setOf(
                    SpreadsheetField.TITLE,
                    SpreadsheetField.STATUS,
                    SpreadsheetField.WATCHED,
                    SpreadsheetField.TOTAL,
                ),
                status = "看完",
                watchedEpisodes = 24,
                totalEpisodes = 24,
            ),
        )

        assertEquals("看完", result.status)
        assertEquals(24, result.watchedEpisodes)
        assertEquals(24, result.totalEpisodes)
        assertEquals(24, result.tvEpisodes)
    }

    @Test
    fun newRowsClampProgressToKnownTotal() {
        val result = newAnimeFromSpreadsheet(
            title = "测试作品",
            patch = SpreadsheetAnimePatch(
                mappedFields = setOf(
                    SpreadsheetField.TITLE,
                    SpreadsheetField.WATCHED,
                    SpreadsheetField.TOTAL,
                ),
                watchedEpisodes = 20,
                totalEpisodes = 12,
            ),
            createdAt = "2026-08-11T00:00:00Z",
        )

        assertEquals(12, result.watchedEpisodes)
        assertEquals(12, result.totalEpisodes)
        assertEquals("未看", result.status)
    }

    @Test
    fun completedRowsFillKnownTotalWhenProgressColumnIsBlank() {
        val result = newAnimeFromSpreadsheet(
            title = "测试作品",
            patch = SpreadsheetAnimePatch(
                mappedFields = setOf(
                    SpreadsheetField.TITLE,
                    SpreadsheetField.STATUS,
                    SpreadsheetField.TOTAL,
                ),
                status = "看完",
                totalEpisodes = 12,
            ),
            createdAt = "2026-08-11T00:00:00Z",
        )

        assertEquals(12, result.watchedEpisodes)
        assertEquals(12, result.totalEpisodes)
    }
}
