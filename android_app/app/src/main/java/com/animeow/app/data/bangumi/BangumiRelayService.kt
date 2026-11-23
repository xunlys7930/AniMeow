package com.animeow.app.data.bangumi

import android.content.Context
import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.BangumiApiException
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.data.cloud.bangumiFailure
import com.animeow.app.data.cloud.safeCloudMessage
import com.animeow.app.data.remote.JsonHttpClient
import com.animeow.app.data.remote.RemoteHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 当前服务器还没有 Bangumi 中转接口（旧后端对中转路径回 404）。改动必须保留，不能删除。 */
internal class BangumiRelayUnavailableException :
    IllegalStateException("服务器暂未开通 Bangumi 中转")

/**
 * 中转接口的业务失败（会话失效与 Bangumi 授权问题另有专门异常）。
 * [upstreamStatus] 是 Bangumi 原始状态码（例如条目不存在时为 404），用于区分永久与临时失败。
 */
internal class BangumiRelayException(
    val httpStatus: Int,
    val code: String?,
    val upstreamStatus: Int?,
    message: String,
) : IllegalStateException(message)

/**
 * 经 AniMeow 服务器转交 Bangumi 读写（后端 /api/auth/bangumi/relay/ 下的接口，契约见 backend/docs/bangumi-relay.md）。
 *
 * 服务器用它保存的 Bangumi 授权代为调用，手机不需要能访问 api.bgm.tv。
 * 用户凭证是 AniMeow 登录会话；会话失效只按 [bangumiFailure] 的既有契约判定，普通 401 不会登出。
 */
internal class BangumiRelayService(
    context: Context,
    // 服务端写一次进度可能要翻多页剧集列表，读超时比普通接口放宽
    private val client: JsonHttpClient = JsonHttpClient(connectTimeoutMs = 10_000, readTimeoutMs = 45_000),
    private val apiBase: String = BuildConfig.CLOUD_API_BASE,
    private val expectedUserId: Long? = null,
    private val expectedBangumiId: Long? = null,
) {
    private val sessionStore = SecureCloudSessionStore(context.applicationContext)

    suspend fun writeCollection(subjectId: Long, type: Int?, rate: Int?) {
        require(subjectId > 0) { "作品 ID 无效" }
        require(type != null || rate != null) { "没有需要同步的收藏变更" }
        val body = JSONObject().put("subject_id", subjectId).apply {
            expectedBangumiId?.let { put("expected_bangumi_id", it) }
            type?.let { put("type", it) }
            rate?.let { put("rate", it) }
        }
        request("POST", "/api/auth/bangumi/relay/collection", body)
    }

    suspend fun writeEpisodes(subjectId: Long, delta: BangumiEpisodeDelta) {
        require(subjectId > 0) { "作品 ID 无效" }
        if (delta.isEmpty) return
        val body = JSONObject()
            .put("subject_id", subjectId)
            .put("add", JSONArray(delta.add.sorted()))
            .put("remove", JSONArray(delta.remove.sorted()))
        request("POST", "/api/auth/bangumi/relay/episodes", body)
    }

    /** 自己的动画收藏（含私密）一页，返回与 Bangumi `/v0/users/-/collections` 同构的 JSON。 */
    suspend fun fetchMyAnimeCollectionsPage(offset: Int, limit: Int): String {
        val root = request(
            "GET",
            "/api/auth/bangumi/relay/me/collections?subject_type=2&limit=${limit.coerceIn(1, 50)}&offset=${offset.coerceAtLeast(0)}",
            null,
        )
        return (root.optJSONObject("data") ?: JSONObject()).toString()
    }

    suspend fun fetchMyGameCollectionsPage(offset: Int, limit: Int = 50): String {
        val root = request("GET", "/api/auth/bangumi/relay/me/collections?subject_type=4&expected_bangumi_id=${expectedBangumiId ?: ""}&limit=${limit.coerceIn(1, 50)}&offset=${offset.coerceAtLeast(0)}", null)
        return (root.optJSONObject("data") ?: JSONObject()).toString()
    }

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val base = apiBase.trim().trimEnd('/').takeIf(String::isNotEmpty)
                ?: throw BangumiRelayUnavailableException()
            val session = sessionStore.load() ?: error("请先登录云账号")
            check(expectedUserId == null || session.userId == expectedUserId) { "账号已变化，请重新同步" }
            val headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer ${session.token}",
            )
            val response = try {
                when (method) {
                    "GET" -> client.get(base + path, headers)
                    else -> client.post(base + path, body?.toString().orEmpty(), headers)
                }
            } catch (exception: RemoteHttpException) {
                throw relayFailure(exception.statusCode, exception.responseBody, session)
            }
            val root = runCatching { JSONObject(response) }.getOrNull()
                ?: throw BangumiRelayException(502, null, null, "服务器返回了无法识别的内容，请稍后重试")
            if (root.optString("status") != "success") {
                throw relayFailure(502, response, session)
            }
            root
        }
}

/**
 * 中转接口 HTTP 失败 → 异常：
 * - 404：旧后端没有中转接口（中转自身约定绝不回 404）→ [BangumiRelayUnavailableException]
 * - 401：交给 [bangumiFailure]，只有会话契约的 401 才是会话失效
 * - bangumi_not_bound / bangumi_auth_expired：沿用 [BangumiApiException]，与直连路径的授权判断一致
 * - 其余：[BangumiRelayException]，保留状态码供分类
 */
internal fun relayFailure(statusCode: Int, responseBody: String, session: CloudSession?): Throwable {
    if (statusCode == 404) return BangumiRelayUnavailableException()
    if (statusCode == 401) return bangumiFailure(statusCode, responseBody, session)
    val payload = runCatching { JSONObject(responseBody) }.getOrNull()
    val code = payload?.optString("code")?.takeIf(String::isNotBlank)
    val message = safeCloudMessage(payload?.optString("message"))
    if (code == "bangumi_not_bound" || code == "bangumi_auth_expired") {
        return BangumiApiException(code, message ?: if (code == "bangumi_not_bound") "尚未绑定 Bangumi 账号" else "Bangumi 授权已过期，请重新连接")
    }
    val upstream = payload?.optInt("upstream_status", 0)?.takeIf { it > 0 }
    val fallback = when {
        statusCode == 429 -> "同步请求过于频繁，请稍后再试"
        statusCode in 500..599 -> "服务器或 Bangumi 暂时不可用，请稍后再试"
        else -> "Bangumi 拒绝了这次修改"
    }
    return BangumiRelayException(statusCode, code, upstream, message ?: fallback)
}
