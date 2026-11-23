package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunityReactionControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var stored: CloudSession? = session
    private val post = communityTestPost(1)
    private val state = MutableStateFlow(CommunityUiState(session = session, posts = listOf(post), selectedPost = post,
        profilePosts = listOf(post), searchResults = listOf(post), comments = listOf(communityTestComment()), draftText = "保留草稿"))
    private var calls = 0
    private var onLike: suspend (CloudSession) -> Boolean = { true }
    private var onFavorite: suspend () -> Boolean = { true }
    private val changed = mutableListOf<Pair<CloudSession, Long>>()
    private val controller = CommunityReactionController(scope, state, { stored }, { session, _, _ -> calls++; onLike(session) },
        { _, _ -> calls++; onFavorite() }, { _, fallback -> fallback }, { session, post -> changed += session to post.id })
    @After fun close() { scope.cancel() }

    @Test fun confirmedPostReactionUpdatesEveryExistingCopyAndPublishesOnce() {
        controller.likePost(1)
        for (copy in state.value.posts + state.value.profilePosts + state.value.searchResults + listOf(state.value.selectedPost!!)) {
            assertTrue(copy.likedByViewer); assertEquals(1, copy.likeCount)
        }
        assertEquals(1, calls); assertEquals(listOf(session to 1L), changed)
    }

    @Test fun repeatedTapsStartOnceButIndependentReactionKindsCanProceed() {
        val pending = CompletableDeferred<Boolean>(); onLike = { pending.await() }
        controller.likePost(1); controller.likePost(1); controller.favoritePost(1)
        assertEquals(2, calls); assertTrue(state.value.posts.single().isFavorited)
        assertNull(state.value.posts.single().favoriteCount)
        pending.complete(true); assertTrue(state.value.posts.single().likedByViewer); assertEquals(2, changed.size)
    }

    @Test fun aNewAccountCanReactWhileAnOldAccountsResponseIsStillPending() {
        val old = CompletableDeferred<Boolean>(); onLike = { if (it == session) withContext(NonCancellable) { old.await() } else true }
        controller.likePost(1); controller.cancel()
        val replacement = session.copy(userId = 2, token = "replacement-synthetic-token")
        stored = replacement; state.value = state.value.copy(session = replacement)
        controller.likePost(1); old.complete(false)
        assertEquals(2, calls); assertTrue(state.value.posts.single().likedByViewer)
        assertEquals(listOf(replacement to 1L), changed)
    }

    @Test fun lateCommentReactionCannotChangeAnotherOpenPost() {
        val pending = CompletableDeferred<Boolean>(); onLike = { pending.await() }
        controller.likeComment(10)
        state.value = state.value.copy(selectedPost = communityTestPost(2), comments = listOf(communityTestComment(20, 2)))
        pending.complete(true)
        assertEquals(20L, state.value.comments.single().id); assertFalse(state.value.comments.single().likedByViewer)
    }

    @Test fun commentReactionDeduplicatesAndDoesNotCountAnAlreadyAppliedStateTwice() {
        state.value = state.value.copy(comments = listOf(communityTestComment().copy(likedByViewer = true, likeCount = 3)))
        val pending = CompletableDeferred<Boolean>(); onLike = { pending.await() }
        controller.likeComment(10); controller.likeComment(10); pending.complete(true)
        assertEquals(1, calls); assertEquals(3, state.value.comments.single().likeCount)
    }

    @Test fun reactionFailureDoesNotUnlockAnUnrelatedPublicationOrEraseItsDraft() {
        state.value = state.value.copy(busy = true, busyLabel = "正在发布…")
        onLike = { throw java.io.IOException("synthetic") }; controller.likePost(1)
        assertTrue(state.value.busy); assertEquals("正在发布…", state.value.busyLabel)
        assertEquals("保留草稿", state.value.draftText); assertNotNull(state.value.message)
    }

    @Test fun changedStoredSessionsAndUnknownCommentIdsCannotDispatch() {
        controller.likeComment(999); assertEquals(0, calls)
        stored = session.copy(token = "replacement-synthetic-token"); controller.likePost(1); controller.favoritePost(1)
        assertEquals(0, calls); assertTrue(changed.isEmpty())
    }
}
