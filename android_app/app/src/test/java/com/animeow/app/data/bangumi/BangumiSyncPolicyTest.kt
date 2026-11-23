package com.animeow.app.data.bangumi

import com.animeow.app.data.cloud.BangumiApiException
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.cloud.isBangumiAuthRejected
import com.animeow.app.data.remote.RemoteHttpException
import java.io.IOException
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class BangumiSyncPolicyTest {
    private val session = CloudSession(userId = 1, username = "u", token = "jwt")

    /** 模拟 BangumiService.preferred()：官方 401 挂在镜像 403 的 suppressed 上。 */
    private fun officialRejectedMirrorForbidden(): Throwable {
        val mirror = RemoteHttpException(403, """{"request":"ROUTE_NOT_ALLOWED"}""")
        mirror.addSuppressed(RemoteHttpException(401, "{}"))
        return IllegalStateException("首选与备用接口均不可用", mirror)
    }

    @Test
    fun authRejectionFoundInSuppressed() {
        assertTrue(isBangumiAuthRejected(officialRejectedMirrorForbidden()))
        assertTrue(isBangumiAuthRejected(BangumiApiException("bangumi_auth_expired", "x")))
        assertFalse(isBangumiAuthRejected(RemoteHttpException(403, "")))
    }

    @Test
    fun transientFailuresNeverDropRows() {
        val cases = listOf(
            IOException("网络连接中断", java.net.SocketTimeoutException()),
            RemoteHttpException(502, ""),
            BangumiRelayUnavailableException(),
            BangumiRelayException(429, "bangumi_rate_limited", null, "x"),
            BangumiRelayException(503, "bangumi_upstream_unavailable", null, "x"),
            BangumiApiException("bangumi_auth_expired", "x"),
            BangumiApiException("bangumi_not_bound", "x"),
            CloudSessionExpiredException(),
            officialRejectedMirrorForbidden(),
        )
        cases.forEach { error ->
            assertTrue(error.toString(), classifyBangumiSyncFailure(error).kind != BangumiSyncFailureKind.PERMANENT)
        }
        assertEquals(BangumiSyncFailureKind.RELAY_MISSING, classifyBangumiSyncFailure(BangumiRelayUnavailableException()).kind)
        assertTrue(classifyBangumiSyncFailure(IOException("x")).stopsRun)
    }

    @Test
    fun definitiveBangumiAnswersArePermanent() {
        assertEquals(
            BangumiSyncFailureKind.PERMANENT,
            classifyBangumiSyncFailure(BangumiRelayException(400, "bangumi_bad_request", 404, "x")).kind,
        )
        assertEquals(BangumiSyncFailureKind.PERMANENT, classifyBangumiSyncFailure(RemoteHttpException(404, "")).kind)
    }

    @Test
    fun relayFallbackDecision() {
        assertTrue(shouldFallbackToRelay(IOException("reset")))
        assertTrue(shouldFallbackToRelay(RemoteHttpException(403, "")))
        assertTrue(shouldFallbackToRelay(officialRejectedMirrorForbidden()))
        assertFalse(shouldFallbackToRelay(RemoteHttpException(404, "")))
        assertFalse(shouldFallbackToRelay(BangumiApiException("bangumi_not_bound", "x")))
        assertFalse(shouldFallbackToRelay(IllegalStateException("请先登录云账号")))
    }

    @Test
    fun routePlanPrefersRelayAfterRecentDirectFailure() {
        val now = 1_000_000_000L
        assertEquals(BangumiSyncRoute.DIRECT, planBangumiRoutes(now, null, null).first())
        assertEquals(BangumiSyncRoute.RELAY, planBangumiRoutes(now, now - 60_000, null).first())
        assertEquals(BangumiSyncRoute.DIRECT, planBangumiRoutes(now, now - 11 * 60_000, null).first())
        assertEquals(BangumiSyncRoute.DIRECT, planBangumiRoutes(now, now - 60_000, now - 60_000).first())
        assertEquals(2, planBangumiRoutes(now, null, null).size)
    }

    @Test
    fun relay404MeansOldBackendAndOrdinary401NeverLogsOut() {
        assertTrue(relayFailure(404, "", session) is BangumiRelayUnavailableException)
        assertFalse(relayFailure(401, """{"code":"x"}""", session) is CloudSessionExpiredException)
        val expired = relayFailure(409, """{"code":"bangumi_auth_expired","message":"Bangumi 授权已过期，请重新连接"}""", session)
        assertTrue(expired is BangumiApiException)
        val missing = relayFailure(400, """{"code":"bangumi_bad_request","upstream_status":404}""", session) as BangumiRelayException
        assertEquals(404, missing.upstreamStatus)
    }

    @Test
    fun reportRoundTripAndTexts() {
        val report = BangumiSyncReport(
            finishedAt = "2026-09-25T08:30:00Z", manual = true, uploaded = 2, remaining = 1, dropped = 1,
            reason = "网络连接不稳定", droppedItems = listOf("《A》状态：Bangumi 上没有这个条目"), usedRelay = true,
        )
        assertEquals(report, BangumiSyncReport.decode(report.encode()))
        assertNull(BangumiSyncReport.decode("not json"))
        val message = bangumiSyncResultMessage(report)
        assertTrue(message.contains("已上报 2 项"))
        assertTrue(message.contains("1 项被 Bangumi 拒绝"))
        assertTrue(message.contains("1 项改动仍在等待上报：网络连接不稳定"))
        assertEquals("同步未完成 · 3 项等待重试", bangumiQueueStatusText(3, report))
        assertEquals("待上报 3 项改动", bangumiQueueStatusText(3, null))
        assertFalse(bangumiQueueStatusText(0, report).contains("所有改动均已上报"))
        assertEquals("所有改动均已上报", bangumiQueueStatusText(0, report.copy(dropped = 0)))
        assertEquals("暂无待同步改动", bangumiQueueStatusText(0, null))
        assertEquals(
            "上次同步：9月25日 08:30（经服务器中转） · 上报 2 项 · 被拒 1 项 · 剩余 1 项",
            bangumiLastRunText(report, ZoneOffset.UTC),
        )
    }
}
