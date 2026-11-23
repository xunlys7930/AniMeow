package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal interface CommunityPeopleApi {
    suspend fun profile(session: CloudSession, id: Long): CommunityProfile
    suspend fun posts(session: CloudSession, id: Long, before: Long?): CommunityFeedPage
    suspend fun follow(session: CloudSession, id: Long, following: Boolean): CommunityFollowResult
    suspend fun follows(session: CloudSession, id: Long, direction: String, offset: Int): CommunityFollowPage
    suspend fun blocks(session: CloudSession): List<CommunityProfile>
}

internal class ServiceCommunityPeopleApi(private val service: CommunityService) : CommunityPeopleApi {
    override suspend fun profile(session: CloudSession, id: Long) = service.getProfile(session, id)
    override suspend fun posts(session: CloudSession, id: Long, before: Long?) = service.listUserPosts(session, id, before)
    override suspend fun follow(session: CloudSession, id: Long, following: Boolean) = service.setFollowing(session, id, following)
    override suspend fun follows(session: CloudSession, id: Long, direction: String, offset: Int) = service.listFollows(session, id, direction, offset)
    override suspend fun blocks(session: CloudSession) = service.listBlocks(session)
}

/** Profile, author preview and follow lists have separate lifetimes, independent of the feed. */
internal class CommunityPeopleController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val api: CommunityPeopleApi,
    private val loadSession: () -> CloudSession?,
    private val failure: (Throwable, String) -> String,
) {
    private var profileVersion = 0L
    private var listVersion = 0L
    private var profileJob: Job? = null
    private var moreJob: Job? = null
    private var listJob: Job? = null
    private var followVersion = 0L
    private var blocksVersion = 0L
    private var blocksJob: Job? = null

    private fun current(session: CloudSession) = state.value.session == session && loadSession() == session
    private fun report(session: CloudSession, error: Throwable, fallback: String): String? {
        if (state.value.session != session || loadSession() != session &&
            !(error is CloudSessionExpiredException && error.isFor(session) && loadSession() == null)) return null
        return communityFailureMessage(failure(error, fallback), fallback)
    }

    fun cancel() { closeProfile(); closeFollows(); closeBlocks(); followVersion++; state.update { it.copy(followBusyUserId = null) } }
    fun closeProfile() {
        profileVersion++; profileJob?.cancel(); moreJob?.cancel()
        state.update { it.copy(selectedProfile = null, profilePosts = emptyList(), profileNextBeforeId = null,
            profileLoading = false, profileLoadingMore = false, profileLoadError = false, profileError = null) }
    }

    fun openProfile(id: Long, authorOnly: Boolean = false) {
        val session = state.value.session ?: return
        if (!current(session) || authorOnly && id == state.value.currentUser?.userId) return
        closeProfile(); val ticket = profileVersion
        state.update { it.copy(profileLoading = true) }
        profileJob = scope.launch {
            fun valid() = ticket == profileVersion && current(session)
            try {
                val profile = try { api.profile(session, id) }
                catch (error: CancellationException) { throw error }
                catch (error: Throwable) {
                    // Only a transient failure may fall back to this account's own cached profile.
                    if (error is CloudSessionExpiredException || !valid()) throw error
                    state.value.currentUser?.takeIf { it.userId == id } ?: throw error
                }
                if (!valid()) return@launch
                check(profile.userId == id) { "用户名片与请求不一致，请重试" }
                state.update { it.copy(selectedProfile = profile, profileLoading = !authorOnly) }
                if (authorOnly) return@launch
                try {
                    val page = api.posts(session, id, null)
                    if (valid()) state.update { it.copy(profilePosts = page.items.distinctBy(CommunityPost::id), profileNextBeforeId = page.nextBeforeId) }
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) {
                    if (valid() || error is CloudSessionExpiredException) report(session, error, "读取作品失败，请重试")?.let { message ->
                        if (ticket == profileVersion) state.update { it.copy(profileLoadError = true, profileError = message) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (ticket == profileVersion) report(session, error, "读取个人名片失败，请重试")?.let { message ->
                    if (!authorOnly) state.update { it.copy(profileError = message, message = message) }
                }
            } finally { if (ticket == profileVersion) state.update { it.copy(profileLoading = false) } }
        }
    }

    fun morePosts() {
        val snapshot = state.value; val session = snapshot.session ?: return
        val profile = snapshot.selectedProfile ?: return; val cursor = snapshot.profileNextBeforeId ?: return
        if (!current(session) || snapshot.profileLoading || snapshot.profileLoadingMore) return
        val ticket = profileVersion
        state.update { it.copy(profileLoadingMore = true, profileLoadError = false, profileError = null) }
        moreJob = scope.launch {
            fun valid() = ticket == profileVersion && current(session) && state.value.selectedProfile?.userId == profile.userId
            try {
                val page = api.posts(session, profile.userId, cursor)
                if (valid()) state.update {
                    val known = it.profilePosts.mapTo(hashSetOf(), CommunityPost::id)
                    it.copy(profilePosts = it.profilePosts + page.items.filter { row -> known.add(row.id) }, profileNextBeforeId = page.nextBeforeId)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (ticket == profileVersion) report(session, error, "读取更多作品失败，请重试")?.let { message -> state.update { it.copy(profileLoadError = true, profileError = message) } }
            } finally { if (ticket == profileVersion) state.update { it.copy(profileLoadingMore = false) } }
        }
    }

    fun toggleFollow(id: Long) {
        val session = state.value.session ?: run { state.update { it.copy(message = "请先登录云账号") }; return }
        val selected = state.value.selectedProfile?.takeIf { it.userId == id } ?: return
        val following = selected.isFollowing ?: return
        if (!current(session) || state.value.followBusyUserId != null) return
        val ticket = ++followVersion; val profileTicket = profileVersion
        state.update { it.copy(followBusyUserId = id) }
        scope.launch {
            fun valid() = ticket == followVersion && profileTicket == profileVersion && current(session) && state.value.selectedProfile?.userId == id
            try {
                val result = api.follow(session, id, !following)
                if (valid()) state.update { value -> value.copy(selectedProfile = value.selectedProfile?.let {
                    it.copy(isFollowing = result.following, stats = it.stats?.copy(followerCount = result.followerCount ?: it.stats.followerCount))
                }, message = if (result.following) "已关注" else "已取消关注") }
            } catch (error: CancellationException) { throw error }
            catch (_: CommunityFeatureUnavailableException) {
                if (valid()) state.update { it.copy(selectedProfile = it.selectedProfile?.copy(isFollowing = null), message = "当前服务器暂不支持关注") }
            } catch (error: Throwable) { if (valid()) report(session, error, "关注操作结果未确认，请刷新后核对")?.let { message -> state.update { it.copy(message = message) } } }
            finally { if (ticket == followVersion) state.update { it.copy(followBusyUserId = null) } }
        }
    }

    fun closeFollows() {
        listVersion++; listJob?.cancel()
        state.update { it.copy(followListUserId = null, followList = emptyList(), followListNextOffset = null,
            followListLoading = false, followListLoadingMore = false, followListError = null) }
    }
    fun invalidateBlocks() {
        blocksVersion++; blocksJob?.cancel()
        state.update { it.copy(blocksLoading = false, blocksError = null) }
    }
    fun closeBlocks() { invalidateBlocks(); state.update { it.copy(blocks = emptyList()) } }
    fun loadBlocks() {
        val session = state.value.session ?: return
        if (!current(session)) return
        invalidateBlocks(); val ticket = blocksVersion
        state.update { it.copy(blocksLoading = true) }
        blocksJob = scope.launch {
            try {
                val rows = api.blocks(session)
                if (ticket == blocksVersion && current(session)) state.update { it.copy(blocks = rows.distinctBy(CommunityProfile::userId)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (ticket == blocksVersion) report(session, error, "读取屏蔽列表失败，请重试")?.let { message -> state.update { it.copy(blocksError = message) } }
            } finally { if (ticket == blocksVersion) state.update { it.copy(blocksLoading = false) } }
        }
    }
    fun openFollows(id: Long, direction: String) {
        closeFollows()
        state.update { it.copy(followListUserId = id, followListDirection = if (direction == "followers") direction else "following", followListSupported = true) }
        loadFollows(reset = true)
    }
    fun direction(value: String) {
        val id = state.value.followListUserId ?: return
        if (state.value.followListDirection != value) openFollows(id, value)
    }
    fun loadFollows(reset: Boolean = false) {
        val snapshot = state.value; val session = snapshot.session ?: return; val id = snapshot.followListUserId ?: return
        if (!current(session) || snapshot.followListLoading || snapshot.followListLoadingMore || !reset && snapshot.followListNextOffset == null) return
        val ticket = listVersion
        state.update { it.copy(followListLoading = reset, followListLoadingMore = !reset, followListError = null) }
        listJob = scope.launch {
            fun valid() = ticket == listVersion && current(session) && state.value.followListUserId == id && state.value.followListDirection == snapshot.followListDirection
            try {
                val page = api.follows(session, id, snapshot.followListDirection, if (reset) 0 else snapshot.followListNextOffset ?: 0)
                if (valid()) state.update {
                    val items = if (reset) emptyList() else it.followList
                    val known = items.mapTo(hashSetOf(), CommunityProfile::userId)
                    it.copy(followList = items + page.items.filter { user -> known.add(user.userId) }, followListNextOffset = page.nextOffset)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: CommunityFeatureUnavailableException) { if (valid()) state.update { it.copy(followListSupported = false) } }
            catch (error: Throwable) { if (ticket == listVersion) report(session, error, "读取关注列表失败，请重试")?.let { message -> state.update { it.copy(followListError = message) } } }
            finally { if (ticket == listVersion) state.update { it.copy(followListLoading = false, followListLoadingMore = false) } }
        }
    }
}
