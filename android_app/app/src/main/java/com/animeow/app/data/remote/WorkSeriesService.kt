package com.animeow.app.data.remote

import org.json.JSONObject

internal data class SourceSeriesWork(val source: String, val id: String, val title: String, val relation: String, val url: String)
internal data class SourceSeriesGroup(val id: String, val name: String, val url: String, val works: List<SourceSeriesWork>)
internal data class SourceSeriesPage(val groups: List<SourceSeriesGroup>, val partial: Boolean, val stale: Boolean)

internal fun parseSourceSeries(response: String): SourceSeriesPage {
    val root = JSONObject(response)
    val groups = root.getJSONArray("groups")
    fun safeUrl(value: String): Boolean = runCatching {
        val u = java.net.URI(value)
        u.scheme == "https" && u.userInfo == null && u.port in listOf(-1, 443) &&
            u.host in listOf("www.dlsite.com", "bgm.tv", "vndb.org")
    }.getOrDefault(false)
    return SourceSeriesPage((0 until minOf(groups.length(), 3)).map { index ->
        val group = groups.getJSONObject(index); val rows = group.getJSONArray("works")
        val url = group.getString("url"); require(safeUrl(url))
        SourceSeriesGroup(group.getString("id"), group.getString("name").take(100), url,
            (0 until minOf(rows.length(), 40)).map { i ->
                val row = rows.getJSONObject(i); val source = row.getString("source"); val id = row.getString("id")
                require(when (source) { "dlsite" -> id.matches(Regex("RJ\\d{6,8}")); "bangumi" -> id.matches(Regex("[1-9]\\d{0,9}")); "vndb" -> id.matches(Regex("v[1-9]\\d{0,8}")); else -> false })
                val link = row.getString("url"); require(safeUrl(link))
                SourceSeriesWork(source, id, row.getString("title").take(200), row.optString("relation").take(80), link)
            }.distinctBy { it.source to it.id })
    }, root.optBoolean("partial"), root.optJSONObject("_animeow_cache")?.optBoolean("stale") == true)
}

internal class WorkSeriesService(private val metadata: PublicMetadataService = PublicMetadataService(JsonHttpClient(10_000, 45_000))) {
    suspend fun lookup(source: String, id: String): SourceSeriesPage = parseSourceSeries(metadata.series(source, id))
}
