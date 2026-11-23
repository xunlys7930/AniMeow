package com.animeow.app.data.community

import android.content.Context
import com.animeow.app.data.cloud.CloudSession
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder

data class WatchProgram(val subjectId: Int, val episodeId: Int, val title: String, val episodeTitle: String,
    val type: Int = 0, val number: Double? = null)
data class WatchItem(val id: String, val subjectId: Int = 0, val episodeId: Int = 0, val kind: String = "danmaku",
    val content: String = "", val authorId: Long? = null, val nickname: String = "", val parentId: Long? = null,
    val timeMs: Long? = null, val color: Int = 0xffffff, val mode: Int = 1, val spoiler: Boolean = false,
    val status: String = "visible", val version: Int = 1, val canDelete: Boolean = false, val canReport: Boolean = false,
    val unavailable: Boolean = false, val title: String = "", val episodeTitle: String = "", val external: Boolean = false,
    val reports: List<String> = emptyList())
data class WatchPage(val items: List<WatchItem>, val before: Long? = null, val truncated: Boolean = false)
data class WatchSubject(val id: Int, val title: String)
data class WatchCapabilities(val external: Boolean, val admin: Boolean = false)
data class ExternalWatchProgram(val episodeId: Long, val title: String, val episodeTitle: String, val number: String)
data class ExternalWatchWork(val id: Long, val title: String)
data class WatchSubmission(val program: WatchProgram, val kind: String, val content: String, val clientId: String,
    val timeMs: Long? = null, val parentId: Long? = null, val spoiler: Boolean = false)

interface WatchApi {
    fun session(): CloudSession?
    suspend fun capabilities(session: CloudSession?): WatchCapabilities
    suspend fun programs(subject: Int, session: CloudSession?): List<WatchProgram>
    suspend fun search(query: String, session: CloudSession?): List<WatchSubject>
    suspend fun list(program: WatchProgram, kind: String, session: CloudSession?, before: Long? = null, parent: Long? = null, from: Long = 0): WatchPage
    suspend fun item(id: Long, session: CloudSession?): WatchItem
    suspend fun create(input: WatchSubmission, session: CloudSession): WatchItem
    suspend fun request(clientId: String, session: CloudSession): WatchItem?
    suspend fun remove(id: Long, session: CloudSession)
    suspend fun report(id: Long, reason: String, session: CloudSession)
    suspend fun block(id: Long, session: CloudSession)
    suspend fun reviews(session: CloudSession, before: Long? = null): WatchPage
    suspend fun moderate(item: WatchItem, remove: Boolean, reason: String, session: CloudSession)
    suspend fun shared(program: WatchProgram, session: CloudSession?): List<Long>
    suspend fun linked(subject: Int, session: CloudSession?): List<ExternalWatchProgram>
    suspend fun externalSearch(query: String, session: CloudSession?): List<ExternalWatchWork>
    suspend fun externalEpisodes(work: Long, session: CloudSession?): List<ExternalWatchProgram>
    suspend fun externalComments(episode: Long, from: Long, session: CloudSession?): WatchPage
}

internal class WatchService(context: Context, private val community: CommunityService = CommunityService(context)) : WatchApi {
    override fun session() = community.loadSession()
    private suspend fun call(path: String, session: CloudSession?, method: String = "GET", body: JSONObject? = null): JSONObject =
        community.requestJson(method, "/api/community/watch/$path", session, body)
    override suspend fun capabilities(session: CloudSession?): WatchCapabilities {
        val value = call("capabilities", session).getJSONObject("data")
        check(value.optInt("interaction") == 1 && value.optInt("share") == 1) { "服务器尚未开通观看互动" }
        return WatchCapabilities(value.optBoolean("external"), value.optBoolean("is_admin"))
    }
    override suspend fun programs(subject: Int, session: CloudSession?) = call("programs/$subject", session).getJSONArray("data").objects().map { it.watchProgram() }
    override suspend fun search(query: String, session: CloudSession?) = call("search?q=${URLEncoder.encode(query, "UTF-8")}", session).getJSONArray("data").objects().map { WatchSubject(it.getInt("subject_id"), it.getString("title")) }
    override suspend fun list(program: WatchProgram, kind: String, session: CloudSession?, before: Long?, parent: Long?, from: Long): WatchPage {
        val data = call("items?subject_id=${program.subjectId}&episode_id=${program.episodeId}&kind=$kind&from_ms=$from" +
            (before?.let { "&before_id=$it" } ?: "") + (parent?.let { "&parent_id=$it" } ?: ""), session).getJSONObject("data")
        return data.watchPage()
    }
    override suspend fun item(id: Long, session: CloudSession?) = call("items/$id", session).getJSONObject("data").watchItem()
    override suspend fun create(input: WatchSubmission, session: CloudSession): WatchItem {
        val body = JSONObject().put("subject_id", input.program.subjectId).put("episode_id", input.program.episodeId)
            .put("kind", input.kind).put("content", input.content).put("client_id", input.clientId).put("spoiler", input.spoiler)
        input.parentId?.let { body.put("parent_id", it) }; input.timeMs?.let { body.put("time_ms", it) }
        return call("items", session, "POST", body).getJSONObject("data").watchItem()
    }
    override suspend fun request(clientId: String, session: CloudSession): WatchItem? = call("requests/$clientId", session).optJSONObject("data")?.watchItem()
    override suspend fun remove(id: Long, session: CloudSession) { call("items/$id", session, "DELETE") }
    override suspend fun report(id: Long, reason: String, session: CloudSession) { call("items/$id/report", session, "POST", JSONObject().put("reason", reason)) }
    override suspend fun block(id: Long, session: CloudSession) = community.blockUser(session, id)
    override suspend fun reviews(session: CloudSession, before: Long?) = call("reviews" + (before?.let { "?before_id=$it" } ?: ""), session).getJSONObject("data").watchPage()
    override suspend fun moderate(item: WatchItem, remove: Boolean, reason: String, session: CloudSession) {
        call("items/${item.id}/moderate", session, "POST", JSONObject().put("action", if (remove) "remove" else "keep").put("version", item.version).put("reason", reason))
    }
    override suspend fun shared(program: WatchProgram, session: CloudSession?) = call("shared?subject_id=${program.subjectId}&episode_id=${program.episodeId}", session).getJSONArray("data").let { array -> (0 until array.length()).map { array.getLong(it) } }
    private fun JSONObject.externalProgram() = ExternalWatchProgram(getLong("episode_id"), optString("title"), optString("episode_title"), optString("number"))
    override suspend fun linked(subject: Int, session: CloudSession?) = call("external/linked/$subject", session).getJSONArray("data").objects().map { it.externalProgram() }
    override suspend fun externalSearch(query: String, session: CloudSession?) = call("external/search?q=${URLEncoder.encode(query, "UTF-8")}", session).getJSONArray("data").objects().map { ExternalWatchWork(it.getLong("id"), it.getString("title")) }
    override suspend fun externalEpisodes(work: Long, session: CloudSession?) = call("external/episodes/$work", session).getJSONArray("data").objects().map { it.externalProgram() }
    override suspend fun externalComments(episode: Long, from: Long, session: CloudSession?): WatchPage {
        val data = call("external/comments/$episode?from_ms=$from", session).getJSONObject("data")
        return WatchPage(data.getJSONArray("items").objects().map { it.watchItem(external = true) }, truncated = data.optBoolean("truncated"))
    }
}

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.watchProgram() = WatchProgram(getInt("subject_id"), getInt("episode_id"), getString("title"), getString("episode_title"), optInt("episode_type"), if (isNull("episode_number")) null else optDouble("episode_number").takeIf(Double::isFinite))
internal fun JSONObject.watchPage() = WatchPage(getJSONArray("items").objects().map { it.watchItem() }, optLong("next_before_id").takeIf { it > 0 })
internal fun JSONObject.watchItem(external: Boolean = false): WatchItem {
    val author = optJSONObject("author")
    return WatchItem(optString("id"), optInt("subject_id"), optInt("episode_id"), optString("kind", if (external) "danmaku" else "comment"),
        optString("content"), author?.optLong("id")?.takeIf { it > 0 }, author?.optString("nickname").orEmpty(), optLong("parent_id").takeIf { it > 0 },
        if (isNull("time_ms")) null else optLong("time_ms"), optInt("color", 0xffffff), optInt("mode", 1), optBoolean("spoiler"),
        optString("status", "visible"), optInt("version", 1), optBoolean("can_delete"), optBoolean("can_report"), optBoolean("unavailable"),
        optString("title"), optString("episode_title"), external, optJSONArray("reports")?.objects()?.map { it.optString("reason") }.orEmpty())
}
