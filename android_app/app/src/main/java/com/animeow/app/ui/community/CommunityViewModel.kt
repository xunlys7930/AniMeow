package com.animeow.app.ui.community

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.CommunityActivityChanges
import com.animeow.app.data.community.CommunityBootstrap
import com.animeow.app.data.community.CommunityComment
import com.animeow.app.data.community.CommunityDraftImages
import com.animeow.app.data.community.CommunityGroup
import com.animeow.app.data.community.CommunityImageCompressor
import com.animeow.app.data.community.CommunityLimits
import com.animeow.app.data.community.CommunityModerationLogEntry
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.CommunityResolvedReview
import com.animeow.app.data.community.CommunityReviewCase
import com.animeow.app.data.community.CommunityService
import com.animeow.app.data.community.CommunityStats
import com.animeow.app.data.community.CommunityFeatureUnavailableException
import com.animeow.app.data.community.CommunityTag
import com.animeow.app.data.community.listTags
import com.animeow.app.data.community.withCommunityTag
import com.animeow.app.data.community.SensitiveWord
import com.animeow.app.data.community.PresetImage
import com.animeow.app.data.community.PresetImageService
import com.animeow.app.ui.statistics.StatisticsUiState
import com.animeow.app.ui.statistics.calculateStatistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CommunityStatisticMetric(
    val key: String,
    val label: String,
) {
    TOTAL_ITEMS("total_items", "总收录"),
    ANIME_COUNT("anime_count", "动画"),
    BOOK_COUNT("book_count", "书籍"),
    WATCHED_EPISODES("watched_episodes", "已看集数"),
    ESTIMATED_MINUTES("estimated_minutes", "估算时长"),
    AVERAGE_RATING("average_rating", "平均评分"),
    STREAK_DAYS("streak_days", "连续打卡"),
}

val DefaultCommunityStatisticKeys: Set<String> = setOf(
    CommunityStatisticMetric.TOTAL_ITEMS.key,
    CommunityStatisticMetric.ANIME_COUNT.key,
    CommunityStatisticMetric.BOOK_COUNT.key,
    CommunityStatisticMetric.WATCHED_EPISODES.key,
    CommunityStatisticMetric.ESTIMATED_MINUTES.key,
    CommunityStatisticMetric.AVERAGE_RATING.key,
)

data class CommunityUiState(
    val configured: Boolean = false,
    val session: CloudSession? = null,
    val bootstrap: CommunityBootstrap? = null,
    val posts: List<CommunityPost> = emptyList(),
    val nextBeforeId: Long? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val draftText: String = "",
    val draftImages: List<Uri> = emptyList(),
    val draftPresetMedia: List<String> = emptyList(),
    val importingImages: Boolean = false,
    val publishedPostId: Long? = null,
    val selectedProfile: CommunityProfile? = null,
    val profileSaved: Boolean = false,
    val profileSaveError: String? = null,
    val profilePosts: List<CommunityPost> = emptyList(),
    val reviews: List<CommunityReviewCase> = emptyList(),
    val showingReviews: Boolean = false,
    val reviewsLoading: Boolean = false,
    val reviewsError: String? = null,
    val reviewBusy: Boolean = false,
    val resolvedReviews: List<CommunityResolvedReview> = emptyList(),
    val users: List<CommunityProfile> = emptyList(),
    val usersLoading: Boolean = false,
    val usersError: String? = null,
    val administrationBusy: Boolean = false,
    val administrationBusyLabel: String? = null,
    val moderationLogLoading: Boolean = false,
    val moderationLogError: String? = null,
    val resolvedReviewsLoading: Boolean = false,
    val resolvedReviewsError: String? = null,
    val sensitiveWords: List<SensitiveWord> = emptyList(),
    val sensitiveWordsTotal: Int = 0,
    val sensitiveWordsPage: Int = 1,
    val sensitiveWordsSearch: String = "",
    val sensitiveWordsLoading: Boolean = false,
    val sensitiveWordsError: String? = null,
    val sensitiveWordsPageSize: Int = 50,
    val blocks: List<CommunityProfile> = emptyList(),
    val blocksLoading: Boolean = false,
    val blocksError: String? = null,
    /** 关注名单：查看中的用户、方向（following/followers）、名单与分页游标。 */
    val followListUserId: Long? = null,
    val followListDirection: String = "following",
    val followList: List<CommunityProfile> = emptyList(),
    val followListLoading: Boolean = false,
    val followListLoadingMore: Boolean = false,
    val followListNextOffset: Int? = null,
    val followListError: String? = null,
    /** 旧服务器不支持关注名单时为 false，界面隐藏入口。 */
    val followListSupported: Boolean = true,
    val searchResults: List<CommunityPost> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val stats: CommunityStats? = null,
    val auditExport: String? = null,
    val statsLoading: Boolean = false,
    val statsError: String? = null,
    val auditLoading: Boolean = false,
    val auditError: String? = null,
    val selectedPost: CommunityPost? = null,
    val comments: List<CommunityComment> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentsBusy: Boolean = false,
    val commentsError: String? = null,
    val postDetailLoading: Boolean = false,
    val postDetailError: String? = null,
    val commentDraft: String = "",
    val replyTarget: CommunityComment? = null,
    val replyDraft: String = "",
    val commentSort: String = "newest",
    val replies: Map<Long, List<CommunityComment>> = emptyMap(),
    val replyBranches: Map<Long, CommunityReplyBranch> = emptyMap(),
    val feedSort: String = "newest",
    val feedFeaturedOnly: Boolean = false,
    val feedError: String? = null,
    val moderationLog: List<CommunityModerationLogEntry> = emptyList(),
    val presetImages: List<PresetImage> = emptyList(),
    val presetDisclaimer: String = "",
    val message: String? = null,
    /** 当前标签筛选；null 表示全部。 */
    val feedTag: String? = null,
    /** 计数排序的下一页偏移（新服务器）；为 null 时退回 nextBeforeId。 */
    val nextOffset: Int? = null,
    /** 常用标签；null 表示服务器不支持或尚未取到，界面隐藏标签行。 */
    val commonTags: List<CommunityTag>? = null,
    /** 旧服务器忽略了关注/标签筛选：不展示它返回的全部帖子，改为说明。 */
    val feedFilterUnsupported: Boolean = false,
    val draftTags: List<String> = emptyList(),
    val draftWatchReferenceId: Long? = null,
    val watchShareLoading: Boolean = false,
    val relatedPosts: List<CommunityPost> = emptyList(),
    val relatedLoading: Boolean = false,
    val followBusyUserId: Long? = null,
    val profileNextBeforeId: Long? = null,
    val profileLoadingMore: Boolean = false,
    val profileLoadError: Boolean = false,
    val profileLoading: Boolean = false,
    val profileError: String? = null,
) {
    val hasMoreFeed: Boolean get() = nextOffset != null || nextBeforeId != null
    val group: CommunityGroup? get() = bootstrap?.group
    val currentUser: CommunityProfile? get() = bootstrap?.currentUser
    val limits: CommunityLimits get() = bootstrap?.limits ?: CommunityLimits()
}

class CommunityViewModel @JvmOverloads constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    apiBase: String = com.animeow.app.BuildConfig.CLOUD_API_BASE,
) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val service = CommunityService(application, apiBase = apiBase)
    private val compressor = CommunityImageCompressor(application)
    private val draftImages = CommunityDraftImages(application)
    private val draftStore = CommunityDraftStore(savedStateHandle)
    private val restoredDraft = draftStore.read()
    private val _state = MutableStateFlow(
        CommunityUiState(
            configured = service.isConfigured,
            session = service.loadSession(),
            draftText = restoredDraft.text,
            draftImages = restoredDraft.images,
            draftPresetMedia = restoredDraft.presetMedia,
            draftTags = restoredDraft.tags,
            draftWatchReferenceId = restoredDraft.watchReferenceId,
        ),
    )
    val state: StateFlow<CommunityUiState> = _state.asStateFlow()

    private val feed = CommunityFeedController(viewModelScope, _state,
        loadSession = service::loadSession,
        bootstrap = service::bootstrap,
        posts = { request -> service.listPosts(request.session, request.groupId, request.sort, request.featured,
            beforeId = request.beforeId, tag = request.tag, offset = request.offset) },
        tags = { session -> service.listTags(session).items },
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) },
    )

    private val thread = CommunityThreadController(viewModelScope, _state, ServiceCommunityThreadApi(service), service::loadSession,
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) },
        onPostLoaded = { session, post -> CommunityActivityChanges.publish(session.token, post) },
    )

    private val administration = CommunityAdministrationController(viewModelScope, _state, ServiceCommunityAdministrationApi(service), service::loadSession,
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) },
        refreshFeed = feed::reloadAfterWrite,
    )

    private val composer = CommunityComposerController(viewModelScope, _state, service::loadSession,
        compress = { uri, limit -> compressor.compressPostImage(uri, maxBytes = limit) },
        create = { session, groupId, draft, images -> service.createPost(session, groupId, draft.text, images, draft.presetMedia, draft.tags, draft.watchReferenceId) },
        saveDraft = draftStore::write, release = draftImages::release,
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) },
        published = { session, post -> CommunityActivityChanges.publish(session.token, post) },
    )

    private val people = CommunityPeopleController(viewModelScope, _state, ServiceCommunityPeopleApi(service), service::loadSession,
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) },
    )

    private val searchController = CommunitySearchController(viewModelScope, _state, service::loadSession,
        search = { session, query, sort, featured -> service.searchPosts(session, query, sort, featured).items },
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) },
    )

    private val mutations = CommunityMutationController(viewModelScope, _state, service::loadSession,
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) },
    )

    private val reactions = CommunityReactionController(viewModelScope, _state, service::loadSession,
        like = { session, kind, id -> service.like(session, kind, id) }, favorite = service::favoritePost,
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) },
        changed = { session, post -> CommunityActivityChanges.publish(session.token, post) },
    )

    private val moderation = CommunityModerationController(viewModelScope, _state, ServiceCommunityModerationApi(service), service::loadSession,
        failure = { error, fallback -> handleFailure(error, fallback, updateState = false) }, refreshFeed = feed::reloadAfterWrite,
    )

    val localStatistics: StateFlow<StatisticsUiState> = combine(
        combine(
            app.libraryRepository.observeAnimes(),
            app.libraryRepository.observeEpisodeDetailsWithDuration(),
        ) { animes, details -> animes to details },
        app.libraryRepository.observeTags(),
        app.libraryRepository.observeAnimeTags(),
        app.libraryRepository.observeWatchStatuses(),
        app.libraryRepository.observeWatchRecords(),
    ) { library, tags, links, statuses, records ->
        calculateStatistics(library.first, tags, links, records, statuses, episodeDetails = library.second)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatisticsUiState(),
    )

    init {
        restoreDraftAttachments()
        if (_state.value.configured) loadInitial()
        loadPresetImages()
        viewModelScope.launch {
            CommunityActivityChanges.profileUpdates.collect { change ->
                if (_state.value.session?.token != change.sessionToken) return@collect
                _state.value = applyProfileUpdateToCommunityState(_state.value, change.profile, markSaved = false)
            }
        }
    }

    // Save synchronously with each edit so lifecycle state saving cannot overtake a collector.
    // Attachments are private files, so restored drafts do not depend on picker URI grants.
    private fun updateDraft(transform: (CommunityUiState) -> CommunityUiState) {
        val next = transform(_state.value)
        draftStore.write(CommunityComposerDraft(next.draftText, next.draftImages, next.draftPresetMedia, next.draftTags, next.draftWatchReferenceId))
        _state.value = next
    }

    private fun restoreDraftAttachments() {
        val restored = _state.value.draftImages
        if (restored.isEmpty()) return
        importDraftAttachments(restored, restoring = true)
    }

    private fun importDraftAttachments(
        uris: List<Uri>,
        restoring: Boolean = false,
        replacingPreset: String? = null,
    ) {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.importingImages || uris.isEmpty()) return
        val capacity = if (restoring) snapshot.limits.postImages - snapshot.draftPresetMedia.size else {
            snapshot.limits.postImages - snapshot.draftImages.size - snapshot.draftPresetMedia.size +
                if (replacingPreset in snapshot.draftPresetMedia) 1 else 0
        }
        val sources = uris.distinct().take(capacity.coerceAtLeast(0))
        if (sources.isEmpty()) return
        _state.value = snapshot.copy(importingImages = true)
        viewModelScope.launch {
            val retained = mutableListOf<Uri>()
            var attached = false
            var failed = 0
            try {
                sources.forEach { uri ->
                    try {
                        retained += draftImages.retain(uri)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed++
                    }
                }
                updateDraft { current ->
                    current.copy(
                        draftImages = if (restoring) retained.toList() else (current.draftImages + retained).distinct(),
                        draftPresetMedia = if (retained.isNotEmpty() && replacingPreset != null) {
                            current.draftPresetMedia - replacingPreset
                        } else current.draftPresetMedia,
                        message = if (failed > 0) "$failed 张图片无法读取或超过 20 MB，请重新选择" else current.message,
                    )
                }
                attached = true
            } finally {
                if (!attached) {
                    withContext(NonCancellable) { draftImages.release(retained - snapshot.draftImages.toSet()) }
                }
                _state.value = _state.value.copy(importingImages = false)
            }
        }
    }

    fun refreshSession() {
        val session = service.loadSession()
        val current = _state.value.session
        if (session != current) {
            feed.cancel()
            thread.cancel()
            administration.cancel()
            composer.cancel()
            people.cancel()
            searchController.clear()
            mutations.cancel()
            reactions.cancel()
            moderation.cancel()
            val draft = _state.value
            draftStore.write(draft.composerDraft().copy(watchReferenceId = null))
            _state.value = CommunityUiState(
                configured = service.isConfigured,
                session = session,
                draftText = draft.draftText,
                draftImages = draft.draftImages,
                draftPresetMedia = draft.draftPresetMedia,
                draftTags = draft.draftTags,
                importingImages = draft.importingImages,
            )
        }
        if (service.isConfigured && _state.value.bootstrap == null && !_state.value.loading) {
            loadInitial()
        }
        loadPresetImages()
    }

    fun prepareWatchShare(id: Long) {
        val session = service.loadSession() ?: return
        _state.value = _state.value.copy(watchShareLoading = true)
        viewModelScope.launch {
            try {
                val api = com.animeow.app.data.community.WatchService(app, service)
                api.capabilities(session)
                val item = api.item(id, session)
                if (service.loadSession() != session || _state.value.session != session) return@launch
                require(!item.unavailable && item.kind == "comment") { "原评论暂不可分享" }
                updateDraft { it.copy(draftWatchReferenceId = id) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Throwable) {
                if (service.loadSession() == session) {
                    _state.value = _state.value.copy(message = handleFailure(error, "原评论暂不可分享，请返回后重新读取", updateState = false))
                }
            }
            finally {
                if (_state.value.session == session) _state.value = _state.value.copy(watchShareLoading = false)
                if (service.loadSession() != session) refreshSession()
            }
        }
    }
    fun removeWatchReference() = updateDraft { it.copy(draftWatchReferenceId = null) }

    fun refresh() = loadInitial(force = true)

    fun setFeedSort(sort: String, featuredOnly: Boolean) {
        _state.value = _state.value.copy(feedSort = sort, feedFeaturedOnly = featuredOnly)
        loadInitial(force = true)
    }

    /** 瀑布流顶部页签：latest / following / featured。 */
    fun setFeedTab(tab: String) {
        val (sort, featured) = when (tab) {
            "following" -> "following" to false
            "featured" -> "newest" to true
            else -> "newest" to false
        }
        setFeedSort(sort, featured)
    }

    fun setFeedTag(tag: String?) {
        if (_state.value.feedTag == tag) return
        _state.value = _state.value.copy(feedTag = tag)
        loadInitial(force = true)
    }

    fun addDraftTag(raw: String) {
        if (_state.value.busy) return
        updateDraft { it.copy(draftTags = it.draftTags.withCommunityTag(raw)) }
    }

    fun removeDraftTag(tag: String) {
        if (_state.value.busy) return
        updateDraft { it.copy(draftTags = it.draftTags - tag) }
    }



    fun toggleFollow(userId: Long) = people.toggleFollow(userId)
    fun loadAuthorProfile(userId: Long) = people.openProfile(userId, authorOnly = true)
    fun loadMoreProfilePosts() = people.morePosts()

    fun refreshLatestSilently() = feed.refreshLatestSilently()

    fun loadMore() = feed.loadMore()

    fun loadPresetImages() {
        if (!service.isConfigured) return
        viewModelScope.launch {
            try {
                val list = PresetImageService.fetchPresetImages() ?: return@launch
                _state.value = _state.value.copy(
                    presetImages = list.images,
                    presetDisclaimer = list.disclaimer,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // 预设图片为可选辅助资源，加载失败不打断社区主流程。
            }
        }
    }

    fun updateDraftText(value: String) {
        if (_state.value.busy) return
        val maxLength = _state.value.limits.postTextLength
        updateDraft { it.copy(draftText = value.take(maxLength)) }
    }

    fun addDraftImages(uris: List<Uri>) = importDraftAttachments(uris)

    fun removeDraftImage(uri: Uri) {
        if (_state.value.busy || _state.value.importingImages) return
        updateDraft { it.copy(draftImages = it.draftImages - uri) }
        viewModelScope.launch { withContext(NonCancellable) { draftImages.release(listOf(uri)) } }
    }

    fun addDraftPresetMedia(name: String) {
        if (name.isBlank()) return
        val snapshot = _state.value
        if (snapshot.busy || snapshot.importingImages) return
        if (name in snapshot.draftPresetMedia) return
        val maxImages = snapshot.limits.postImages
        val totalSelected = snapshot.draftImages.size + snapshot.draftPresetMedia.size
        if (totalSelected >= maxImages) return
        updateDraft { it.copy(draftPresetMedia = snapshot.draftPresetMedia + name) }
    }

    /**
     * 替换整个预设贴纸选择（用于多选对话框确认时同步完整选中状态，包括取消选择）。
     */
    fun setDraftPresetMedia(names: Set<String>) {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.importingImages) return
        val maxImages = snapshot.limits.postImages
        val remaining = (maxImages - snapshot.draftImages.size).coerceAtLeast(0)
        updateDraft { it.copy(draftPresetMedia = names.filter(String::isNotBlank).take(remaining)) }
    }

    fun removeDraftPresetMedia(name: String) {
        if (_state.value.busy || _state.value.importingImages) return
        updateDraft { it.copy(draftPresetMedia = it.draftPresetMedia - name) }
    }

    /**
     * 将裁剪后的预设图片转为普通上传图片（从 draftPresetMedia 移到 draftImages）。
     * 先保留到草稿目录，成功后才替换预设图片。
     */
    fun convertPresetToCropped(name: String, croppedUri: Uri) {
        importDraftAttachments(listOf(croppedUri), replacingPreset = name)
    }

    /**
     * 移动草稿图片顺序（-1 左移，+1 右移）。
     */
    fun moveDraftImage(index: Int, direction: Int) {
        if (_state.value.busy || _state.value.importingImages) return
        val list = _state.value.draftImages.toMutableList()
        val target = index + direction
        if (index !in list.indices || target !in list.indices) return
        java.util.Collections.swap(list, index, target)
        updateDraft { it.copy(draftImages = list) }
    }

    /**
     * 移动预设贴纸顺序（-1 左移，+1 右移）。
     */
    fun moveDraftPresetMedia(index: Int, direction: Int) {
        if (_state.value.busy || _state.value.importingImages) return
        val list = _state.value.draftPresetMedia.toMutableList()
        val target = index + direction
        if (index !in list.indices || target !in list.indices) return
        java.util.Collections.swap(list, index, target)
        updateDraft { it.copy(draftPresetMedia = list) }
    }

    fun publish() = composer.publish()

    fun deletePost(post: CommunityPost) = runBusy("正在删除…") {
        request { service.deletePost(session, post.id) }
        _state.value = _state.value.copy(
            posts = _state.value.posts.filterNot { it.id == post.id },
            profilePosts = _state.value.profilePosts.filterNot { it.id == post.id },
            searchResults = _state.value.searchResults.filterNot { it.id == post.id },
            selectedPost = _state.value.selectedPost?.takeUnless { it.id == post.id },
        )
        CommunityActivityChanges.delete(session.token, post.id)
        "帖子已删除"
    }

    fun reportPost(post: CommunityPost, reason: String, details: String) = runBusy("正在提交举报…") {
        request { service.reportPost(session, post.id, reason, details) }
        _state.value = _state.value.copy(
            posts = _state.value.posts.filterNot { it.author.userId == post.author.userId },
            selectedProfile = null,
        )
        "已临时隐藏该作者近 7 天及后续帖子，等待管理员复审"
    }

    fun joinGroup(code: String) = runBusy("正在校验群码…") {
        val group = request { service.joinGroup(session, code) }
        val message = "已加入 ${group.name}"
        confirmed(message)
        val current = _state.value.bootstrap ?: error("社区尚未加载")
        _state.value = _state.value.copy(bootstrap = current.copy(group = group))
        request { loadFeedNow(session, group.id) }
        message
    }

    fun setReceiveMessages(enabled: Boolean) = runBusy("正在保存群设置…") {
        val current = _state.value.bootstrap ?: error("社区尚未加载")
        val group = request { service.updateGroupPreferences(session, current.group.id, enabled) }
        feed.cancel()
        _state.value = _state.value.copy(bootstrap = (_state.value.bootstrap ?: current).copy(group = group))
        if (enabled) "已开启群消息" else "已关闭群消息；仍可随时进入查看"
    }

    fun openProfile(userId: Long) = people.openProfile(userId)
    fun closeProfile() = people.closeProfile()

    fun updateProfile(
        nickname: String,
        signature: String,
        privateProfile: Boolean,
        showStatistics: Boolean,
        selectedStatistics: Set<String>,
        avatarUri: Uri?,
        removeAvatar: Boolean,
        presetAvatar: String? = null,
    ) {
        _state.value = _state.value.copy(profileSaved = false, profileSaveError = null)
        runBusy("正在保存个人名片…") {
            try {
                require(nickname.trim().isNotEmpty()) { "昵称不能为空" }
                val snapshot = _state.value
                val avatar = avatarUri?.let { uri ->
                    compressor.compressAvatar(uri, maxBytes = minOf(snapshot.limits.avatarBytes, 384 * 1024))
                }
                val updated = request { service.updateProfile(
                    session = session,
                    nickname = nickname,
                    signature = signature,
                    profileVisibility = if (privateProfile) "private" else "members",
                    showStatisticsCard = showStatistics,
                    statistics = statisticsPayload(localStatistics.value, selectedStatistics),
                    avatar = avatar,
                    removeAvatar = removeAvatar,
                    presetAvatar = presetAvatar,
                ) }
                check(_state.value.bootstrap != null) { "社区尚未加载" }
                _state.value = applyProfileUpdateToCommunityState(_state.value, updated, markSaved = true)
                if (avatarUri != null && updated.avatarReview?.pending == true) "头像已提交，审核通过后自动替换；原头像继续保留" else "个人名片已更新"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    profileSaveError = communityFailureMessage(error.message, "保存个人名片失败，请重试"),
                )
                throw error
            }
        }
    }

    fun consumeProfileSaved() {
        _state.value = _state.value.copy(profileSaved = false, profileSaveError = null)
    }

    fun clearProfileSaveError() {
        _state.value = _state.value.copy(profileSaveError = null)
    }

    fun openReviews() = moderation.openReviews()
    fun closeReviews() = moderation.closeReviews()
    fun refreshReviews() = moderation.reviews()
    fun resolveReview(review: CommunityReviewCase, confirmViolation: Boolean, note: String) = moderation.resolve(review.id, confirmViolation, note)

    private fun updatePostEverywhere(postId: Long, update: (CommunityPost) -> CommunityPost) = reactions.updatePost(postId, update)
    fun likePost(postId: Long) = reactions.likePost(postId)
    fun likeComment(commentId: Long) = reactions.likeComment(commentId)
    fun favoritePost(postId: Long) = reactions.favoritePost(postId)

    fun featurePost(postId: Long, feature: Boolean) = runBusy(if (feature) "正在设为精华…" else "正在取消精华…", admin = true) {
        request { service.featurePost(session, postId, feature) }
        updatePostEverywhere(postId) { it.copy(isFeatured = feature) }
        if (feature) "已设为精华" else "已取消精华"
    }

    fun blockUser(userId: Long) = runBusy("正在屏蔽…") {
        request { service.blockUser(session, userId) }
        people.invalidateBlocks()
        _state.value = _state.value.copy(
            posts = _state.value.posts.filterNot { it.author.userId == userId },
        )
        "已屏蔽该用户"
    }

    fun unblockUser(userId: Long) = runBusy("正在取消屏蔽…") {
        request { service.unblockUser(session, userId) }
        people.invalidateBlocks()
        _state.value = _state.value.copy(blocks = _state.value.blocks.filterNot { it.userId == userId })
        "已取消屏蔽"
    }

    fun refreshBlocks() = people.loadBlocks()
    fun closeBlocks() = people.closeBlocks()

    fun openFollowList(userId: Long, direction: String) = people.openFollows(userId, direction)
    fun setFollowListDirection(direction: String) = people.direction(direction)
    fun loadMoreFollowList() = people.loadFollows()
    fun closeFollowList() = people.closeFollows()

    fun editPost(postId: Long, content: String) = runBusy("正在保存…") {
        request { service.editPost(session, postId, content) }
        updatePostEverywhere(postId) { it.copy(content = content.trim()) }
        "帖子已更新"
    }

    fun editComment(commentId: Long, content: String) = thread.edit(commentId, content)

    fun search(keyword: String, sort: String = "newest", featuredOnly: Boolean = false) = searchController.search(keyword, sort, featuredOnly)
    fun clearSearch() = searchController.clear()

    fun refreshStats() = moderation.stats()
    fun exportAudit() = moderation.audit()
    fun clearAuditExport() = moderation.clearAudit()
    fun closeStats() = moderation.closeStats()

    fun pinPost(postId: Long, pin: Boolean) = runBusy(if (pin) "正在置顶…" else "正在取消置顶…", admin = true) {
        request { service.pinPost(session, postId, pin) }
        updatePostEverywhere(postId) { it.copy(isPinned = pin) }
        if (pin) "帖子已置顶" else "已取消置顶"
    }

    fun openComments(post: CommunityPost) = thread.open(post)
    fun closeComments() = thread.close()
    fun loadPostDetail(postId: Long) = thread.loadDetail(postId)
    fun setCommentSort(sort: String) = thread.setSort(sort)
    fun loadComments(postId: Long, sort: String = "newest") = thread.loadComments(postId, sort)
    fun toggleReplies(parentId: Long) = thread.toggleReplies(parentId)
    fun loadReplies(parentId: Long, sort: String, append: Boolean = false) = thread.loadReplies(parentId, sort, append)
    fun updateCommentDraft(value: String) = thread.updateDraft(value)
    fun addComment(text: String) = thread.add(text)
    fun replyComment(commentId: Long, text: String) = thread.reply(commentId, text)
    fun startReply(commentId: Long) = thread.startReply(commentId)
    fun updateReplyDraft(value: String) = thread.updateReplyDraft(value)
    fun cancelReply() = thread.cancelReply()
    fun deleteComment(commentId: Long) = thread.delete(commentId)

    fun openModerationLog() = administration.logs()
    fun closeModerationLog() = administration.closeLogs()
    fun refreshModerationLog() = administration.logs()
    fun refreshResolvedReviews() = administration.reviews()
    fun closeResolvedReviews() = administration.closeReviews()
    fun overrideReview(review: CommunityResolvedReview, confirmViolation: Boolean, note: String) = administration.overrideReview(review.id, confirmViolation, note)
    fun refreshUsers(query: String? = null, role: String? = null) = administration.users(query, role)
    fun closeUsers() = administration.closeUsers()
    fun setUserAdmin(userId: Long, isAdmin: Boolean) = administration.setAdmin(userId, isAdmin)
    fun refreshSensitiveWords() = administration.refreshWords()
    fun searchSensitiveWords(query: String) = administration.searchWords(query)
    fun loadSensitiveWordsPage(page: Int) = administration.wordPage(page)
    fun cancelSensitiveWordsLoading() = administration.cancelWords()
    fun closeSensitiveWords() = administration.closeWords()
    fun addSensitiveWord(word: String) = administration.addWord(word)
    fun deleteSensitiveWord(id: Long) = administration.deleteWord(id)
    fun importSensitiveWords(words: List<String>) = administration.importWords(words)

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun clearPublishedPost() {
        _state.value = _state.value.copy(publishedPostId = null)
    }

    private fun loadInitial(force: Boolean = false) = feed.loadInitial(force)

    private suspend fun loadFeedNow(session: CloudSession, groupId: Long) = feed.reloadAfterWrite(session, groupId)

    private fun runBusy(label: String, admin: Boolean = false, block: suspend CommunityMutation.() -> String?) =
        mutations.run(label, admin, block)

    private fun handleFailure(
        error: Throwable,
        fallback: String,
        updateState: Boolean = true,
    ): String {
        if (error is CloudSessionExpiredException) {
            if (!error.isFor(_state.value.session) || service.loadSession() != null) {
                return "账号状态已更新，请重试此操作"
            }
            feed.cancel(); thread.cancel(); administration.cancel(); composer.cancel(); people.cancel(); searchController.clear(); mutations.cancel(); reactions.cancel(); moderation.cancel()
            val draft = _state.value
            val message = communityFailureMessage(error.message, "登录会话已失效，请重新登录")
            _state.value = CommunityUiState(
                configured = service.isConfigured,
                session = null,
                draftText = draft.draftText,
                draftImages = draft.draftImages,
                draftPresetMedia = draft.draftPresetMedia,
                draftTags = draft.draftTags,
                importingImages = draft.importingImages,
                message = message,
            )
            return message
        }
        val message = communityFailureMessage(error.message, fallback)
        if (updateState) {
            _state.value = _state.value.copy(message = message)
        }
        return message
    }

    private fun setMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }


}

internal fun statisticsPayload(
    statistics: StatisticsUiState,
    selectedKeys: Set<String>,
): Map<String, Double> = buildMap {
    if (CommunityStatisticMetric.TOTAL_ITEMS.key in selectedKeys) {
        put(CommunityStatisticMetric.TOTAL_ITEMS.key, statistics.totalAnime.toDouble())
    }
    if (CommunityStatisticMetric.ANIME_COUNT.key in selectedKeys) {
        put(CommunityStatisticMetric.ANIME_COUNT.key, statistics.animeCount.toDouble())
    }
    if (CommunityStatisticMetric.BOOK_COUNT.key in selectedKeys) {
        put(CommunityStatisticMetric.BOOK_COUNT.key, statistics.bookCount.toDouble())
    }
    if (CommunityStatisticMetric.WATCHED_EPISODES.key in selectedKeys) {
        put(CommunityStatisticMetric.WATCHED_EPISODES.key, statistics.watchedEpisodes.toDouble())
    }
    if (CommunityStatisticMetric.ESTIMATED_MINUTES.key in selectedKeys) {
        put(CommunityStatisticMetric.ESTIMATED_MINUTES.key, statistics.watchMinutes.toDouble())
    }
    if (CommunityStatisticMetric.AVERAGE_RATING.key in selectedKeys) {
        statistics.averageRating?.let { put(CommunityStatisticMetric.AVERAGE_RATING.key, it) }
    }
    if (CommunityStatisticMetric.STREAK_DAYS.key in selectedKeys) {
        put(CommunityStatisticMetric.STREAK_DAYS.key, statistics.currentStreakDays.toDouble())
    }
}

internal fun applyProfileUpdateToCommunityState(
    state: CommunityUiState,
    updated: CommunityProfile,
    markSaved: Boolean,
): CommunityUiState = state.copy(
    bootstrap = state.bootstrap?.let { bootstrap ->
        if (bootstrap.currentUser?.userId == updated.userId || state.session?.userId == updated.userId) {
            bootstrap.copy(currentUser = updated)
        } else {
            bootstrap
        }
    },
    posts = state.posts.map { post ->
        if (post.author.userId == updated.userId) post.copy(author = updated) else post
    },
    profilePosts = state.profilePosts.map { post ->
        if (post.author.userId == updated.userId) post.copy(author = updated) else post
    },
    selectedProfile = if (state.selectedProfile?.userId == updated.userId) updated else state.selectedProfile,
    profileSaved = if (markSaved) true else state.profileSaved,
    profileSaveError = if (markSaved) null else state.profileSaveError,
)
