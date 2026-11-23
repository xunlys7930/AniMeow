package com.animeow.app.data.remote

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class ServerSeasonalPageTest {
    private fun response(stale: Boolean) = """{
        "status":"success",
        "data":[{"id":123,"api_id":123,"source":"bangumi","subject_type":"anime","name_cn":"下一季作品","air_date":"2027-01-01"}],
        "snapshot":{"source":"bangumi","updatedAt":"2026-10-15T00:00:00Z","stale":$stale,"excludedCount":1},
        "pagination":{"page":2,"total":25,"totalPages":2,"hasMore":false}
    }"""

    @Test fun snapshotPreservesImportIdentityAndExactFilteredPagination() {
        val result = ServerCatalogService().parseSeasonalPage(response(false))
        assertEquals("bangumi", result.items.single().importSource)
        assertEquals("123", result.items.single().importId)
        assertEquals(2, result.page)
        assertEquals(25, result.totalCount)
        assertTrue(result.totalCountExact)
        assertFalse(result.hasMore)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.resultScope!!.contains("服务器整理"))
        assertTrue(result.resultScope!!.contains("另有 1 条"))
    }

    @Test fun staleSnapshotRemainsVisibleWithAWarning() {
        val result = ServerCatalogService().parseSeasonalPage(response(true))
        assertEquals(1, result.items.size)
        assertEquals(1, result.warnings.size)
    }
}
