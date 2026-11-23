package com.animeow.app.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.data.bangumi.EpisodeNumberMetadata
import com.animeow.app.data.bangumi.EpisodeNumbering
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.safeCloudMessage
import com.animeow.app.data.community.*
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.sourceReferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

internal data class WatchUiState(val ready: Boolean = false, val session: CloudSession? = null, val admin: Boolean = false,
    val externalAvailable: Boolean = false, val program: WatchProgram? = null, val candidates: List<WatchProgram> = emptyList(),
    val comments: List<WatchItem> = emptyList(), val before: Long? = null, val replies: List<WatchItem> = emptyList(), val replyBefore: Long? = null,
    val parent: WatchItem? = null, val focused: WatchItem? = null, val danmaku: List<WatchItem> = emptyList(),
    val overall: Boolean = false, val draft: String = "", val busy: Boolean = false, val loading: Boolean = false,
    val error: String? = null, val message: String? = null, val externalError: String? = null, val danmakuError: String? = null,
    val settings: DanmakuSettings = DanmakuSettings(), val externalEpisode: Long? = null,
    val externalPrograms: List<ExternalWatchProgram> = emptyList(), val externalWorks: List<ExternalWatchWork> = emptyList(),
    val reviews: List<WatchItem> = emptyList(), val reviewBefore: Long? = null, val reviewLoading: Boolean = false, val reviewError: String? = null,
    val sharedPosts: List<Long> = emptyList(), val uncertain: Boolean = false, val subjects: List<WatchSubject> = emptyList(),
    val matchTitle: String = "", val alternateTitles: List<String> = emptyList(), val matchLoading: Boolean = false,
    val matchError: String? = null, val noSubjects: Boolean = false, val selectedMatchSubject: Int? = null,
    val suggestedMatch: WatchProgram? = null)

internal fun watchEpisodeNumbering(programs: List<WatchProgram>): EpisodeNumbering? {
    val regular = programs.filter { it.episodeId > 0 && it.type == 0 }
    return EpisodeNumbering.fromEpisodes(regular.map { EpisodeNumberMetadata(it.episodeId.toLong(), it.type, it.number ?: Double.NaN) },
        regular.size)?.takeIf { it.usesContinuousNumbering }
}

internal fun automaticWatchEpisode(programs: List<WatchProgram>, number: Int?, label: String, total: Int = 0): WatchProgram? {
    if (number == null || number <= 0 || !Regex("(?i)^\\s*(?:(?:第\\s*)?\\d{1,4}\\s*(?:集|話|话)?|EP(?:ISODE)?\\s*\\d{1,4})\\s*$").matches(label)) return null
    val numbering = watchEpisodeNumbering(programs)
    val local = numbering?.numberForWatchedCount(number)
    val matches = programs.filter {
        it.episodeId > 0 && it.type == 0 &&
            (it.number == number.toDouble() || (local != null && it.number == local.toDouble()))
    }
    val match = matches.singleOrNull() ?: return null
    return match.takeIf { it.number == number.toDouble() || numbering?.totalEpisodes == total }
}

private val watchSubjectReference = Regex("""(?:#|https?://(?:www\.)?(?:bgm\.tv|bangumi\.tv|chii\.in)/subject/)(\d+)(?:[/?#].*)?""", RegexOption.IGNORE_CASE)

/** Reads are pinned to both the playback binding and full login; write retries first look up their persisted request ID. */
internal class WatchViewModel(private val api: WatchApi, private val storage: WatchStorage) : ViewModel() {
    private val mutable = MutableStateFlow(WatchUiState())
    val state = mutable.asStateFlow()
    private var binding = ""
    private var epoch = 0L
    private var active = false
    private var mutating = false
    private var autoSubject: Int? = null
    private var autoNumber: Int? = null
    private var autoLabel = ""
    private var autoTotal = 0
    private var workMappingKey: String? = null
    private var matchTitles = emptyList<String>()
    private val jobs = mutableMapOf<String, Job>()
    private val reads = mutableMapOf<String, Long>()
    private fun stopRead(name: String) { reads[name] = (reads[name] ?: 0) + 1; jobs.remove(name)?.cancel() }
    private var position = 0L
    private var lastWindow = -1L
    private var lastRefresh = 0L
    private var discussionOnly = false
    private fun save(key: String, value: String?): Boolean = try { storage.write(key, value); true }
        catch (error: Exception) { mutable.update { it.copy(message = failure(error)) }; false }
    private fun draftKey(session: CloudSession? = mutable.value.session) = "draft:${session?.userId ?: 0}:$binding"
    private fun pendingKey(session: CloudSession? = mutable.value.session) = "pending:${session?.userId ?: 0}:$binding"
    private fun current(ticket: Long, session: CloudSession?) = active && epoch == ticket && api.session() == session
    private fun syncChangedAccount(ticket: Long, session: CloudSession?): Boolean {
        if (epoch != ticket || mutable.value.session != session || api.session() == session) return false
        invalidate(); resetState()
        if (active) activate()
        return true
    }
    private fun invalidate() { epoch++; jobs.values.forEach(Job::cancel); jobs.clear(); lastWindow = -1 }
    private fun failure(error: Throwable) = safeCloudMessage(error.message) ?: "互动暂时无法完成，请检查网络后重试"
    private fun read(name: String, error: (String) -> Unit = { message -> mutable.update { it.copy(error = message) } }, work: suspend (CloudSession?, () -> Unit) -> Unit) {
        stopRead(name)
        val serial = reads.getValue(name)
        val ticket = epoch; val session = mutable.value.session
        fun guard() { if (!current(ticket, session) || reads[name] != serial) { syncChangedAccount(ticket, session); throw CancellationException("stale watch read") } }
        jobs[name] = viewModelScope.launch {
            try { work(session, ::guard) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (!syncChangedAccount(ticket, session) && current(ticket, session) && reads[name] == serial) {
                if (e is CommunityManagementForbiddenException) mutable.update { it.copy(admin = false, reviews = emptyList(), reviewBefore = null) }
                error(failure(e))
            } }
        }
    }
    private fun checkCurrent(ticket: Long, session: CloudSession?) { if (!current(ticket, session)) { syncChangedAccount(ticket, session); throw CancellationException("watch context changed") } }

    fun bind(anime: AnimeEntity?, session: PlaybackSession?) {
        if (anime == null || session == null) return
        val workIdentity = "${anime.id}:${anime.createdAt}:${session.source}:${session.work.id}"
        val key = watchKey("$workIdentity:${session.episode.id}")
        if (binding == key) return
        invalidate(); binding = key; discussionOnly = false
        autoSubject = anime.sourceReferences().filter { it.source.equals("bangumi", true) }.mapNotNull { it.externalId?.toIntOrNull()?.takeIf { n -> n > 0 } }.distinct().singleOrNull()
        autoNumber = session.episode.number; autoLabel = session.episode.label
        autoTotal = anime.totalEpisodes
        workMappingKey = "work-mapping:${watchKey(workIdentity)}"
        matchTitles = listOfNotNull(anime.title, anime.originalTitle, session.work.title).map(String::trim).filter(String::isNotEmpty).distinct()
        save("link:$binding", JSONObject().put("anime_id", anime.id).put("created_at", anime.createdAt.orEmpty()).put("source", session.source)
            .put("work", session.work.id).put("episode", session.episode.id).put("updated_at", System.currentTimeMillis()).toString())
        resetState()
        if (active) activate()
    }
    fun bindDiscussion(subject: Int, episode: Int, focus: Long? = null) {
        val key = "discussion:$subject:$episode"
        if (binding == key) return
        invalidate(); binding = key; discussionOnly = true; autoSubject = subject; autoNumber = null; focusRequest = focus
        workMappingKey = null; matchTitles = emptyList()
        resetState()
        if (active) activate(episode, focus)
        else { initialEpisode = episode; initialFocus = focus }
    }
    fun bindLibrary(anime: AnimeEntity) {
        val key = "library:${anime.id}:${anime.createdAt}"
        if (binding == key) return
        invalidate(); binding = key; discussionOnly = true; autoNumber = null
        workMappingKey = null
        matchTitles = listOfNotNull(anime.title, anime.originalTitle).map(String::trim).filter(String::isNotEmpty).distinct()
        autoSubject = anime.sourceReferences().filter { it.source.equals("bangumi", true) }.mapNotNull { it.externalId?.toIntOrNull()?.takeIf { n -> n > 0 } }.distinct().singleOrNull()
        resetState(); if (active) activate(0)
    }
    private var initialEpisode: Int? = null
    private var initialFocus: Long? = null
    private var focusRequest: Long? = null
    private fun resetState() {
        val session = api.session()
        mutable.value = WatchUiState(session = session, settings = danmakuSettings(storage.read("settings:$binding")),
            draft = storage.read(draftKey(session)).orEmpty(), uncertain = storage.read(pendingKey(session)) != null,
            matchTitle = matchTitles.firstOrNull().orEmpty(), alternateTitles = matchTitles.drop(1))
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            save(draftKey(), mutable.value.draft)
            invalidate(); mutable.update { it.copy(reviews = emptyList(), reviewError = null, reviewLoading = false, loading = false,
                matchLoading = false, busy = false) }
        } else activate(initialEpisode, initialFocus)
    }
    private fun activate(episode: Int? = null, focus: Long? = null) {
        val login = api.session()
        if (login != mutable.value.session) { invalidate(); resetState() }
        read("capabilities", { error -> mutable.update {
            if (it.ready) it.copy(matchError = error, matchLoading = false) else it.copy(error = error)
        } }) { session, guard ->
            val caps = api.capabilities(session); guard()
            mutable.update { it.copy(ready = true, admin = session != null && caps.admin, externalAvailable = caps.external, error = null) }
            mutable.value.selectedMatchSubject?.let { subject ->
                searchSubject(subject)
                return@read
            }
            val savedMapping = storage.read("mapping:$binding")
            val workMapping = workMappingKey?.let(storage::read)
            if (savedMapping == "cleared" || workMapping == "cleared") {
                mutable.update { it.copy(message = "关联已清除，请重新确认作品与集数") }
                return@read
            }
            val rememberedWork = workMapping?.split(':', limit = 2)
            val rememberedSubject = rememberedWork?.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
            val saved = watchProgramOrNull(savedMapping)?.takeIf { rememberedSubject == null || it.subjectId == rememberedSubject }
            val subject = saved?.subjectId ?: rememberedSubject ?: autoSubject ?: return@read
            mutable.update { it.copy(matchLoading = true, matchError = null) }
            val programs = api.programs(subject, session); guard()
            val chosen = if (discussionOnly) programs.firstOrNull { it.episodeId == (episode ?: saved?.episodeId ?: 0) }
                else if (saved != null) programs.firstOrNull { it.episodeId == saved.episodeId }
                else if (rememberedSubject == null || rememberedWork?.getOrNull(1) == "1") automaticWatchEpisode(programs, autoNumber, autoLabel, autoTotal)
                else null
            mutable.update { it.copy(candidates = programs, matchLoading = false) }
            chosen?.let { choose(it, restore = true) }
            if (chosen == null) mutable.update { it.copy(message = "请确认作品与集数，特别篇和歧义不会自动对应") }
            (focus ?: focusRequest)?.let(::loadFocus)
            initialEpisode = null; initialFocus = null
        }
    }
    fun refresh() { if (active) activate() }
    fun reloadDanmaku() = sample(position, true)
    fun search(query: String) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        watchSubjectReference.matchEntire(keyword)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            ?.let { searchSubject(it); return }
        stopRead("capabilities")
        mutable.update { it.copy(matchLoading = true, matchError = null, noSubjects = false, subjects = emptyList(),
            candidates = emptyList(), selectedMatchSubject = null, suggestedMatch = null) }
        read("matching", { error -> mutable.update { it.copy(matchError = error, matchLoading = false) } }) { session, guard ->
            val rows = api.search(keyword, session); guard()
            mutable.update { it.copy(subjects = rows.distinctBy(WatchSubject::id), noSubjects = rows.isEmpty(), matchLoading = false) }
        }
    }
    fun searchSubject(subject: Int) {
        stopRead("capabilities")
        mutable.update { it.copy(matchLoading = true, matchError = null, noSubjects = false, candidates = emptyList(),
            selectedMatchSubject = subject, suggestedMatch = null) }
        read("matching", { error -> mutable.update { it.copy(matchError = error, matchLoading = false) } }) { session, guard ->
            val rows = api.programs(subject, session); guard()
            val suggestion = automaticWatchEpisode(rows, autoNumber, autoLabel, autoTotal)
            mutable.update { it.copy(candidates = rows, matchLoading = false, suggestedMatch = suggestion,
                matchError = "暂未找到该作品的集数目录，请核对作品信息后重试".takeIf { rows.isEmpty() }) }
        }
    }
    fun returnToWatchSubjects() {
        stopRead("matching")
        mutable.update { it.copy(candidates = emptyList(), matchLoading = false, matchError = null,
            selectedMatchSubject = null, suggestedMatch = null) }
    }
    fun choose(program: WatchProgram, restore: Boolean = false): Boolean {
        if (program !in mutable.value.candidates || mutating || mutable.value.busy) return false
        val previous = mutable.value
        val keepContext = restore && previous.program?.subjectId == program.subjectId && previous.program.episodeId == program.episodeId
        val changes = mutableMapOf<String, String?>("mapping:$binding" to program.json().toString(), "program-link:$binding" to program.json().toString())
        if (!restore) workMappingKey?.let { key ->
            val previousMatch = storage.read(key)
            val agrees = automaticWatchEpisode(previous.candidates, autoNumber, autoLabel, autoTotal) == program
            val autoMatch = agrees || (program.episodeId == 0 && previousMatch == "${program.subjectId}:1")
            changes[key] = "${program.subjectId}:${if (autoMatch) 1 else 0}"
        }
        try { storage.writeAll(changes) }
        catch (error: Exception) { mutable.update { it.copy(message = failure(error)) }; return false }
        invalidate()
        if (!restore) mutable.update { it.copy(message = null) }
        val external = runCatching {
            val saved = JSONObject(storage.read("external:$binding").orEmpty())
            saved.optLong("id").takeIf { it > 0 && saved.optInt("subject") == program.subjectId && saved.optInt("episode") == program.episodeId }
        }.getOrNull()
        mutable.update { it.copy(program = program, comments = if (keepContext) it.comments else emptyList(), before = if (keepContext) it.before else null,
            parent = if (keepContext) it.parent else null, replies = if (keepContext) it.replies else emptyList(), focused = if (keepContext) it.focused else null,
            danmaku = emptyList(), sharedPosts = if (keepContext) it.sharedPosts else emptyList(), externalEpisode = external, overall = if (keepContext) it.overall else program.episodeId == 0,
            error = null, loading = false, matchError = null, matchLoading = false,
            selectedMatchSubject = null, suggestedMatch = null) }
        loadComments()
        mutable.value.parent?.let { replies(it) }
        mutable.value.focused?.id?.toLongOrNull()?.let(::loadFocus)
        if (mutable.value.externalAvailable && external == null && program.episodeId > 0) {
            val ticket = epoch
            read("external-match", { error -> mutable.update { it.copy(externalError = error) } }) { session, guard ->
                val rows = api.linked(program.subjectId, session); guard()
                // Upstream cross-site links are candidates, never silently replace a user's confirmed match.
                mutable.update { it.copy(externalPrograms = rows) }
            }
        }
        sample(position, force = true)
        return true
    }

    fun clearMatching(): Boolean {
        if (mutating || mutable.value.busy) return false
        val changes = mutableMapOf<String, String?>("mapping:$binding" to "cleared", "program-link:$binding" to null, "external:$binding" to null)
        workMappingKey?.let { changes[it] = "cleared" }
        try { storage.writeAll(changes) }
        catch (error: Exception) { mutable.update { it.copy(message = failure(error)) }; return false }
        invalidate()
        mutable.update { it.copy(program = null, candidates = emptyList(), subjects = emptyList(), selectedMatchSubject = null,
            suggestedMatch = null, matchLoading = false, matchError = null, noSubjects = false, comments = emptyList(), before = null,
            replies = emptyList(), replyBefore = null, parent = null, focused = null, danmaku = emptyList(), externalEpisode = null,
            externalPrograms = emptyList(), externalWorks = emptyList(), sharedPosts = emptyList(), loading = false,
            error = null, danmakuError = null, externalError = null, message = "关联已清除，请重新确认作品与集数") }
        return true
    }
    private fun topic() = mutable.value.program?.let { if (mutable.value.overall) it.copy(episodeId = 0, episodeTitle = "作品总讨论") else it }
    fun selectOverall(value: Boolean) {
        if (mutable.value.overall == value) return
        stopRead("shared"); stopRead("focus"); stopRead("replies")
        mutable.update { it.copy(overall = value, parent = null, replies = emptyList(), focused = null, sharedPosts = emptyList()) }; loadComments()
    }
    fun loadComments(append: Boolean = false) {
        val program = topic() ?: return
        val ticket = epoch; val before = if (append) mutable.value.before ?: return else null
        mutable.update { it.copy(loading = true, error = null) }
        read("comments", { error -> mutable.update { it.copy(error = error, loading = false) } }) { session, guard ->
            val page = api.list(program, "comment", session, before); guard()
            if (program != topic()) return@read
            mutable.update { it.copy(comments = ((if (append) it.comments else emptyList()) + page.items).distinctBy(WatchItem::id), before = page.before, loading = false) }
        }
    }
    fun replies(parent: WatchItem, append: Boolean = false) {
        val program = topic() ?: return; val ticket = epoch
        val before = if (append) mutable.value.replyBefore ?: return else null
        mutable.update { it.copy(parent = parent) }
        read("replies") { session, guard ->
            val currentParent = api.item(parent.id.toLong(), session); guard()
            val page = api.list(program, "comment", session, before, parent.id.toLong()); guard()
            if (mutable.value.parent?.id != parent.id || program != topic()) return@read
            mutable.update { it.copy(parent = currentParent, replies = ((if (append) it.replies else emptyList()) + page.items).distinctBy(WatchItem::id), replyBefore = page.before) }
        }
    }
    fun closeReplies() { stopRead("replies"); mutable.update { it.copy(parent = null, replies = emptyList(), replyBefore = null) } }
    fun loadFocus(id: Long) {
        val ticket = epoch
        read("focus") { session, guard ->
            val item = api.item(id, session); guard()
            if (item.subjectId == topic()?.subjectId && item.episodeId == topic()?.episodeId) mutable.update { it.copy(focused = item) }
        }
    }
    fun draft(value: String) {
        try { storage.draft(draftKey(), value); mutable.update { it.copy(draft = value) } }
        catch (error: Exception) { mutable.update { it.copy(message = failure(error)) } }
    }
    fun settings(value: DanmakuSettings) {
        if (!save("settings:$binding", value.json())) return
        mutable.update { it.copy(settings = value) }; lastWindow = -1
        if (!value.enabled) { stopRead("danmaku"); stopRead("external-danmaku"); mutable.update { it.copy(danmaku = emptyList()) } }
        else sample(position, force = true)
    }
    fun chooseExternal(value: ExternalWatchProgram) {
        if (value !in mutable.value.externalPrograms) return
        val program = mutable.value.program ?: return
        if (!save("external:$binding", JSONObject().put("id", value.episodeId).put("subject", program.subjectId).put("episode", program.episodeId).toString())) return
        mutable.update { it.copy(externalEpisode = value.episodeId, externalError = null, danmaku = it.danmaku.filterNot(WatchItem::external)) }; sample(position, true)
    }
    fun searchExternal(query: String) {
        val ticket = epoch
        read("external-search", { error -> mutable.update { it.copy(externalError = error) } }) { session, guard ->
            val rows = api.externalSearch(query, session); guard(); mutable.update { it.copy(externalWorks = rows, externalPrograms = emptyList(), externalError = null) }
        }
    }
    fun externalEpisodes(work: ExternalWatchWork) {
        val ticket = epoch
        read("external-match", { error -> mutable.update { it.copy(externalError = error) } }) { session, guard ->
            val rows = api.externalEpisodes(work.id, session); guard(); mutable.update { it.copy(externalPrograms = rows, externalError = null) }
        }
    }
    fun sample(value: Long, force: Boolean = false) {
        position = value.coerceAtLeast(0)
        val state = mutable.value; val program = state.program ?: return
        if (!active || !state.ready || !state.settings.enabled || program.episodeId == 0) return
        val window = ((position - state.settings.offsetMs).coerceAtLeast(0) / 60000) * 60000
        val now = System.nanoTime() / 1000000
        if (!force && window == lastWindow && now - lastRefresh < 20000) return
        lastWindow = window; lastRefresh = now
        val ticket = epoch
        read("danmaku", { error -> mutable.update { it.copy(danmakuError = error) } }) { session, guard ->
            val rows = mutableListOf<WatchItem>()
            for (from in listOf((window - 60000).coerceAtLeast(0), window).distinct()) {
                var cursor: Long? = null
                do {
                    val page = api.list(program, "danmaku", session, cursor, from = from); guard()
                    rows += page.items
                    val next = page.before
                    check(next == null || next != cursor) { "弹幕分页未前进，请重新读取" }
                    cursor = next
                } while (cursor != null && rows.size < 2000)
            }
            guard()
            mutable.update { it.copy(danmaku = (it.danmaku.filter(WatchItem::external) + rows).distinctBy(WatchItem::id).filterNot(::hidden),
                danmakuError = if (rows.size >= 2000) "本段弹幕较多，仅显示已读取部分" else null) }
        }
        val external = state.externalEpisode ?: return
        read("external-danmaku", { error -> mutable.update { it.copy(externalError = error) } }) { session, guard ->
            val pages = listOf((window - 60000).coerceAtLeast(0), window).distinct().map { api.externalComments(external, it, session) }
            val rows = pages.flatMap { it.items }
            guard()
            mutable.update { it.copy(danmaku = (it.danmaku.filterNot(WatchItem::external) + rows).distinctBy(WatchItem::id).filterNot(::hidden), externalError = if (pages.any { page -> page.truncated }) "外部弹幕较多，仅显示已读取部分" else null) }
        }
    }
    private fun hidden(item: WatchItem) = storage.read("hide:${mutable.value.session?.userId ?: 0}:${item.id}") == "1"
    fun hide(item: WatchItem) { if (save("hide:${mutable.value.session?.userId ?: 0}:${item.id}", "1")) mutable.update { it.copy(danmaku = it.danmaku.filterNot { row -> row.id == item.id }) } }
    private fun mutate(run: suspend (CloudSession) -> Unit) {
        val session = mutable.value.session ?: return mutable.update { it.copy(message = "请先登录 AniMeow 账号") }
        if (mutating) return mutable.update { it.copy(message = "上次操作仍在确认，请稍候") }
        if (session != api.session()) return mutable.update { it.copy(message = "账号已变化，请重新进入讨论") }
        val ticket = epoch; mutating = true; mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { run(session) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (!syncChangedAccount(ticket, session) && current(ticket, session)) mutable.update { it.copy(message = failure(e),
                admin = if (e is CommunityManagementForbiddenException) false else it.admin,
                reviews = if (e is CommunityManagementForbiddenException) emptyList() else it.reviews) } }
            finally { mutating = false; if (current(ticket, session)) mutable.update { it.copy(busy = false) } }
        }
    }
    fun send(danmaku: Boolean, withTime: Boolean, spoiler: Boolean, explicitTime: Long? = null) {
        val program = (if (danmaku) mutable.value.program else topic()) ?: return
        val content = mutable.value.draft.trim(); if (content.isBlank()) return
        if (explicitTime != null) position = explicitTime + mutable.value.settings.offsetMs
        if (danmaku && program.episodeId == 0) return mutable.update { it.copy(message = "请先匹配具体集数") }
        val parent = if (danmaku) null else mutable.value.parent?.id?.toLongOrNull()
        val key = pendingKey(); val draftKey = draftKey(); val ticket = epoch
        mutate { session ->
            val pending = storage.read(key)?.let(::JSONObject)
            if (pending != null) {
                val prior = api.request(pending.getString("client"), session)
                checkCurrent(ticket, session)
                if (prior != null) {
                    storage.write(key, null)
                    if (pending.optString("content") == mutable.value.draft.trim()) { storage.write(draftKey, null); mutable.update { it.copy(draft = "") } }
                    mutable.update { it.copy(uncertain = false, message = "上次内容已保存，未重复发送") }; loadComments(); sample(position, true); return@mutate
                }
            }
            val chosenProgram = pending?.optJSONObject("program")?.watchProgram() ?: program
            val kind = pending?.optString("kind") ?: if (danmaku) "danmaku" else "comment"
            val client = pending?.getString("client") ?: UUID.randomUUID().toString()
            val sentContent = pending?.getString("content") ?: content
            val time = if (pending != null) pending.optLong("time", -1).takeIf { it >= 0 }
                else if ((danmaku || withTime) && program.episodeId > 0) (explicitTime ?: (position - mutable.value.settings.offsetMs)).coerceIn(0, 86400000) else null
            val sentParent = if (pending != null) pending.optLong("parent").takeIf { it > 0 } else parent
            val submission = WatchSubmission(chosenProgram, kind, sentContent, client, time, sentParent, pending?.optBoolean("spoiler") ?: spoiler)
            storage.write(key, JSONObject().put("client", client).put("content", sentContent).put("program", chosenProgram.json())
                .put("kind", kind).put("time", time).put("parent", sentParent).put("spoiler", submission.spoiler).toString())
            mutable.update { it.copy(uncertain = true) }
            val result = api.create(submission, session); checkCurrent(ticket, session)
            storage.write(key, null)
            if (mutable.value.draft.trim() == sentContent) { storage.write(draftKey, null); mutable.update { it.copy(draft = "") } }
            mutable.update { it.copy(uncertain = false, message = if (result.status == "pending") "已保存，等待管理员审核" else "发送成功") }
            if (parent != null) mutable.value.parent?.let { replies(it) } else loadComments()
            sample(position, true)
        }
    }
    fun remove(item: WatchItem) = mutate { session ->
        val ticket = epoch; api.remove(item.id.toLong(), session); checkCurrent(ticket, session)
        stopRead("focus")
        mutable.update { it.copy(danmaku = it.danmaku.filterNot { row -> row.id == item.id }, focused = it.focused?.takeUnless { row -> row.id == item.id }, message = "已删除") }
        loadComments(); mutable.value.parent?.let { replies(it) }; sample(position, true)
    }
    fun report(item: WatchItem, reason: String) = mutate { session ->
        val ticket = epoch; api.report(item.id.toLong(), reason, session); checkCurrent(ticket, session)
        mutable.update { it.copy(message = "举报已提交，等待管理员处理") }
    }
    fun block(item: WatchItem) = mutate { session ->
        val ticket = epoch; api.block(requireNotNull(item.authorId), session); checkCurrent(ticket, session)
        stopRead("focus"); stopRead("replies")
        mutable.update { it.copy(comments = it.comments.filterNot { row -> row.authorId == item.authorId }, replies = it.replies.filterNot { row -> row.authorId == item.authorId },
            danmaku = it.danmaku.filterNot { row -> row.authorId == item.authorId }, parent = it.parent?.takeUnless { row -> row.authorId == item.authorId },
            focused = null, sharedPosts = emptyList(), message = "已屏蔽此作者") }; loadComments(); sample(position, true)
    }
    fun loadReviews(append: Boolean = false) {
        val session = mutable.value.session ?: return
        if (!mutable.value.admin) return
        val ticket = epoch; val before = if (append) mutable.value.reviewBefore ?: return else null
        mutable.update { it.copy(reviewLoading = true, reviewError = null) }
        read("reviews", { error -> mutable.update { it.copy(reviewLoading = false, reviewError = error, reviews = emptyList()) } }) { _, guard ->
            val page = api.reviews(session, before); guard()
            mutable.update { it.copy(reviews = ((if (append) it.reviews else emptyList()) + page.items).distinctBy(WatchItem::id), reviewBefore = page.before, reviewLoading = false) }
        }
    }
    fun moderate(item: WatchItem, remove: Boolean, reason: String) = mutate { session ->
        val ticket = epoch; api.moderate(item, remove, reason, session); checkCurrent(ticket, session)
        stopRead("focus")
        mutable.update { it.copy(reviews = it.reviews.filterNot { row -> row.id == item.id }, focused = it.focused?.takeUnless { row -> remove && row.id == item.id }, message = "处理已保存") }; loadReviews(); loadComments(); sample(position, true)
    }
    fun shared() {
        val program = topic() ?: return; val ticket = epoch
        read("shared") { session, guard -> val rows = api.shared(program, session); guard(); if (program == topic()) mutable.update { it.copy(sharedPosts = rows) } }
    }
}
