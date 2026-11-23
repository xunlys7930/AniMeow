package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.CommunityComment
import com.animeow.app.data.community.CommunityCommentPage
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityService
import com.animeow.app.data.community.relatedPosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface CommunityThreadApi {
    suspend fun post(session: CloudSession, id: Long): CommunityPost
    suspend fun comments(session: CloudSession?, postId: Long, parentId: Long?, sort: String, cursor: Long? = null): CommunityCommentPage
    suspend fun related(session: CloudSession?, postId: Long): List<CommunityPost>
    suspend fun create(session: CloudSession, postId: Long, text: String)
    suspend fun reply(session: CloudSession, commentId: Long, text: String)
    suspend fun delete(session: CloudSession, commentId: Long)
    suspend fun edit(session: CloudSession, commentId: Long, text: String)
}

internal class ServiceCommunityThreadApi(private val service: CommunityService) : CommunityThreadApi {
    override suspend fun post(session: CloudSession, id: Long) = service.getPost(session, id)
    override suspend fun comments(session: CloudSession?, postId: Long, parentId: Long?, sort: String, cursor: Long?) =
        service.listComments(session, postId, parentId, sort,
            beforeId = cursor?.takeIf { sort == "newest" }, afterId = cursor?.takeIf { sort == "oldest" })
    override suspend fun related(session: CloudSession?, postId: Long) = service.relatedPosts(session, postId)
    override suspend fun create(session: CloudSession, postId: Long, text: String) { service.createComment(session, postId, text) }
    override suspend fun reply(session: CloudSession, commentId: Long, text: String) { service.replyComment(session, commentId, text) }
    override suspend fun delete(session: CloudSession, commentId: Long) { service.deleteComment(session, commentId) }
    override suspend fun edit(session: CloudSession, commentId: Long, text: String) { service.editComment(session, commentId, text) }
}

data class CommunityReplyBranch(
    val expanded: Boolean = false,
    val sort: String = "newest",
    val nextCursor: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val retryAppend: Boolean = false,
)

internal class CommunityThreadController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val api: CommunityThreadApi,
    private val loadSession: () -> CloudSession?,
    private val failure: (Throwable, String) -> String,
    private val onPostLoaded: (CloudSession, CommunityPost) -> Unit,
) {
    private var generation = 0L
    private var rootRequest = 0L
    private var replyRequest = 0L
    private var detailJob: Job? = null
    private var commentsJob: Job? = null
    private var relatedJob: Job? = null
    private val replyJobs = mutableMapOf<Long, Job>()
    private val replyVersions = mutableMapOf<Long, Long>()

    fun cancel() {
        generation++; rootRequest++
        detailJob?.cancel(); commentsJob?.cancel(); relatedJob?.cancel()
        replyJobs.values.forEach(Job::cancel); replyJobs.clear(); replyVersions.clear()
        state.update { it.copy(postDetailLoading = false, commentsLoading = false, relatedLoading = false, commentsBusy = false,
            replyBranches = emptyMap()) }
    }

    private fun owner(session: CloudSession?, ticket: Long) = ticket == generation && state.value.session == session
    private fun current(session: CloudSession?, ticket: Long, postId: Long) =
        owner(session, ticket) && loadSession() == session && state.value.selectedPost?.id == postId

    private fun report(session: CloudSession?, ticket: Long, error: Throwable, fallback: String): String? {
        if (!owner(session, ticket)) return null
        if (loadSession() != session && !(error is CloudSessionExpiredException && error.isFor(session) && loadSession() == null)) return null
        return communityFailureMessage(failure(error, fallback), fallback)
    }

    private fun clearThread(post: CommunityPost?) {
        state.update { it.copy(selectedPost = post, comments = emptyList(), commentDraft = "", commentSort = "newest",
            commentsError = null, postDetailError = null, replyTarget = null, replyDraft = "", replies = emptyMap(), relatedPosts = emptyList()) }
    }

    fun open(post: CommunityPost) { cancel(); clearThread(post); loadComments(post.id) }
    fun close() { cancel(); clearThread(null) }

    fun loadDetail(postId: Long) {
        cancel(); clearThread(null)
        val session = state.value.session
        if (session == null) { state.update { it.copy(postDetailError = "请先登录云账号", message = "请先登录云账号") }; return }
        val ticket = generation
        state.update { it.copy(postDetailLoading = true, message = null) }
        detailJob = scope.launch {
            try {
                val post = api.post(session, postId)
                if (!owner(session, ticket) || loadSession() != session) return@launch
                check(post.id == postId) { "帖子资料与请求不一致，请重试" }
                state.update { it.copy(selectedPost = post) }
                onPostLoaded(session, post)
                loadRelated(session, ticket, postId)
                loadComments(postId)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { report(session, ticket, error, "读取帖子详情失败，请重试")?.let { message -> state.update { it.copy(postDetailError = message) } } }
            finally { if (ticket == generation) state.update { it.copy(postDetailLoading = false) } }
        }
    }

    private fun loadRelated(session: CloudSession?, ticket: Long, postId: Long) {
        if (!current(session, ticket, postId)) return
        state.update { it.copy(relatedLoading = true) }
        relatedJob = scope.launch {
            try {
                val items = api.related(session, postId)
                if (current(session, ticket, postId)) state.update { it.copy(relatedPosts = items) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Related posts are optional on older servers. */ }
            finally { if (ticket == generation) state.update { it.copy(relatedLoading = false) } }
        }
    }

    fun setSort(sort: String) {
        val post = state.value.selectedPost ?: return
        state.update { it.copy(commentSort = sort) }; loadComments(post.id, sort)
    }

    fun loadComments(postId: Long, sort: String = "newest") {
        if (state.value.selectedPost?.id != postId) return
        val session = state.value.session; val ticket = generation; val request = ++rootRequest
        commentsJob?.cancel()
        state.update { it.copy(commentsLoading = true, commentsError = null) }
        commentsJob = scope.launch {
            try {
                if (!current(session, ticket, postId) || request != rootRequest) return@launch
                val page = api.comments(session, postId, null, sort)
                if (current(session, ticket, postId) && request == rootRequest) state.update { it.copy(comments = page.items) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (request == rootRequest) report(session, ticket, error, "读取评论失败，请重试")?.let { message -> state.update { it.copy(commentsError = message) } }
            } finally { if (ticket == generation && request == rootRequest) state.update { it.copy(commentsLoading = false) } }
        }
    }

    fun toggleReplies(parentId: Long) {
        val branch = state.value.replyBranches[parentId] ?: CommunityReplyBranch()
        if (branch.expanded) {
            updateBranch(parentId, branch.copy(expanded = false))
        } else {
            updateBranch(parentId, branch.copy(expanded = true))
            if (parentId !in state.value.replies && !branch.loading) loadReplies(parentId, branch.sort)
        }
    }

    private fun updateBranch(parentId: Long, branch: CommunityReplyBranch) {
        state.update { it.copy(replyBranches = it.replyBranches + (parentId to branch)) }
    }

    fun loadReplies(parentId: Long, sort: String, append: Boolean = false) {
        val session = state.value.session
        val postId = state.value.selectedPost?.id ?: return
        if (comment(parentId) == null) return
        val branch = state.value.replyBranches[parentId] ?: CommunityReplyBranch()
        val cursor = if (append) branch.nextCursor ?: return else null
        if (append && branch.loading) return
        val ticket = generation; val request = ++replyRequest
        replyJobs[parentId]?.cancel(); replyVersions[parentId] = request
        updateBranch(parentId, branch.copy(expanded = true, sort = sort, loading = true, error = null, retryAppend = append))
        replyJobs[parentId] = scope.launch {
            try {
                if (!current(session, ticket, postId)) return@launch
                val page = api.comments(session, postId, parentId, sort, cursor)
                if (current(session, ticket, postId) && replyVersions[parentId] == request) state.update {
                    val existing = if (append) it.replies[parentId].orEmpty() else emptyList()
                    val items = (existing + page.items).distinctBy(CommunityComment::id)
                    val next = page.nextBeforeId?.takeIf { it != cursor && items.size > existing.size }
                    it.copy(replies = it.replies + (parentId to items),
                        replyBranches = it.replyBranches + (parentId to it.replyBranches.getValue(parentId).copy(nextCursor = next)))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (replyVersions[parentId] == request) report(session, ticket, error, "读取回复失败，请重试")?.let { message ->
                    updateBranch(parentId, state.value.replyBranches.getValue(parentId).copy(error = message))
                }
            } finally {
                if (replyVersions[parentId] == request) {
                    updateBranch(parentId, state.value.replyBranches.getValue(parentId).copy(loading = false))
                }
            }
        }
    }

    private fun comment(id: Long): CommunityComment? = (state.value.comments.firstOrNull { it.id == id }
        ?: state.value.replies.values.asSequence().flatten().firstOrNull { it.id == id }
        ?: state.value.replyTarget?.takeIf { it.id == id })?.takeIf { it.postId == state.value.selectedPost?.id }

    fun startReply(id: Long) {
        if (state.value.commentsBusy) return
        val target = comment(id) ?: return
        state.update { it.copy(replyTarget = target, replyDraft = if (it.replyTarget?.id == id) it.replyDraft else "") }
    }

    fun updateReplyDraft(value: String) { state.update { it.copy(replyDraft = value.take(1000)) } }
    fun updateDraft(value: String) { state.update { it.copy(commentDraft = value.take(1000)) } }
    fun cancelReply() { if (!state.value.commentsBusy) state.update { it.copy(replyTarget = null, replyDraft = "") } }

    private fun write(fallback: String, action: suspend (CloudSession, Long) -> Unit) {
        val session = state.value.session ?: run { state.update { it.copy(message = "请先登录云账号") }; return }
        val postId = state.value.selectedPost?.id ?: return
        if (state.value.commentsBusy || loadSession() != session) return
        val ticket = generation
        state.update { it.copy(commentsBusy = true) }
        scope.launch {
            try { if (current(session, ticket, postId)) action(session, postId) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { report(session, ticket, error, fallback)?.let { message -> state.update { it.copy(message = message) } } }
            finally { if (ticket == generation) state.update { it.copy(commentsBusy = false) } }
        }
    }

    fun add(text: String) {
        val clean = text.trim(); if (clean.isEmpty()) return
        val ticket = generation
        write("评论失败，请重试") { session, postId ->
            api.create(session, postId, clean)
            if (!current(session, ticket, postId)) return@write
            state.update { it.copy(commentDraft = if (it.commentDraft.trim() == clean) "" else it.commentDraft,
                posts = it.posts.map { post -> if (post.id == postId) post.copy(commentCount = post.commentCount + 1) else post },
                selectedPost = it.selectedPost?.let { post -> post.copy(commentCount = post.commentCount + 1) }) }
            loadComments(postId, state.value.commentSort)
        }
    }

    fun reply(commentId: Long, text: String) {
        val clean = text.trim(); if (clean.isEmpty()) return
        if (comment(commentId) == null) return
        val ticket = generation
        write("回复失败，请重试") { session, postId ->
            api.reply(session, commentId, clean)
            if (!current(session, ticket, postId)) return@write
            fun incrementReplyCount(row: CommunityComment) =
                if (row.id == commentId) row.copy(replyCount = row.replyCount + 1) else row
            state.update { it.copy(posts = it.posts.map { post -> if (post.id == postId) post.copy(commentCount = post.commentCount + 1) else post },
                selectedPost = it.selectedPost?.let { post -> post.copy(commentCount = post.commentCount + 1) },
                comments = it.comments.map(::incrementReplyCount),
                replies = it.replies.mapValues { (_, rows) -> rows.map(::incrementReplyCount) },
                replyTarget = if (it.replyTarget?.id == commentId && it.replyDraft.trim() == clean) null else it.replyTarget,
                replyDraft = if (it.replyTarget?.id == commentId && it.replyDraft.trim() == clean) "" else it.replyDraft) }
            loadReplies(commentId, state.value.replyBranches[commentId]?.sort ?: "newest")
        }
    }

    fun delete(commentId: Long) {
        if (comment(commentId) == null) return
        val ticket = generation
        write("删除评论失败，请重试") { session, postId ->
            api.delete(session, commentId)
            if (!current(session, ticket, postId)) return@write
            state.update { it.copy(comments = it.comments.filterNot { row -> row.id == commentId },
                replies = it.replies.mapValues { (_, rows) -> rows.filterNot { row -> row.id == commentId } },
                posts = it.posts.map { post -> if (post.id == postId) post.copy(commentCount = (post.commentCount - 1).coerceAtLeast(0)) else post },
                selectedPost = it.selectedPost?.let { post -> post.copy(commentCount = (post.commentCount - 1).coerceAtLeast(0)) }) }
        }
    }

    fun edit(commentId: Long, text: String) {
        if (comment(commentId) == null || text.isBlank()) return
        val ticket = generation
        write("编辑评论失败，请重试") { session, postId ->
            api.edit(session, commentId, text)
            if (current(session, ticket, postId)) state.update { it.copy(
                comments = it.comments.map { row -> if (row.id == commentId) row.copy(content = text.trim()) else row },
                replies = it.replies.mapValues { (_, rows) -> rows.map { row -> if (row.id == commentId) row.copy(content = text.trim()) else row } },
                message = "评论已更新") }
        }
    }
}
