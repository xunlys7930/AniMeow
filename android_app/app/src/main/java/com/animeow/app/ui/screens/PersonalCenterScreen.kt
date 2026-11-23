package com.animeow.app.ui.screens

import com.animeow.app.ui.icons.AppIcons

import com.animeow.app.ui.navigation.LocalFloatingNavigationInset

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Brush
import com.animeow.app.ui.icons.IconGlyph
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.BuildConfig
import com.animeow.app.data.playback.PlaybackDownloads
import com.animeow.app.data.playback.PlaybackHistory
import com.animeow.app.ui.playback.PlaybackActivity
import com.animeow.app.ui.playback.playbackTime
import androidx.media3.exoplayer.offline.Download
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.PersonalActivityTab
import com.animeow.app.ui.community.CommunityAvatar
import com.animeow.app.ui.community.CommunityProfileEditorDialog
import com.animeow.app.ui.community.CommunityInboxButton
import com.animeow.app.ui.community.authenticatedCommunityImageModel
import com.animeow.app.ui.community.formatCommunityTime
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.statistics.StatisticsUiState
import com.animeow.app.ui.statistics.StatisticsViewModel
import com.animeow.app.ui.maintenance.SupportDevelopmentCard
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalCenterScreen(
    onCloudAccountRequested: () -> Unit,
    onCommunityRequested: () -> Unit,
    onCreatePostRequested: () -> Unit,
    onPostClick: (Long) -> Unit,
    onSettingsRequested: () -> Unit,
    onToolsRequested: () -> Unit,
    onStatisticsRequested: () -> Unit,
    onRemindersRequested: () -> Unit,
    onCustomizationRequested: () -> Unit,
    modifier: Modifier = Modifier,
    onMessagesRequested: (() -> Unit)? = null,
    unreadMessages: Int = 0,
    onAnnouncementsRequested: (() -> Unit)? = null,
    unreadAnnouncements: Int = 0,
    onBack: (() -> Unit)? = null,
    showVersion: Boolean = true,
    showCommunityActivity: Boolean = true,
    viewModel: PersonalCenterViewModel = viewModel(),
    statisticsViewModel: StatisticsViewModel = viewModel(),
    onSupportRequested: (() -> Unit)? = null,
    onOpenAnimeDetail: (Long) -> Unit = {},
    onLegadoSyncRequested: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val statistics by statisticsViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showEditProfile by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(showCommunityActivity) {
        viewModel.onResume(showCommunityActivity)
        onPauseOrDispose {}
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    LaunchedEffect(state.profileSaved) {
        if (state.profileSaved) { showEditProfile = false; viewModel.consumeProfileSaved() }
    }
    LaunchedEffect(state.session?.userId) { if (state.session == null) showEditProfile = false }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalFloatingNavigationInset.current))
        },
        topBar = {
            TopAppBar(
                title = { Text(if (onBack == null) "我的" else "个人中心") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    onMessagesRequested?.let { CommunityInboxButton(unreadMessages, it) }
                    IconButton(onClick = onSettingsRequested) {
                        Icon(AppIcons.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PersonalCenterContent(
            state = state,
            statistics = statistics,
            showVersion = showVersion,
            showCommunityActivity = showCommunityActivity,
            onTabSelected = viewModel::selectTab,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onLogin = onCloudAccountRequested,
            onEditProfile = { viewModel.loadProfilePresets(); showEditProfile = true },
            onCommunity = onCommunityRequested,
            onCreatePost = onCreatePostRequested,
            onPostClick = onPostClick,
            onTools = onToolsRequested,
            onAnnouncements = onAnnouncementsRequested,
            unreadAnnouncements = unreadAnnouncements,
            onStatistics = onStatisticsRequested,
            onReminders = onRemindersRequested,
            onCustomization = onCustomizationRequested,
            modifier = Modifier.padding(padding),
            onSupport = onSupportRequested,
            onOpenAnimeDetail = onOpenAnimeDetail,
            onLegadoSync = onLegadoSyncRequested,
        )
    }
    if (showEditProfile && state.profile != null) {
        CommunityProfileEditorDialog(
            profile = state.profile!!,
            localStatistics = statistics,
            token = state.session?.token,
            busy = state.savingProfile,
            presetImages = state.presetImages,
            presetDisclaimer = state.presetDisclaimer,
            saveError = state.profileSaveError,
            onDismiss = { if (!state.savingProfile) showEditProfile = false },
            onSave = { nickname, signature, privateProfile, showStatistics, selected, avatar, removeAvatar, presetAvatar ->
                viewModel.updateProfile(nickname, signature, privateProfile, showStatistics, selected, avatar, removeAvatar, presetAvatar, statistics)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalCenterContent(
    state: PersonalCenterUiState,
    statistics: StatisticsUiState,
    showVersion: Boolean,
    onTabSelected: (PersonalActivityTab) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLogin: () -> Unit,
    onEditProfile: () -> Unit,
    onCommunity: () -> Unit,
    onCreatePost: () -> Unit,
    onPostClick: (Long) -> Unit,
    onTools: () -> Unit,
    onStatistics: () -> Unit,
    onReminders: () -> Unit,
    onCustomization: () -> Unit,
    onAnnouncements: (() -> Unit)? = null,
    unreadAnnouncements: Int = 0,
    modifier: Modifier = Modifier,
    onSupport: (() -> Unit)? = null,
    onOpenAnimeDetail: (Long) -> Unit = {},
    onLegadoSync: () -> Unit = {},
    showCommunityActivity: Boolean = true,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val activity = state.activity
    var showRecentBooks by rememberSaveable { mutableStateOf(false) }
    if (showRecentBooks) {
        com.animeow.app.ui.reader.RecentBookReadingSheet(
            onDismiss = { showRecentBooks = false },
            onOpenAnimeDetail = onOpenAnimeDetail,
        )
    }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = maxOf(32.dp, LocalFloatingNavigationInset.current + 20.dp)),
        ) {
            item(key = "identity") {
                PersonalIdentity(state, statistics, onLogin, onEditProfile, onStatistics)
            }
            item(key = "shortcuts") {
                PersonalShortcuts(onReminders, onLogin, onCustomization, onTools)
            }
            item(key = "watch-records") {
                WatchRecordsCard(
                    onBangumiSync = onLogin,
                    onRecentBooks = { showRecentBooks = true },
                    onLegadoSync = onLegadoSync,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (onSupport != null) item(key = "support") {
                SupportDevelopmentCard(onSupport, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (onAnnouncements != null) item(key = "announcements") {
                Surface(shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(MaterialTheme.shapes.large)
                        .animatedPressClick(onClickLabel = "查看消息与公告", onClick = onAnnouncements)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(AppIcons.Notification, null, Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text("消息与公告", fontWeight = FontWeight.SemiBold)
                            Text(if (unreadAnnouncements > 0) "$unreadAnnouncements 条未读 · 来自开发者" else "版本消息与小喵的近况",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(AppIcons.Forward, "查看消息与公告", Modifier.size(18.dp))
                    }
                }
            }
            if (showCommunityActivity) {
                stickyHeader(key = "activity-tabs") {
                    PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                        PersonalActivityTab.entries.forEach { tab ->
                            Tab(
                                selected = state.tab == tab,
                                onClick = {
                                    if (state.tab != tab) {
                                        onTabSelected(tab)
                                        val tabsIndex = 3 + (if (onSupport != null) 1 else 0) + (if (onAnnouncements != null) 1 else 0)
                                        if (listState.firstVisibleItemIndex > tabsIndex) scope.launch { listState.scrollToItem(tabsIndex) }
                                    }
                                },
                                text = { Text(tab.label, fontWeight = if (state.tab == tab) FontWeight.Bold else FontWeight.Normal) },
                            )
                        }
                    }
                }
                if (state.session == null) {
                    item(key = "login") {
                        PersonalEmptyState(
                            icon = AppIcons.Profile,
                            title = "登录，留住每一次分享",
                            message = "登录后，在这里查看发布的帖子、点赞和收藏。",
                            actionLabel = "登录 / 注册",
                            onAction = onLogin,
                        )
                    }
                } else if (!state.configured) {
                    item(key = "unavailable") {
                        PersonalEmptyState(AppIcons.CloudDone, "社区服务暂未启用", "本地追番资料和工具仍可正常使用。", "查看全部工具", onTools)
                    }
                } else if (state.profileError != null) {
                    item(key = "profile-error") {
                        PersonalEmptyState(AppIcons.Refresh, "暂时没能加载", state.profileError, "重试", onRefresh)
                    }
                } else {
                    item(key = "activity-heading") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.tab == PersonalActivityTab.POSTS) "我的发布 · 最新在前" else "按发布时间 · 仅自己可见",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.tab == PersonalActivityTab.POSTS) TextButton(onClick = onCreatePost) {
                                Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("发帖")
                            } else IconButton(onClick = onRefresh, enabled = !state.refreshing && !activity.loading) {
                                Icon(AppIcons.Refresh, contentDescription = "刷新记录", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    items(activity.items, key = { "${state.tab.name}:${it.id}" }) { post ->
                        PersonalPostCard(post, state.session.token, state.tab == PersonalActivityTab.POSTS, motionAnimateItem()) { onPostClick(post.id) }
                    }
                    item(key = "activity-state") {
                        when {
                            activity.loading || state.refreshing -> Box(
                                Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
                            activity.error != null -> PersonalEmptyState(
                                AppIcons.Refresh, "记录加载失败", activity.error, "重试", onLoadMore,
                            )
                            activity.items.isEmpty() -> {
                                val exhausted = activity.loaded && activity.endReached
                                val icon = when (state.tab) {
                                    PersonalActivityTab.POSTS -> AppIcons.Chat
                                    PersonalActivityTab.LIKES -> AppIcons.Heart
                                    PersonalActivityTab.FAVORITES -> AppIcons.Bookmark
                                }
                                PersonalEmptyState(
                                    icon = icon,
                                    title = if (!exhausted) "还没找到相关记录" else when (state.tab) {
                                        PersonalActivityTab.POSTS -> "还没有发布过帖子"
                                        PersonalActivityTab.LIKES -> "还没有点赞记录"
                                        PersonalActivityTab.FAVORITES -> "还没有收藏帖子"
                                    },
                                    message = if (!exhausted) "可以继续查看更早的帖子，查找你的${state.tab.label}记录。" else when (state.tab) {
                                        PersonalActivityTab.POSTS -> "分享最近喜欢的作品，让同好认识你。"
                                        PersonalActivityTab.LIKES -> "在社区点过赞的帖子，会收录在这里。"
                                        PersonalActivityTab.FAVORITES -> "遇到想反复看的帖子，收藏后随时回来。"
                                    },
                                    actionLabel = if (exhausted) "去社区看看" else "继续查找",
                                    onAction = if (exhausted) onCommunity else onLoadMore,
                                )
                            }
                            !activity.endReached -> Column(
                                Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("已加载 ${activity.items.size} 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = onLoadMore) { Text("加载更早记录") }
                            }
                            else -> Text(
                                "共 ${activity.items.size} 条 · 已经到底了",
                                Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (showVersion) item(key = "version") {
                Text(
                    "AniMeow ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun WatchRecordsCard(
    onBangumiSync: () -> Unit,
    onRecentBooks: () -> Unit = {},
    onLegadoSync: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<String?>(null) }
    var downloads by remember { mutableStateOf<String?>(null) }
    var recentReading by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        history = runCatching { PlaybackHistory(context).entries().firstOrNull() }.getOrNull()?.let { entry ->
            listOfNotNull(entry.title, entry.episode.takeIf(String::isNotBlank),
                playbackTime(entry.checkpoint?.positionMs ?: 0)).joinToString(" · ")
        } ?: "暂无播放记录"
        downloads = runCatching { PlaybackDownloads.get(context).list() }.getOrNull()?.let { rows ->
            if (rows.isEmpty()) "暂无下载" else "${rows.count { it.state == Download.STATE_COMPLETED }} 个已下载 · 共 ${rows.size} 个任务"
        } ?: "暂无下载"
        recentReading = runCatching {
            val db = com.animeow.app.data.local.AniMeowDatabase.getInstance(context)
            val latest = db.bookReadingDao().observeRecentStates(1).firstOrNull()?.firstOrNull()
            if (latest == null) {
                "暂无阅读记录 · 支持本地 TXT / EPUB 与《开源阅读》"
            } else {
                val bookTitle = db.libraryDao().getAnime(latest.animeId)?.title ?: "书籍"
                val chapter = when {
                    latest.hasLocalBook -> latest.chapterTitle ?: "第 ${latest.chapterIndex + 1} 章"
                    latest.hasLegadoLink -> latest.legadoChapterTitle ?: "第 ${(latest.legadoChapterIndex ?: 0) + 1} 章"
                    else -> "查看详情"
                }
                "$bookTitle · $chapter"
            }
        }.getOrDefault("续读本地 TXT / EPUB 电子书与《开源阅读》记录")
    }
    LaunchedEffect(Unit) { reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { reload() } }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column {
            WatchRecordRow(AppIcons.History, "观看记录", history ?: "读取中…") {
                context.startActivity(PlaybackActivity.history(context))
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            WatchRecordRow(AppIcons.Book, "最近阅读", recentReading ?: "读取中…", onRecentBooks)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            WatchRecordRow(AppIcons.Download, "下载记录", downloads ?: "读取中…") {
                context.startActivity(PlaybackActivity.downloads(context))
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            WatchRecordRow(AppIcons.CloudDone, "Bangumi 同步", "连接账号、自动同步进度或上传本机收藏", onBangumiSync)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            WatchRecordRow(AppIcons.Sync, "《开源阅读》同步", "从本地备份、WebDAV 或局域网同步小说进度", onLegadoSync)
        }
    }
}

@Composable
private fun WatchRecordRow(icon: IconGlyph, title: String, subtitle: String, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().animatedPressClick(onClick = open).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(AppIcons.Forward, "打开", Modifier.size(18.dp))
    }
}

@Composable
private fun PersonalIdentity(
    state: PersonalCenterUiState,
    statistics: StatisticsUiState,
    onLogin: () -> Unit,
    onEdit: () -> Unit,
    onStatistics: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (com.animeow.app.ui.legacy.LocalFrontendMode.current == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val profile = state.profile
                if (profile != null) CommunityAvatar(profile, state.session?.token, Modifier.size(80.dp), cacheOwnAvatar = true)
                else Surface(Modifier.size(80.dp), shape = CircleShape, color = colors.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Text("喵", style = MaterialTheme.typography.headlineLarge, color = colors.onPrimaryContainer) }
                }
                Text(profile?.nickname ?: state.session?.username ?: "我的追番生活", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(profile?.signature?.takeIf(String::isNotBlank) ?: "记录作品，收藏心动", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = if (state.session == null) onLogin else onEdit, enabled = state.session == null || profile != null) { Text(if (state.session == null) "登录 / 注册" else "编辑个人资料") }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    PersonalStat(Modifier.weight(1f), statistics.totalAnime, "收录作品", onStatistics)
                    PersonalStat(Modifier.weight(1f), statistics.statusCounts.firstOrNull { it.name == "在看" }?.count ?: 0, "正在追", onStatistics)
                    PersonalStat(Modifier.weight(1f), statistics.watchHours, "观看小时", onStatistics)
                }
            }
        }
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerLow,
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = 0.7f), colors.surfaceContainerLow)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val profile = state.profile
                if (profile != null) CommunityAvatar(profile, state.session?.token, Modifier.size(64.dp), cacheOwnAvatar = true)
                else Surface(Modifier.size(64.dp), shape = CircleShape, color = colors.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(state.session?.username?.take(1)?.uppercase() ?: "喵", style = MaterialTheme.typography.headlineSmall, color = colors.onPrimaryContainer)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        profile?.nickname?.ifBlank { null } ?: state.session?.username ?: "欢迎来到 AniMeow",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.session?.let { "@${it.username}" } ?: "记录喜欢，遇见同好",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    state.profile?.signature?.takeIf(String::isNotBlank) ?: if (state.session == null) "你的追番生活，从这里开始。" else "还没有签名，写下此刻的热爱吧。",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = if (state.session == null) onLogin else onEdit, enabled = state.session == null || state.profile != null) {
                    Text(if (state.session == null) "去登录" else if (LocalDensity.current.fontScale >= 1.3f) "编辑" else "编辑资料")
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth()) {
                PersonalStat(Modifier.weight(1f), statistics.totalAnime, "收录作品", onStatistics)
                PersonalStat(Modifier.weight(1f), statistics.statusCounts.firstOrNull { it.name == "在看" }?.count ?: 0, "正在追", onStatistics)
                PersonalStat(Modifier.weight(1f), statistics.watchHours, "观看小时", onStatistics)
            }
        }
    }
}

@Composable
private fun PersonalStat(modifier: Modifier, value: Int, label: String, onClick: () -> Unit) {
    Column(
        modifier.animatedPressClick(role = Role.Button, onClickLabel = "查看追番统计", onClick = onClick).heightIn(min = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonalShortcuts(onReminders: () -> Unit, onCloud: () -> Unit, onCustomize: () -> Unit, onTools: () -> Unit) {
    val actions = listOf(
        Triple(AppIcons.Notification, "追番提醒", onReminders),
        Triple(AppIcons.CloudDone, "云端备份", onCloud),
        Triple(AppIcons.Palette, "外观与阅读", onCustomize),
        Triple(AppIcons.Grid, "全部工具", onTools),
    )
    if (com.animeow.app.ui.legacy.LocalFrontendMode.current == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (icon, label, action) ->
                        Surface(Modifier.weight(1f).animatedPressClick(onClick = action), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.heightIn(min = 60.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val columns = if (maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f) 2 else 4
        Column {
            actions.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (icon, label, action) ->
                        Column(
                            Modifier.weight(1f).clip(MaterialTheme.shapes.large).animatedPressClick(role = Role.Button, onClickLabel = label, onClick = action)
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalPostCard(post: CommunityPost, token: String?, ownPost: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.large).animatedPressClick(role = Role.Button, onClickLabel = "查看帖子详情", onClick = onClick),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ownPost) CommunityAvatar(post.author, token, Modifier.size(28.dp))
                Text(
                    if (ownPost) formatCommunityTime(post.createdAt) else post.author.nickname,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!ownPost) Text(formatCommunityTime(post.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                if (post.isQuarantined) Text("审核中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (post.content.isNotBlank()) Text(post.content, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (post.media.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                post.media.take(3).forEach { media ->
                    AsyncImage(
                        model = authenticatedCommunityImageModel(media.url, token), contentDescription = "帖子配图",
                        modifier = Modifier.weight(1f).aspectRatio(if (post.media.size == 1) 2.3f else 1f)
                            .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(AppIcons.Heart, contentDescription = "点赞数", modifier = Modifier.size(15.dp), tint = if (post.likedByViewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                Text("${post.likeCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Icon(AppIcons.Chat, contentDescription = "评论数", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.outline)
                Text("${post.commentCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (post.isFavorited) Text("已收藏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (post.media.size > 3) Text("${post.media.size} 张图片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun PersonalEmptyState(icon: IconGlyph, title: String, message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(16.dp).size(28.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        FilledTonalButton(onClick = onAction) { Text(actionLabel) }
    }
}
