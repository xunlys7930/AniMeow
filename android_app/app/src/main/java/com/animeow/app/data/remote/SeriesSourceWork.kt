package com.animeow.app.data.remote

import org.json.JSONObject

internal data class SeriesSourceWork(val id: String, val title: String, val type: Int, val relation: String, val cacheStale: Boolean = false) {
    val subjectType: String? get() = when (type) { 1 -> "book"; 2 -> "anime"; else -> null }
    val typeLabel: String get() = when (type) { 1 -> "书籍"; 2 -> "动画"; 3 -> "音乐"; 4 -> "游戏"; 6 -> "真人影视"; else -> "其他类型" }
    val url: String get() = "https://bgm.tv/subject/$id"
}

internal fun parseSeriesSourceWorks(response: String): List<SeriesSourceWork> {
    val rows = publicArray(response)
    val stale = runCatching { JSONObject(response).optJSONObject("_animeow_cache")?.optBoolean("stale") == true }.getOrDefault(false)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.opt("id")?.toString()?.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE } ?: return@mapNotNull null
        val title = row.optString("name_cn").takeIf { it.isNotBlank() && it != "null" }
            ?: row.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        SeriesSourceWork(id.toString(), title.take(300), row.optInt("type"),
            row.optString("relation").takeIf { it != "null" }.orEmpty().take(80), stale)
    }.distinctBy { it.id }
}
