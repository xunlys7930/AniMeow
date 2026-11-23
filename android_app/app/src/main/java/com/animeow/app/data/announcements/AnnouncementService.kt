package com.animeow.app.data.announcements

import android.content.Context
import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.data.cloud.cloudRemoteFailure
import com.animeow.app.data.remote.JsonHttpClient
import com.animeow.app.data.remote.RemoteHttpException
import org.json.JSONArray
import org.json.JSONObject

data class AppAnnouncement(
    val id: Long,
    val kind: String,
    val title: String,
    val content: String,
    val publishedAt: String,
    val withdrawn: Boolean = false,
) {
    val kindLabel: String get() = if (kind == "message") "开发者消息" else "公告"
}

data class AnnouncementFeed(val items: List<AppAnnouncement>, val isDeveloper: Boolean = false)

internal fun parseAnnouncements(array: JSONArray): List<AppAnnouncement> = buildList {
    for (index in 0 until minOf(array.length(), 100)) {
        val item = array.optJSONObject(index) ?: continue
        val id = item.optLong("id").takeIf { it > 0 } ?: continue
        val title = item.optString("title").take(120).takeIf { it.isNotBlank() } ?: continue
        add(AppAnnouncement(id, item.optString("kind", "announcement"), title,
            item.optString("content").take(4000), item.optString("published_at"),
            !item.isNull("withdrawn_at") && item.optString("withdrawn_at").isNotBlank()))
    }
}.distinctBy { it.id }.sortedByDescending { it.id }

internal fun List<AppAnnouncement>.toAnnouncementJson(): JSONArray = JSONArray().also { array ->
    forEach { item -> array.put(JSONObject().put("id", item.id).put("kind", item.kind)
        .put("title", item.title).put("content", item.content).put("published_at", item.publishedAt)) }
}

internal class AnnouncementService(
    context: Context,
    private val client: JsonHttpClient = JsonHttpClient(),
    private val apiBase: String = BuildConfig.CLOUD_API_BASE,
) {
    private val sessions = SecureCloudSessionStore(context)
    val configured get() = apiBase.isNotBlank()

    suspend fun list(manage: Boolean = false): AnnouncementFeed {
        val token = sessions.load()?.token
        val data = request(if (manage) "/api/developer/announcements" else "/api/announcements", "GET", token)
        return AnnouncementFeed(parseAnnouncements(data.getJSONArray("items")),
            token != null && sessions.load()?.token == token && data.optBoolean("viewer_is_developer"))
    }

    suspend fun publish(kind: String, title: String, content: String, requestId: String) {
        val token = sessions.load()?.token ?: throw CloudSessionExpiredException()
        request("/api/developer/announcements", "POST", token, JSONObject().put("kind", kind)
            .put("title", title.trim()).put("content", content.trim()).put("request_id", requestId))
    }

    suspend fun withdraw(id: Long) {
        val token = sessions.load()?.token ?: throw CloudSessionExpiredException()
        request("/api/developer/announcements/$id", "DELETE", token)
    }

    private suspend fun request(path: String, method: String, token: String?, body: JSONObject? = null): JSONObject {
        check(configured) { "公告服务尚未配置" }
        val url = apiBase.trim().trimEnd('/') + path
        val headers = if (token == null) emptyMap() else mapOf("Authorization" to "Bearer $token")
        val response = try {
            when (method) {
                "POST" -> client.post(url, body.toString(), headers)
                "DELETE" -> client.delete(url, headers)
                else -> client.get(url, headers)
            }
        } catch (error: RemoteHttpException) {
            if (error.statusCode in listOf(404, 405, 501)) throw IllegalStateException("服务器尚未启用公告功能，请稍后再试")
            val failure = cloudRemoteFailure(error.statusCode, error.responseBody, token != null, token)
            if (failure is CloudSessionExpiredException && token != null) sessions.clearIfCurrent(token)
            throw failure
        }
        val root = JSONObject(response)
        check(root.optString("status") == "success") { "公告暂时加载失败，请重试" }
        return root.getJSONObject("data")
    }
}
