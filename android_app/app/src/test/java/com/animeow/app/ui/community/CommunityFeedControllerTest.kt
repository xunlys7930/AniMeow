package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunityFeedControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val group = CommunityGroup(1, "合成社区", "", "", true, true, null)
    private val user = CommunityProfile(1, "synthetic", "合成", "", "public", false, false, false, null, false, emptyMap(), null)
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var storedSession: CloudSession? = session
    private val state = MutableStateFlow(CommunityUiState(session = session, draftText = "保留草稿", commentDraft = "保留评论"))
    private val calls = mutableListOf<CommunityFeedRequest>()
    private val errors = mutableListOf<String>()
    private var fetch: suspend (CommunityFeedRequest) -> CommunityFeedPage = { page(listOf(1)) }
    private var fetchBootstrap: suspend (CloudSession?) -> CommunityBootstrap = { CommunityBootstrap(null, group, CommunityLimits()) }
    private val controller = CommunityFeedController(scope, state, { storedSession }, { fetchBootstrap(it) },
        posts = { request -> calls += request; fetch(request) }, tags = { emptyList() },
        failure = { error, fallback ->
            errors += fallback
            if (error is CloudSessionExpiredException && error.isFor(state.value.session)) state.value = state.value.copy(session = null)
            fallback
        })
    @After fun close() { scope.cancel() }

    private fun post(id: Long) = CommunityPost(id, 1, "合成$id", emptyList(), user, "approved", false, false, null,
        false, null, 0, 0, false, false, true, false, "2026-11-05", "2026-11-05")
    private fun page(ids: List<Long>, offset: Int? = null, sort: String? = null, tag: String? = null) =
        CommunityFeedPage(group, ids.map(::post), null, offset, sort, tag)

    @Test fun changedFilterSupersedesALoadingRequestAndIgnoresItsLateResult() = runBlocking {
        val old = CompletableDeferred<CommunityFeedPage>()
        fetch = { request -> if (request.tag == null) withContext(NonCancellable) { old.await() } else page(listOf(2), tag = request.tag) }
        controller.loadInitial()
        assertTrue(state.value.loading)
        state.value = state.value.copy(feedTag = "合成标签")
        controller.loadInitial(force = true)
        assertEquals(listOf(2L), state.value.posts.map { it.id })
        old.complete(page(listOf(1)))
        assertEquals(listOf(2L), state.value.posts.map { it.id })
        assertEquals("保留草稿", state.value.draftText); assertEquals("保留评论", state.value.commentDraft)
    }

    @Test fun accountSwitchPreventsAnOldSuccessOrErrorFromTouchingTheNewAccount() = runBlocking {
        val old = CompletableDeferred<CommunityFeedPage>()
        fetch = { withContext(NonCancellable) { old.await() } }
        controller.loadInitial()
        val replacement = CloudSession(2, "other", "other-synthetic-token")
        storedSession = replacement
        state.value = state.value.copy(session = replacement, posts = listOf(post(9)))
        controller.cancel()
        old.completeExceptionally(CloudSessionExpiredException(rejectedToken = session.token))
        assertEquals(replacement, state.value.session); assertEquals(listOf(9L), state.value.posts.map { it.id })
        assertTrue(errors.isEmpty()); assertFalse(state.value.loading)
    }

    @Test fun aChangedStoredLoginIsProtectedBeforeTheScreenRefreshesItsSession() = runBlocking {
        val pending = CompletableDeferred<CommunityFeedPage>()
        fetch = { pending.await() }; controller.loadInitial()
        storedSession = session.copy(token = "replacement-synthetic-token")
        pending.complete(page(listOf(8)))
        assertTrue(state.value.posts.isEmpty()); assertTrue(errors.isEmpty()); assertFalse(state.value.loading)
    }

    @Test fun paginationStartsOnceUsesItsCursorAndDeduplicatesRepeatedItems() = runBlocking {
        controller.loadInitial()
        state.value = state.value.copy(nextOffset = 20)
        val pending = CompletableDeferred<CommunityFeedPage>()
        fetch = { pending.await() }
        controller.loadMore(); controller.loadMore()
        assertEquals(2, calls.size); assertEquals(20, calls.last().offset); assertNull(calls.last().beforeId)
        pending.complete(page(listOf(1, 2, 2), offset = 40))
        assertEquals(listOf(1L, 2L), state.value.posts.map { it.id }); assertEquals(40, state.value.nextOffset)
        assertFalse(state.value.loadingMore)
    }

    @Test fun aPaginationFailureDoesNotClearAnUnrelatedBusyOperationOrDraft() = runBlocking {
        controller.loadInitial(); state.value = state.value.copy(nextBeforeId = 1)
        val pending = CompletableDeferred<CommunityFeedPage>(); fetch = { pending.await() }
        controller.loadMore()
        state.value = state.value.copy(busy = true, busyLabel = "发布中")
        pending.completeExceptionally(java.io.IOException("synthetic failure"))
        assertTrue(state.value.busy); assertEquals("发布中", state.value.busyLabel)
        assertEquals("保留草稿", state.value.draftText); assertFalse(state.value.loadingMore)
        assertEquals("加载更多失败", state.value.message)
    }

    @Test fun legacyServersCannotShowAnUnfilteredFeedAsTheFollowingFeed() {
        state.value = state.value.copy(feedSort = "following")
        fetch = { page(listOf(3), offset = 20) }
        controller.loadInitial()
        assertTrue(state.value.feedFilterUnsupported); assertTrue(state.value.posts.isEmpty())
        assertFalse(state.value.hasMoreFeed)
    }

    @Test fun explicitCurrentSessionExpiryStillUsesTheExistingAccountFailureHandler() {
        fetchBootstrap = { storedSession = null; throw CloudSessionExpiredException(rejectedToken = session.token) }
        controller.loadInitial()
        assertNull(state.value.session); assertEquals(listOf("社区暂时无法加载"), errors)
        assertEquals("保留草稿", state.value.draftText); assertFalse(state.value.loading)
    }

    @Test fun guestsCanReadThePublicFeedButNeverRequestFollowingPosts() {
        storedSession = null; state.value = state.value.copy(session = null, feedSort = "following")
        controller.loadInitial()
        assertTrue(calls.isEmpty()); assertTrue(state.value.posts.isEmpty()); assertFalse(state.value.loading)
    }

    @Test fun pollingRefreshesOnlyTheFirstPageWithoutAssumingIdsAreRankedByAge() {
        state.value = state.value.copy(feedSort = "likes")
        fetch = { if (it.offset == null) page(listOf(1, 50), offset = 20) else page(listOf(70, 2), offset = 40) }
        controller.loadInitial(); controller.loadMore()
        fetch = { page(listOf(8, 50), offset = 20) }
        controller.refreshLatestSilently()
        assertEquals(listOf(8L, 50L, 70L, 2L), state.value.posts.map { it.id })
        assertEquals(40, state.value.nextOffset)
    }

    @Test fun aLatePollCannotReplaceANewFilterOrAnUpdatedPostList() = runBlocking {
        controller.loadInitial()
        val pending = CompletableDeferred<CommunityFeedPage>()
        fetch = { withContext(NonCancellable) { pending.await() } }
        controller.refreshLatestSilently()
        state.value = state.value.copy(posts = listOf(post(5)))
        pending.complete(page(listOf(9)))
        assertEquals(listOf(5L), state.value.posts.map { it.id })
    }

    @Test fun pollingDoesNotResurrectAnExhaustedPaginationCursor() {
        fetch = { if (it.offset == null) page(listOf(1), offset = 20) else page(listOf(2)) }
        controller.loadInitial(); controller.loadMore(); assertFalse(state.value.hasMoreFeed)
        fetch = { page(listOf(3), offset = 20) }; controller.refreshLatestSilently()
        assertEquals(listOf(3L, 2L), state.value.posts.map { it.id }); assertFalse(state.value.hasMoreFeed)
    }

    @Test fun anOldAccountsWriteRefreshDoesNotCancelTheCurrentAccountsFeed() = runBlocking {
        val pending = CompletableDeferred<CommunityFeedPage>(); fetch = { pending.await() }
        controller.loadInitial()
        controller.reloadAfterWrite(session.copy(token = "old-synthetic-token"), group.id)
        assertTrue(state.value.loading)
        pending.complete(page(listOf(9)))
        assertEquals(listOf(9L), state.value.posts.map { it.id })
    }

    @Test fun aWriteRefreshCannotEraseANewerPublicationOrReaction() = runBlocking {
        controller.loadInitial()
        val pending = CompletableDeferred<CommunityFeedPage>(); fetch = { pending.await() }
        val refreshing = async(Dispatchers.Unconfined) { controller.reloadAfterWrite(session, group.id) }
        state.value = state.value.copy(posts = listOf(post(99)))
        pending.complete(page(listOf(1))); refreshing.await()
        assertEquals(listOf(99L), state.value.posts.map { it.id })
    }
}
