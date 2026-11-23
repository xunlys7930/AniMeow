package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunityModerationControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var stored: CloudSession? = session
    private val profile = communityTestProfile().copy(isAdmin = true, isDeveloper = true)
    private val review = CommunityReviewCase(1, profile, 1, 1, 1, "2026-11-06", emptyList(), listOf(communityTestPost()))
    private val state = MutableStateFlow(CommunityUiState(session = session, busy = true, busyLabel = "正在发布…", draftText = "保留草稿",
        bootstrap = CommunityBootstrap(profile, CommunityGroup(1, "合成群", "", "", true, true, null), CommunityLimits()), reviews = listOf(review)))
    private var readReviews: suspend () -> List<CommunityReviewCase> = { listOf(review) }
    private var readStats: suspend () -> CommunityStats = { CommunityStats(postCount = 5) }
    private var readAudit: suspend () -> String = { "synthetic audit" }
    private var onResolve: suspend () -> Unit = {}
    private var refresh: suspend () -> Unit = {}
    private var writes = 0; private var audits = 0
    private val api = object : CommunityModerationApi {
        override suspend fun reviews(session: CloudSession) = readReviews()
        override suspend fun stats(session: CloudSession) = readStats()
        override suspend fun audit(session: CloudSession): String { audits++; return readAudit() }
        override suspend fun resolve(session: CloudSession, id: Long, violation: Boolean, note: String) { writes++; onResolve() }
    }
    private val controller = CommunityModerationController(scope, state, api, { stored }, { _, fallback -> fallback }, { _, _ -> refresh() })
    @After fun close() { scope.cancel() }

    @Test fun readFailuresHaveScopedRetryStateAndNeverUnlockThePublication() {
        readReviews = { throw java.io.IOException("synthetic") }; controller.reviews()
        assertNotNull(state.value.reviewsError); assertFalse(state.value.reviewsLoading); assertTrue(state.value.busy)
        readReviews = { listOf(review) }; controller.reviews(); assertNull(state.value.reviewsError)
        readStats = { throw java.io.IOException("synthetic") }; controller.stats()
        assertNotNull(state.value.statsError); assertTrue(state.value.busy); assertEquals("正在发布…", state.value.busyLabel)
    }

    @Test fun closingReviewsIgnoresALateRead() {
        val pending = CompletableDeferred<List<CommunityReviewCase>>(); readReviews = { withContext(NonCancellable) { pending.await() } }
        controller.reviews(); controller.closeReviews(); pending.complete(listOf(review))
        assertTrue(state.value.reviews.isEmpty()); assertFalse(state.value.reviewsLoading)
    }

    @Test fun losingTheRoleClearsCachesAndRejectsLateWrites() {
        val pending = CompletableDeferred<Unit>(); onResolve = { pending.await() }
        controller.stats(); controller.audit(); controller.resolve(1, false, "")
        state.value = state.value.copy(bootstrap = state.value.bootstrap!!.copy(currentUser = communityTestProfile()))
        pending.complete(Unit)
        assertTrue(state.value.reviews.isEmpty()); assertNull(state.value.stats); assertNull(state.value.auditExport)
        assertFalse(state.value.reviewBusy); assertTrue(state.value.busy); assertEquals("保留草稿", state.value.draftText)
    }

    @Test fun auditRequiresDeveloperAndRoleLossKeepsAdminStatisticsButClearsAudit() {
        state.value = state.value.copy(bootstrap = state.value.bootstrap!!.copy(currentUser = profile.copy(isDeveloper = false)))
        controller.audit(); assertEquals(0, audits)
        state.value = state.value.copy(bootstrap = state.value.bootstrap!!.copy(currentUser = profile))
        controller.stats()
        val pending = CompletableDeferred<String>(); readAudit = { withContext(NonCancellable) { pending.await() } }; controller.audit()
        state.value = state.value.copy(bootstrap = state.value.bootstrap!!.copy(currentUser = profile.copy(isDeveloper = false)))
        pending.complete("private synthetic audit")
        assertNull(state.value.auditExport); assertFalse(state.value.auditLoading); assertEquals(5, state.value.stats!!.postCount)
    }

    @Test fun confirmedResolutionInvalidatesAnOlderListRead() {
        val pending = CompletableDeferred<List<CommunityReviewCase>>(); readReviews = { withContext(NonCancellable) { pending.await() } }
        controller.reviews(); controller.resolve(1, false, ""); pending.complete(listOf(review))
        assertTrue(state.value.reviews.isEmpty()); assertFalse(state.value.reviewsLoading); assertEquals(1, writes)
    }

    @Test fun duplicateResolutionIsBlockedAndRefreshFailureDoesNotInviteRepeatingTheWrite() {
        val pending = CompletableDeferred<Unit>(); onResolve = { pending.await() }; refresh = { throw java.io.IOException("synthetic refresh") }
        controller.resolve(1, true, ""); controller.resolve(1, true, ""); assertEquals(1, writes)
        pending.complete(Unit)
        assertTrue(state.value.reviews.isEmpty()); assertFalse(state.value.reviewBusy)
        assertTrue(state.value.message!!.contains("列表刷新未完成")); assertTrue(state.value.busy)
    }

    @Test fun aChangedStoredSessionRejectsPendingDataBeforeUiRefresh() {
        val pending = CompletableDeferred<CommunityStats>(); readStats = { pending.await() }; controller.stats()
        stored = session.copy(token = "replacement-synthetic-token"); pending.complete(CommunityStats(postCount = 99))
        assertNull(state.value.stats); assertFalse(state.value.statsLoading)
    }

    @Test fun closingStatisticsRejectsPendingStatsAndAudit() {
        val stats = CompletableDeferred<CommunityStats>(); val audit = CompletableDeferred<String>()
        readStats = { withContext(NonCancellable) { stats.await() } }; readAudit = { withContext(NonCancellable) { audit.await() } }
        controller.stats(); controller.audit(); controller.closeStats()
        stats.complete(CommunityStats(postCount = 99)); audit.complete("private synthetic audit")
        assertNull(state.value.stats); assertNull(state.value.auditExport); assertFalse(state.value.statsLoading); assertFalse(state.value.auditLoading)
    }
}
