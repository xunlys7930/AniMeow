package com.animeow.app.ui.community

import com.animeow.app.ui.navigation.PageAction
import com.animeow.app.ui.navigation.PageActionRequests
import com.animeow.app.ui.navigation.HandlePageAction

import com.animeow.app.ui.icons.AppIcons

import com.animeow.app.ui.navigation.LocalFloatingNavigationInset

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredGridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import com.animeow.app.ui.icons.AppIcon as Icon
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.community.CommunityComment
import com.animeow.app.data.community.CommunityMedia
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.PresetImage
import com.animeow.app.data.community.presetImageUrl
import com.animeow.app.ui.components.ConfirmationDialog
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.ZoomableImageViewer
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.components.motionBarEnter
import com.animeow.app.ui.components.motionBarExit
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.CommunityStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 瀑布流图片缺失宽高时的默认比例（宽/高 = 3/4）。 */
private const val WATERFALL_IMAGE_FALLBACK_RATIO = 0.75f

/** 瀑布流纯文字卡最多显示的行数；超出以省略号收尾，点击卡片进详情看全文。 */
private const val WATERFALL_TEXT_CARD_MAX_LINES = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    modifier: Modifier = Modifier,
    onCloudAccountRequested: () -> Unit,
    onCharacterCommunityRequested: () -> Unit,
    onPersonalCenterRequested: () -> Unit = {},
    onMessagesRequested: () -> Unit = {},
    unreadMessages: Int = 0,
    pageActions: PageActionRequests? = null,
    onManagementRequested: () -> Unit = {},
    onPostClick: (CommunityPost) -> Unit = {},
    onUserClick: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    showBack: Boolean = false,
    topBarContainerColor: Color = Color.Transparent,
    viewModel: CommunityViewModel = viewModel(),
    appearanceViewModel: AppearanceViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearanceSettings by appearanceViewModel.settings.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var reportTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var deleteTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var blockTarget by remember { mutableStateOf<CommunityProfile?>(null) }
    var viewingImageUrl by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var editPostTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var showComposer by rememberSaveable { mutableStateOf(false) }
    val composerSheetVisible = showComposer && state.session != null && state.bootstrap != null
    HandlePageAction(pageActions, PageAction.COMPOSE_POST, state.session != null && state.bootstrap != null) { showComposer = true }
    var showGroupInfo by rememberSaveable { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showStylePicker by remember { mutableStateOf(false) }
    var cropPresetTarget by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxOf(2, state.limits.postImages)),
    ) { uris ->
        viewModel.addDraftImages(uris)
    }

    LaunchedEffect(state.publishedPostId) {
        if (state.publishedPostId != null) {
            showComposer = false
            viewModel.clearPublishedPost()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSession(); viewModel.refresh() }
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
    LaunchedEffect(Unit) {
        CommunityTagRequests.pending.collect { tag ->
            if (tag != null && appearanceSettings.communityStyle == CommunityStyle.WATERFALL) {
                CommunityTagRequests.consume()
                viewModel.setFeedTag(tag)
            }
        }
    }
    // 信息流形态没有标签行与“关注”页签：切回时清掉瀑布流专属筛选，保持原有行为。
    LaunchedEffect(appearanceSettings.communityStyle) {
        if (appearanceSettings.communityStyle == CommunityStyle.FEED) {
            if (state.feedTag != null) viewModel.setFeedTag(null)
            if (state.feedSort == "following") viewModel.setFeedSort("newest", false)
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
        snackbarHost = {
            if (!composerSheetVisible) SnackbarHost(
                snackbarHost,
                modifier = Modifier.padding(bottom = LocalFloatingNavigationInset.current),
            )
        },
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
                                if (state.session != null) "官方群聊 · 分享你的追番日常"
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
                            Icon(AppIcons.Back, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (state.session != null) {
                        IconButton(onClick = { showSearch = true; viewModel.clearSearch() }) {
                            Icon(AppIcons.Search, contentDescription = "搜索帖子")
                        }
                        CommunityInboxButton(unreadMessages, onMessagesRequested)
                    }
                    Box {
                        IconButton(onClick = { showMore = true }) {
                            Icon(AppIcons.MoreVertical, contentDescription = "更多社区操作")
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text("我的个人空间") },
                                leadingIcon = { Icon(AppIcons.Account, null) },
                                onClick = { showMore = false; onPersonalCenterRequested() },
                            )
                            if (state.group != null) DropdownMenuItem(
                                text = { Text("群聊信息与工具") },
                                leadingIcon = { Icon(AppIcons.Info, contentDescription = null) },
                                onClick = { showMore = false; showGroupInfo = true },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新帖子") },
                                leadingIcon = { Icon(AppIcons.Refresh, contentDescription = null) },
                                enabled = !state.loading && !state.busy,
                                onClick = { showMore = false; viewModel.refresh() },
                            )
                            DropdownMenuItem(
                                text = { Text("社区样式") },
                                leadingIcon = { Icon(AppIcons.Grid, contentDescription = null) },
                                onClick = { showMore = false; showStylePicker = true },
                            )
                            if (state.currentUser?.isAdmin == true || state.currentUser?.isDeveloper == true) {
                                DropdownMenuItem(
                                    text = { Text("社区管理") },
                                    leadingIcon = { Icon(AppIcons.Admin, contentDescription = null) },
                                    onClick = { showMore = false; onManagementRequested() },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarContainerColor,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = appearanceSettings.communityStyle == CommunityStyle.FEED &&
                    state.bootstrap != null && LocalFloatingNavigationInset.current == 0.dp,
                enter = motionBarEnter(),
                exit = motionBarExit(),
            ) {
                CommunityComposerEntry(
                    text = if (state.session == null) "登录后参与讨论" else state.draftText,
                    imageCount = state.draftImages.size + state.draftPresetMedia.size,
                    busy = state.busy || state.importingImages || state.watchShareLoading,
                    onClick = { if (state.session == null) onCloudAccountRequested() else showComposer = true },
                )
            }
        },
    ) { padding ->
        val communityStage = when {
            !state.configured -> "unconfigured"
            state.loading && state.bootstrap == null -> "loading"
            state.bootstrap != null && appearanceSettings.communityStyle == CommunityStyle.WATERFALL -> "waterfall"
            state.bootstrap != null -> "feed"
            state.feedError != null -> "error"
            state.session == null -> "login"
            else -> "unavailable"
        }
        val motionLevel = LocalMotionLevel.current
        AnimatedContent(
            targetState = communityStage,
            transitionSpec = { motionFadeThroughSpec(motionLevel, 200, 140) },
            label = "communityStage",
        ) { stage ->
            when (stage) {
                "unconfigured" -> CommunityUnavailable(
                    title = "社区服务暂未开放",
                    body = "此版本暂未提供社区服务，你可以继续使用本地收藏功能。",
                    modifier = Modifier.padding(padding),
                )
                "loading" -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                "waterfall" -> {
                    CommunityWaterfallFeed(
                        state = state,
                        onPostClick = onPostClick,
                        onTabChange = viewModel::setFeedTab,
                        onTagChange = viewModel::setFeedTag,
                        onLogin = onCloudAccountRequested,
                        onCompose = { if (state.session == null) onCloudAccountRequested() else showComposer = true },
                        onLoadMore = viewModel::loadMore,
                        onRetry = viewModel::refresh,
                        onImageClick = { url -> viewingImageUrl = url },
                        onEdit = { editPostTarget = it },
                        onReport = { reportTarget = it },
                        onPin = { post, pin -> viewModel.pinPost(post.id, pin) },
                        onFeature = { post, feature -> viewModel.featurePost(post.id, feature) },
                        onDelete = { deleteTarget = it },
                        onBlockAuthor = { blockTarget = it.author },
                        modifier = Modifier.padding(padding),
                    )
                }
                "feed" -> CommunityFeed(
                    state = state,
                    onAuthorClick = onUserClick,
                    onPostClick = onPostClick,
                    onSortChange = viewModel::setFeedSort,
                    onCompose = { if (state.session == null) onCloudAccountRequested() else showComposer = true },
                    onReport = { reportTarget = it },
                    onDelete = { deleteTarget = it },
                    onLoadMore = viewModel::loadMore,
                    onRetry = viewModel::refresh,
                    onLike = { viewModel.likePost(it.id) },
                    onComment = onPostClick,
                    onFavorite = { viewModel.favoritePost(it.id) },
                    onPin = { post, pin -> viewModel.pinPost(post.id, pin) },
                    onFeature = { post, feature -> viewModel.featurePost(post.id, feature) },
                    onEdit = { editPostTarget = it },
                    onBlockAuthor = { blockTarget = it.author },
                    onImageClick = { url -> viewingImageUrl = url },
                    modifier = Modifier.padding(padding),
                )
                "error" -> Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = LocalFloatingNavigationInset.current + 24.dp),
                ) {
                    com.animeow.app.ui.components.LoadErrorCard(
                        title = "社区暂时无法加载",
                        message = state.feedError.orEmpty(),
                        onRetry = viewModel::refresh,
                        secondaryActionLabel = if (state.session == null) "登录或注册" else null,
                        onSecondaryAction = if (state.session == null) onCloudAccountRequested else null,
                    )
                }
                "login" -> LoginRequiredCommunity(
                    onLogin = onCloudAccountRequested,
                    modifier = Modifier.padding(padding),
                )
                else -> CommunityUnavailable(
                    title = "社区暂时无法加载",
                    body = "请检查网络与登录状态，在右上角菜单中刷新重试。",
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    if (composerSheetVisible) {
        StableModalBottomSheet(onDismissRequest = {
            if (!state.busy && !state.importingImages) showComposer = false
        }) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (state.draftWatchReferenceId != null) "分享评论到社区" else "发帖", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showComposer = false }, enabled = !state.busy && !state.importingImages) {
                        Text("收起")
                    }
                }
                SnackbarHost(snackbarHost)
                CommunityComposer(
                    modifier = Modifier.weight(1f, fill = false),
                    text = state.draftText,
                    images = state.draftImages,
                    draftPresetMedia = state.draftPresetMedia,
                    maxImages = state.limits.postImages,
                    maxTextLength = state.limits.postTextLength,
                    busy = state.busy || state.importingImages,
                    busyLabel = if (state.watchShareLoading) "正在核对原评论…" else if (state.importingImages) "正在保存草稿图片…" else state.busyLabel,
                    presetImages = state.presetImages,
                    presetDisclaimer = state.presetDisclaimer,
                    onTextChange = viewModel::updateDraftText,
                    onPickImages = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemoveImage = viewModel::removeDraftImage,
                    onRemovePresetMedia = viewModel::removeDraftPresetMedia,
                    onMoveImage = viewModel::moveDraftImage,
                    onMovePresetMedia = viewModel::moveDraftPresetMedia,
                    onCropPresetMedia = { cropPresetTarget = it },
                    onPickPresetMedia = viewModel::addDraftPresetMedia,
                    onConfirmPresetSelection = viewModel::setDraftPresetMedia,
                    onPublish = viewModel::publish,
                    draftTags = state.draftTags,
                    watchReferenceId = state.draftWatchReferenceId,
                    onRemoveWatchReference = viewModel::removeWatchReference,
                    tagSuggestions = state.commonTags,
                    onAddTag = viewModel::addDraftTag,
                    onRemoveTag = viewModel::removeDraftTag,
                    imageFirst = appearanceSettings.communityStyle == CommunityStyle.WATERFALL,
                )
            }
        }
    }
    cropPresetTarget?.let { name ->
        ImageCropDialog(
            imageUrl = presetImageUrl(name),
            cropShape = CropShape.Rectangle,
            onConfirm = { uri -> viewModel.convertPresetToCropped(name, uri); cropPresetTarget = null },
            onDismiss = { cropPresetTarget = null },
        )
    }
    if (showGroupInfo) {
        StableModalBottomSheet(onDismissRequest = { showGroupInfo = false }) {
            CommunityGroupHeader(
                state = state,
                onToggleMessages = viewModel::setReceiveMessages,
                onCharacterCommunityRequested = { showGroupInfo = false; onCharacterCommunityRequested() },
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

    blockTarget?.let { author ->
        ConfirmationDialog(
            title = "屏蔽该用户？",
            message = "屏蔽后将不再看到 ${author.nickname} 的帖子，对方的动态也不会再出现在你的社区里。可在「设置 → 屏蔽管理」中取消。",
            confirmLabel = "屏蔽",
            onConfirm = {
                viewModel.blockUser(author.userId)
                blockTarget = null
            },
            onDismiss = { blockTarget = null },
            destructive = true,
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
        androidx.compose.runtime.DisposableEffect(viewModel) { onDispose { viewModel.clearSearch() } }
        CommunitySearchDialog(
            results = state.searchResults,
            searching = state.searching,
            error = state.searchError,
            token = state.session?.token,
            onSearch = { query, sort, featured -> viewModel.search(query, sort, featured) },
            onDismiss = { showSearch = false; viewModel.clearSearch() },
            onImageClick = { url -> viewingImageUrl = url },
            onAuthorClick = { userId ->
                showSearch = false; viewModel.clearSearch()
                onUserClick(userId)
            },
            onPostClick = { post ->
                showSearch = false; viewModel.clearSearch()
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

    if (showStylePicker) {
        CommunityStyleDialog(
            selected = appearanceSettings.communityStyle,
            onSelect = appearanceViewModel::setCommunityStyle,
            onDismiss = { showStylePicker = false },
        )
    }
}

@Composable
private fun CommunityFeed(
    modifier: Modifier = Modifier,
    state: CommunityUiState,
    onAuthorClick: (Long) -> Unit,
    onPostClick: (CommunityPost) -> Unit,
    onSortChange: (String, Boolean) -> Unit,
    onCompose: () -> Unit,
    onReport: (CommunityPost) -> Unit,
    onDelete: (CommunityPost) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLike: (CommunityPost) -> Unit,
    onComment: (CommunityPost) -> Unit,
    onFavorite: (CommunityPost) -> Unit,
    onPin: (CommunityPost, Boolean) -> Unit,
    onFeature: (CommunityPost, Boolean) -> Unit,
    onEdit: (CommunityPost) -> Unit,
    onBlockAuthor: (CommunityPost) -> Unit,
    onImageClick: (String) -> Unit = {},
) {
    val floating = LocalFloatingNavigationInset.current > 0.dp
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, maxOf(24.dp, LocalFloatingNavigationInset.current + 16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (floating) item("compose") {
            CommunityComposerEntry(text = if (state.session == null) "登录后参与讨论" else state.draftText,
                imageCount = state.draftImages.size + state.draftPresetMedia.size,
                busy = state.busy || state.importingImages, onClick = onCompose)
        }
        item("sort") {
            FeedSortChips(
                current = state.feedSort,
                featuredOnly = state.feedFeaturedOnly,
                onSelect = onSortChange,
            )
        }
        state.feedError?.let { message ->
            item("refresh-error") {
                com.animeow.app.ui.components.LoadErrorCard("刷新暂未完成", message, onRetry)
            }
        }
        if (state.posts.isEmpty() && state.feedError == null) {
            item("empty") {
                CommunityUnavailable(
                    title = "茶话会还很安静",
                    body = "发一条文字或图片，成为第一个开场的人吧。",
                )
            }
        } else {
            items(state.posts, key = CommunityPost::id) { post ->
                CommunityPostCard(
                    modifier = motionAnimateItem(),
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
                    onBlockAuthor = if (state.currentUser?.let { it.userId != post.author.userId } == true) {
                        { onBlockAuthor(post) }
                    } else null,
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

/**
 * 图片瀑布流形态：双列错位网格，复用与信息流同一套 [CommunityUiState] 与刷新/加载更早交互。
 * 排序 chips、刷新错误与空状态占满整行；右下角 FAB 打开与信息流底部发帖条完全相同的弹层。
 */
@Composable
private fun CommunityWaterfallFeed(
    state: CommunityUiState,
    onPostClick: (CommunityPost) -> Unit,
    onTabChange: (String) -> Unit,
    onTagChange: (String?) -> Unit,
    onLogin: () -> Unit,
    onCompose: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onImageClick: (String) -> Unit,
    onEdit: (CommunityPost) -> Unit,
    onReport: (CommunityPost) -> Unit,
    onPin: (CommunityPost, Boolean) -> Unit,
    onFeature: (CommunityPost, Boolean) -> Unit,
    onDelete: (CommunityPost) -> Unit,
    onBlockAuthor: (CommunityPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canModerate = state.currentUser?.isDeveloper == true
    // 经典底栏时外层已垫过底部安全区；悬浮底栏需要自己让出高度，FAB 再抬高 16dp。
    val bottomInset = maxOf(24.dp, LocalFloatingNavigationInset.current + 16.dp)
    val gridState = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState()
    var autoLoadFailed by remember(state.feedSort, state.feedFeaturedOnly, state.feedTag) { mutableStateOf(false) }
    // 接近末尾时自动加载下一页；失败后停止自动加载，保留手动重试按钮。
    val nearEnd by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.hasMoreFeed, state.loadingMore, autoLoadFailed) {
        if (nearEnd && state.hasMoreFeed && !state.loadingMore && !state.loading && !autoLoadFailed) onLoadMore()
    }
    var wasLoadingMore by remember { mutableStateOf(false) }
    LaunchedEffect(state.loadingMore) {
        if (wasLoadingMore && !state.loadingMore && state.message != null) autoLoadFailed = true
        wasLoadingMore = state.loadingMore
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = bottomInset + 72.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
        ) {
            item(key = "tabs", span = StaggeredGridItemSpan.FullLine) {
                WaterfallFeedTabs(current = waterfallTabOf(state), onSelect = onTabChange)
            }
            state.commonTags?.let { tags ->
                item(key = "tags", span = StaggeredGridItemSpan.FullLine) {
                    CommunityTagFilterRow(tags = tags, selected = state.feedTag, onSelect = onTagChange)
                }
            }
            state.feedError?.let { message ->
                item(key = "refresh-error", span = StaggeredGridItemSpan.FullLine) {
                    com.animeow.app.ui.components.LoadErrorCard("刷新暂未完成", message, onRetry)
                }
            }
            if (state.loading && state.posts.isEmpty()) {
                item(key = "loading", span = StaggeredGridItemSpan.FullLine) {
                    CommunityLoadMoreFooter(loading = true, failed = false, onRetry = {})
                }
            } else if (state.posts.isEmpty() && state.feedError == null) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    WaterfallEmptyState(state, onLogin)
                }
            } else {
                staggeredGridItems(state.posts, key = CommunityPost::id) { post ->
                    CommunityWaterfallCard(
                        modifier = motionAnimateItem(),
                        post = post,
                        token = state.session?.token,
                        canPin = canModerate,
                        canFeature = canModerate,
                        canEdit = state.currentUser?.userId == post.author.userId,
                        // 瀑布流中点图片进入详情，放大查看在详情页里进行。
                        onImageClick = { onPostClick(post) },
                        onClick = { onPostClick(post) },
                        onEdit = { onEdit(post) },
                        onReport = { onReport(post) },
                        onPin = { pin -> onPin(post, pin) },
                        onFeature = { feature -> onFeature(post, feature) },
                        onDelete = { onDelete(post) },
                        onBlockAuthor = if (state.currentUser?.let { it.userId != post.author.userId } == true) {
                            { onBlockAuthor(post) }
                        } else null,
                    )
                }
            }
            if (state.hasMoreFeed) {
                item(key = "load-more", span = StaggeredGridItemSpan.FullLine) {
                    CommunityLoadMoreFooter(
                        loading = state.loadingMore,
                        failed = autoLoadFailed,
                        onRetry = { autoLoadFailed = false; onLoadMore() },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = onCompose,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = bottomInset),
        ) {
            Icon(AppIcons.Edit, contentDescription = "发布帖子")
        }
    }
}

/**
 * 瀑布流卡片：有图帖首图满宽、按原图宽高比铺陈，图下信息条显示作者名与点赞/评论计数，
 * 多图叠「+N」角标；纯文字帖走 surface 变体底色的文字卡，正文限行、点击进详情看全文。
 * 信息条末尾的「帖子操作」按钮打开与信息流卡片一致的操作菜单（编辑、举报、置顶、精华、删除、屏蔽作者）。
 */
@Composable
private fun CommunityWaterfallCard(
    post: CommunityPost,
    token: String?,
    canPin: Boolean,
    canFeature: Boolean,
    canEdit: Boolean,
    onImageClick: (String) -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onReport: () -> Unit,
    onPin: (Boolean) -> Unit,
    onFeature: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onBlockAuthor: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val firstMedia = post.media.firstOrNull()
    val likeTint by animateColorAsState(
        targetValue = if (post.likedByViewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionAnimationSpec(180),
        label = "waterfallLikeTint",
    )
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardDefaults.elevatedShape)
            .animatedPressClick(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (firstMedia == null) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        if (firstMedia != null) {
            Box {
                AsyncImage(
                    model = authenticatedCommunityImageModel(firstMedia.url, token),
                    contentDescription = "帖子图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(firstMedia.displayAspectRatio(WATERFALL_IMAGE_FALLBACK_RATIO))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onImageClick(firstMedia.url) },
                )
                if (post.media.size > 1) {
                    Text(
                        "+${post.media.size - 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .background(
                                Color.Black.copy(alpha = 0.62f),
                                RoundedCornerShape(topEnd = 8.dp, bottomStart = 8.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (post.isPinned || post.isFeatured) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (post.isPinned) {
                        Icon(
                            AppIcons.Pin,
                            contentDescription = "已置顶",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    if (post.isFeatured) {
                        Text(
                            "精华",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (post.isQuarantined) {
                Text(
                    "这条帖子正在等待管理员复审，目前仅你自己可见。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (post.content.isNotBlank()) {
                Text(
                    post.content,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (firstMedia != null) FontWeight.Medium else null,
                    maxLines = if (firstMedia != null) 2 else WATERFALL_TEXT_CARD_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    post.author.nickname,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (post.likedByViewer) AppIcons.HeartFilled else AppIcons.Heart,
                    contentDescription = null,
                    tint = likeTint,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    post.likeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    AppIcons.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    post.commentCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (post.canReport || post.canDelete || canPin || canFeature || canEdit || onBlockAuthor != null) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                            Icon(AppIcons.MoreVertical, contentDescription = "帖子操作", modifier = Modifier.size(17.dp))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (canEdit) {
                                DropdownMenuItem(
                                    text = { Text("编辑") },
                                    leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                                    onClick = { menuExpanded = false; onEdit() },
                                )
                            }
                            if (post.canReport) {
                                DropdownMenuItem(
                                    text = { Text("举报违规") },
                                    leadingIcon = { Icon(AppIcons.Report, contentDescription = null) },
                                    onClick = { menuExpanded = false; onReport() },
                                )
                            }
                            if (canPin) {
                                DropdownMenuItem(
                                    text = { Text(if (post.isPinned) "取消置顶" else "置顶帖子") },
                                    leadingIcon = { Icon(AppIcons.Pin, contentDescription = null) },
                                    onClick = { menuExpanded = false; onPin(!post.isPinned) },
                                )
                            }
                            if (canFeature) {
                                DropdownMenuItem(
                                    text = { Text(if (post.isFeatured) "取消精华" else "设为精华") },
                                    leadingIcon = { Icon(AppIcons.BookmarkFilled, contentDescription = null) },
                                    onClick = { menuExpanded = false; onFeature(!post.isFeatured) },
                                )
                            }
                            if (post.canDelete) {
                                DropdownMenuItem(
                                    text = { Text("删除帖子") },
                                    leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                                    onClick = { menuExpanded = false; onDelete() },
                                )
                            }
                            if (onBlockAuthor != null) {
                                DropdownMenuItem(
                                    text = { Text("屏蔽作者") },
                                    leadingIcon = { Icon(AppIcons.Block, contentDescription = null) },
                                    onClick = { menuExpanded = false; onBlockAuthor() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FeedSortChips(current: String, featuredOnly: Boolean, onSelect: (String, Boolean) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(listOf(
            "newest" to "最新",
            "oldest" to "最旧",
            "likes" to "点赞最多",
            "comments" to "评论最多",
        )) { (value, label) ->
            FilterChip(
                selected = current == value,
                onClick = { onSelect(value, featuredOnly) },
                label = { Text(label) },
            )
        }
        item { FilterChip(
            selected = featuredOnly,
            onClick = { onSelect(current, !featuredOnly) },
            label = { Text("精华") },
        ) }
    }
}

@Composable
private fun CommunityGroupHeader(
    state: CommunityUiState,
    onToggleMessages: (Boolean) -> Unit,
    onCharacterCommunityRequested: () -> Unit,
) {
    val group = state.group ?: return
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.motionAnimateContentSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
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
                        AppIcons.Chat,
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
                                AppIcons.Verified,
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
                    )
                }
                Icon(
                    if (expanded) AppIcons.Collapse else AppIcons.Expand,
                    contentDescription = if (expanded) "收起群信息" else "展开群信息",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = motionExpandIn(),
                exit = motionShrinkOut(),
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    if (state.session != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (group.membership?.receiveMessages == false) AppIcons.NotificationOff else AppIcons.Chat,
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
                        Icon(AppIcons.FolderShared, contentDescription = null)
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
    onBlockAuthor: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val likeTint by animateColorAsState(
        targetValue = if (post.likedByViewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionAnimationSpec(180),
        label = "feedLikeTint",
    )
    val favoriteTint by animateColorAsState(
        targetValue = if (post.isFavorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionAnimationSpec(180),
        label = "feedFavoriteTint",
    )
    ElevatedCard(
        modifier = onClick?.let { modifier.clip(CardDefaults.elevatedShape).animatedPressClick(onClick = it) } ?: modifier,
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
                        AppIcons.Pin,
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
                if (post.canReport || post.canDelete || canPin || canFeature || canEdit || onBlockAuthor != null) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(AppIcons.MoreVertical, contentDescription = "帖子操作")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (canEdit) {
                                DropdownMenuItem(
                                    text = { Text("编辑") },
                                    leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit()
                                    },
                                )
                            }
                            if (post.canReport) {
                                DropdownMenuItem(
                                    text = { Text("举报违规") },
                                    leadingIcon = { Icon(AppIcons.Report, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onReport()
                                    },
                                )
                            }
                            if (canPin) {
                                DropdownMenuItem(
                                    text = { Text(if (post.isPinned) "取消置顶" else "置顶帖子") },
                                    leadingIcon = { Icon(AppIcons.Pin, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onPin(!post.isPinned)
                                    },
                                )
                            }
                            if (canFeature) {
                                DropdownMenuItem(
                                    text = { Text(if (post.isFeatured) "取消精华" else "设为精华") },
                                    leadingIcon = { Icon(AppIcons.BookmarkFilled, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onFeature(!post.isFeatured)
                                    },
                                )
                            }
                            if (post.canDelete) {
                                DropdownMenuItem(
                                    text = { Text("删除帖子") },
                                    leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                )
                            }
                            if (onBlockAuthor != null) {
                                DropdownMenuItem(
                                    text = { Text("屏蔽作者") },
                                    leadingIcon = { Icon(AppIcons.Block, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onBlockAuthor()
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
            post.watchReference?.let { WatchReferenceCard(it) }
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
                val motionLevel = LocalMotionLevel.current
                IconButton(onClick = onLike) {
                    AnimatedContent(
                        targetState = post.likedByViewer,
                        transitionSpec = { motionFadeThroughSpec(motionLevel, 150, 90) },
                        label = "feedLikeIcon",
                    ) { liked ->
                        Icon(
                            if (liked) AppIcons.HeartFilled else AppIcons.Heart,
                            contentDescription = if (liked) "取消点赞" else "点赞",
                            tint = likeTint,
                        )
                    }
                }
                Text(
                    post.likeCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onComment) {
                    Icon(AppIcons.Chat, contentDescription = "评论")
                }
                Text(
                    post.commentCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onFavorite) {
                    AnimatedContent(
                        targetState = post.isFavorited,
                        transitionSpec = { motionFadeThroughSpec(motionLevel, 150, 90) },
                        label = "feedFavoriteIcon",
                    ) { favorited ->
                        Icon(
                            if (favorited) AppIcons.StarFilled else AppIcons.Star,
                            contentDescription = if (favorited) "取消收藏" else "收藏",
                            tint = favoriteTint,
                        )
                    }
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
internal fun CommunityComposerEntry(
    text: String,
    imageCount: Int,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick)
                .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(AppIcons.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text.ifBlank { if (imageCount > 0) "继续编辑草稿" else "分享此刻的追番心情…" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else if (imageCount > 0) Text("$imageCount 张图", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun CommunityComposer(
    text: String,
    images: List<Uri>,
    draftPresetMedia: List<String>,
    maxImages: Int,
    maxTextLength: Int,
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
    draftTags: List<String> = emptyList(),
    tagSuggestions: List<com.animeow.app.data.community.CommunityTag>? = null,
    onAddTag: ((String) -> Unit)? = null,
    onRemoveTag: (String) -> Unit = {},
    imageFirst: Boolean = false,
    watchReferenceId: Long? = null,
    onRemoveWatchReference: () -> Unit = {},
    watchApi: com.animeow.app.data.community.WatchApi? = null,
    modifier: Modifier = Modifier,
) {
    var showPresetPicker by remember { mutableStateOf(false) }
    val totalSelected = images.size + draftPresetMedia.size
    Surface(
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (watchReferenceId != null) WatchReferenceCard(null, watchReferenceId, onRemoveWatchReference, watchApi)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                busyLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (imageFirst && totalSelected == 0) {
                // 图片优先：先给出醒目的选图区域，文字作为配图说明。
                OutlinedButton(
                    onClick = onPickImages,
                    enabled = !busy && totalSelected < maxImages,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("添加图片（最多 $maxImages 张）")
                    }
                }
            }
            if (!imageFirst) OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (watchReferenceId != null) "写点转发感想（可选）…" else "和大家聊聊最近在看的作品…") },
                minLines = 3,
                maxLines = 6,
                enabled = !busy,
                shape = MaterialTheme.shapes.large,
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text("收起后可继续编辑", modifier = Modifier.weight(1f))
                        Text("${text.length} / $maxTextLength")
                    }
                },
            )
            // 统一预览行：普通图片 + 预设贴纸
            if (totalSelected > 0) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 普通图片
                    itemsIndexed(images, key = { _, uri -> uri.toString() }) { index, uri ->
                        DraftImageCell(
                            model = uri,
                            label = "图片 ${index + 1}",
                            canMoveLeft = index > 0,
                            canMoveRight = index < images.size - 1,
                            canCrop = false,
                            enabled = !busy,
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
                            canMoveLeft = index > 0,
                            canMoveRight = index < draftPresetMedia.size - 1,
                            canCrop = true,
                            enabled = !busy,
                            onRemove = { onRemovePresetMedia(name) },
                            onMoveLeft = { onMovePresetMedia(index, -1) },
                            onMoveRight = { onMovePresetMedia(index, 1) },
                            onCrop = { onCropPresetMedia(name) },
                        )
                    }
                }
            }
            if (imageFirst) OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (watchReferenceId != null) "写点转发感想（可选）…" else "为图片写一段说明（可选）…") },
                minLines = 2,
                maxLines = 6,
                enabled = !busy,
                shape = MaterialTheme.shapes.large,
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text("收起后可继续编辑", modifier = Modifier.weight(1f))
                        Text("${text.length} / $maxTextLength")
                    }
                },
            )
            // 标签接口不可用（旧服务器）时不显示标签输入，避免发出会被忽略的标签。
            if (onAddTag != null && tagSuggestions != null) {
                CommunityDraftTagEditor(
                    tags = draftTags,
                    suggestions = tagSuggestions,
                    enabled = !busy,
                    onAdd = onAddTag,
                    onRemove = onRemoveTag,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onPickImages,
                    enabled = !busy && totalSelected < maxImages,
                ) { Icon(AppIcons.Image, contentDescription = "选择图片") }
                IconButton(
                    onClick = { showPresetPicker = true },
                    enabled = !busy && (totalSelected < maxImages || draftPresetMedia.isNotEmpty()),
                ) { Icon(AppIcons.Smile, contentDescription = "选择贴纸/表情") }
                Text("$totalSelected/$maxImages", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onPublish,
                    enabled = !busy && (text.isNotBlank() || totalSelected > 0 || watchReferenceId != null),
                ) {
                    Text("发布")
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
    enabled: Boolean,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onCrop: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            AsyncImage(
                model = model,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(104.dp).clip(RoundedCornerShape(12.dp)),
            )
            // 删除按钮（保留 48dp 热区，视觉圆钮紧凑不遮图）
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
            ) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f)) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = "移除$label",
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(4.dp).size(16.dp),
                    )
                }
            }
            if (canCrop) {
                IconButton(
                    onClick = onCrop,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.BottomEnd).size(48.dp),
                ) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f)) {
                        Icon(
                            AppIcons.Crop,
                            contentDescription = "裁剪$label",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(5.dp).size(14.dp),
                        )
                    }
                }
            }
        }
        // 左右调序胶囊（宽度与 104dp 缩略图对齐，不再把间距撑宽到 144dp）
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                IconButton(onClick = onMoveLeft, enabled = enabled && canMoveLeft, modifier = Modifier.size(48.dp, 36.dp)) {
                    Icon(
                        AppIcons.ChevronLeft,
                        contentDescription = "左移$label",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onMoveRight, enabled = enabled && canMoveRight, modifier = Modifier.size(48.dp, 36.dp)) {
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = "右移$label",
                        modifier = Modifier.size(18.dp),
                    )
                }
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
                Icon(AppIcons.Verified, contentDescription = null, modifier = Modifier.size(48.dp))
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

@Composable
internal fun CommunityCommentRow(
    comment: CommunityComment,
    token: String?,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: () -> Unit,
    replyTo: String? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        AppIcons.MoreHorizontal,
                        contentDescription = "更多评论操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("复制内容") },
                        leadingIcon = { Icon(AppIcons.Copy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("评论", comment.content)))
                            }
                        },
                    )
                    if (onEdit != null) {
                        DropdownMenuItem(
                            text = { Text("编辑评论") },
                            leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                            onClick = { menuExpanded = false; onEdit() },
                        )
                    }
                    if (comment.canDelete) {
                        DropdownMenuItem(
                            text = { Text("删除评论") },
                            leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
        if (replyTo != null) Text("回复 $replyTo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReply, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("回复") }
            TextButton(onClick = onLike, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Icon(
                    if (comment.likedByViewer) AppIcons.HeartFilled else AppIcons.Heart,
                    contentDescription = if (comment.likedByViewer) "取消点赞" else "点赞",
                    tint = if (comment.likedByViewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                if (comment.likeCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(comment.likeCount.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CommunitySearchDialog(
    results: List<CommunityPost>,
    searching: Boolean,
    token: String?,
    onSearch: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onImageClick: (String) -> Unit,
    onAuthorClick: (Long) -> Unit,
    onPostClick: (CommunityPost) -> Unit,
    error: String? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("newest") }
    var featuredOnly by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (query.isNotBlank()) onSearch(query, sort, featuredOnly) }
    ManagementListDialog("搜索帖子", onDismiss) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("query") {
                OutlinedTextField(value = query, onValueChange = {
                    query = it; onSearch(it, sort, featuredOnly)
                }, modifier = Modifier.fillMaxWidth(), label = { Text("帖子内容或用户名") }, singleLine = true)
            }
            item("filters") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("newest" to "最新", "oldest" to "最旧", "likes" to "点赞最多", "comments" to "评论最多").forEach { (value, label) ->
                        FilterChip(selected = sort == value && !featuredOnly, onClick = {
                            sort = value; featuredOnly = false; onSearch(query, sort, featuredOnly)
                        }, label = { Text(label) })
                    }
                    FilterChip(selected = featuredOnly, onClick = {
                        featuredOnly = !featuredOnly; onSearch(query, sort, featuredOnly)
                    }, label = { Text("精华") })
                }
            }
            when {
                searching -> item("loading") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                error != null -> item("error") { com.animeow.app.ui.components.LoadErrorCard("搜索暂时不可用", error,
                    onRetry = { onSearch(query, sort, featuredOnly) }) }
                results.isEmpty() && query.isNotBlank() -> item("empty") { Text("没有找到相关帖子", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> items(results, key = CommunityPost::id) { post ->
                    CommunityPostCard(post = post, token = token, onAuthorClick = { onAuthorClick(post.author.userId) },
                        onReport = {}, onDelete = {}, onLike = {}, onComment = { onPostClick(post) }, onFavorite = {},
                        onPin = {}, canPin = false, onFeature = {}, canFeature = false, onEdit = {}, canEdit = false,
                        onImageClick = onImageClick, onClick = { onPostClick(post) })
                }
            }
        }
    }
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
        title = { Text("屏蔽管理") },
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
