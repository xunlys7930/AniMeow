package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.CommunityBootstrap
import com.animeow.app.data.community.CommunityFeedPage
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityTag
import com.animeow.app.data.community.matchesRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CommunityFeedRequest(
    val session: CloudSession?, val groupId: Long, val sort: String, val featured: Boolean, val tag: String?,
    val beforeId: Long? = null, val offset: Int? = null,
)

/** Owns feed requests; draft, comments and moderation keep their existing state and operations. */
internal class CommunityFeedController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val loadSession: () -> CloudSession?,
    private val bootstrap: suspend (CloudSession?) -> CommunityBootstrap,
    private val posts: suspend (CommunityFeedRequest) -> CommunityFeedPage,
    private val tags: suspend (CloudSession?) -> List<CommunityTag>,
    private val failure: (Throwable, String) -> String,
) {
    private var generation = 0L
    private var initialJob: Job? = null
    private var moreJob: Job? = null
    private var tagsJob: Job? = null
    private var silentJob: Job? = null
    private var firstPageIds = emptySet<Long>()

    fun cancel() {
        generation++
        initialJob?.cancel(); moreJob?.cancel(); tagsJob?.cancel(); silentJob?.cancel()
        state.update { it.copy(loading = false, loadingMore = false) }
    }

    private fun matches(snapshot: CommunityUiState, ticket: Long): Boolean {
        val current = state.value
        return ticket == generation && current.session == snapshot.session &&
            current.feedSort == snapshot.feedSort && current.feedFeaturedOnly == snapshot.feedFeaturedOnly && current.feedTag == snapshot.feedTag
    }

    private fun current(snapshot: CommunityUiState, ticket: Long) = matches(snapshot, ticket) && loadSession() == snapshot.session

    private fun request(snapshot: CommunityUiState, groupId: Long, more: Boolean = false) = CommunityFeedRequest(
        snapshot.session, groupId, snapshot.feedSort, snapshot.feedFeaturedOnly, snapshot.feedTag,
        beforeId = snapshot.nextBeforeId.takeIf { more && snapshot.nextOffset == null },
        offset = snapshot.nextOffset.takeIf { more },
    )

    private fun publish(snapshot: CommunityUiState, ticket: Long, page: CommunityFeedPage, initial: CommunityBootstrap? = null, append: Boolean = false) {
        if (!current(snapshot, ticket)) return
        val supported = page.matchesRequest(snapshot.feedSort, snapshot.feedTag)
        if (!append) firstPageIds = if (supported) page.items.mapTo(hashSetOf(), CommunityPost::id) else emptySet()
        state.update { current ->
            val known = current.posts.mapTo(hashSetOf(), CommunityPost::id)
            current.copy(bootstrap = (initial ?: current.bootstrap)?.copy(group = page.group),
                posts = if (!supported) emptyList() else if (append) current.posts + page.items.filter { known.add(it.id) } else page.items.distinctBy(CommunityPost::id),
                nextBeforeId = page.nextBeforeId.takeIf { supported }, nextOffset = page.nextOffset.takeIf { supported },
                feedFilterUnsupported = !supported, feedError = null)
        }
    }

    private fun report(snapshot: CommunityUiState, ticket: Long, error: Throwable, fallback: String, more: Boolean = false) {
        if (!matches(snapshot, ticket)) return
        // The service clears an explicitly expired session before the existing failure handler resets UI state.
        if (loadSession() != snapshot.session && !(error is CloudSessionExpiredException && error.isFor(snapshot.session) && loadSession() == null)) return
        val message = failure(error, fallback)
        if (error !is CloudSessionExpiredException) state.update {
            if (more) it.copy(message = message) else it.copy(feedError = message)
        }
    }

    fun loadInitial(force: Boolean = false) {
        val snapshot = state.value
        if (snapshot.busy || !force && (snapshot.loading || snapshot.bootstrap != null)) return
        cancel()
        val ticket = generation
        state.update { it.copy(loading = true, message = null, feedError = null) }
        initialJob = scope.launch {
            try {
                val result = bootstrap(snapshot.session)
                if (!current(snapshot, ticket)) return@launch
                if (state.value.commonTags == null) loadTags(snapshot, ticket)
                if (snapshot.feedSort == "following" && snapshot.session == null) {
                    state.update { it.copy(bootstrap = result, posts = emptyList(), nextBeforeId = null, nextOffset = null, feedFilterUnsupported = false) }
                } else publish(snapshot, ticket, posts(request(snapshot, result.group.id)), result)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { report(snapshot, ticket, error, "社区暂时无法加载") }
            finally { if (ticket == generation) state.update { it.copy(loading = false) } }
        }
    }

    private fun loadTags(snapshot: CommunityUiState, ticket: Long) {
        tagsJob = scope.launch {
            try {
                val result = tags(snapshot.session)
                if (current(snapshot, ticket)) state.update { it.copy(commonTags = result) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Optional on older servers; do not interrupt the feed. */ }
        }
    }

    fun loadMore() {
        val snapshot = state.value
        val group = snapshot.group ?: return
        if (!snapshot.hasMoreFeed || snapshot.loading || snapshot.loadingMore || snapshot.busy) return
        val ticket = generation
        silentJob?.cancel()
        state.update { it.copy(loadingMore = true) }
        moreJob = scope.launch {
            try { publish(snapshot, ticket, posts(request(snapshot, group.id, more = true)), append = true) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { report(snapshot, ticket, error, "加载更多失败", more = true) }
            finally { if (ticket == generation) state.update { it.copy(loadingMore = false) } }
        }
    }

    fun refreshLatestSilently() {
        val snapshot = state.value
        val group = snapshot.group ?: return
        if (snapshot.loading || snapshot.loadingMore || snapshot.busy || silentJob?.isActive == true || snapshot.feedFilterUnsupported ||
            snapshot.feedSort == "following" && snapshot.session == null) return
        val ticket = generation
        silentJob = scope.launch {
            try {
                val page = posts(request(snapshot, group.id))
                if (!current(snapshot, ticket) || state.value.posts !== snapshot.posts || state.value.loadingMore ||
                    !page.matchesRequest(snapshot.feedSort, snapshot.feedTag)) return@launch
                val refreshedIds = page.items.mapTo(hashSetOf(), CommunityPost::id)
                val older = state.value.posts.filter { it.id !in firstPageIds && it.id !in refreshedIds }
                firstPageIds = refreshedIds
                state.update { it.copy(bootstrap = it.bootstrap?.copy(group = page.group),
                    posts = page.items.distinctBy(CommunityPost::id) + older,
                    nextBeforeId = if (older.isEmpty()) page.nextBeforeId else it.nextBeforeId,
                    nextOffset = if (older.isEmpty()) page.nextOffset else it.nextOffset) }
            } catch (error: CancellationException) { throw error }
            catch (error: CloudSessionExpiredException) { report(snapshot, ticket, error, "登录会话已失效") }
            catch (_: Exception) { /* Polling failure must not interrupt reading. */ }
        }
    }

    suspend fun reloadAfterWrite(session: CloudSession, groupId: Long) {
        val snapshot = state.value
        if (snapshot.session != session || loadSession() != session) return
        cancel()
        val ticket = generation
        val page = posts(request(snapshot, groupId))
        if (state.value.posts !== snapshot.posts) return
        publish(snapshot, ticket, page)
    }
}
