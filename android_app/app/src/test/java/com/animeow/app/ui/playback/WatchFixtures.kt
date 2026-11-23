package com.animeow.app.ui.playback

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*

internal val watchTestProgram = WatchProgram(123, 456, "合成动画", "本篇第 1 集", 0, 1.0)
internal fun watchTestItem(id: String = "1", content: String = "合成评论") = WatchItem(id, 123, 456, "comment", content, 1, "合成昵称")
internal class MemoryWatchStorage : WatchStorage {
    val values = mutableMapOf<String, String>()
    var reject: (String) -> Boolean = { false }
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String?) { check(!reject(key)) { "合成存储失败" }; if (value == null) values.remove(key) else values[key] = value }
    override fun writeAll(values: Map<String, String?>) {
        check(values.keys.none(reject)) { "合成存储失败" }
        values.forEach { (key, value) -> write(key, value) }
    }
}
internal class FakeWatchApi : WatchApi {
    var login: CloudSession? = CloudSession(1, "synthetic", "synthetic-token")
    var admin = false
    val submitted = mutableListOf<WatchSubmission>()
    val confirmed = mutableMapOf<String, WatchItem>()
    var onPrograms: suspend (Int) -> List<WatchProgram> = { subject -> listOf(watchTestProgram.copy(subjectId = subject), watchTestProgram.copy(subjectId = subject, episodeId = 0)) }
    var onSearch: suspend (String) -> List<WatchSubject> = { listOf(WatchSubject(123, "合成动画")) }
    var onList: suspend (WatchProgram, String, Long?, Long?, Long) -> WatchPage = { _, _, _, _, _ -> WatchPage(emptyList()) }
    var onCreate: suspend (WatchSubmission) -> WatchItem = { watchTestItem((submitted.size + 1).toString(), it.content) }
    var onItem: suspend (Long) -> WatchItem = { watchTestItem(it.toString()) }
    var externalPrograms: List<ExternalWatchProgram> = emptyList()
    var onReviews: suspend () -> WatchPage = { WatchPage(listOf(watchTestItem())) }
    var onExternal: suspend () -> WatchPage = { WatchPage(emptyList()) }
    override fun session() = login
    override suspend fun capabilities(session: CloudSession?) = WatchCapabilities(true, admin)
    override suspend fun programs(subject: Int, session: CloudSession?) = onPrograms(subject)
    override suspend fun search(query: String, session: CloudSession?) = onSearch(query)
    override suspend fun list(program: WatchProgram, kind: String, session: CloudSession?, before: Long?, parent: Long?, from: Long) = onList(program, kind, before, parent, from)
    override suspend fun item(id: Long, session: CloudSession?) = onItem(id)
    override suspend fun create(input: WatchSubmission, session: CloudSession): WatchItem { submitted += input; return onCreate(input).also { confirmed[input.clientId] = it } }
    override suspend fun request(clientId: String, session: CloudSession) = confirmed[clientId]
    override suspend fun remove(id: Long, session: CloudSession) {}
    override suspend fun report(id: Long, reason: String, session: CloudSession) {}
    override suspend fun block(id: Long, session: CloudSession) {}
    override suspend fun reviews(session: CloudSession, before: Long?) = onReviews()
    override suspend fun moderate(item: WatchItem, remove: Boolean, reason: String, session: CloudSession) {}
    override suspend fun shared(program: WatchProgram, session: CloudSession?) = listOf(1L)
    override suspend fun linked(subject: Int, session: CloudSession?) = externalPrograms
    override suspend fun externalSearch(query: String, session: CloudSession?) = emptyList<ExternalWatchWork>()
    override suspend fun externalEpisodes(work: Long, session: CloudSession?) = emptyList<ExternalWatchProgram>()
    override suspend fun externalComments(episode: Long, from: Long, session: CloudSession?) = onExternal()
}
