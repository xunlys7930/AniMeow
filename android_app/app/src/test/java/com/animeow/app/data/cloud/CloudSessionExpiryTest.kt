package com.animeow.app.data.cloud

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class CloudSessionExpiryTest {
    @Test
    fun readsJwtExpiryAndDetectsExpiredSession() {
        val token = tokenWithPayload("""{"sub":1,"exp":1_700_000_000}""".replace("_", ""))

        assertEquals(1_700_000_000L, cloudSessionExpiryEpochSeconds(token))
        assertFalse(isCloudSessionExpired(token, nowEpochSeconds = 1_699_999_999L))
        assertTrue(isCloudSessionExpired(token, nowEpochSeconds = 1_700_000_000L))
    }

    @Test
    fun keepsLegacyOrMalformedTokensUntilServerValidation() {
        assertFalse(isCloudSessionExpired("legacy-token", nowEpochSeconds = Long.MAX_VALUE))
        assertFalse(isCloudSessionExpired(tokenWithPayload("""{"sub":1}"""), nowEpochSeconds = Long.MAX_VALUE))
    }

    @Test
    fun authenticatedUnauthorizedResponseBecomesSessionExpiry() {
        val failure = cloudRemoteFailure(
            statusCode = 401,
            responseBody = """{"status":"error","message":"请先登录账号"}""",
            authenticated = true,
        )

        assertTrue(failure is CloudSessionExpiredException)
        assertEquals("请先登录账号", failure.message)
    }

    @Test
    fun publicUnauthorizedAndForbiddenResponsesStayRegularFailures() {
        assertFalse(cloudRemoteFailure(401, "{}", authenticated = false) is CloudSessionExpiredException)
        assertFalse(cloudRemoteFailure(403, "{}", authenticated = true) is CloudSessionExpiredException)
    }

    @Test
    fun rawAccessDeniedPayloadIsNeverShownToUsers() {
        val failure = cloudRemoteFailure(
            401,
            """{"status":"error","message":"Unauthorized: Access Denied"}""",
            authenticated = true,
        )

        assertFalse(failure is CloudSessionExpiredException)
        assertEquals("此云端功能暂时无法访问，请稍后重试", failure.message)
    }

    @Test
    fun ambiguousGatewaysAndServerFailuresNeverInvalidateAccounts() {
        listOf("{}", "<html>Unauthorized</html>", """{"message":"Unauthorized"}""",
            """{"message":"API token required"}""").forEach { body ->
            assertFalse(cloudRemoteFailure(401, body, true) is CloudSessionExpiredException)
        }
        assertFalse(cloudRemoteFailure(500, """{"message":"用户会话校验失败"}""", true) is CloudSessionExpiredException)
    }

    @Test
    fun expiryBelongsOnlyToTheSessionThatSentTheRequest() {
        val expired = cloudRemoteFailure(401, """{"message":"登录会话已失效，请重新登录"}""",
            authenticated = true, sessionToken = "earlier-session") as CloudSessionExpiredException
        assertTrue(expired.isFor(CloudSession(1, "fixture", "earlier-session")))
        assertFalse(expired.isFor(CloudSession(1, "fixture", "new-session")))
        assertFalse(expired.isFor(null))
    }

    @Test
    fun legacyBackendMissingEndpointGetsCompatibilityMessage() {
        val failure = cloudRemoteFailure(404, "<html>Not Found</html>", authenticated = true)

        assertEquals("当前服务器版本暂不支持此功能；本地资料不受影响", failure.message)
    }

    private fun tokenWithPayload(payload: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        return "header.$encoded.signature"
    }
}
