package com.animeow.app.data.remote

import android.content.Context
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.data.preferences.PublicDataRoute
import org.json.JSONObject

internal data class BangumiPerson(val id: Long, val name: String, val job: String, val episodes: String)
internal data class BangumiStaffPage(val people: List<BangumiPerson>, val stale: Boolean, val updatedAt: String?)
internal data class BangumiComment(val id: Long, val author: String, val title: String, val text: String, val rate: Int?, val date: Long?, val url: String)
internal data class BangumiDiscussionPage(val comments: List<BangumiComment>, val total: Int, val page: Int, val hasMore: Boolean, val stale: Boolean, val updatedAt: String?)

internal fun parseBangumiStaff(response: String): BangumiStaffPage {
    val rows = publicArray(response)
    val meta = runCatching { JSONObject(response).optJSONObject("_animeow_cache") }.getOrNull()
    val people = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optLong("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
        val name = row.optString("name").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
        BangumiPerson(id, name.take(200), row.optString("relation").take(200).takeUnless { it == "null" }.orEmpty(), row.optString("eps").take(500).takeUnless { it == "null" }.orEmpty())
    }.distinctBy { Triple(it.id, it.job, it.episodes) }
    return BangumiStaffPage(people, meta?.optBoolean("stale") == true, meta?.optString("updatedAt"))
}

internal fun parseBangumiDiscussion(response: String, subjectId: Long, kind: String, page: Int): BangumiDiscussionPage {
    val root = JSONObject(response)
    val rows = root.getJSONArray("data")
    require(root.has("total") && root.optInt("total", -1) >= 0) { "评论资料不完整，请重试" }
    val reviews = kind == "reviews"
    fun clean(value: String, max: Int) = value.replace(Regex("<[^>]*>"), "").take(max).trim().takeUnless { it == "null" }.orEmpty()
    val comments = (0 until rows.length().coerceAtMost(20)).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optLong("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
        val entry = row.optJSONObject("entry")
        if (reviews && entry != null && !entry.optBoolean("public")) return@mapNotNull null
        val blogId = entry?.optLong("id", 0)
        val rawUrl = row.optString("url")
        val url = if (reviews) {
            if (blogId != null && blogId > 0) "https://bgm.tv/blog/$blogId"
            else rawUrl.takeIf { Regex("https://bgm\\.tv/blog/[1-9]\\d*").matches(it) } ?: return@mapNotNull null
        } else "https://bgm.tv/subject/$subjectId/comments"
        BangumiComment(id, clean(row.optJSONObject("user")?.optString("nickname") ?: row.optString("author"), 100).ifBlank { "Bangumi 用户" },
            clean(entry?.optString("title") ?: row.optString("title"), 500),
            clean(if (reviews) entry?.optString("summary") ?: row.optString("text") else row.optString("comment", row.optString("text")), 10000),
            row.optInt("rate", 0).takeIf { !reviews && it in 1..10 },
            (entry ?: row).optLong("updatedAt", 0).takeIf { it > 0 }, url)
    }.distinctBy(BangumiComment::id)
    val meta = root.optJSONObject("_animeow_cache")
    return BangumiDiscussionPage(comments, root.getInt("total"), page, page.toLong() * 20 < root.getInt("total"), meta?.optBoolean("stale") == true, meta?.optString("updatedAt"))
}

internal class BangumiDiscussionService(context: Context, private val client: JsonHttpClient = JsonHttpClient()) {
    private val preferences = DiscoveryPreferences(context.applicationContext)
    suspend fun page(subjectId: Long, kind: String, page: Int): BangumiDiscussionPage {
        require(subjectId in 1..Int.MAX_VALUE.toLong() && kind in listOf("comments", "reviews") && page in 1..501) { "评论查询条件无效" }
        val response = if (preferences.snapshot().publicDataRoute == PublicDataRoute.SERVER) PublicMetadataService().discussion(subjectId, kind, page)
            else client.get("https://next.bgm.tv/p1/subjects/$subjectId/$kind?limit=20&offset=${(page - 1) * 20}")
        return parseBangumiDiscussion(response, subjectId, kind, page)
    }
}
