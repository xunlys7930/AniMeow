package com.animeow.app.data.remote

import com.animeow.app.BuildConfig
import org.json.JSONObject

/** Public metadata only: account credentials and arbitrary destinations never enter this request. */
internal class PublicMetadataService(private val client: JsonHttpClient = JsonHttpClient(10_000, 25_000)) {
    suspend fun bangumi(method: String, path: String, body: String? = null): String = fetch("bangumi",
        JSONObject().put("method", method).put("path", path).apply { body?.let { put("body", JSONObject(it)) } })

    suspend fun anilist(operation: String, variables: JSONObject): String = fetch("anilist",
        JSONObject().put("operation", operation).put("variables", variables))

    suspend fun supplemental(source: String, query: String, id: String?): String = fetch(source,
        JSONObject().apply { if (id != null) put("id", id) else put("query", query) })

    suspend fun discussion(id: Long, kind: String, page: Int): String = fetch("bangumi-discussion",
        JSONObject().put("subjectId", id).put("kind", kind).put("page", page))

    suspend fun series(source: String, id: String): String = fetch("work-series", JSONObject().put("source", source).put("id", id))

    private suspend fun fetch(source: String, body: JSONObject): String {
        val base = BuildConfig.CLOUD_API_BASE.trimEnd('/')
        check(base.isNotBlank()) { "尚未配置资料服务器，请在高级设置中选择直连模式" }
        val root = try { JSONObject(client.post("$base/api/catalog/public/$source", body.toString())) }
        catch (error: RemoteHttpException) {
            if (error.statusCode == 404) error("服务器尚未提供统一资料入口，请稍后重试或主动选择直连模式")
            throw error
        }
        check(root.optString("status") == "success" && root.has("data")) { "服务器返回的资料不完整，请稍后重试" }
        val data = root.get("data")
        val meta = JSONObject().put("stale", root.optBoolean("stale")).put("updatedAt", root.optString("updatedAt"))
        return if (data is JSONObject) data.put("_animeow_cache", meta).toString()
        else JSONObject().put("_animeow_data", data).put("_animeow_cache", meta).toString()
    }
}

internal fun publicArray(response: String): org.json.JSONArray = if (response.trimStart().startsWith("[")) org.json.JSONArray(response)
    else JSONObject(response).getJSONArray("_animeow_data")

internal fun List<RemoteAnime>.withPublicCache(response: String): List<RemoteAnime> {
    val meta = runCatching { JSONObject(response).optJSONObject("_animeow_cache") }.getOrNull() ?: return this
    return map { it.copy(cacheStale = meta.optBoolean("stale"), cacheUpdatedAt = meta.optString("updatedAt").takeIf(String::isNotBlank)) }
}

internal fun publicCacheNotice(items: List<RemoteAnime>): String? {
    val stale = items.filter { it.cacheStale }
    if (stale.isEmpty()) return null
    val oldest = stale.mapNotNull { runCatching { java.time.Instant.parse(it.cacheUpdatedAt) }.getOrNull() }.minOrNull()
    val time = oldest?.atZone(java.time.ZoneId.systemDefault())?.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    return "部分资料暂时无法更新，显示服务器保留的内容" + (time?.let { "（更新于 $it）" } ?: "")
}
