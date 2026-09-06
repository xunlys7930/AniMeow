package com.animeow.app.ui.community

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.CommunityBootstrap
import com.animeow.app.data.community.CommunityComment
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
import com.animeow.app.data.community.SensitiveWord
import com.animeow.app.data.community.PresetImage
import com.animeow.app.data.community.PresetImageService
import com.animeow.app.ui.statistics.StatisticsUiState
import com.animeow.app.ui.statistics.calculateStatistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val selectedProfile: CommunityProfile? = null,
    val profilePosts: List<CommunityPost> = emptyList(),
    val reviews: List<CommunityReviewCase> = emptyList(),
    val showingReviews: Boolean = false,
    val resolvedReviews: List<CommunityResolvedReview> = emptyList(),
    val users: List<CommunityProfile> = emptyList(),
    val sensitiveWords: List<SensitiveWord> = emptyList(),
    val sensitiveWordsTotal: Int = 0,
    val sensitiveWordsPage: Int = 1,
    val sensitiveWordsSearch: String = "",
    val sensitiveWordsLoading: Boolean = false,
    val blocks: List<CommunityProfile> = emptyList(),
    val searchResults: List<CommunityPost> = emptyList(),
    val searching: Boolean = false,
    val stats: CommunityStats? = null,
    val auditExport: String? = null,
    val selectedPost: CommunityPost? = null,
    val comments: List<CommunityComment> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentDraft: String = "",
    val commentSort: String = "newest",
    val replies: Map<Long, List<CommunityComment>> = emptyMap(),
    val feedSort: String = "newest",
    val feedFeaturedOnly: Boolean = false,
    val moderationLog: List<CommunityModerationLogEntry> = emptyList(),
    val presetImages: List<PresetImage> = emptyList(),
    val presetDisclaimer: String = "",
    val message: String? = null,
) {
    val group: CommunityGroup? get() = bootstrap?.group
    val currentUser: CommunityProfile? get() = bootstrap?.currentUser
    val limits: CommunityLimits get() = bootstrap?.limits ?: CommunityLimits()
}

class CommunityViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val service = CommunityService(application)
    private val compressor = CommunityImageCompressor(application)
    private val _state = MutableStateFlow(
        CommunityUiState(
            configured = service.isConfigured,
            session = service.loadSession(),
        ),
    )
    val state: StateFlow<CommunityUiState> = _state.asStateFlow()

    val localStatistics: StateFlow<StatisticsUiState> = combine(
        app.libraryRepository.observeAnimes(),
        app.libraryRepository.observeTags(),
        app.libraryRepository.observeAnimeTags(),
        app.libraryRepository.observeWatchStatuses(),
        app.libraryRepository.observeWatchRecords(),
    ) { animes, tags, links, statuses, records ->
        calculateStatistics(animes, tags, links, records, statuses)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatisticsUiState(),
    )

    init {
        if (_state.value.configured) loadInitial()
        loadPresetImages()
    }

    fun refreshSession() {
        val session = service.loadSession()
        val current = _state.value.session
        if (session?.token != current?.token) {
            _state.value = CommunityUiState(
                configured = service.isConfigured,
                session = session,
            )
        }
        if (service.isConfigured && _state.value.bootstrap == null && !_state.value.loading) {
            loadInitial()
        }
        loadPresetImages()
    }

    fun refresh() = loadInitial(force = true)

    fun setFeedSort(sort: String, featuredOnly: Boolean) {
        _state.value = _state.value.copy(feedSort = sort, feedFeaturedOnly = featuredOnly)
        loadInitial(force = true)
    }

    fun refreshLatestSilently() {
        val snapshot = _state.value
        val group = snapshot.group ?: return
        if (snapshot.loading || snapshot.loadingMore || snapshot.busy) return
        viewModelScope.launch {
            try {
                val page = service.listPosts(snapshot.session, group.id, snapshot.feedSort, snapshot.feedFeaturedOnly)
                val firstPageIds = page.items.mapTo(hashSetOf(), CommunityPost::id)
                val oldestFirstPageId = page.items.minOfOrNull(CommunityPost::id)
                val olderItems = if (oldestFirstPageId == null) {
                    emptyList()
                } else {
                    _state.value.posts.filter { it.id < oldestFirstPageId && it.id !in firstPageIds }
                }
                val bootstrap = _state.value.bootstrap
                _state.value = _state.value.copy(
                    bootstrap = bootstrap?.copy(group = page.group),
                    posts = page.items + olderItems,
                    nextBeforeId = _state.value.nextBeforeId ?: page.nextBeforeId,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: CloudSessionExpiredException) {
                handleFailure(error, "登录会话已失效")
            } catch (_: Throwable) {
                // 后台轮询失败不打断阅读；手动刷新仍会显示明确错误。
            }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        val group = snapshot.group ?: return
        val beforeId = snapshot.nextBeforeId ?: return
        if (snapshot.loadingMore || snapshot.loading || snapshot.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            try {
                val page = service.listPosts(snapshot.session, group.id, snapshot.feedSort, snapshot.feedFeaturedOnly, beforeId, 20)
                val known = _state.value.posts.asSequence().map(CommunityPost::id).toHashSet()
                _state.value = _state.value.copy(
                    posts = _state.value.posts + page.items.filterNot { it.id in known },
                    nextBeforeId = page.nextBeforeId,
                    loadingMore = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "加载更多失败", loadingMore = false)
            }
        }
    }

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
        val maxLength = _state.value.limits.postTextLength
        _state.value = _state.value.copy(draftText = value.take(maxLength))
    }

    fun setDraftImages(uris: List<Uri>) {
        val maxImages = _state.value.limits.postImages
        _state.value = _state.value.copy(draftImages = uris.distinct().take(maxImages))
    }

    fun removeDraftImage(uri: Uri) {
        _state.value = _state.value.copy(draftImages = _state.value.draftImages - uri)
    }

    fun addDraftPresetMedia(name: String) {
        if (name.isBlank()) return
        val snapshot = _state.value
        if (name in snapshot.draftPresetMedia) return
        val maxImages = snapshot.limits.postImages
        val totalSelected = snapshot.draftImages.size + snapshot.draftPresetMedia.size
        if (totalSelected >= maxImages) return
        _state.value = _state.value.copy(draftPresetMedia = snapshot.draftPresetMedia + name)
    }

    /**
     * 替换整个预设贴纸选择（用于多选对话框确认时同步完整选中状态，包括取消选择）。
     */
    fun setDraftPresetMedia(names: Set<String>) {
        val snapshot = _state.value
        val maxImages = snapshot.limits.postImages
        val remaining = maxImages - snapshot.draftImages.size
        _state.value = _state.value.copy(
            draftPresetMedia = names.filter { it.isNotBlank() }.distinct().take(remaining),
        )
    }

    fun removeDraftPresetMedia(name: String) {
        _state.value = _state.value.copy(
            draftPresetMedia = _state.value.draftPresetMedia.filterNot { it == name },
        )
    }

    /**
     * 将裁剪后的预设图片转为普通上传图片（从 draftPresetMedia 移到 draftImages）。
     * 裁剪后的图片以缓存文件 Uri 形式存在，和用户本地选择的图片走同一上传通道。
     */
    fun convertPresetToCropped(name: String, croppedUri: Uri) {
        val snapshot = _state.value
        val maxImages = snapshot.limits.postImages
        val totalSelected = snapshot.draftImages.size + snapshot.draftPresetMedia.size
        if (totalSelected >= maxImages && name !in snapshot.draftPresetMedia) return
        _state.value = _state.value.copy(
            draftImages = snapshot.draftImages + croppedUri,
            draftPresetMedia = snapshot.draftPresetMedia.filterNot { it == name },
        )
    }

    /**
     * 移动草稿图片顺序（-1 左移，+1 右移）。
     */
    fun moveDraftImage(index: Int, direction: Int) {
        val list = _state.value.draftImages.toMutableList()
        val target = index + direction
        if (target < 0 || target >= list.size) return
        java.util.Collections.swap(list, index, target)
        _state.value = _state.value.copy(draftImages = list)
    }

    /**
     * 移动预设贴纸顺序（-1 左移，+1 右移）。
     */
    fun moveDraftPresetMedia(index: Int, direction: Int) {
        val list = _state.value.draftPresetMedia.toMutableList()
        val target = index + direction
        if (target < 0 || target >= list.size) return
        java.util.Collections.swap(list, index, target)
        _state.value = _state.value.copy(draftPresetMedia = list)
    }

    fun publish() {
        val snapshot = _state.value
        val session = snapshot.session ?: return setMessage("请先登录云账号")
        val group = snapshot.group ?: return setMessage("群聊尚未加载")
        if (snapshot.busy) return
        if (snapshot.draftText.isBlank() && snapshot.draftImages.isEmpty() && snapshot.draftPresetMedia.isEmpty()) {
            return setMessage("写点内容，或选择图片后再发布")
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "正在压缩并发布…", message = null)
            try {
                val perImageLimit = minOf(snapshot.limits.imageBytes, 768 * 1024)
                val images = snapshot.draftImages.map { uri ->
                    compressor.compressPostImage(uri, maxBytes = perImageLimit)
                }
                val totalBytes = images.sumOf { it.bytes.size }
                require(totalBytes <= snapshot.limits.postMediaBytes) { "所选图片合计仍然过大，请减少图片数量" }
                val post = service.createPost(
                    session = session,
                    groupId = group.id,
                    content = snapshot.draftText,
                    images = images,
                    presetMedia = snapshot.draftPresetMedia,
                )
                _state.value = _state.value.copy(
                    posts = listOf(post) + _state.value.posts.filterNot { it.id == post.id },
                    draftText = "",
                    draftImages = emptyList(),
                    draftPresetMedia = emptyList(),
                    busy = false,
                    busyLabel = null,
                    message = if (post.isQuarantined) {
                        "帖子已保存，管理员复审完成前仅你自己可见"
                    } else {
                        "发布成功"
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "发布失败")
            }
        }
    }

    fun deletePost(post: CommunityPost) = runBusy("正在删除…") { session ->
        service.deletePost(session, post.id)
        _state.value = _state.value.copy(posts = _state.value.posts.filterNot { it.id == post.id })
        "帖子已删除"
    }

    fun reportPost(post: CommunityPost, reason: String, details: String) = runBusy("正在提交举报…") { session ->
        service.reportPost(session, post.id, reason, details)
        _state.value = _state.value.copy(
            posts = _state.value.posts.filterNot { it.author.userId == post.author.userId },
            selectedProfile = null,
        )
        "已临时隐藏该作者近 7 天及后续帖子，等待管理员复审"
    }

    fun joinGroup(code: String) = runBusy("正在校验群码…") { session ->
        val group = service.joinGroup(session, code)
        val current = _state.value.bootstrap ?: error("社区尚未加载")
        _state.value = _state.value.copy(bootstrap = current.copy(group = group))
        loadFeedNow(session, group.id)
        "已加入 ${group.name}"
    }

    fun setReceiveMessages(enabled: Boolean) = runBusy("正在保存群设置…") { session ->
        val current = _state.value.bootstrap ?: error("社区尚未加载")
        val group = service.updateGroupPreferences(session, current.group.id, enabled)
        _state.value = _state.value.copy(bootstrap = current.copy(group = group))
        if (enabled) "已开启群消息" else "已关闭群消息；仍可随时进入查看"
    }

    fun openProfile(userId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            try {
                val profile = if (userId == _state.value.currentUser?.userId) {
                    _state.value.currentUser
                } else {
                    service.getProfile(session, userId)
                }
                val posts = runCatching { service.listUserPosts(session, userId).items }
                    .getOrDefault(emptyList())
                _state.value = _state.value.copy(selectedProfile = profile, profilePosts = posts)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取个人名片失败")
            }
        }
    }

    fun closeProfile() {
        _state.value = _state.value.copy(selectedProfile = null, profilePosts = emptyList())
    }

    fun updateProfile(
        nickname: String,
        signature: String,
        privateProfile: Boolean,
        showStatistics: Boolean,
        selectedStatistics: Set<String>,
        avatarUri: Uri?,
        removeAvatar: Boolean,
        presetAvatar: String? = null,
    ) = runBusy("正在保存个人名片…") { session ->
        require(nickname.trim().isNotEmpty()) { "昵称不能为空" }
        val snapshot = _state.value
        val avatar = avatarUri?.let { uri ->
            compressor.compressAvatar(uri, maxBytes = minOf(snapshot.limits.avatarBytes, 384 * 1024))
        }
        val updated = service.updateProfile(
            session = session,
            nickname = nickname,
            signature = signature,
            profileVisibility = if (privateProfile) "private" else "members",
            showStatisticsCard = showStatistics,
            statistics = statisticsPayload(localStatistics.value, selectedStatistics),
            avatar = avatar,
            removeAvatar = removeAvatar,
            presetAvatar = presetAvatar,
        )
        val bootstrap = _state.value.bootstrap ?: error("社区尚未加载")
        _state.value = _state.value.copy(
            bootstrap = bootstrap.copy(currentUser = updated),
            posts = _state.value.posts.map { post ->
                if (post.author.userId == updated.userId) post.copy(author = updated) else post
            },
            selectedProfile = updated,
        )
        "个人名片已更新"
    }

    fun openReviews() {
        if (_state.value.currentUser?.isAdmin != true) return
        _state.value = _state.value.copy(showingReviews = true)
        refreshReviews()
    }

    fun closeReviews() {
        _state.value = _state.value.copy(showingReviews = false)
    }

    fun refreshReviews() {
        val session = _state.value.session ?: return
        if (_state.value.currentUser?.isAdmin != true || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "正在读取待复审内容…")
            try {
                _state.value = _state.value.copy(
                    reviews = service.listReviews(session),
                    busy = false,
                    busyLabel = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取待复审内容失败")
            }
        }
    }

    fun resolveReview(review: CommunityReviewCase, confirmViolation: Boolean, note: String) =
        runBusy("正在提交复审结论…") { session ->
            service.resolveReview(session, review.id, confirmViolation, note)
            _state.value = _state.value.copy(reviews = _state.value.reviews.filterNot { it.id == review.id })
            loadFeedNow(session, _state.value.group?.id ?: return@runBusy "复审已完成")
            if (confirmViolation) {
                "已移除被举报内容，并恢复其余帖子"
            } else {
                "举报未确认，已恢复全部临时隐藏内容"
            }
        }

    fun likePost(postId: Long) {
        val session = _state.value.session ?: return setMessage("请先登录云账号")
        viewModelScope.launch {
            try {
                val liked = service.like(session, "post", postId)
                _state.value = _state.value.copy(
                    posts = _state.value.posts.map { post ->
                        if (post.id == postId) {
                            post.copy(
                                likedByViewer = liked,
                                likeCount = (post.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
                            )
                        } else {
                            post
                        }
                    },
                    selectedPost = _state.value.selectedPost?.let { post ->
                        if (post.id == postId) {
                            post.copy(
                                likedByViewer = liked,
                                likeCount = (post.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
                            )
                        } else {
                            post
                        }
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "点赞失败")
            }
        }
    }

    fun likeComment(commentId: Long) {
        val session = _state.value.session ?: return setMessage("请先登录云账号")
        viewModelScope.launch {
            try {
                val liked = service.like(session, "comment", commentId)
                val update: (CommunityComment) -> CommunityComment = { comment ->
                    if (comment.id == commentId) {
                        comment.copy(
                            likedByViewer = liked,
                            likeCount = (comment.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
                        )
                    } else {
                        comment
                    }
                }
                _state.value = _state.value.copy(
                    comments = _state.value.comments.map(update),
                    replies = _state.value.replies.mapValues { (_, list) -> list.map(update) },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "点赞失败")
            }
        }
    }

    fun favoritePost(postId: Long) {
        val session = _state.value.session ?: return setMessage("请先登录云账号")
        viewModelScope.launch {
            try {
                val favorited = service.favoritePost(session, postId)
                _state.value = _state.value.copy(
                    posts = _state.value.posts.map { post ->
                        if (post.id == postId) post.copy(isFavorited = favorited) else post
                    },
                    selectedPost = _state.value.selectedPost?.let { post ->
                        if (post.id == postId) post.copy(isFavorited = favorited) else post
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "收藏失败")
            }
        }
    }

    fun featurePost(postId: Long, feature: Boolean) = runBusy(if (feature) "正在设为精华…" else "正在取消精华…") { session ->
        service.featurePost(session, postId, feature)
        _state.value = _state.value.copy(
            posts = _state.value.posts.map {
                if (it.id == postId) it.copy(isFeatured = feature) else it
            },
        )
        if (feature) "已设为精华" else "已取消精华"
    }

    fun blockUser(userId: Long) = runBusy("正在屏蔽…") { session ->
        service.blockUser(session, userId)
        _state.value = _state.value.copy(
            posts = _state.value.posts.filterNot { it.author.userId == userId },
        )
        "已屏蔽该用户"
    }

    fun unblockUser(userId: Long) = runBusy("正在取消屏蔽…") { session ->
        service.unblockUser(session, userId)
        _state.value = _state.value.copy(blocks = _state.value.blocks.filterNot { it.userId == userId })
        "已取消屏蔽"
    }

    fun refreshBlocks() {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(blocks = service.listBlocks(session))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取黑名单失败")
            }
        }
    }

    fun editPost(postId: Long, content: String) = runBusy("正在保存…") { session ->
        service.editPost(session, postId, content)
        _state.value = _state.value.copy(
            posts = _state.value.posts.map { if (it.id == postId) it.copy(content = content.trim()) else it },
        )
        "帖子已更新"
    }

    fun editComment(commentId: Long, content: String) = runBusy("正在保存…") { session ->
        service.editComment(session, commentId, content)
        _state.value = _state.value.copy(
            comments = _state.value.comments.map { if (it.id == commentId) it.copy(content = content.trim()) else it },
        )
        "评论已更新"
    }

    fun search(keyword: String, sort: String = "newest", featuredOnly: Boolean = false) {
        val session = _state.value.session ?: return
        val clean = keyword.trim()
        if (clean.isEmpty()) {
            _state.value = _state.value.copy(searchResults = emptyList())
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true)
            try {
                val page = service.searchPosts(session, clean, sort, featuredOnly)
                _state.value = _state.value.copy(searchResults = page.items, searching = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = handleFailure(error, "搜索失败", updateState = false)
                _state.value = _state.value.copy(searching = false, message = message)
            }
        }
    }

    fun clearSearch() {
        _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
    }

    fun refreshStats() {
        val session = _state.value.session ?: return
        if (_state.value.currentUser?.isAdmin != true) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(stats = service.communityStats(session))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取统计看板失败")
            }
        }
    }

    fun exportAudit() {
        val session = _state.value.session ?: return
        if (_state.value.currentUser?.isAdmin != true) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(auditExport = service.exportAudit(session))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "导出审计失败")
            }
        }
    }

    fun clearAuditExport() {
        _state.value = _state.value.copy(auditExport = null)
    }

    fun pinPost(postId: Long, pin: Boolean) = runBusy(if (pin) "正在置顶…" else "正在取消置顶…") { session ->
        service.pinPost(session, postId, pin)
        _state.value = _state.value.copy(
            posts = _state.value.posts.map { if (it.id == postId) it.copy(isPinned = pin) else it },
        )
        if (pin) "帖子已置顶" else "已取消置顶"
    }

    fun openComments(post: CommunityPost) {
        _state.value = _state.value.copy(
            selectedPost = post,
            comments = emptyList(),
            commentDraft = "",
            commentSort = "newest",
            replies = emptyMap(),
        )
        loadComments(post.id, "newest")
    }

    fun closeComments() {
        _state.value = _state.value.copy(
            selectedPost = null, comments = emptyList(), commentDraft = "", replies = emptyMap(),
        )
    }

    fun loadPostDetail(postId: Long) {
        val session = _state.value.session
        if (session == null) {
            setMessage("请先登录云账号")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            try {
                val post = service.getPost(session, postId)
                _state.value = _state.value.copy(
                    selectedPost = post,
                    loading = false,
                    commentSort = "newest",
                    replies = emptyMap(),
                    commentDraft = "",
                )
                loadCommentsNow(postId, "newest")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取帖子详情失败", loading = false)
            }
        }
    }

    fun setCommentSort(sort: String) {
        val post = _state.value.selectedPost ?: return
        _state.value = _state.value.copy(commentSort = sort)
        loadComments(post.id, sort)
    }

    fun loadComments(postId: Long, sort: String = "newest") {
        viewModelScope.launch {
            _state.value = _state.value.copy(commentsLoading = true)
            try {
                loadCommentsNow(postId, sort)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取评论失败", updateState = false)
                _state.value = _state.value.copy(commentsLoading = false)
            }
        }
    }

    fun loadReplies(parentId: Long, sort: String = "newest") {
        val session = _state.value.session ?: return
        val post = _state.value.selectedPost ?: return
        viewModelScope.launch {
            try {
                val page = service.listComments(session, post.id, parentId, sort)
                _state.value = _state.value.copy(
                    replies = _state.value.replies + (parentId to page.items),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取回复失败")
            }
        }
    }

    fun updateCommentDraft(value: String) {
        _state.value = _state.value.copy(commentDraft = value.take(1000))
    }

    fun addComment(text: String) {
        val session = _state.value.session ?: return setMessage("请先登录云账号")
        val post = _state.value.selectedPost ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                service.createComment(session, post.id, clean)
                _state.value = _state.value.copy(
                    commentDraft = "",
                    posts = _state.value.posts.map {
                        if (it.id == post.id) it.copy(commentCount = it.commentCount + 1) else it
                    },
                    selectedPost = post.copy(commentCount = post.commentCount + 1),
                )
                loadCommentsNow(post.id, _state.value.commentSort)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "评论失败")
            }
        }
    }

    fun replyComment(commentId: Long, text: String) {
        val session = _state.value.session ?: return setMessage("请先登录云账号")
        val post = _state.value.selectedPost ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                service.replyComment(session, commentId, clean)
                _state.value = _state.value.copy(
                    posts = _state.value.posts.map {
                        if (it.id == post.id) it.copy(commentCount = it.commentCount + 1) else it
                    },
                    selectedPost = post.copy(commentCount = post.commentCount + 1),
                    comments = _state.value.comments.map {
                        if (it.id == commentId) it.copy(replyCount = it.replyCount + 1) else it
                    },
                )
                loadReplies(commentId, "newest")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "回复失败")
            }
        }
    }

    fun deleteComment(commentId: Long) {
        val session = _state.value.session ?: return setMessage("请先登录云账号")
        viewModelScope.launch {
            try {
                service.deleteComment(session, commentId)
                val selected = _state.value.selectedPost
                _state.value = _state.value.copy(
                    comments = _state.value.comments.filterNot { it.id == commentId },
                    replies = _state.value.replies.mapValues { (_, list) -> list.filterNot { it.id == commentId } },
                    posts = _state.value.posts.map { post ->
                        if (post.id == selected?.id) {
                            post.copy(commentCount = (post.commentCount - 1).coerceAtLeast(0))
                        } else {
                            post
                        }
                    },
                    selectedPost = selected?.copy(
                        commentCount = (selected.commentCount - 1).coerceAtLeast(0),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "删除评论失败")
            }
        }
    }

    fun openModerationLog() {
        if (_state.value.currentUser?.isDeveloper != true) return
        refreshModerationLog()
    }

    fun closeModerationLog() {
        _state.value = _state.value.copy(moderationLog = emptyList())
    }

    fun refreshModerationLog() {
        val session = _state.value.session ?: return
        if (_state.value.currentUser?.isDeveloper != true) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(moderationLog = service.listModerationLog(session))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取审核记录失败")
            }
        }
    }

    fun refreshResolvedReviews() {
        val session = _state.value.session ?: return
        if (_state.value.currentUser?.isDeveloper != true) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(resolvedReviews = service.listResolvedReviews(session))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取已处理复审记录失败")
            }
        }
    }

    fun overrideReview(review: CommunityResolvedReview, confirmViolation: Boolean, note: String) =
        runBusy("正在强行修改处理结果…") { session ->
            service.overrideReview(session, review.id, confirmViolation, note)
            _state.value = _state.value.copy(
                resolvedReviews = _state.value.resolvedReviews.filterNot { it.id == review.id },
            )
            loadFeedNow(session, _state.value.group?.id ?: return@runBusy "处理结果已修改")
            "已强行修改本次举报处理结果"
        }

    fun refreshUsers(query: String? = null, role: String? = null) {
        val session = _state.value.session ?: return
        if (_state.value.currentUser?.isDeveloper != true) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(users = service.listUsers(session, query, role))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "读取用户列表失败")
            }
        }
    }

    fun setUserAdmin(userId: Long, isAdmin: Boolean) = runBusy("正在调整管理员…") { session ->
        service.setUserAdmin(session, userId, isAdmin)
        _state.value = _state.value.copy(
            users = _state.value.users.map {
                if (it.userId == userId) it.copy(isAdmin = isAdmin) else it
            },
        )
        if (isAdmin) "已任命为管理员" else "已取消管理员"
    }

    fun refreshSensitiveWords() {
        val session = _state.value.session ?: return
        if (_state.value.currentUser?.isDeveloper != true) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(sensitiveWordsLoading = true)
                val result = service.listSensitiveWords(
                    session,
                    page = _state.value.sensitiveWordsPage,
                    search = _state.value.sensitiveWordsSearch.takeIf { it.isNotBlank() },
                )
                _state.value = _state.value.copy(
                    sensitiveWords = result.words,
                    sensitiveWordsTotal = result.total,
                    sensitiveWordsLoading = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(sensitiveWordsLoading = false)
                handleFailure(error, "读取敏感词失败")
            }
        }
    }

    fun searchSensitiveWords(query: String) {
        _state.value = _state.value.copy(sensitiveWordsSearch = query, sensitiveWordsPage = 1)
        refreshSensitiveWords()
    }

    fun loadSensitiveWordsPage(page: Int) {
        _state.value = _state.value.copy(sensitiveWordsPage = page)
        refreshSensitiveWords()
    }

    fun addSensitiveWord(word: String) = runBusy("正在添加敏感词…") { session ->
        service.addSensitiveWord(session, word)
        val result = service.listSensitiveWords(
            session,
            page = _state.value.sensitiveWordsPage,
            search = _state.value.sensitiveWordsSearch.takeIf { it.isNotBlank() },
        )
        _state.value = _state.value.copy(
            sensitiveWords = result.words,
            sensitiveWordsTotal = result.total,
        )
        "敏感词已添加"
    }

    fun deleteSensitiveWord(id: Long) = runBusy("正在删除敏感词…") { session ->
        service.deleteSensitiveWord(session, id)
        _state.value = _state.value.copy(
            sensitiveWords = _state.value.sensitiveWords.filterNot { it.id == id },
            sensitiveWordsTotal = (_state.value.sensitiveWordsTotal - 1).coerceAtLeast(0),
        )
        "敏感词已删除"
    }

    fun importSensitiveWords(words: List<String>) = runBusy("正在导入敏感词…") { session ->
        val clean = words.map { it.trim() }.filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) return@runBusy "没有可导入的敏感词"
        val added = service.importSensitiveWords(session, clean)
        val result = service.listSensitiveWords(
            session,
            page = _state.value.sensitiveWordsPage,
            search = _state.value.sensitiveWordsSearch.takeIf { it.isNotBlank() },
        )
        _state.value = _state.value.copy(
            sensitiveWords = result.words,
            sensitiveWordsTotal = result.total,
        )
        "已导入 $added 个敏感词"
    }

    private suspend fun loadCommentsNow(postId: Long, sort: String = "newest") {
        _state.value = _state.value.copy(commentsLoading = true)
        val page = service.listComments(_state.value.session, postId, null, sort)
        _state.value = _state.value.copy(comments = page.items, commentsLoading = false)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun loadInitial(force: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.busy) return
        if (!force && snapshot.bootstrap != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            try {
                val bootstrap = service.bootstrap(snapshot.session)
                val page = service.listPosts(snapshot.session, bootstrap.group.id, snapshot.feedSort, snapshot.feedFeaturedOnly)
                _state.value = _state.value.copy(
                    bootstrap = bootstrap.copy(group = page.group),
                    posts = page.items,
                    nextBeforeId = page.nextBeforeId,
                    loading = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, "社区暂时无法加载", loading = false)
            }
        }
    }

    private suspend fun loadFeedNow(session: CloudSession, groupId: Long) {
        val page = service.listPosts(session, groupId, _state.value.feedSort, _state.value.feedFeaturedOnly)
        val bootstrap = _state.value.bootstrap
        _state.value = _state.value.copy(
            bootstrap = bootstrap?.copy(group = page.group),
            posts = page.items,
            nextBeforeId = page.nextBeforeId,
        )
    }

    private fun runBusy(
        label: String,
        block: suspend (CloudSession) -> String,
    ) {
        val session = _state.value.session ?: return setMessage("请先登录云账号")
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = label, message = null)
            val message = try {
                block(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleFailure(error, label.removeSuffix("…") + "失败", updateState = false)
            }
            _state.value = _state.value.copy(busy = false, busyLabel = null, message = message)
        }
    }

    private fun handleFailure(
        error: Throwable,
        fallback: String,
        loading: Boolean = _state.value.loading,
        loadingMore: Boolean = _state.value.loadingMore,
        updateState: Boolean = true,
    ): String {
        if (error is CloudSessionExpiredException) {
            _state.value = CommunityUiState(
                configured = service.isConfigured,
                session = null,
                message = error.message ?: "登录会话已失效，请重新登录",
            )
            return error.message ?: fallback
        }
        val message = error.message ?: fallback
        if (updateState) {
            _state.value = _state.value.copy(
                loading = loading,
                loadingMore = loadingMore,
                busy = false,
                busyLabel = null,
                message = message,
            )
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
        put(CommunityStatisticMetric.ESTIMATED_MINUTES.key, statistics.watchedEpisodes * 24.0)
    }
    if (CommunityStatisticMetric.AVERAGE_RATING.key in selectedKeys) {
        statistics.averageRating?.let { put(CommunityStatisticMetric.AVERAGE_RATING.key, it) }
    }
    if (CommunityStatisticMetric.STREAK_DAYS.key in selectedKeys) {
        put(CommunityStatisticMetric.STREAK_DAYS.key, statistics.currentStreakDays.toDouble())
    }
}
