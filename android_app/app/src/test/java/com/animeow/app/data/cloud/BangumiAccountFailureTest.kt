package com.animeow.app.data.cloud

import com.animeow.app.data.remote.RemoteHttpException
import com.animeow.app.ui.cloud.bangumiCallbackErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 失败映射的会话保护红线：裸 401 绝不能变成 [CloudSessionExpiredException]（否则会被登出），
 * 只有后端会话契约的 401 才映射为会话失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class BangumiAccountFailureTest {
    private val session = CloudSession(userId = 7, username = "tester", token = "jwt-token")

    @Test
    fun bareUnauthorizedNeverClearsSession() {
        val error = bangumiFailure(401, """{"code":"invalid_token","message":"令牌无效"}""", session)
        assertTrue(error is BangumiApiException)
        assertFalse(error is CloudSessionExpiredException)
        assertEquals("invalid_token", (error as BangumiApiException).code)
    }

    @Test
    fun sessionContractUnauthorizedMapsToSessionExpired() {
        assertTrue(bangumiFailure(401, """{"code":"SESSION_EXPIRED"}""", session) is CloudSessionExpiredException)
        assertTrue(
            bangumiFailure(401, """{"message":"登录会话已失效，请重新登录"}""", session) is CloudSessionExpiredException,
        )
    }

    @Test
    fun rateLimitAndServerErrorsGetFriendlyTexts() {
        assertEquals("尝试过于频繁，请稍后再试", bangumiFailure(429, "", session).message)
        assertEquals("云端服务暂时不可用；本地资料不受影响", bangumiFailure(502, "", session).message)
        assertEquals("Bangumi 请求失败，请稍后重试", bangumiFailure(404, "", session).message)
    }

    @Test
    fun bangumiExpiryKeepsCodeAndTriggersRefreshPath() {
        val error = bangumiFailure(
            409,
            """{"code":"bangumi_auth_expired","message":"Bangumi 授权已过期"}""",
            session,
        )
        assertEquals("bangumi_auth_expired", (error as BangumiApiException).code)
        assertTrue(isBangumiAuthRejected(error))
    }

    @Test
    fun authRejectedDetectionWalksCauseChains() {
        assertTrue(isBangumiAuthRejected(RemoteHttpException(401, "")))
        assertTrue(isBangumiAuthRejected(IllegalStateException("线路包装", RemoteHttpException(401, ""))))
        assertTrue(
            isBangumiAuthRejected(
                RuntimeException(
                    "外层",
                    IllegalStateException("中层", BangumiApiException("bangumi_auth_expired", "x")),
                ),
            ),
        )
        assertFalse(isBangumiAuthRejected(RemoteHttpException(404, "")))
        assertFalse(isBangumiAuthRejected(IllegalStateException("请先登录云账号")))
        assertFalse(isBangumiAuthRejected(BangumiApiException("password_required", "x")))
    }

    @Test
    fun callbackErrorMessagesAreUserFacing() {
        assertEquals("已取消 Bangumi 授权，未做任何更改", bangumiCallbackErrorMessage("access_denied"))
        assertEquals("登录凭证已失效，请重新发起登录", bangumiCallbackErrorMessage("state_invalid"))
        assertEquals("Bangumi 授权失败，请稍后重试", bangumiCallbackErrorMessage("exchange_failed"))
        assertEquals("Bangumi 授权失败，请稍后重试", bangumiCallbackErrorMessage("authorize_failed"))
    }
}
