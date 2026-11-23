package com.animeow.app.data.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class GameDiscoveryPageTest {
    @Test fun persistedGamePageKeepsStalenessPaginationAndSeparateIdentity() {
        val page = parseGameDiscoveryPage("""{"status":"success","data":[{"id":3154,"title":"命运石之门","platforms":"PC"}],"total":100,"offset":20,"hasMore":true,"stale":true,"updatedAt":"2026-10-16T08:42:50Z","scope":"按收藏热度排列"}""")
        assertEquals(3154L, page.items.single().bangumiId)
        assertEquals(0L, page.items.single().id)
        assertEquals("想玩", page.items.single().status)
        assertEquals(100, page.total); assertEquals(20, page.offset)
        assertTrue(page.hasMore); assertTrue(page.stale)
        assertEquals("2026-10-16T08:42:50Z", page.updatedAt)
    }
    @Test fun successfulEmptySearchIsDifferentFromFailedRequest() {
        val empty = parseGameDiscoveryPage("""{"status":"success","data":[],"total":0,"offset":0,"hasMore":false}""")
        assertTrue(empty.items.isEmpty()); assertFalse(empty.hasMore)
        assertThrows(IllegalStateException::class.java) { parseGameDiscoveryPage("""{"status":"error","message":"unavailable"}""") }
        assertThrows(IllegalArgumentException::class.java) { parseGameDiscoveryPage("""{"status":"success","data":[{"id":0,"title":"bad"}],"total":1,"offset":0}""") }
    }
    @Test fun optionalFreshnessFieldsKeepOldServerCompatibilityAndParseSeparateRankingTime() {
        val old = parseGameDiscoveryPage("""{"status":"success","data":[],"total":0,"offset":0,"stale":true}""")
        assertNull(old.freshness)
        val current = parseGameDiscoveryPage("""{"status":"success","data":[],"total":0,"offset":0,"stale":false,"rankingUpdatedAt":"2026-10-18T08:00:00Z","dataUpdatedAt":null,"dataStale":true,"refreshing":false,"refreshFailed":false,"scope":null}""")
        assertNull(current.scope); assertNull(current.freshness?.dataUpdatedAt)
        assertEquals("2026-10-18T08:00:00Z", current.freshness?.rankingUpdatedAt)
        assertTrue(requireNotNull(current.freshness).dataStale)
    }
    @Test fun outdatedDataDoesNotPretendThereWasARefreshFailureAndRunningStateTakesPriority() {
        val old = gameDiscoveryMessages(true, null, true, null)
        assertTrue(old.any { it.contains("较早") }); assertFalse(old.any { it.contains("失败") })
        val meta = GameDiscoveryFreshness("2026-10-18T08:00:00Z", null, true, false, false)
        val current = gameDiscoveryMessages(false, null, true, meta)
        assertTrue(current.any { it.startsWith("热榜更新于") })
        assertTrue(current.any { it.startsWith("本页部分") }); assertFalse(current.any { it.contains("失败") || it.contains("延迟") })
        val failed = gameDiscoveryMessages(true, null, true, meta.copy(refreshFailed = true))
        assertTrue(failed.any { it.contains("热榜更新失败") })
        val running = gameDiscoveryMessages(true, null, true, meta.copy(refreshing = true, refreshFailed = true))
        assertTrue(running.any { it.contains("正在更新") }); assertFalse(running.any { it.contains("失败") })
        val search = gameDiscoveryMessages(true, null, false, meta.copy(refreshFailed = true))
        assertTrue(search.any { it.contains("来源暂时不可用") }); assertFalse(search.any { it.contains("热榜") })
    }
}
