package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface CommunityAdministrationApi {
    suspend fun users(session: CloudSession, query: String?, role: String?): List<CommunityProfile>
    suspend fun words(session: CloudSession, page: Int, search: String): SensitiveWordPage
    suspend fun logs(session: CloudSession): List<CommunityModerationLogEntry>
    suspend fun reviews(session: CloudSession): List<CommunityResolvedReview>
    suspend fun setAdmin(session: CloudSession, userId: Long, admin: Boolean)
    suspend fun addWord(session: CloudSession, word: String)
    suspend fun deleteWord(session: CloudSession, id: Long)
    suspend fun importWords(session: CloudSession, words: List<String>): Int
    suspend fun overrideReview(session: CloudSession, id: Long, violation: Boolean, note: String)
}

internal class ServiceCommunityAdministrationApi(private val service: CommunityService) : CommunityAdministrationApi {
    override suspend fun users(session: CloudSession, query: String?, role: String?) = service.listUsers(session, query, role)
    override suspend fun words(session: CloudSession, page: Int, search: String) = service.listSensitiveWords(session, page = page, search = search)
    override suspend fun logs(session: CloudSession) = service.listModerationLog(session)
    override suspend fun reviews(session: CloudSession) = service.listResolvedReviews(session)
    override suspend fun setAdmin(session: CloudSession, userId: Long, admin: Boolean) { service.setUserAdmin(session, userId, admin) }
    override suspend fun addWord(session: CloudSession, word: String) { service.addSensitiveWord(session, word) }
    override suspend fun deleteWord(session: CloudSession, id: Long) { service.deleteSensitiveWord(session, id) }
    override suspend fun importWords(session: CloudSession, words: List<String>) = service.importSensitiveWords(session, words)
    override suspend fun overrideReview(session: CloudSession, id: Long, violation: Boolean, note: String) { service.overrideReview(session, id, violation, note) }
}

internal class CommunityAdministrationController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val api: CommunityAdministrationApi,
    private val loadSession: () -> CloudSession?,
    private val failure: (Throwable, String) -> String,
    private val refreshFeed: suspend (CloudSession, Long) -> Unit,
) {
    private var generation = 0L
    private var usersVersion = 0L; private var wordsVersion = 0L
    private var logsVersion = 0L; private var reviewsVersion = 0L
    private var usersJob: Job? = null; private var wordsJob: Job? = null
    private var logsJob: Job? = null; private var reviewsJob: Job? = null
    private var wordsVisible = false; private var usersVisible = false
    private var observedSession = state.value.session

    init {
        scope.launch {
            state.map { Triple(it.session, it.currentUser?.userId, it.currentUser?.isDeveloper) }.distinctUntilChanged().collect { role ->
                val changed = observedSession != role.first
                observedSession = role.first
                if (changed || role.third != true || role.first?.userId != role.second) cancel()
            }
        }
    }

    private fun role(session: CloudSession?) = session != null && state.value.session == session &&
        state.value.currentUser?.let { it.userId == session.userId && it.isDeveloper } == true
    private fun current(session: CloudSession, ticket: Long) = ticket == generation && role(session) && loadSession() == session
    private fun session() = state.value.session?.takeIf { role(it) && loadSession() == it }
    private fun report(session: CloudSession, ticket: Long, error: Throwable, fallback: String): String? {
        if (ticket != generation || !role(session)) return null
        if (loadSession() != session && !(error is CloudSessionExpiredException && error.isFor(session) && loadSession() == null)) return null
        return communityFailureMessage(failure(error, fallback), fallback)
    }

    fun cancel() {
        generation++; usersVersion++; wordsVersion++; logsVersion++; reviewsVersion++
        usersJob?.cancel(); wordsJob?.cancel(); logsJob?.cancel(); reviewsJob?.cancel()
        usersVisible = false; wordsVisible = false
        state.update { it.copy(users = emptyList(), usersLoading = false, usersError = null,
            sensitiveWords = emptyList(), sensitiveWordsTotal = 0, sensitiveWordsLoading = false, sensitiveWordsError = null,
            sensitiveWordsSearch = "", sensitiveWordsPage = 1,
            moderationLog = emptyList(), moderationLogLoading = false, moderationLogError = null,
            resolvedReviews = emptyList(), resolvedReviewsLoading = false, resolvedReviewsError = null,
            administrationBusy = false, administrationBusyLabel = null) }
    }

    fun users(query: String? = null, filter: String? = null) {
        val session = session() ?: return
        usersVisible = true
        usersJob?.cancel(); val request = ++usersVersion; val ticket = generation
        state.update { it.copy(usersLoading = true, usersError = null) }
        usersJob = scope.launch {
            try {
                val result = api.users(session, query, filter)
                if (current(session, ticket) && request == usersVersion) state.update { it.copy(users = result) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (request == usersVersion) report(session, ticket, error, "读取用户列表失败，请重试")?.let { text -> state.update { it.copy(usersError = text) } } }
            finally { if (ticket == generation && request == usersVersion) state.update { it.copy(usersLoading = false) } }
        }
    }

    fun closeUsers() {
        usersVisible = false; usersVersion++; usersJob?.cancel()
        state.update { it.copy(users = emptyList(), usersLoading = false, usersError = null) }
    }

    fun refreshWords() { wordsVisible = true; words(state.value.sensitiveWordsPage) }
    private fun words(page: Int, debounce: Boolean = false) {
        val session = session() ?: return
        val search = state.value.sensitiveWordsSearch
        wordsJob?.cancel(); val request = ++wordsVersion; val ticket = generation
        state.update { it.copy(sensitiveWordsLoading = true, sensitiveWordsError = null) }
        wordsJob = scope.launch {
            try {
                if (debounce) delay(300)
                var result = api.words(session, page.coerceAtLeast(1), search)
                val last = ((result.total.coerceAtLeast(0).toLong() + result.pageSize.coerceAtLeast(1) - 1) / result.pageSize.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                if (result.page > last) result = api.words(session, last, search)
                if (current(session, ticket) && request == wordsVersion) state.update { it.copy(sensitiveWords = result.words,
                    sensitiveWordsTotal = result.total.coerceAtLeast(0), sensitiveWordsPage = result.page.coerceAtLeast(1), sensitiveWordsPageSize = result.pageSize.coerceAtLeast(1)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (request == wordsVersion) report(session, ticket, error, "读取敏感词失败，请重试")?.let { text -> state.update { it.copy(sensitiveWordsError = text) } } }
            finally { if (ticket == generation && request == wordsVersion) state.update { it.copy(sensitiveWordsLoading = false) } }
        }
    }

    fun searchWords(query: String) {
        if (session() == null) return
        if (state.value.busy || state.value.administrationBusy) return
        val clean = query.take(64); if (clean == state.value.sensitiveWordsSearch) return
        state.update { it.copy(sensitiveWordsSearch = clean, sensitiveWordsPage = 1, sensitiveWords = emptyList(), sensitiveWordsTotal = 0) }
        wordsVisible = true; words(1, debounce = true)
    }
    fun wordPage(page: Int) { if (!state.value.busy && !state.value.administrationBusy) words(page) }
    fun cancelWords() { wordsJob?.cancel(); wordsVersion++; state.update { it.copy(sensitiveWordsLoading = false) } }
    fun closeWords() { wordsVisible = false; cancelWords(); state.update { it.copy(sensitiveWords = emptyList(), sensitiveWordsTotal = 0, sensitiveWordsError = null) } }

    fun logs() {
        val session = session() ?: return
        logsJob?.cancel(); val request = ++logsVersion; val ticket = generation
        state.update { it.copy(moderationLogLoading = true, moderationLogError = null) }
        logsJob = scope.launch {
            try { val result = api.logs(session); if (current(session, ticket) && request == logsVersion) state.update { it.copy(moderationLog = result) } }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (request == logsVersion) report(session, ticket, error, "读取审核记录失败，请重试")?.let { text -> state.update { it.copy(moderationLogError = text) } } }
            finally { if (ticket == generation && request == logsVersion) state.update { it.copy(moderationLogLoading = false) } }
        }
    }
    fun closeLogs() { logsJob?.cancel(); logsVersion++; state.update { it.copy(moderationLog = emptyList(), moderationLogLoading = false, moderationLogError = null) } }

    fun reviews() {
        val session = session() ?: return
        reviewsJob?.cancel(); val request = ++reviewsVersion; val ticket = generation
        state.update { it.copy(resolvedReviewsLoading = true, resolvedReviewsError = null) }
        reviewsJob = scope.launch {
            try { val result = api.reviews(session); if (current(session, ticket) && request == reviewsVersion) state.update { it.copy(resolvedReviews = result) } }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (request == reviewsVersion) report(session, ticket, error, "读取复审记录失败，请重试")?.let { text -> state.update { it.copy(resolvedReviewsError = text) } } }
            finally { if (ticket == generation && request == reviewsVersion) state.update { it.copy(resolvedReviewsLoading = false) } }
        }
    }
    fun closeReviews() { reviewsJob?.cancel(); reviewsVersion++; state.update { it.copy(resolvedReviews = emptyList(), resolvedReviewsLoading = false, resolvedReviewsError = null) } }

    private fun write(label: String, work: suspend (CloudSession) -> String, after: suspend (CloudSession) -> Unit = {}) {
        val session = session() ?: return
        if (state.value.busy || state.value.administrationBusy) return
        val ticket = generation
        state.update { it.copy(administrationBusy = true, administrationBusyLabel = label, message = null) }
        scope.launch {
            var committed: String? = null
            try {
                if (!current(session, ticket)) return@launch
                val message = work(session); committed = message
                if (!current(session, ticket)) return@launch
                after(session)
                if (current(session, ticket)) state.update { it.copy(message = message) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (committed != null && current(session, ticket)) state.update { it.copy(message = "$committed；列表刷新未完成，请手动刷新") }
                else report(session, ticket, error, "操作结果未确认，请刷新后核对")?.let { text -> state.update { it.copy(message = text) } }
            } finally { if (ticket == generation) state.update { it.copy(administrationBusy = false, administrationBusyLabel = null) } }
        }
    }

    fun setAdmin(userId: Long, admin: Boolean) = write("正在调整管理员…", { session -> api.setAdmin(session, userId, admin); if (admin) "已任命为管理员" else "已取消管理员" },
        after = {
            usersJob?.cancel(); usersVersion++
            state.update { it.copy(usersLoading = false, users = if (usersVisible) it.users.map { user -> if (user.userId == userId) user.copy(isAdmin = admin) else user } else it.users) }
        })
    fun addWord(word: String) = write("正在添加敏感词…", { api.addWord(it, word); "敏感词已添加" }, { if (wordsVisible) refreshWords() })
    fun deleteWord(id: Long) = write("正在删除敏感词…", { api.deleteWord(it, id); "敏感词已删除" }, { if (wordsVisible) refreshWords() })
    fun importWords(words: List<String>) {
        if (session() == null) return
        val clean = words.map(String::trim).filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) { state.update { it.copy(message = "没有可导入的敏感词") }; return }
        write("正在导入敏感词…", { "已导入 ${api.importWords(it, clean)} 个敏感词" }, { if (wordsVisible) refreshWords() })
    }
    fun overrideReview(id: Long, violation: Boolean, note: String) = write("正在修改处理结果…", { api.overrideReview(it, id, violation, note); "处理结果已修改" }, { session ->
        reviewsJob?.cancel(); reviewsVersion++
        state.update { it.copy(resolvedReviewsLoading = false, resolvedReviews = it.resolvedReviews.filterNot { review -> review.id == id }) }
        state.value.group?.id?.let { refreshFeed(session, it) }
    })
}
