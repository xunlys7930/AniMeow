package com.animeow.app.ui.community

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.ui.components.ConfirmationDialog
import com.animeow.app.ui.components.ZoomableImageViewer
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.CommunityStyle
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
internal fun CommunityProfilePostsState(loading: Boolean, failed: Boolean, error: String?, retry: () -> Unit) {
    val stateKey = when {
        loading -> "loading"
        failed -> "failed"
        else -> "empty"
    }
    val motionLevel = LocalMotionLevel.current
    AnimatedContent(
        targetState = stateKey,
        transitionSpec = { motionFadeThroughSpec(motionLevel, 180, 120) },
        label = "profilePostsState",
    ) { mode ->
        when (mode) {
            "loading" -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            "failed" -> com.animeow.app.ui.components.LoadErrorCard("帖子暂时无法读取", error ?: "请重新加载后再试", retry)
            else -> Text("还没有发布过帖子", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 个人空间：展示用户名片信息与其发布的帖子（含图片），点击帖子进入详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityUserSpaceScreen(
    userId: Long,
    onBack: () -> Unit,
    onPostClick: (CommunityPost) -> Unit,
    onUserClick: (Long) -> Unit,
    onFollowsClick: (Long, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = viewModel(),
    appearanceViewModel: AppearanceViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearance by appearanceViewModel.settings.collectAsStateWithLifecycle()
    // 形态 B：作品网格 + 动态两个页签；形态 A 保持原样。
    val styleB = appearance.communityStyle == CommunityStyle.WATERFALL
    var spaceTab by rememberSaveable { mutableStateOf(0) }
    val localStatistics by viewModel.localStatistics.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showEdit by remember { mutableStateOf(false) }
    var showBlacklist by remember { mutableStateOf(false) }
    var blockConfirm by remember { mutableStateOf(false) }
    var viewingImageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(userId) { viewModel.openProfile(userId) }
    androidx.compose.runtime.DisposableEffect(viewModel, userId) {
        onDispose { viewModel.closeProfile(); viewModel.closeBlocks() }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.profileSaved) {
        if (state.profileSaved) {
            showEdit = false
            viewModel.consumeProfileSaved()
        }
    }

    val profile = state.selectedProfile
    val isSelf = profile?.userId == state.currentUser?.userId

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (isSelf) "我的个人空间" else "个人空间") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isSelf) {
                        IconButton(onClick = {
                            showBlacklist = true
                            viewModel.refreshBlocks()
                        }) {
                            Icon(AppIcons.Block, contentDescription = "屏蔽管理")
                        }
                        IconButton(onClick = {
                            viewModel.clearProfileSaveError()
                            showEdit = true
                        }) {
                            Icon(AppIcons.Edit, contentDescription = "编辑资料")
                        }
                    } else {
                        IconButton(onClick = { blockConfirm = true }) {
                            Icon(AppIcons.Block, contentDescription = "屏蔽该用户")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profile == null) {
                item {
                    if (state.profileLoading) Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    } else com.animeow.app.ui.components.LoadErrorCard("名片暂时无法读取",
                        state.profileError ?: if (state.session == null) "请先登录云账号" else "请重新加载后再试",
                        onRetry = { viewModel.openProfile(userId) })
                }
            } else if (styleB) {
                item("header") {
                    UserSpaceHeader(profile, state.session?.token)
                }
                item("follow-and-counts") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()) {
                        CommunityFollowButton(profile, isSelf, state.followBusyUserId != null, state.session != null) {
                            viewModel.toggleFollow(profile.userId)
                        }
                        profile.stats?.let { stats ->
                            CommunityCountRow(listOf("作品" to stats.postCount, "关注者" to stats.followerCount,
                                "关注中" to stats.followingCount, "获赞" to stats.likesReceived),
                                clickableLabels = setOf("关注者", "关注中"),
                                onLabelClick = { label ->
                                    onFollowsClick(profile.userId, if (label == "关注者") "followers" else "following")
                                })
                        }
                    }
                }
                item("tabs") {
                    PrimaryTabRow(selectedTabIndex = spaceTab) {
                        Tab(selected = spaceTab == 0, onClick = { spaceTab = 0 }, text = { Text("作品") })
                        Tab(selected = spaceTab == 1, onClick = { spaceTab = 1 }, text = { Text("动态") })
                    }
                }
                if (state.profilePosts.isEmpty()) {
                    item("empty") {
                        CommunityProfilePostsState(state.profileLoading, state.profileLoadError, state.profileError) { viewModel.openProfile(userId) }
                    }
                } else if (spaceTab == 0) {
                    item("grid") {
                        CommunityPostTileGrid(state.profilePosts, columns = 3, token = state.session?.token, onPostClick = onPostClick)
                    }
                } else {
                    items(state.profilePosts, key = CommunityPost::id) { post ->
                        Box(motionAnimateItem()) {
                            CommunityPostCard(
                                post = post,
                                token = state.session?.token,
                                onAuthorClick = { },
                                onReport = { },
                                onDelete = { },
                                onLike = { viewModel.likePost(post.id) },
                                onComment = { onPostClick(post) },
                                onFavorite = { viewModel.favoritePost(post.id) },
                                onPin = { },
                                canPin = false,
                                onFeature = { },
                                canFeature = false,
                                onEdit = { },
                                canEdit = false,
                                onImageClick = { url -> viewingImageUrl = url },
                                onClick = { onPostClick(post) },
                            )
                        }
                    }
                }
            } else {
                item { UserSpaceHeader(profile, state.session?.token) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()) {
                        CommunityFollowButton(profile, isSelf, state.followBusyUserId != null, state.session != null) {
                            viewModel.toggleFollow(profile.userId)
                        }
                        profile.stats?.let { stats ->
                            CommunityCountRow(listOf("作品" to stats.postCount, "关注者" to stats.followerCount,
                                "关注中" to stats.followingCount, "获赞" to stats.likesReceived),
                                clickableLabels = setOf("关注者", "关注中"),
                                onLabelClick = { label ->
                                    onFollowsClick(profile.userId, if (label == "关注者") "followers" else "following")
                                })
                        }
                    }
                }
                item {
                    Text("TA 的帖子 · ${state.profilePosts.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (state.profilePosts.isEmpty()) {
                    item { CommunityProfilePostsState(state.profileLoading, state.profileLoadError, state.profileError) { viewModel.openProfile(userId) } }
                } else {
                    items(state.profilePosts, key = CommunityPost::id) { post ->
                        Box(motionAnimateItem()) {
                            CommunityPostCard(
                                post = post,
                                token = state.session?.token,
                                onAuthorClick = { onUserClick(post.author.userId) },
                                onReport = { },
                                onDelete = { },
                                onLike = { viewModel.likePost(post.id) },
                                onComment = { onPostClick(post) },
                                onFavorite = { viewModel.favoritePost(post.id) },
                                onPin = { },
                                canPin = false,
                                onFeature = { },
                                canFeature = false,
                                onEdit = { },
                                canEdit = false,
                                onImageClick = { url -> viewingImageUrl = url },
                                onClick = { onPostClick(post) },
                            )
                        }
                    }
                }
            }
            if (profile != null && state.profileNextBeforeId != null) item("more") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.profileLoadingMore) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else androidx.compose.material3.OutlinedButton(onClick = viewModel::loadMoreProfilePosts) {
                        Text(if (state.profileLoadError) "加载失败，点此重试" else "加载更早的帖子")
                    }
                }
            }
        }
    }

    viewingImageUrl?.let { url ->
        ZoomableImageViewer(
            imageUrl = url,
            title = "帖子图片",
            onDismiss = { viewingImageUrl = null },
            authToken = state.session?.token,
        )
    }

    if (showEdit && profile != null && isSelf) {
        CommunityProfileEditorDialog(
            profile = profile,
            localStatistics = localStatistics,
            token = state.session?.token,
            busy = state.busy,
            presetImages = state.presetImages,
            presetDisclaimer = state.presetDisclaimer,
            saveError = state.profileSaveError,
            onDismiss = {
                if (!state.busy) {
                    showEdit = false
                    viewModel.clearProfileSaveError()
                }
            },
            onSave = { nickname, signature, privateProfile, showStatistics, selected, avatar, removeAvatar, presetAvatar ->
                viewModel.updateProfile(
                    nickname = nickname,
                    signature = signature,
                    privateProfile = privateProfile,
                    showStatistics = showStatistics,
                    selectedStatistics = selected,
                    avatarUri = avatar,
                    removeAvatar = removeAvatar,
                    presetAvatar = presetAvatar,
                )
            },
        )
    }

    if (showBlacklist) {
        CommunityBlacklistDialog(
            blocks = state.blocks,
            onDismiss = { showBlacklist = false; viewModel.closeBlocks() },
            onUnblock = viewModel::unblockUser,
        )
    }

    if (blockConfirm && !isSelf && profile != null) {
        ConfirmationDialog(
            title = "屏蔽该用户？",
            message = "屏蔽后将不再看到 ${profile.nickname} 的帖子，对方的动态也不会再出现在你的社区里。可在「设置 → 屏蔽管理」中取消。",
            confirmLabel = "屏蔽",
            onConfirm = {
                blockConfirm = false
                viewModel.blockUser(userId)
                onBack()
            },
            onDismiss = { blockConfirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun UserSpaceHeader(
    profile: CommunityProfile,
    token: String?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().motionAnimateContentSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CommunityAvatar(profile, token, Modifier.size(88.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (profile.isDeveloper) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "神秘人",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    } else if (profile.isAdmin) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            AppIcons.Admin,
                            contentDescription = "管理员",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text("@${profile.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (profile.isPrivate) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Lock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("该用户已将详细名片设为仅自己可见。")
                    }
                }
            } else {
                if (profile.signature.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(profile.signature, modifier = Modifier.padding(12.dp))
                    }
                }
                CommunityStatisticsCard(profile.statistics, profile.showStatisticsCard)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (profile.profileVisibility == "private") AppIcons.Lock else AppIcons.Public,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (profile.profileVisibility == "private") "名片仅自己可见" else "名片向社区成员展示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
