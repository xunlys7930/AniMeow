package com.animeow.app.ui.community

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.community.CommunityComment
import com.animeow.app.data.community.CommunityMedia
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.PresetImage
import com.animeow.app.data.community.presetImageUrl
import com.animeow.app.data.media.copyUriToCacheFile
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.ZoomableImageViewer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    onCloudAccountRequested: () -> Unit,
    onCharacterCommunityRequested: () -> Unit,
    onManagementRequested: () -> Unit = {},
    onPostClick: (CommunityPost) -> Unit = {},
    onUserClick: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    showBack: Boolean = false,
    topBarContainerColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var reportTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var deleteTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var viewingImageUrl by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var editPostTarget by remember { mutableStateOf<CommunityPost?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxOf(2, state.limits.postImages)),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val available = (state.limits.postImages - state.draftImages.size - state.draftPresetMedia.size).coerceAtLeast(0)
        val toCopy = uris.take(available)
        scope.launch {
            val cached = toCopy.mapNotNull { uri ->
                runCatching { copyUriToCacheFile(context, uri, "community_img") }.getOrNull()
            }
            if (cached.isNotEmpty()) {
                viewModel.setDraftImages(state.draftImages + cached)
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshSession() }
    LaunchedEffect(
        state.session?.token,
        state.group?.id,
        state.group?.membership?.receiveMessages,
    ) {
        if (state.session != null && state.group?.membership?.receiveMessages == true) {
            while (true) {
                delay(30_000)
                viewModel.refreshLatestSilently()
            }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.group?.name ?: "社区",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.group != null) {
                            Text(
                                if (state.session != null) "官方群聊 · 已登录成员可见"
                                else "浏览模式 · 登录后可发言互动",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (state.session != null) {
                        if (state.currentUser?.isAdmin == true || state.currentUser?.isDeveloper == true) {
                            IconButton(onClick = onManagementRequested) {
                                Icon(Icons.Outlined.AdminPanelSettings, contentDescription = "社区管理")
                            }
                        }
                        IconButton(onClick = { showSearch = true; viewModel.clearSearch() }) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索帖子")
                        }
                        IconButton(onClick = {
                            state.currentUser?.userId?.let(onUserClick)
                        }) {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = "我的个人空间")
                        }
                    }
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.loading && !state.busy,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarContainerColor,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            if (state.session != null && state.bootstrap != null) {
                var cropPresetTarget by remember { mutableStateOf<String?>(null) }
                CommunityComposer(
                    text = state.draftText,
                    images = state.draftImages,
                    draftPresetMedia = state.draftPresetMedia,
                    maxImages = state.limits.postImages,
                    busy = state.busy,
                    busyLabel = state.busyLabel,
                    presetImages = state.presetImages,
                    presetDisclaimer = state.presetDisclaimer,
                    onTextChange = viewModel::updateDraftText,
                    onPickImages = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onRemoveImage = viewModel::removeDraftImage,
                    onRemovePresetMedia = viewModel::removeDraftPresetMedia,
                    onMoveImage = viewModel::moveDraftImage,
                    onMovePresetMedia = viewModel::moveDraftPresetMedia,
                    onCropPresetMedia = { name -> cropPresetTarget = name },
                    onPickPresetMedia = viewModel::addDraftPresetMedia,
                    onConfirmPresetSelection = viewModel::setDraftPresetMedia,
                    onPublish = viewModel::publish,
                )
                cropPresetTarget?.let { name ->
                    ImageCropDialog(
                        imageUrl = presetImageUrl(name),
                        cropShape = CropShape.Rectangle,
                        onConfirm = { uri ->
                            viewModel.convertPresetToCropped(name, uri)
                            cropPresetTarget = null
                        },
                        onDismiss = { cropPresetTarget = null },
                    )
                }
            }
        },
    ) { padding ->
        when {
            !state.configured -> CommunityUnavailable(
                title = "当前构建未配置社区服务",
                body = "本地资料库不受影响。配置 CLOUD_API_BASE 后即可使用登录社区。",
                modifier = Modifier.padding(padding),
            )
            state.loading && state.bootstrap == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.bootstrap != null -> CommunityFeed(
                state = state,
                onToggleMessages = viewModel::setReceiveMessages,
                onCharacterCommunityRequested = onCharacterCommunityRequested,
                onAuthorClick = onUserClick,
                onPostClick = onPostClick,
                onSortChange = viewModel::setFeedSort,
                onReport = { reportTarget = it },
                onDelete = { deleteTarget = it },
                onLoadMore = viewModel::loadMore,
                onLike = { viewModel.likePost(it.id) },
                onComment = onPostClick,
                onFavorite = { viewModel.favoritePost(it.id) },
                onPin = { post, pin -> viewModel.pinPost(post.id, pin) },
                onFeature = { post, feature -> viewModel.featurePost(post.id, feature) },
                onEdit = { editPostTarget = it },
                onImageClick = { url -> viewingImageUrl = url },
                modifier = Modifier.padding(padding),
            )
            state.session == null -> LoginRequiredCommunity(
                onLogin = onCloudAccountRequested,
                modifier = Modifier.padding(padding),
            )
            else -> CommunityUnavailable(
                title = "社区暂时无法加载",
                body = "请检查网络与登录状态，然后点击右上角刷新。",
                modifier = Modifier.padding(padding),
            )
        }
    }

    reportTarget?.let { post ->
        CommunityReportDialog(
            authorName = post.author.nickname,
            busy = state.busy,
            onDismiss = { reportTarget = null },
            onSubmit = { reason, details ->
                viewModel.reportPost(post, reason, details)
                reportTarget = null
            },
        )
    }

    deleteTarget?.let { post ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条帖子？") },
            text = { Text("删除后不会继续在社区显示。") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deletePost(post)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    viewingImageUrl?.let { url ->
        ZoomableImageViewer(
            imageUrl = url,
            title = "社区图片",
            onDismiss = { viewingImageUrl = null },
            authToken = state.session?.token,
        )
    }

    if (showSearch) {
        CommunitySearchDialog(
            results = state.searchResults,
            searching = state.searching,
            token = state.session?.token,
            onSearch = { query, sort, featured -> viewModel.search(query, sort, featured) },
            onDismiss = { showSearch = false },
            onImageClick = { url -> viewingImageUrl = url },
            onAuthorClick = { userId ->
                showSearch = false
                onUserClick(userId)
            },
            onPostClick = { post ->
                showSearch = false
                onPostClick(post)
            },
        )
    }

    editPostTarget?.let { post ->
        CommunityEditDialog(
            title = "编辑帖子",
            initial = post.content,
            onDismiss = { editPostTarget = null },
            onConfirm = { text ->
                viewModel.editPost(post.id, text)
                editPostTarget = null
            },
        )
    }
}

@Composable
private fun CommunityFeed(
    state: CommunityUiState,
    onToggleMessages: (Boolean) -> Unit,
    onCharacterCommunityRequested: () -> Unit,
    onAuthorClick: (Long) -> Unit,
    onPostClick: (CommunityPost) -> Unit,
    onSortChange: (String, Boolean) -> Unit,
    onReport: (CommunityPost) -> Unit,
    onDelete: (CommunityPost) -> Unit,
    onLoadMore: () -> Unit,
    onLike: (CommunityPost) -> Unit,
    onComment: (CommunityPost) -> Unit,
    onFavorite: (CommunityPost) -> Unit,
    onPin: (CommunityPost, Boolean) -> Unit,
    onFeature: (CommunityPost, Boolean) -> Unit,
    onEdit: (CommunityPost) -> Unit,
    onImageClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("group-header") {
            CommunityGroupHeader(
                state = state,
                onToggleMessages = onToggleMessages,
                onCharacterCommunityRequested = onCharacterCommunityRequested,
            )
        }
        item("sort") {
            FeedSortChips(
                current = state.feedSort,
                featuredOnly = state.feedFeaturedOnly,
                onSelect = onSortChange,
            )
        }
        if (state.posts.isEmpty()) {
            item("empty") {
                CommunityUnavailable(
                    title = "茶话会还很安静",
                    body = "发一条文字或图片，成为第一个开场的人吧。",
                )
            }
        } else {
            items(state.posts, key = CommunityPost::id) { post ->
                CommunityPostCard(
                    post = post,
                    token = state.session?.token,
                    onAuthorClick = { onAuthorClick(post.author.userId) },
                    onReport = { onReport(post) },
                    onDelete = { onDelete(post) },
                    onLike = { onLike(post) },
                    onComment = { onComment(post) },
                    onFavorite = { onFavorite(post) },
                    onPin = { pin -> onPin(post, pin) },
                    canPin = state.currentUser?.isDeveloper == true,
                    onFeature = { feature -> onFeature(post, feature) },
                    canFeature = state.currentUser?.isDeveloper == true,
                    onEdit = { onEdit(post) },
                    canEdit = state.currentUser?.userId == post.author.userId,
                    onImageClick = onImageClick,
                    onClick = { onPostClick(post) },
                )
            }
        }
        if (state.nextBeforeId != null) {
            item("load-more") {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !state.loadingMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.loadingMore) "正在加载…" else "加载更早的帖子")
                }
            }
        }
    }
}

@Composable
private fun FeedSortChips(current: String, featuredOnly: Boolean, onSelect: (String, Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "newest" to "最新",
            "oldest" to "最旧",
            "likes" to "点赞最多",
            "comments" to "评论最多",
        ).forEach { (value, label) ->
            FilterChip(
                selected = current == value && !featuredOnly,
                onClick = { onSelect(value, false) },
                label = { Text(label) },
            )
        }
        FilterChip(
            selected = featuredOnly,
            onClick = { onSelect(current, !featuredOnly) },
            label = { Text("精华") },
        )
    }
}

@Composable
private fun CommunityGroupHeader(
    state: CommunityUiState,
    onToggleMessages: (Boolean) -> Unit,
    onCharacterCommunityRequested: () -> Unit,
) {
    val group = state.group ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (group.isOfficial) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.VerifiedUser,
                                contentDescription = "官方群",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(
                        group.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起群信息" else "展开群信息",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    if (state.session != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (group.membership?.receiveMessages == false) Icons.Outlined.NotificationsOff else Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("接收群消息", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("关闭后仍可主动进入查看和发言", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = group.membership?.receiveMessages != false,
                                onCheckedChange = onToggleMessages,
                                enabled = !state.busy,
                            )
                        }
                    }
                    FilledTonalButton(onClick = onCharacterCommunityRequested, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FolderShared, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("角色群组分享工具")
                    }
                    Text(
                        "免责声明：社区中的图片由群友上传，仅用于交流分享。如有违规内容请联系管理员处理。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CommunityPostCard(
    post: CommunityPost,
    token: String?,
    onAuthorClick: () -> Unit,
    onReport: () -> Unit,
    onDelete: () -> Unit,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onFavorite: () -> Unit,
    onPin: (Boolean) -> Unit,
    canPin: Boolean,
    onFeature: (Boolean) -> Unit,
    canFeature: Boolean,
    onEdit: () -> Unit,
    canEdit: Boolean,
    onImageClick: (String) -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CommunityAvatar(
                    profile = post.author,
                    token = token,
                    modifier = Modifier.size(44.dp).clickable(onClick = onAuthorClick),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable(onClick = onAuthorClick)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(post.author.nickname, fontWeight = FontWeight.SemiBold)
                        if (post.author.isDeveloper) {
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "神秘人",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (post.author.isAdmin) {
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "管理员",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        formatCommunityTime(post.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (post.isPinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "已置顶",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (post.isFeatured) {
                    Text(
                        "精华",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (post.canReport || post.canDelete || canPin || canFeature || canEdit) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "帖子操作")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (canEdit) {
                                DropdownMenuItem(
                                    text = { Text("编辑") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit()
                                    },
                                )
                            }
                            if (post.canReport) {
                                DropdownMenuItem(
                                    text = { Text("举报违规") },
                                    leadingIcon = { Icon(Icons.Outlined.Report, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onReport()
                                    },
                                )
                            }
                            if (canPin) {
                                DropdownMenuItem(
                                    text = { Text(if (post.isPinned) "取消置顶" else "置顶帖子") },
                                    leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onPin(!post.isPinned)
                                    },
                                )
                            }
                            if (canFeature) {
                                DropdownMenuItem(
                                    text = { Text(if (post.isFeatured) "取消精华" else "设为精华") },
                                    leadingIcon = { Icon(Icons.Outlined.Bookmark, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onFeature(!post.isFeatured)
                                    },
                                )
                            }
                            if (post.canDelete) {
                                DropdownMenuItem(
                                    text = { Text("删除帖子") },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (post.isQuarantined) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "这条帖子正在等待管理员复审，目前仅你自己可见。",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (post.content.isNotBlank()) {
                Text(post.content, style = MaterialTheme.typography.bodyLarge)
            }
            if (post.media.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (post.media.size == 1) {
                        val media = post.media.first()
                        AsyncImage(
                            model = authenticatedCommunityImageModel(media.url, token),
                            contentDescription = "帖子图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(media.displayAspectRatio(1.6f))
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onImageClick(media.url) },
                        )
                    } else {
                        post.media.chunked(2).forEach { rowMedia ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowMedia.forEach { media ->
                                    AsyncImage(
                                        model = authenticatedCommunityImageModel(media.url, token),
                                        contentDescription = "帖子图片",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { onImageClick(media.url) },
                                    )
                                }
                                if (rowMedia.size == 1 && post.media.size > 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onLike) {
                    Icon(
                        if (post.likedByViewer) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (post.likedByViewer) "取消点赞" else "点赞",
                        tint = if (post.likedByViewer) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    post.likeCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onComment) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "评论")
                }
                Text(
                    post.commentCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (post.isFavorited) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (post.isFavorited) "取消收藏" else "收藏",
                        tint = if (post.isFavorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun CommunityMedia.displayAspectRatio(fallback: Float): Float {
    val w = width ?: return fallback
    val h = height ?: return fallback
    if (w <= 0 || h <= 0) return fallback
    return w.toFloat() / h.toFloat()
}

@Composable
private fun CommunityComposer(
    text: String,
    images: List<Uri>,
    draftPresetMedia: List<String>,
    maxImages: Int,
    busy: Boolean,
    busyLabel: String?,
    presetImages: List<PresetImage>,
    presetDisclaimer: String,
    onTextChange: (String) -> Unit,
    onPickImages: () -> Unit,
    onRemoveImage: (Uri) -> Unit,
    onRemovePresetMedia: (String) -> Unit,
    onMoveImage: (Int, Int) -> Unit,
    onMovePresetMedia: (Int, Int) -> Unit,
    onCropPresetMedia: (String) -> Unit,
    onPickPresetMedia: (String) -> Unit,
    onConfirmPresetSelection: (Set<String>) -> Unit,
    onPublish: () -> Unit,
) {
    var showPresetPicker by remember { mutableStateOf(false) }
    val totalSelected = images.size + draftPresetMedia.size
    Surface(
        tonalElevation = 5.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                busyLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            // 统一预览行：普通图片 + 预设贴纸
            if (totalSelected > 0) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 普通图片
                    itemsIndexed(images, key = { _, uri -> uri.toString() }) { index, uri ->
                        DraftImageCell(
                            model = uri,
                            label = "图片 ${index + 1}",
                            canMoveLeft = index > 0,
                            canMoveRight = index < images.size - 1 || draftPresetMedia.isNotEmpty(),
                            canCrop = false,
                            onRemove = { onRemoveImage(uri) },
                            onMoveLeft = { onMoveImage(index, -1) },
                            onMoveRight = { onMoveImage(index, 1) },
                            onCrop = {},
                        )
                    }
                    // 预设贴纸
                    itemsIndexed(draftPresetMedia, key = { _, name -> name }) { index, name ->
                        DraftImageCell(
                            model = presetImageUrl(name),
                            label = "贴纸 ${index + 1}",
                            canMoveLeft = images.isNotEmpty() || index > 0,
                            canMoveRight = index < draftPresetMedia.size - 1,
                            canCrop = true,
                            onRemove = { onRemovePresetMedia(name) },
                            onMoveLeft = { onMovePresetMedia(index, -1) },
                            onMoveRight = { onMovePresetMedia(index, 1) },
                            onCrop = { onCropPresetMedia(name) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = onPickImages,
                    enabled = !busy && totalSelected < maxImages,
                ) { Icon(Icons.Outlined.Image, contentDescription = "选择图片") }
                IconButton(
                    onClick = { showPresetPicker = true },
                    enabled = !busy && totalSelected < maxImages,
                ) { Icon(Icons.Outlined.EmojiEmotions, contentDescription = "选择贴纸/表情") }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("和大家聊聊…") },
                    minLines = 1,
                    maxLines = 4,
                    enabled = !busy,
                )
                IconButton(
                    onClick = onPublish,
                    enabled = !busy && (text.isNotBlank() || totalSelected > 0),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发布")
                }
            }
        }
    }

    if (showPresetPicker) {
        PresetImagePickerDialog(
            images = presetImages,
            disclaimer = presetDisclaimer,
            multiSelect = true,
            selectedNames = draftPresetMedia.toSet(),
            onSelected = onPickPresetMedia,
            onConfirmSelection = onConfirmPresetSelection,
            onDismiss = { showPresetPicker = false },
        )
    }
}

/**
 * 草稿图片/贴纸的预览单元：缩略图 + 删除 + 左移/右移 + 裁剪（仅预设贴纸）。
 */
@Composable
private fun DraftImageCell(
    model: Any,
    label: String,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    canCrop: Boolean,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onCrop: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            AsyncImage(
                model = model,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
            )
            // 删除按钮
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
            ) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
        // 排序 + 裁剪按钮行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(onClick = onMoveLeft, enabled = canMoveLeft, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = "左移",
                    modifier = Modifier.size(18.dp),
                )
            }
            if (canCrop) {
                IconButton(onClick = onCrop, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Crop,
                        contentDescription = "裁剪",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            IconButton(onClick = onMoveRight, enabled = canMoveRight, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "右移",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LoginRequiredCommunity(
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.padding(24.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, modifier = Modifier.size(48.dp))
                Text("登录后进入社区", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "社区消息、图片和个人名片仅向已登录用户开放。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLogin) { Text("登录或注册云账号") }
            }
        }
    }
}

@Composable
internal fun CommunityUnavailable(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityCommentsSheet(
    post: CommunityPost,
    comments: List<CommunityComment>,
    commentDraft: String,
    loading: Boolean,
    token: String?,
    onDismiss: () -> Unit,
    onCommentDraftChange: (String) -> Unit,
    onSendComment: (String) -> Unit,
    onReplyComment: (Long, String) -> Unit,
    onLikeComment: (Long) -> Unit,
    onEditComment: (CommunityComment) -> Unit,
    onDeleteComment: (Long) -> Unit,
) {
    var replyTarget by remember { mutableStateOf<CommunityComment?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<CommunityComment?>(null) }
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(bottom = 20.dp)) {
            Text(
                "评论 ${post.commentCount}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (loading && comments.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (comments.isEmpty()) {
                Text(
                    "还没有评论，来抢沙发吧",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(comments, key = CommunityComment::id) { comment ->
                        CommunityCommentRow(
                            comment = comment,
                            token = token,
                            onReply = {
                                replyTarget = comment
                                replyDraft = ""
                            },
                            onLike = { onLikeComment(comment.id) },
                            onEdit = { onEditComment(comment) },
                            onDelete = { deleteTarget = comment },
                        )
                    }
                }
            }
            HorizontalDivider()
            replyTarget?.let { target ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "回复 ${target.author.nickname}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        replyTarget = null
                        replyDraft = ""
                    }) { Text("取消") }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = if (replyTarget != null) replyDraft else commentDraft,
                    onValueChange = { value ->
                        if (replyTarget != null) replyDraft = value.take(1000) else onCommentDraftChange(value)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (replyTarget != null) "写下回复…" else "写下你的评论…") },
                    maxLines = 3,
                )
                IconButton(
                    onClick = {
                        val target = replyTarget
                        if (target != null) {
                            onReplyComment(target.id, replyDraft)
                            replyTarget = null
                            replyDraft = ""
                        } else {
                            onSendComment(commentDraft)
                        }
                    },
                    enabled = if (replyTarget != null) replyDraft.isNotBlank() else commentDraft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                }
            }
        }
    }

    deleteTarget?.let { comment ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条评论？") },
            text = { Text("删除后不会恢复。") },
            confirmButton = {
                Button(onClick = {
                    onDeleteComment(comment.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun CommunityCommentRow(
    comment: CommunityComment,
    token: String?,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommunityAvatar(profile = comment.author, token = token, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                comment.author.nickname,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatCommunityTime(comment.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReply) { Text("回复") }
            TextButton(onClick = onLike) {
                Icon(
                    if (comment.likedByViewer) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (comment.likedByViewer) "取消点赞" else "点赞",
                    tint = if (comment.likedByViewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                if (comment.likeCount > 0) {
                    Spacer(Modifier.width(3.dp))
                    Text(comment.likeCount.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("评论", comment.content)))
                }
            }) { Text("复制") }
            TextButton(onClick = onEdit) { Text("编辑") }
            if (comment.canDelete) TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun CommunitySearchDialog(
    results: List<CommunityPost>,
    searching: Boolean,
    token: String?,
    onSearch: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onImageClick: (String) -> Unit,
    onAuthorClick: (Long) -> Unit,
    onPostClick: (CommunityPost) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("newest") }
    var featuredOnly by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索帖子") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it, sort, featuredOnly)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索帖子内容或用户名") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "newest" to "最新",
                        "oldest" to "最旧",
                        "likes" to "点赞最多",
                        "comments" to "评论最多",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = sort == value && !featuredOnly,
                            onClick = {
                                sort = value
                                featuredOnly = false
                                onSearch(query, sort, featuredOnly)
                            },
                            label = { Text(label) },
                        )
                    }
                    FilterChip(
                        selected = featuredOnly,
                        onClick = {
                            featuredOnly = !featuredOnly
                            onSearch(query, sort, featuredOnly)
                        },
                        label = { Text("精华") },
                    )
                }
                if (searching) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (results.isEmpty() && query.isNotBlank()) {
                    Text("没有找到相关帖子", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.height(320.dp)) {
                        items(results, key = CommunityPost::id) { post ->
                            CommunityPostCard(
                                post = post,
                                token = token,
                                onAuthorClick = { onAuthorClick(post.author.userId) },
                                onReport = {},
                                onDelete = {},
                                onLike = {},
                                onComment = { onPostClick(post) },
                                onFavorite = {},
                                onPin = {},
                                canPin = false,
                                onFeature = {},
                                canFeature = false,
                                onEdit = {},
                                canEdit = false,
                                onImageClick = onImageClick,
                                onClick = { onPostClick(post) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun CommunityEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun CommunityBlacklistDialog(
    blocks: List<CommunityProfile>,
    onDismiss: () -> Unit,
    onUnblock: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("黑名单") },
        text = {
            if (blocks.isEmpty()) {
                Text("你还没有屏蔽任何用户", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.height(280.dp)) {
                    items(blocks, key = CommunityProfile::userId) { user ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(user.nickname.ifBlank { user.username }, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onUnblock(user.userId) }) { Text("取消屏蔽") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
