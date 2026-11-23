package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal interface CommunityModerationApi {
    suspend fun reviews(session: CloudSession): List<CommunityReviewCase>
    suspend fun resolve(session: CloudSession, id: Long, violation: Boolean, note: String)
    suspend fun stats(session: CloudSession): CommunityStats
    suspend fun audit(session: CloudSession): String
}
internal class ServiceCommunityModerationApi(private val service: CommunityService) : CommunityModerationApi {
    override suspend fun reviews(session: CloudSession) = service.listReviews(session)
    override suspend fun resolve(session: CloudSession, id: Long, violation: Boolean, note: String) { service.resolveReview(session, id, violation, note) }
    override suspend fun stats(session: CloudSession) = service.communityStats(session)
    override suspend fun audit(session: CloudSession) = service.exportAudit(session)
}

/** Ordinary moderation and developer audit reads never acquire the publication lock. */
internal class CommunityModerationController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val api: CommunityModerationApi,
    private val loadSession: () -> CloudSession?,
    private val failure: (Throwable, String) -> String,
    private val refreshFeed: suspend (CloudSession, Long) -> Unit,
) {
    private var generation = 0L
    private var reviewVersion = 0L; private var statsVersion = 0L; private var auditVersion = 0L
    private var reviewsJob: Job? = null; private var statsJob: Job? = null; private var auditJob: Job? = null
    private var observedSession = state.value.session
    init {
        scope.launch {
            state.map { Triple(it.session, it.currentUser?.userId, it.currentUser?.let { user -> user.isAdmin to user.isDeveloper }) }
                .distinctUntilChanged().collect { role ->
                    val changed = observedSession != role.first; observedSession = role.first
                    if (changed || !authorized(role.first)) this@CommunityModerationController.cancel()
                    else if (role.third?.second != true) clearAudit()
                }
        }
    }
    private fun authorized(session: CloudSession?, developer: Boolean = false): Boolean = session != null && state.value.session == session &&
        state.value.currentUser?.let { it.userId == session.userId && (it.isDeveloper || !developer && it.isAdmin) } == true
    private fun current(session: CloudSession, ticket: Long, developer: Boolean = false) =
        ticket == generation && authorized(session, developer) && loadSession() == session
    private fun session(developer: Boolean = false) = state.value.session?.takeIf { authorized(it, developer) && loadSession() == it }
    private fun report(session: CloudSession, ticket: Long, error: Throwable, fallback: String, developer: Boolean = false): String? {
        if (ticket != generation || !authorized(session, developer)) return null
        if (loadSession() != session && !(error is CloudSessionExpiredException && error.isFor(session) && loadSession() == null)) return null
        return communityFailureMessage(failure(error, fallback), fallback)
    }

    fun cancel() { generation++; closeReviews(); closeStats(); state.update { it.copy(reviewBusy = false) } }
    fun closeReviews() {
        reviewVersion++; reviewsJob?.cancel()
        state.update { it.copy(showingReviews = false, reviews = emptyList(), reviewsLoading = false, reviewsError = null) }
    }
    fun openReviews() { if (session() != null) { state.update { it.copy(showingReviews = true) }; reviews() } }
    fun reviews() {
        val session = session() ?: return
        reviewsJob?.cancel(); val version = ++reviewVersion; val ticket = generation
        state.update { it.copy(reviewsLoading = true, reviewsError = null) }
        reviewsJob = scope.launch {
            try {
                val rows = api.reviews(session)
                if (version == reviewVersion && current(session, ticket)) state.update { it.copy(reviews = rows.distinctBy(CommunityReviewCase::id)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (version == reviewVersion) report(session, ticket, error, "读取待复审内容失败，请重试")?.let { message -> state.update { it.copy(reviewsError = message) } } }
            finally { if (ticket == generation && version == reviewVersion) state.update { it.copy(reviewsLoading = false) } }
        }
    }
    fun resolve(id: Long, violation: Boolean, note: String) {
        val session = session() ?: return
        if (state.value.reviewBusy || state.value.reviews.none { it.id == id }) return
        val ticket = generation
        val message = if (violation) "已移除被举报内容，并恢复其余帖子" else "举报未确认，已恢复全部临时隐藏内容"
        state.update { it.copy(reviewBusy = true, message = null) }
        scope.launch {
            var committed = false
            try {
                if (!current(session, ticket)) return@launch
                api.resolve(session, id, violation, note); committed = true
                if (!current(session, ticket)) return@launch
                reviewVersion++; reviewsJob?.cancel()
                state.update { it.copy(reviewsLoading = false, reviews = it.reviews.filterNot { row -> row.id == id }) }
                state.value.group?.id?.let { refreshFeed(session, it) }
                if (current(session, ticket)) state.update { it.copy(message = message) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (committed && current(session, ticket)) state.update { it.copy(message = "$message；列表刷新未完成，请手动刷新") }
                else report(session, ticket, error, "处理结果未确认，请刷新后核对")?.let { text -> state.update { it.copy(message = text) } }
            } finally { if (ticket == generation && state.value.session == session) state.update { it.copy(reviewBusy = false) } }
        }
    }
    fun stats() {
        val session = session() ?: return
        statsJob?.cancel(); val version = ++statsVersion; val ticket = generation
        state.update { it.copy(statsLoading = true, statsError = null) }
        statsJob = scope.launch {
            try { val result = api.stats(session); if (version == statsVersion && current(session, ticket)) state.update { it.copy(stats = result) } }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (version == statsVersion) report(session, ticket, error, "读取统计看板失败，请重试")?.let { message -> state.update { it.copy(statsError = message) } } }
            finally { if (ticket == generation && version == statsVersion) state.update { it.copy(statsLoading = false) } }
        }
    }
    fun audit() {
        val session = session(developer = true) ?: return
        if (state.value.auditLoading) return
        val version = ++auditVersion; val ticket = generation
        state.update { it.copy(auditLoading = true, auditError = null) }
        auditJob = scope.launch {
            try { val result = api.audit(session); if (version == auditVersion && current(session, ticket, true)) state.update { it.copy(auditExport = result) } }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (version == auditVersion) report(session, ticket, error, "读取审计失败，请重试", true)?.let { message -> state.update { it.copy(auditError = message) } } }
            finally { if (ticket == generation && version == auditVersion) state.update { it.copy(auditLoading = false) } }
        }
    }
    fun clearAudit() {
        auditVersion++; auditJob?.cancel()
        state.update { it.copy(auditExport = null, auditLoading = false, auditError = null) }
    }
    fun closeStats() {
        statsVersion++; statsJob?.cancel(); clearAudit()
        state.update { it.copy(stats = null, statsLoading = false, statsError = null) }
    }
}
