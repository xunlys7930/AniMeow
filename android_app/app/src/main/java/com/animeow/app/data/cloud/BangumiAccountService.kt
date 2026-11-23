package com.animeow.app.data.cloud

import android.content.Context
import com.animeow.app.BuildConfig
import com.animeow.app.data.remote.JsonHttpClient
import com.animeow.app.data.remote.RemoteHttpException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Bangumi 相关业务错误；[code] 对应后端机器可读错误码（如 password_required）。 */
internal class BangumiApiException(val code: String?, message: String) : IllegalStateException(message)

data class BangumiAuthorizeLink(
    val authorizeUrl: String,
    val state: String,
    val expiresAt: String?,
)

data class BangumiBinding(
    val id: Long,
    val username: String,
    val nickname: String?,
    val avatar: String?,
    val tokenActive: Boolean,
    val expiresAt: String?,
)

data class BangumiAccountProfile(
    val hasPassword: Boolean,
    val bangumi: BangumiBinding?,
)

data class BangumiExchangeResult(
    val session: CloudSession,
    val bangumi: BangumiBinding,
    val accessToken: String,
)

data class BangumiTokenGrant(
    val accessToken: String,
    val expiresAt: String?,
)

/**
 * Bangumi 账号链路（授权 / 换票 / 刷新 / 解绑）的后端客户端。
 *
 * 令牌权威副本在服务端；本机只做读缓存。BGM 侧 401 由调用方处理为
 * 「refresh 一次 → 重试一次 → 黄标」，绝不清除 AniMeow 会话。
 */
internal class BangumiAccountService(
    context: Context,
    private val client: JsonHttpClient = JsonHttpClient(),
    private val apiBase: String = BuildConfig.CLOUD_API_BASE,
) {
    private val appContext = context.applicationContext
    private val sessionStore = SecureCloudSessionStore(appContext)
    private val tokenStore = SecureBangumiTokenStore(appContext)
    private val deviceRegistrationId = DeviceRegistrationId.from(appContext)

    val isConfigured: Boolean get() = apiBase.isNotBlank()

    fun loadTokens(): BangumiTokenSession? = tokenStore.load()

    fun clearTokens() = tokenStore.clear()

    fun clearTokensIfUser(userId: Long) = tokenStore.clearIfUser(userId)

    suspend fun requestAuthorizeUrl(): BangumiAuthorizeLink {
        val root = request("POST", "/api/auth/bangumi/authorize-url", body = JSONObject())
        val data = root.getJSONObject("data")
        return BangumiAuthorizeLink(
            authorizeUrl = data.getString("authorize_url"),
            state = data.optString("state"),
            expiresAt = data.optString("expires_at").takeIf(String::isNotBlank),
        )
    }

    /**
     * code+state（S1 直跳）或 ticket（后端中转）二选一换票。
     * 未带会话 = 登录/自动建号分支；带会话 = 绑定分支。返回的 [BangumiExchangeResult.session]
     * 是应当前使用的会话（同用户时保留原 token，避免飞行中的请求被判过期）。
     */
    suspend fun exchange(
        code: String? = null,
        state: String? = null,
        ticket: String? = null,
    ): BangumiExchangeResult {
        val session = sessionStore.load()
        val body = JSONObject()
            .put("device_registration_id", deviceRegistrationId)
        code?.takeIf(String::isNotBlank)?.let { body.put("code", it) }
        state?.takeIf(String::isNotBlank)?.let { body.put("state", it) }
        ticket?.takeIf(String::isNotBlank)?.let { body.put("ticket", it) }
        val root = request("POST", "/api/auth/bangumi/exchange", session = session, body = body)
        val data = root.getJSONObject("data")
        val user = data.getJSONObject("user")
        val returned = CloudSession(
            userId = user.getLong("id"),
            username = user.getString("username"),
            token = data.getString("token"),
        )
        val bangumiJson = data.getJSONObject("bangumi")
        val accessToken = bangumiJson.getString("access_token")
        val binding = bangumiJson.toBinding(tokenActive = true)
        val effective = if (session != null && session.userId == returned.userId) session else returned
        withContext(Dispatchers.IO) {
            if (effective !== session) sessionStore.save(effective)
            tokenStore.save(
                BangumiTokenSession(
                    userId = effective.userId,
                    bangumiId = binding.id,
                    username = binding.username,
                    nickname = binding.nickname,
                    avatar = binding.avatar,
                    accessToken = accessToken,
                    expiresAt = binding.expiresAt,
                ),
            )
        }
        return BangumiExchangeResult(session = effective, bangumi = binding, accessToken = accessToken)
    }

    /** 取可用 access token：缓存未临期直接用，否则向服务端换取（服务端持有权威令牌）。 */
    suspend fun accessToken(): String {
        val session = requireSession()
        val cached = tokenStore.load()?.takeIf { it.userId == session.userId }
        if (cached != null && !isTokenNearExpiry(cached.expiresAt)) return cached.accessToken
        return refreshTokens().accessToken
    }

    suspend fun refreshTokens(): BangumiTokenGrant {
        val session = requireSession()
        val root = request("POST", "/api/auth/bangumi/refresh", session = session, body = JSONObject())
        val data = root.getJSONObject("data")
        val grant = BangumiTokenGrant(
            accessToken = data.getString("access_token"),
            expiresAt = data.optString("expires_at").takeIf(String::isNotBlank),
        )
        withContext(Dispatchers.IO) {
            tokenStore.updateTokens(session.userId, grant.accessToken, grant.expiresAt)
        }
        return grant
    }

    suspend fun unbind() {
        val session = requireSession()
        request("POST", "/api/auth/bangumi/unbind", session = session, body = JSONObject())
        withContext(Dispatchers.IO) { tokenStore.clearIfUser(session.userId) }
    }

    suspend fun fetchProfile(): BangumiAccountProfile {
        val session = requireSession()
        val root = request("GET", "/api/user/me", session = session)
        val user = root.getJSONObject("data").getJSONObject("user")
        return BangumiAccountProfile(
            hasPassword = user.optBoolean("has_password"),
            bangumi = user.optJSONObject("bangumi")?.toBinding(),
        )
    }

    /** SSO 自动建号的首设密码（无旧密码；不吊销当前会话）。 */
    suspend fun setFirstPassword(newPassword: String) {
        val session = requireSession()
        request(
            method = "PUT",
            path = "/api/user/password",
            session = session,
            body = JSONObject().put("new_password", newPassword),
        )
    }

    private fun requireSession(): CloudSession =
        sessionStore.load() ?: error("请先登录云账号")

    private suspend fun request(
        method: String,
        path: String,
        session: CloudSession? = null,
        body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val headers = buildMap {
            put("Accept", "application/json")
            session?.let { put("Authorization", "Bearer ${it.token}") }
        }
        val response = try {
            when (method) {
                "GET" -> client.get(baseUrl() + path, headers)
                "POST" -> client.post(baseUrl() + path, body?.toString().orEmpty(), headers)
                "PUT" -> client.put(baseUrl() + path, body?.toString().orEmpty(), headers)
                else -> error("不支持的请求方法")
            }
        } catch (exception: RemoteHttpException) {
            throw bangumiFailure(exception.statusCode, exception.responseBody, session)
        }
        val root = JSONObject(response)
        if (root.optString("status") != "success") {
            throw BangumiApiException(
                root.optString("code").takeIf(String::isNotBlank),
                safeCloudMessage(root.optString("message")) ?: "云端请求失败，请稍后重试",
            )
        }
        root
    }

    private fun baseUrl(): String = apiBase.trim().trimEnd('/')
        .takeIf(String::isNotEmpty)
        ?: error("云端服务尚未配置，请联系开发者")

    private fun JSONObject.toBinding(tokenActive: Boolean = optBoolean("token_active", true)): BangumiBinding =
        BangumiBinding(
            id = optLong("id"),
            username = optString("username"),
            nickname = optString("nickname").takeIf(String::isNotBlank),
            avatar = optString("avatar").takeIf(String::isNotBlank),
            tokenActive = tokenActive,
            expiresAt = optString("expires_at").takeIf(String::isNotBlank),
        )

    private companion object {
        /** 临期提前 30 分钟换取新令牌，与后端刷新窗口一致。 */
        const val NEAR_EXPIRY_MS = 30L * 60 * 1000

        fun isTokenNearExpiry(expiresAt: String?): Boolean {
            val instant = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return true
            return instant.toEpochMilli() - System.currentTimeMillis() <= NEAR_EXPIRY_MS
        }
    }
}

/**
 * BGM 是否「拒绝了令牌」（BGM 侧 401 或后端 bangumi_auth_expired）。
 * 调用方据此做「换新令牌重试一次 → 降级」处理；AniMeow 会话契约的 401
 * 已在 [bangumiFailure] 映射为 [CloudSessionExpiredException]，与此无关。
 *
 * 必须同时检查 suppressed：`BangumiService.preferred()` 在官方线路 401、备用镜像
 * 403 时，把官方的 401 挂在镜像异常的 suppressed 上，只沿 cause 找会漏掉它。
 */
internal fun isBangumiAuthRejected(error: Throwable): Boolean {
    val visited = HashSet<Throwable>()
    fun visit(current: Throwable?, depth: Int): Boolean {
        if (current == null || depth > 8 || !visited.add(current)) return false
        if (current is RemoteHttpException && current.statusCode == 401) return true
        if (current is BangumiApiException && current.code == "bangumi_auth_expired") return true
        return visit(current.cause, depth + 1) || current.suppressed.any { visit(it, depth + 1) }
    }
    return visit(error, 0)
}

/**
 * HTTP 失败 → 异常。仅「后端会话契约」的 401 映射为 [CloudSessionExpiredException]；
 * 裸 401 与其余状态码都是 [BangumiApiException]，绝不能触发登出。
 * Bangumi 侧的授权失效是 409 bangumi_auth_expired，与 AniMeow 会话无关。
 */
internal fun bangumiFailure(statusCode: Int, responseBody: String, session: CloudSession?): Throwable {
    val payload = runCatching { JSONObject(responseBody) }.getOrNull()
    val code = payload?.optString("code")?.takeIf(String::isNotBlank)
    val message = safeCloudMessage(payload?.optString("message"))
    if (session != null && statusCode == 401) {
        val sessionRejected = code in setOf("SESSION_EXPIRED", "INVALID_USER_TOKEN") ||
            message in setOf("请先登录账号", "登录会话已失效，请重新登录", "登录会话已过期，请重新登录")
        if (sessionRejected) {
            return CloudSessionExpiredException(message ?: "登录会话已失效，请重新登录", session.token)
        }
    }
    val fallback = when {
        statusCode == 429 -> "尝试过于频繁，请稍后再试"
        statusCode in 500..599 -> "云端服务暂时不可用；本地资料不受影响"
        else -> "Bangumi 请求失败，请稍后重试"
    }
    return BangumiApiException(code, message ?: fallback)
}
