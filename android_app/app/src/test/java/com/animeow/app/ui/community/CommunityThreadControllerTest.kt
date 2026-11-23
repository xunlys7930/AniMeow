package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.CommunityCommentPage
import com.animeow.app.data.community.CommunityPost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunityThreadControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var storedSession: CloudSession? = session
    private val state = MutableStateFlow(CommunityUiState(session = session, loading = true, draftText = "保留发帖草稿",
        posts = listOf(communityTestPost(1), communityTestPost(2))))
    private val api = FakeCommunityThreadApi()
    private val published = mutableListOf<Long>()
    private val controller = CommunityThreadController(scope, state, api, { storedSession }, { error, _ -> error.message.orEmpty() },
        onPostLoaded = { _, post -> published += post.id })
    @After fun close() { scope.cancel() }

    @Test fun anOldPostCannotReopenAfterAnotherPostHasLoaded() = runBlocking {
        val old = CompletableDeferred<CommunityPost>()
        api.readPost = { if (it == 1L) withContext(NonCancellable) { old.await() } else communityTestPost(it) }
        controller.loadDetail(1); assertTrue(state.value.postDetailLoading)
        controller.loadDetail(2)
        old.complete(communityTestPost(1))
        assertEquals(2L, state.value.selectedPost!!.id); assertEquals(listOf(2L), published)
        assertEquals(2L, state.value.comments.single().postId); assertTrue(state.value.loading)
        assertFalse(state.value.postDetailLoading)
    }

    @Test fun closingCommentsPreventsLateCommentsFromReturningToState() = runBlocking {
        val old = CompletableDeferred<CommunityCommentPage>()
        api.readComments = { _, _, _, _ -> withContext(NonCancellable) { old.await() } }
        controller.open(communityTestPost())
        controller.close(); old.complete(CommunityCommentPage(listOf(communityTestComment()), null))
        assertNull(state.value.selectedPost); assertTrue(state.value.comments.isEmpty()); assertFalse(state.value.commentsLoading)
        assertEquals("保留发帖草稿", state.value.draftText); assertTrue(state.value.loading)
    }

    @Test fun changedSortIgnoresAnOlderResponseAndItsLoadingCleanup() = runBlocking {
        val old = CompletableDeferred<CommunityCommentPage>()
        api.readComments = { _, _, sort, _ -> if (sort == "newest") withContext(NonCancellable) { old.await() }
            else CommunityCommentPage(listOf(communityTestComment(30)), null) }
        controller.open(communityTestPost()); controller.setSort("oldest")
        old.complete(CommunityCommentPage(listOf(communityTestComment(10)), null))
        assertEquals("oldest", state.value.commentSort); assertEquals(30L, state.value.comments.single().id)
        assertFalse(state.value.commentsLoading)
    }

    @Test fun repeatedSendStartsOnceAndKeepsANewerCommentDraft() = runBlocking {
        controller.open(communityTestPost())
        state.value = state.value.copy(commentDraft = "发送的内容")
        val pending = CompletableDeferred<Unit>(); api.onCreate = { pending.await() }
        controller.add("发送的内容"); controller.add("发送的内容")
        assertEquals(1, api.writes.size); assertTrue(state.value.commentsBusy)
        state.value = state.value.copy(commentDraft = "期间的新内容")
        pending.complete(Unit)
        assertEquals("期间的新内容", state.value.commentDraft); assertEquals(1, state.value.selectedPost!!.commentCount)
        assertFalse(state.value.commentsBusy)
    }

    @Test fun failedReplyKeepsRecipientAndTextUntilAConfirmedRetry() {
        controller.open(communityTestPost()); controller.startReply(10); controller.updateReplyDraft("保留回复")
        api.onReply = { throw java.io.IOException("synthetic failure /private/token") }
        controller.reply(10, "保留回复")
        assertEquals(10L, state.value.replyTarget!!.id); assertEquals("保留回复", state.value.replyDraft)
        assertFalse(state.value.commentsBusy)
        api.onReply = {}; controller.reply(10, "保留回复")
        assertNull(state.value.replyTarget); assertEquals("", state.value.replyDraft)
        assertEquals(1, state.value.selectedPost!!.commentCount)
    }

    @Test fun aCompletedWriteForAnOldPostCannotClearANewPostsBusyStateOrDraft() = runBlocking {
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        api.onCreate = { if (api.writes.size == 1) first.await() else second.await() }
        controller.open(communityTestPost(1)); controller.add("旧帖内容")
        controller.open(communityTestPost(2)); state.value = state.value.copy(commentDraft = "新帖内容")
        controller.add("新帖内容")
        first.complete(Unit)
        assertEquals(2L, state.value.selectedPost!!.id); assertTrue(state.value.commentsBusy)
        assertEquals("新帖内容", state.value.commentDraft)
        second.complete(Unit)
        assertFalse(state.value.commentsBusy); assertEquals(1, state.value.selectedPost!!.commentCount)
    }

    @Test fun changedStoredAccountPreventsWritesEvenBeforeUiSessionRefresh() {
        controller.open(communityTestPost())
        storedSession = CloudSession(2, "other", "other-synthetic-token")
        controller.add("内容"); controller.delete(10); controller.edit(10, "编辑"); controller.reply(10, "回复")
        assertTrue(api.writes.isEmpty()); assertFalse(state.value.commentsBusy)
    }

    @Test fun commentFailuresHaveARetryStateWithoutClearingOtherOperations() {
        api.readComments = { _, _, _, _ -> throw IllegalStateException("读取失败 /private/token-secret") }
        state.value = state.value.copy(busy = true, busyLabel = "其他操作")
        controller.open(communityTestPost())
        assertEquals("读取评论失败，请重试", state.value.commentsError)
        assertTrue(state.value.busy); assertEquals("其他操作", state.value.busyLabel)
        assertEquals("保留发帖草稿", state.value.draftText)
        api.readComments = { _, _, _, _ -> CommunityCommentPage(listOf(communityTestComment()), null) }
        controller.loadComments(1)
        assertNull(state.value.commentsError); assertEquals(1, state.value.comments.size)
    }

    @Test fun actionsForCommentsOutsideTheCurrentThreadDoNotReachTheApi() {
        controller.open(communityTestPost())
        controller.delete(99); controller.edit(99, "内容"); controller.reply(99, "内容"); controller.loadReplies(99, "newest")
        assertTrue(api.writes.isEmpty()); assertEquals(0, state.value.selectedPost!!.commentCount)
    }

    @Test fun replyingToAChildOpensItsBranchAndKeepsTheActualReplyTarget() {
        controller.open(communityTestPost())
        controller.loadReplies(10, "oldest")
        val reads = mutableListOf<Pair<Long?, String>>()
        api.readComments = { post, parent, sort, _ -> reads += parent to sort
            CommunityCommentPage(listOf(communityTestComment(210, post, parent)), null) }
        controller.reply(110, "子回复")
        assertEquals(110L to "newest", reads.single())
        assertEquals(0, state.value.comments.single().replyCount)
        assertEquals(1, state.value.replies.getValue(10).single().replyCount)
        fun rows() = state.value.run { visibleThreadRows(comments, replies, replyBranches) }
        assertEquals(listOf(10L, 110L, 210L), rows().map { it.comment.id })
        assertEquals(110L, rows().last().parent?.id)
        controller.toggleReplies(10)
        assertEquals(listOf(10L), rows().map { it.comment.id })
    }

    @Test fun anOlderReplyPageCanRetryAndAppendWithoutReplacingItsSiblings() {
        controller.open(communityTestPost())
        var fail = true
        api.readComments = { post, parent, sort, cursor ->
            assertEquals("oldest", sort)
            if (cursor == null) CommunityCommentPage(listOf(communityTestComment(110, post, parent)), 110)
            else {
                assertEquals(110L, cursor)
                if (fail) throw java.io.IOException()
                CommunityCommentPage(listOf(communityTestComment(210, post, parent)), null)
            }
        }
        controller.loadReplies(10, "oldest")
        controller.loadReplies(10, "oldest", append = true)
        assertTrue(state.value.replyBranches.getValue(10).retryAppend)
        assertEquals(listOf(110L), state.value.replies.getValue(10).map { it.id })
        fail = false
        controller.loadReplies(10, "oldest", append = true)
        assertEquals(listOf(110L, 210L), state.value.replies.getValue(10).map { it.id })
        assertNull(state.value.replyBranches.getValue(10).nextCursor)
    }
}
