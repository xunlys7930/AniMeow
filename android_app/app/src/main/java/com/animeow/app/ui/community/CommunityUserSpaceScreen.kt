package com.animeow.app.ui.community

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.animeow.app.ui.components.ZoomableImageViewer

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
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localStatistics by viewModel.localStatistics.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showEdit by remember { mutableStateOf(false) }
    var showBlacklist by remember { mutableStateOf(false) }
    var viewingImageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(userId) { viewModel.openProfile(userId) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
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
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isSelf) {
                        IconButton(onClick = {
                            showBlacklist = true
                            viewModel.refreshBlocks()
                        }) {
                            Icon(Icons.Outlined.Block, contentDescription = "黑名单")
                        }
                        IconButton(onClick = { showEdit = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑资料")
                        }
                    } else {
                        IconButton(onClick = {
                            viewModel.blockUser(userId)
                            onBack()
                        }) {
                            Icon(Icons.Outlined.Block, contentDescription = "屏蔽")
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
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item { UserSpaceHeader(profile, state.session?.token) }
                item {
                    Text("TA 的帖子 · ${state.profilePosts.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (state.profilePosts.isEmpty()) {
                    item { Text("还没有发布过帖子", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(state.profilePosts, key = CommunityPost::id) { post ->
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
            onDismiss = { showEdit = false },
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
                showEdit = false
            },
        )
    }

    if (showBlacklist) {
        CommunityBlacklistDialog(
            blocks = state.blocks,
            onDismiss = { showBlacklist = false },
            onUnblock = viewModel::unblockUser,
        )
    }
}

@Composable
private fun UserSpaceHeader(
    profile: CommunityProfile,
    token: String?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            Icons.Outlined.AdminPanelSettings,
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
                        Icon(Icons.Outlined.Lock, contentDescription = null)
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
                    if (profile.profileVisibility == "private") Icons.Outlined.Lock else Icons.Outlined.Public,
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
