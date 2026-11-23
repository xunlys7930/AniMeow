package com.animeow.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class BangumiFailureMessageTest {
    private fun wrapper(first: Throwable, second: Throwable): Throwable {
        second.addSuppressed(first)
        return IllegalStateException("Bangumi 的首选与备用接口均不可用", second)
    }

    @Test fun missingUserIsReportedAsMissingUserNotAsAnOutage() {
        val error = RemoteHttpException(404, """{"title":"Not Found"}""")
        assertEquals("找不到这个 Bangumi 用户，请确认用户名或 UID 后重试", bangumiFailureMessage(error, "读取公开收藏失败"))
    }

    @Test fun unavailableLineSaysSoAndPointsAtTheSetting() {
        val error = RemoteHttpException(502, "<html>502 Bad Gateway</html>")
        val message = bangumiFailureMessage(error, "读取公开收藏失败")
        // 两条线路都失败才会走到这里，所以问题在 Bangumi 侧，不该建议用户换线路。
        assertEquals("Bangumi 服务端暂时不可用，请稍后重试", message)
    }

    @Test fun routeRejectionAndRateLimitHaveTheirOwnWording() {
        assertEquals(
            "当前 Bangumi 线路不支持这个请求，请到设置里更换线路后重试",
            bangumiFailureMessage(RemoteHttpException(403, ""), "fallback"),
        )
        assertEquals(
            "请求过于频繁，请稍后重试",
            bangumiFailureMessage(RemoteHttpException(429, ""), "fallback"),
        )
    }

    @Test fun bothLinesAreConsideredSoA404IsNotHiddenByAFailedFallback() {
        // 官方线路 404、备用线路 502：必须报"用户不存在"，而不是被回退失败盖成"线路不可用"。
        val error = wrapper(RemoteHttpException(404, "not found"), RemoteHttpException(502, "bad gateway"))
        assertEquals("找不到这个 Bangumi 用户，请确认用户名或 UID 后重试", bangumiFailureMessage(error, "读取公开收藏失败"))
    }

    @Test fun transportFailuresKeepTheirActionableWording() {
        val timeout = IOException("连接超时，请检查网络后重试")
        assertEquals("连接超时，请检查网络后重试", bangumiFailureMessage(timeout, "读取公开收藏失败"))
    }

    @Test fun unknownFailuresFallBackInsteadOfShowingNothing() {
        assertEquals("读取公开收藏失败", bangumiFailureMessage(RuntimeException(), "读取公开收藏失败"))
        assertEquals("读取公开收藏失败", bangumiFailureMessage(RuntimeException(""), "读取公开收藏失败"))
    }

    @Test fun selfReferencingCausesDoNotHangTheCollector() {
        val looping = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertEquals(emptyList<RemoteHttpException>(), collectRemoteFailures(looping))
    }
}
