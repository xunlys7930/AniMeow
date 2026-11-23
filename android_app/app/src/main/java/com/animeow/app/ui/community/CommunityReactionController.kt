package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.CommunityPost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class CommunityReactionController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val loadSession: () -> CloudSession?,
    private val like: suspend (CloudSession, String, Long) -> Boolean,
    private val favorite: suspend (CloudSession, Long) -> Boolean,
    private val failure: (Throwable, String) -> String,
    private val changed: (CloudSession, CommunityPost) -> Unit,
) {
    private data class Key(val session: CloudSession, val kind: String, val id: Long)
    private val pending = mutableSetOf<Key>()
    private var generation = 0L
    fun cancel() { generation++ }
    private fun current(session: CloudSession) = state.value.session == session && loadSession() == session

    private fun react(kind: String, id: Long, call: suspend (CloudSession) -> Boolean, apply: (Boolean) -> Unit) {
        val session = state.value.session ?: run { state.update { it.copy(message = "请先登录云账号") }; return }
        if (!current(session)) return
        val key = Key(session, kind, id); if (!pending.add(key)) return
        val ticket = generation
        scope.launch {
            try {
                if (!current(session) || ticket != generation) return@launch
                val value = call(session)
                if (current(session) && ticket == generation) apply(value)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (state.value.session == session && ticket == generation && (loadSession() == session ||
                    error is CloudSessionExpiredException && error.isFor(session) && loadSession() == null)) {
                    val message = failure(error, "操作结果未确认，请刷新后核对")
                    if (state.value.session == session && ticket == generation) state.update { it.copy(message = communityFailureMessage(message, "操作未完成，请稍后重试")) }
                }
            } finally { pending.remove(key) }
        }
    }

    fun updatePost(id: Long, transform: (CommunityPost) -> CommunityPost) {
        val session = state.value.session ?: return
        if (!current(session)) return
        state.update {
            fun map(post: CommunityPost) = if (post.id == id) transform(post) else post
            it.copy(posts = it.posts.map(::map), selectedPost = it.selectedPost?.let(::map),
                profilePosts = it.profilePosts.map(::map), searchResults = it.searchResults.map(::map))
        }
        val value = state.value
        (value.selectedPost?.takeIf { it.id == id } ?: (value.posts + value.profilePosts + value.searchResults).firstOrNull { it.id == id })
            ?.let { changed(session, it) }
    }

    fun likePost(id: Long) = react("post-like", id, { like(it, "post", id) }) { liked ->
        updatePost(id) { it.copy(likedByViewer = liked,
            likeCount = (it.likeCount + if (it.likedByViewer == liked) 0 else if (liked) 1 else -1).coerceAtLeast(0)) }
    }
    fun favoritePost(id: Long) = react("post-favorite", id, { favorite(it, id) }) { favorited ->
        updatePost(id) { it.copy(isFavorited = favorited,
            favoriteCount = it.favoriteCount?.let { count -> (count + if (it.isFavorited == favorited) 0 else if (favorited) 1 else -1).coerceAtLeast(0) }) }
    }
    fun likeComment(id: Long) {
        val snapshot = state.value; val postId = snapshot.selectedPost?.id ?: return
        if ((snapshot.comments + snapshot.replies.values.flatten()).none { it.id == id && it.postId == postId }) return
        react("comment-like", id, { like(it, "comment", id) }) { liked ->
            if (state.value.selectedPost?.id != postId) return@react
            state.update {
                fun map(row: com.animeow.app.data.community.CommunityComment) = if (row.id == id && row.postId == postId)
                    row.copy(likedByViewer = liked, likeCount = (row.likeCount + if (row.likedByViewer == liked) 0 else if (liked) 1 else -1).coerceAtLeast(0)) else row
                it.copy(comments = it.comments.map(::map), replies = it.replies.mapValues { (_, rows) -> rows.map(::map) })
            }
        }
    }
}
