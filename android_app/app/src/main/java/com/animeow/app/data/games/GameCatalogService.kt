package com.animeow.app.data.games

import com.animeow.app.BuildConfig
import com.animeow.app.data.remote.JsonHttpClient
import java.net.URLEncoder
import org.json.JSONObject

internal class GameCatalogService(private val client: JsonHttpClient = JsonHttpClient()) {
    private val base = BuildConfig.CLOUD_API_BASE.trimEnd('/')
    suspend fun search(query: String, offset: Int): GameSearchPage {
        require(query.trim().length in 1..80) { "请输入 1–80 字的游戏名称" }
        val root = JSONObject(client.get("$base/api/games/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}&offset=$offset&includeVndb=1"))
        val rows = root.getJSONArray("data")
        return GameSearchPage(List(rows.length()) { parseGameMetadata(rows.getJSONObject(it)) }, root.optInt("total"),
            root.optInt("nextOffset", offset + rows.length()), root.optBoolean("hasMore", offset + rows.length() < root.optInt("total")), root.optBoolean("partial"))
    }
    suspend fun detail(game: GameEntity): GameEntity {
        val path = game.bangumiId?.toString() ?: game.vndbId?.takeIf { it.matches(Regex("v[1-9]\\d{0,8}")) }?.let { "vndb/$it" }
            ?: error("游戏来源编号无效")
        return parseGameMetadata(JSONObject(client.get("$base/api/games/$path")).getJSONObject("data"))
    }

    suspend fun discover(query: String, sort: String, page: Int): GameDiscoveryPage = parseGameDiscoveryPage(
        client.get("$base/api/games/discover?q=${URLEncoder.encode(query.trim(), "UTF-8")}&sort=$sort&offset=${(page.coerceAtLeast(1) - 1) * 20}&includeVndb=1"),
    )
}

internal data class GameSearchPage(val items: List<GameEntity>, val total: Int, val nextOffset: Int, val hasMore: Boolean, val partial: Boolean)

internal data class GameDiscoveryPage(val items: List<GameEntity>, val total: Int, val offset: Int, val hasMore: Boolean,
    val stale: Boolean, val updatedAt: String?, val scope: String?, val freshness: GameDiscoveryFreshness? = null, val totalPages: Int = ((total + 19) / 20).coerceAtLeast(1))

data class GameDiscoveryFreshness(val rankingUpdatedAt: String?, val dataUpdatedAt: String?, val dataStale: Boolean,
    val refreshing: Boolean, val refreshFailed: Boolean)

internal fun parseGameDiscoveryPage(response: String): GameDiscoveryPage {
    val root = JSONObject(response)
    check(root.optString("status") == "success") { "游戏资料暂时无法获取" }
    val rows = root.getJSONArray("data")
    return GameDiscoveryPage(List(rows.length()) { parseGameMetadata(rows.getJSONObject(it)) }, root.getInt("total"),
        root.getInt("offset"), root.optBoolean("hasMore"), root.optBoolean("stale"),
        root.optString("updatedAt").takeUnless { it.isBlank() || it == "null" }, root.optString("scope").takeUnless { it.isBlank() || it == "null" },
        if (root.has("rankingUpdatedAt")) GameDiscoveryFreshness(
            root.optString("rankingUpdatedAt").takeUnless { it.isBlank() || it == "null" },
            root.optString("dataUpdatedAt").takeUnless { it.isBlank() || it == "null" },
            root.optBoolean("dataStale"), root.optBoolean("refreshing"), root.optBoolean("refreshFailed")) else null, root.optInt("totalPages", ((root.getInt("total") + 19) / 20).coerceAtLeast(1)))
}

internal fun gameDiscoveryMessages(stale: Boolean, updatedAt: String?, heat: Boolean, freshness: GameDiscoveryFreshness?): List<String> {
    fun time(value: String?) = value?.let { runCatching { java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull() }
    return buildList {
        if (freshness == null) {
            if (stale) add("资料较早，当前显示服务器保存的内容")
            time(updatedAt)?.let { add("资料更新于 $it") }
        } else {
            when {
                freshness.refreshing -> add("正在更新热榜，当前仍可浏览")
                freshness.refreshFailed -> add(if (heat) "热榜更新失败，继续显示上次成功整理的榜单" else "部分资料来源暂时不可用，当前结果可能不完整")
                stale -> add(if (heat) "热榜资料较早，当前显示上次成功整理的榜单" else "当前部分资料较早，可打开游戏详情获取最新资料")
            }
            if (heat) {
                time(freshness.rankingUpdatedAt)?.let { add("热榜更新于 $it") }
                if (freshness.dataStale) add("本页部分游戏资料较早，可打开详情更新")
            } else time(freshness.dataUpdatedAt)?.let { add("本页最早更新于 $it") }
        }
    }
}

internal fun parseGameMetadata(row: JSONObject): GameEntity {
    val vn = row.optString("vndbId").takeIf { it.matches(Regex("v[1-9]\\d{0,8}")) }
    require((vn != null || row.optLong("id") > 0) && row.optString("title").isNotBlank()) { "游戏资料不完整" }
    return GameEntity(
        bangumiId = if (vn == null) row.getLong("id") else null, vndbId = vn, title = row.getString("title"), originalTitle = row.optString("originalTitle"),
        coverUrl = row.optString("coverUrl"), synopsis = row.optString("synopsis"), releaseDate = row.optString("releaseDate"),
        platforms = row.optString("platforms"), developer = row.optString("developer"), publisher = row.optString("publisher"),
        genre = row.optString("genre"), score = row.optDouble("score", 0.0).takeIf { it.isFinite() } ?: 0.0,
    )
}
