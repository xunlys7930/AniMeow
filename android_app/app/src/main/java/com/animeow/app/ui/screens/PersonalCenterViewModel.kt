package com.animeow.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.ActivityBatch
import com.animeow.app.data.community.CommunityImageCompressor
import com.animeow.app.data.community.CommunityActivityChanges
import com.animeow.app.data.community.CommunityLimits
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.CommunityService
import com.animeow.app.data.community.PersonalActivityPage
import com.animeow.app.data.community.PersonalActivityTab
import com.animeow.app.data.community.PresetImage
import com.animeow.app.data.community.PresetImageService
import com.animeow.app.data.community.mergePersonalActivity
import com.animeow.app.data.community.scanPersonalActivity
import com.animeow.app.ui.community.statisticsPayload
import com.animeow.app.ui.statistics.StatisticsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PersonalCenterUiState(
    val configured: Boolean = false,
    val session: CloudSession? = null,
    val profile: CommunityProfile? = null,
    val tab: PersonalActivityTab = PersonalActivityTab.POSTS,
    val pages: Map<PersonalActivityTab, PersonalActivityPage> = emptyMap(),
    val refreshing: Boolean = false,
    val profileError: String? = null,
    val savingProfile: Boolean = false,
    val profileSaved: Boolean = false,
    val profileSaveError: String? = null,
    val presetImages: List<PresetImage> = emptyList(),
    val presetDisclaimer: String = "",
    val message: String? = null,
) {
    val activity: PersonalActivityPage get() = pages[tab] ?: PersonalActivityPage()
}

class PersonalCenterViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val service = CommunityService(application)
    private val compressor = CommunityImageCompressor(application)
    private val initialSession = service.loadSession()
    private val _state = MutableStateFlow(
        PersonalCenterUiState(
            configured = service.isConfigured,
            session = initialSession,
            profile = service.cachedProfile(initialSession),
            refreshing = initialSession != null,
            tab = PersonalActivityTab.entries.firstOrNull { it.name == savedStateHandle.get<String>("personal_tab") }
                ?: PersonalActivityTab.POSTS,
        ),
    )
    val state = _state.asStateFlow()
    private var groupId: Long? = null
    private var limits = CommunityLimits()
    private var refreshJob: Job? = null
    private var showCommunityActivity = true
    private val pageJobs = mutableMapOf<PersonalActivityTab, Job>()
    private val postOverrides = mutableMapOf<Long, com.animeow.app.data.community.CommunityPost?>()

    init {
        viewModelScope.launch {
            CommunityActivityChanges.changes.collect { change ->
                if (_state.value.session?.token != change.sessionToken) return@collect
                postOverrides[change.postId] = change.post
                _state.value = _state.value.copy(pages = _state.value.pages.mapValues { (tab, page) ->
                    val post = change.post
                    val belongs = post != null && when (tab) {
                        PersonalActivityTab.POSTS -> post.author.userId == _state.value.session?.userId
                        PersonalActivityTab.LIKES -> post.likedByViewer
                        PersonalActivityTab.FAVORITES -> post.isFavorited
                    }
                    page.copy(items = if (belongs) mergePersonalActivity(page.items, listOf(post!!)) else page.items.filterNot { it.id == change.postId })
                })
            }
        }
        viewModelScope.launch {
            CommunityActivityChanges.profileUpdates.collect { change ->
                if (_state.value.session?.token != change.sessionToken) return@collect
                val updated = change.profile
                _state.value = _state.value.copy(
                    profile = if (_state.value.profile?.userId == updated.userId || _state.value.session?.userId == updated.userId) updated else _state.value.profile,
                    pages = _state.value.pages.mapValues { (_, page) ->
                        page.copy(items = page.items.map { if (it.author.userId == updated.userId) it.copy(author = updated) else it })
                    },
                )
            }
        }
    }

    fun onResume(showActivity: Boolean) {
        if (showCommunityActivity && !showActivity) {
            pageJobs.values.forEach(Job::cancel)
            pageJobs.clear()
            _state.value = _state.value.copy(pages = _state.value.pages.mapValues { (_, page) ->
                page.copy(loading = false)
            })
        }
        showCommunityActivity = showActivity
        refresh(resetRecords = false)
    }
    fun refresh() = refresh(resetRecords = true)

    private fun refresh(resetRecords: Boolean) {
        if (_state.value.savingProfile) return
        refreshJob?.cancel()
        val session = service.loadSession()
        val changedAccount = session?.token != _state.value.session?.token
        if (resetRecords || changedAccount || session == null) {
            pageJobs.values.forEach(Job::cancel)
            pageJobs.clear()
            postOverrides.clear()
        }
        if (changedAccount || session == null) {
            groupId = null
            _state.value = PersonalCenterUiState(configured = service.isConfigured, session = session,
                profile = service.cachedProfile(session), tab = _state.value.tab)
        }
        if (session == null || !service.isConfigured) return
        _state.value = _state.value.copy(
            refreshing = true, profileError = null,
            pages = if (resetRecords) emptyMap() else _state.value.pages,
        )
        refreshJob = viewModelScope.launch {
            try {
                val bootstrap = service.bootstrap(session)
                if (service.loadSession()?.token != session.token) return@launch refresh()
                groupId = bootstrap.group.id
                limits = bootstrap.limits
                val profile = bootstrap.currentUser
                _state.value = _state.value.copy(
                    profile = profile, refreshing = false,
                    pages = _state.value.pages.mapValues { (_, page) ->
                        page.copy(items = page.items.map { if (profile != null && it.author.userId == profile.userId) it.copy(author = profile) else it })
                    },
                )
                if (_state.value.activity.loaded.not()) loadActivity(_state.value.tab)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is CloudSessionExpiredException) clearExpiredSession(error)
                else {
                    val message = error.message ?: "个人中心加载失败，请稍后重试"
                    _state.value = _state.value.copy(
                        refreshing = false,
                        profileError = if (_state.value.profile == null || resetRecords) message else null,
                        message = if (_state.value.profile != null && !resetRecords) "资料刷新失败，已保留当前记录" else null,
                    )
                }
            }
        }
    }

    fun selectTab(tab: PersonalActivityTab) {
        savedStateHandle["personal_tab"] = tab.name
        _state.value = _state.value.copy(tab = tab)
        if (_state.value.pages[tab]?.loaded != true) loadActivity(tab)
    }

    fun loadMore() = loadActivity(_state.value.tab)

    private fun loadActivity(tab: PersonalActivityTab) {
        if (!showCommunityActivity) return
        val session = _state.value.session ?: return
        val group = groupId ?: return
        val previous = _state.value.pages[tab] ?: PersonalActivityPage()
        if (previous.loading || previous.endReached || pageJobs[tab]?.isActive == true) return
        updatePage(tab, previous.copy(loading = true, error = null))
        pageJobs[tab] = viewModelScope.launch {
            try {
                val batch = when {
                    tab == PersonalActivityTab.POSTS -> {
                        val page = service.listUserPosts(session, session.userId, previous.nextBeforeId, limit = 30)
                        check(page.nextBeforeId == null || previous.nextBeforeId == null || page.nextBeforeId < previous.nextBeforeId) {
                            "暂时无法继续加载帖子，请刷新后重试"
                        }
                        ActivityBatch(page.items, page.nextBeforeId, page.nextBeforeId == null)
                    }
                    tab == PersonalActivityTab.FAVORITES && !previous.loaded -> {
                        val posts = service.listFavorites(session, limit = 50)
                        // The server's favorites endpoint has a limit but no cursor. Continue via viewer flags
                        // in paginated feed pages when that limit is reached, without inventing a new API.
                        ActivityBatch(posts, null, posts.size < 50)
                    }
                    else -> scanPersonalActivity(
                        beforeId = previous.nextBeforeId,
                        knownIds = previous.items.mapTo(hashSetOf()) { it.id },
                        matches = { if (tab == PersonalActivityTab.LIKES) it.likedByViewer else it.isFavorited },
                        loadPage = { cursor -> service.listPosts(session, group, beforeId = cursor, limit = 50) },
                    )
                }
                if (_state.value.session?.token != session.token) return@launch
                val currentItems = _state.value.pages[tab]?.items ?: previous.items
                val incoming = batch.items.mapNotNull { post -> if (postOverrides.containsKey(post.id)) postOverrides[post.id] else post }
                    .filter { post -> when (tab) {
                        PersonalActivityTab.POSTS -> post.author.userId == session.userId
                        PersonalActivityTab.LIKES -> post.likedByViewer
                        PersonalActivityTab.FAVORITES -> post.isFavorited
                    } }
                updatePage(tab, PersonalActivityPage(
                    items = mergePersonalActivity(currentItems, incoming),
                    nextBeforeId = batch.nextBeforeId,
                    endReached = batch.endReached,
                    loaded = true,
                ))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is CloudSessionExpiredException) clearExpiredSession(error)
                else if (_state.value.session?.token == session.token) {
                    val current = _state.value.pages[tab] ?: previous
                    updatePage(tab, current.copy(loading = false, error = error.message ?: "记录加载失败，请重试"))
                }
            }
        }
    }

    fun loadProfilePresets() {
        _state.value = _state.value.copy(profileSaveError = null)
        if (!service.isConfigured || _state.value.presetImages.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val presets = PresetImageService.fetchPresetImages() ?: return@launch
                _state.value = _state.value.copy(presetImages = presets.images, presetDisclaimer = presets.disclaimer)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Optional images never prevent editing a nickname or signature.
            }
        }
    }

    fun updateProfile(
        nickname: String,
        signature: String,
        privateProfile: Boolean,
        showStatistics: Boolean,
        selectedStatistics: Set<String>,
        avatarUri: Uri?,
        removeAvatar: Boolean,
        presetAvatar: String?,
        statistics: StatisticsUiState,
    ) {
        val session = _state.value.session ?: return
        if (_state.value.savingProfile) return
        _state.value = _state.value.copy(savingProfile = true, profileSaved = false, profileSaveError = null)
        viewModelScope.launch {
            try {
                val avatar = avatarUri?.let { compressor.compressAvatar(it, minOf(limits.avatarBytes, 384 * 1024)) }
                val profile = service.updateProfile(
                    session, nickname, signature, if (privateProfile) "private" else "members", showStatistics,
                    statisticsPayload(statistics, selectedStatistics), avatar, removeAvatar, presetAvatar,
                )
                if (service.loadSession()?.token != session.token) {
                    _state.value = _state.value.copy(savingProfile = false)
                    refresh()
                    return@launch
                }
                _state.value = _state.value.copy(
                    profile = profile, savingProfile = false, profileSaved = true,
                    message = if (profile.avatarReview?.status in setOf("pending", "processing", "manual")) "个人资料已保存，头像审核通过后会自动显示" else "个人资料已更新",
                    pages = _state.value.pages.mapValues { (_, page) ->
                        page.copy(items = page.items.map { if (it.author.userId == profile.userId) it.copy(author = profile) else it })
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is CloudSessionExpiredException) clearExpiredSession(error)
                else _state.value = _state.value.copy(savingProfile = false, profileSaveError = error.message ?: "保存失败，请重试")
            }
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    fun consumeProfileSaved() { _state.value = _state.value.copy(profileSaved = false) }

    private fun updatePage(tab: PersonalActivityTab, page: PersonalActivityPage) {
        _state.value = _state.value.copy(pages = _state.value.pages + (tab to page))
    }

    private fun clearExpiredSession(error: CloudSessionExpiredException) {
        if (!error.isFor(_state.value.session) || service.loadSession() != null) return
        pageJobs.values.forEach(Job::cancel)
        postOverrides.clear()
        groupId = null
        _state.value = PersonalCenterUiState(configured = service.isConfigured, tab = _state.value.tab, message = error.message)
    }
}
