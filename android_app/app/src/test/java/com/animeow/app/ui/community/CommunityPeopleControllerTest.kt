package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunityPeopleControllerTest {
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var stored: CloudSession? = session
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val state = MutableStateFlow(CommunityUiState(session = session, draftText = "保留草稿", busy = true, busyLabel = "其他操作"))
    private fun profile(id: Long) = communityTestProfile().copy(userId = id, isFollowing = false)
    private val group = CommunityGroup(1, "合成群", "", "", true, true, null)
    private fun posts(ids: List<Long>, cursor: Long? = null) = CommunityFeedPage(group, ids.map(::communityTestPost), cursor)
    private var readProfile: suspend (Long) -> CommunityProfile = { profile(it) }
    private var readPosts: suspend (Long, Long?) -> CommunityFeedPage = { _, _ -> posts(listOf(1), 1) }
    private var readFollows: suspend (String, Int) -> CommunityFollowPage = { _, _ -> CommunityFollowPage(listOf(profile(8)), null) }
    private var onFollow: suspend () -> Unit = {}
    private var writes = 0
    private var followReads = 0
    private var readBlocks: suspend () -> List<CommunityProfile> = { listOf(profile(7)) }
    private val api = object : CommunityPeopleApi {
        override suspend fun profile(session: CloudSession, id: Long) = readProfile(id)
        override suspend fun posts(session: CloudSession, id: Long, before: Long?) = readPosts(id, before)
        override suspend fun follow(session: CloudSession, id: Long, following: Boolean): CommunityFollowResult {
            writes++; onFollow(); return CommunityFollowResult(following, 8)
        }
        override suspend fun follows(session: CloudSession, id: Long, direction: String, offset: Int): CommunityFollowPage {
            followReads++; return readFollows(direction, offset)
        }
        override suspend fun blocks(session: CloudSession) = readBlocks()
    }
    private val controller = CommunityPeopleController(scope, state, api, { stored }, { _, fallback -> fallback })
    @After fun close() { scope.cancel() }

    @Test fun changingProfileRejectsThePreviousProfilesLateResponse() {
        val pending = CompletableDeferred<CommunityProfile>()
        readProfile = { if (it == 2L) withContext(NonCancellable) { pending.await() } else profile(it) }
        controller.openProfile(2); controller.openProfile(3); pending.complete(profile(2))
        assertEquals(3L, state.value.selectedProfile!!.userId); assertFalse(state.value.profileLoading)
        assertEquals("保留草稿", state.value.draftText); assertTrue(state.value.busy)
    }

    @Test fun closingProfilePreventsALateResponseFromReopeningIt() {
        val pending = CompletableDeferred<CommunityProfile>(); readProfile = { withContext(NonCancellable) { pending.await() } }
        controller.openProfile(2); controller.closeProfile(); pending.complete(profile(2))
        assertNull(state.value.selectedProfile); assertFalse(state.value.profileLoading); assertTrue(state.value.profilePosts.isEmpty())
    }

    @Test fun failedProfileEndsLoadingAndCanRetryWithoutClearingTheComposer() {
        readProfile = { throw java.io.IOException("synthetic") }; controller.openProfile(2)
        assertFalse(state.value.profileLoading); assertNotNull(state.value.profileError)
        readProfile = { profile(it) }; controller.openProfile(2)
        assertEquals(2L, state.value.selectedProfile!!.userId); assertNull(state.value.profileError)
        assertTrue(state.value.busy); assertEquals("保留草稿", state.value.draftText)
    }

    @Test fun failedPostsKeepTheSuccessfullyLoadedProfileAndOfferRetry() {
        readPosts = { _, _ -> throw java.io.IOException("synthetic") }; controller.openProfile(2)
        assertEquals(2L, state.value.selectedProfile!!.userId); assertTrue(state.value.profileLoadError)
        assertFalse(state.value.profileLoading); assertNotNull(state.value.profileError)
    }

    @Test fun profilePaginationDeduplicatesBothExistingAndRepeatedRows() {
        controller.openProfile(2); readPosts = { _, _ -> posts(listOf(1, 2, 2), null) }
        controller.morePosts(); assertEquals(listOf(1L, 2L), state.value.profilePosts.map { it.id })
        assertNull(state.value.profileNextBeforeId); assertFalse(state.value.profileLoadingMore)
    }

    @Test fun switchingFollowDirectionCancelsItsLoadingRequestAndRejectsOldData() {
        val pending = CompletableDeferred<CommunityFollowPage>()
        readFollows = { direction, _ -> if (direction == "following") withContext(NonCancellable) { pending.await() }
            else CommunityFollowPage(listOf(profile(9)), null) }
        controller.openFollows(2, "following"); assertTrue(state.value.followListLoading)
        controller.direction("followers"); pending.complete(CommunityFollowPage(listOf(profile(8)), 20))
        assertEquals("followers", state.value.followListDirection); assertEquals(9L, state.value.followList.single().userId)
        assertFalse(state.value.followListLoading); assertNull(state.value.followListNextOffset)
    }

    @Test fun closingFollowListIgnoresALateUnsupportedResponse() {
        val pending = CompletableDeferred<CommunityFollowPage>(); readFollows = { _, _ -> withContext(NonCancellable) { pending.await() } }
        controller.openFollows(2, "following"); controller.closeFollows(); pending.completeExceptionally(CommunityFeatureUnavailableException())
        assertNull(state.value.followListUserId); assertTrue(state.value.followListSupported); assertFalse(state.value.followListLoading)
    }

    @Test fun followListMoreStartsOnceAndDeduplicatesEntries() {
        readFollows = { _, _ -> CommunityFollowPage(listOf(profile(8)), 20) }; controller.openFollows(2, "following")
        val pending = CompletableDeferred<CommunityFollowPage>(); readFollows = { _, offset -> assertEquals(20, offset); pending.await() }
        controller.loadFollows(); controller.loadFollows(); assertEquals(2, followReads)
        pending.complete(CommunityFollowPage(listOf(profile(8), profile(9), profile(9)), null))
        assertEquals(listOf(8L, 9L), state.value.followList.map { it.userId }); assertFalse(state.value.followListLoadingMore)
    }

    @Test fun followWritesAreSingleAndDoNotAlterAnotherViewedProfile() {
        controller.openProfile(2); val pending = CompletableDeferred<Unit>(); onFollow = { pending.await() }
        controller.toggleFollow(2); controller.toggleFollow(2); assertEquals(1, writes)
        controller.openProfile(3); pending.complete(Unit)
        assertEquals(3L, state.value.selectedProfile!!.userId); assertFalse(state.value.selectedProfile!!.isFollowing!!)
        assertNull(state.value.followBusyUserId)
    }

    @Test fun changedStoredAccountRejectsReadsAndWritesBeforeUiRefresh() {
        controller.openProfile(2); stored = session.copy(token = "other-synthetic-token")
        controller.toggleFollow(2); controller.openFollows(2, "followers")
        assertEquals(0, writes); assertEquals(0, followReads)
    }

    @Test fun pendingProfileAndFollowsCannotPopulateAReplacementSession() {
        val pending = CompletableDeferred<CommunityProfile>(); readProfile = { pending.await() }
        controller.openProfile(2); stored = session.copy(token = "new-synthetic-token"); state.value = state.value.copy(session = stored)
        controller.cancel(); pending.complete(profile(2))
        assertNull(state.value.selectedProfile); assertTrue(state.value.followList.isEmpty()); assertFalse(state.value.profileLoading)
    }

    @Test fun closingBlocksRejectsLateDataAndDoesNotClearOtherOperations() {
        val pending = CompletableDeferred<List<CommunityProfile>>(); readBlocks = { withContext(NonCancellable) { pending.await() } }
        controller.loadBlocks(); controller.closeBlocks(); pending.complete(listOf(profile(7)))
        assertTrue(state.value.blocks.isEmpty()); assertFalse(state.value.blocksLoading); assertTrue(state.value.busy)
    }

    @Test fun anUnblockInvalidationPreventsAnOlderListFromReintroducingTheUser() {
        val pending = CompletableDeferred<List<CommunityProfile>>(); readBlocks = { withContext(NonCancellable) { pending.await() } }
        controller.loadBlocks(); controller.invalidateBlocks(); state.value = state.value.copy(blocks = emptyList())
        pending.complete(listOf(profile(7))); assertTrue(state.value.blocks.isEmpty()); assertFalse(state.value.blocksLoading)
    }

    @Test fun blockReadFailureHasItsOwnErrorAndCanRetry() {
        readBlocks = { throw java.io.IOException("synthetic") }; controller.loadBlocks()
        assertNotNull(state.value.blocksError); assertFalse(state.value.blocksLoading); assertTrue(state.value.busy)
        readBlocks = { listOf(profile(7), profile(7)) }; controller.loadBlocks()
        assertNull(state.value.blocksError); assertEquals(1, state.value.blocks.size)
    }
}
