package com.animeow.app.ui.community

import com.animeow.app.ui.icons.AppIcons

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.animeow.app.ui.icons.IconGlyph
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
import com.animeow.app.data.community.CommunityModerationLogEntry
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.CommunityResolvedReview
import com.animeow.app.data.community.CommunityReviewCase
import com.animeow.app.data.community.CommunityStats
import com.animeow.app.data.community.SensitiveWord
import kotlinx.coroutines.launch

/**
 * 管理员 / 开发者的独立管理界面：与普通用户的社区界面分离，
 * 社区主界面仅保留一个“社区管理”入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = viewModel(),
    safetyViewModel: CommunitySafetyViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val safety by safetyViewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var showReviews by rememberSaveable { mutableStateOf(false) }
    var showImages by rememberSaveable { mutableStateOf(false) }
    var showSafetyUsers by rememberSaveable { mutableStateOf(false) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var showResolvedReviews by rememberSaveable { mutableStateOf(false) }
    var showModerationLog by rememberSaveable { mutableStateOf(false) }
    var showUserManagement by rememberSaveable { mutableStateOf(false) }
    var showSensitiveWords by rememberSaveable { mutableStateOf(false) }
    var reviewTarget by remember { mutableStateOf<Pair<CommunityReviewCase, Boolean>?>(null) }

    val isAdmin = state.currentUser?.isAdmin == true
    val isDeveloper = state.currentUser?.isDeveloper == true
    val token = state.session?.token.orEmpty()

    LaunchedEffect(Unit) { viewModel.refreshSession() }
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose {
            viewModel.closeReviews(); viewModel.closeStats(); viewModel.closeUsers()
            viewModel.closeSensitiveWords(); viewModel.closeModerationLog(); viewModel.closeResolvedReviews()
        }
    }
    LaunchedEffect(isDeveloper) {
        if (!isDeveloper) {
            showResolvedReviews = false; showModerationLog = false
            showUserManagement = false; showSensitiveWords = false
        }
    }
    LaunchedEffect(isAdmin, isDeveloper) {
        if (isAdmin || isDeveloper) safetyViewModel.refreshSummary()
        else { showReviews = false; showStats = false; reviewTarget = null }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (showImages) {
        CommunityImageReviewScreen(onBack = { showImages = false; safetyViewModel.refreshSummary() }, viewModel = safetyViewModel)
        return
    }
    if (showSafetyUsers) {
        CommunitySafetyUsersScreen(onBack = { showSafetyUsers = false }, viewModel = safetyViewModel)
        return
    }
    BackHandler(enabled = showReviews) { viewModel.closeReviews(); showReviews = false; safetyViewModel.refreshSummary() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(if (showReviews) "待复审举报" else "审核工作台") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showReviews) { viewModel.closeReviews(); showReviews = false } else onBack()
                    }) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isAdmin) {
                        val watchContext = LocalContext.current
                        TextButton(onClick = { watchContext.startActivity(com.animeow.app.ui.playback.WatchDiscussionActivity.admin(watchContext)) }) { Text("观看互动") }
                    }
                    if (showReviews) {
                        IconButton(onClick = viewModel::refreshReviews, enabled = !state.reviewBusy && !state.reviewsLoading) {
                            Icon(AppIcons.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (showReviews) {
            CommunityReviewList(
                reviews = state.reviews,
                token = token,
                busy = state.reviewBusy,
                busyLabel = if (state.reviewBusy) "正在提交复审结论…" else "正在读取待复审内容…",
                loading = state.reviewsLoading,
                error = state.reviewsError,
                onRetry = viewModel::refreshReviews,
                onResolve = { review, confirm -> reviewTarget = review to confirm },
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isAdmin && !isDeveloper) {
                    item {
                        CommunityUnavailable(
                            title = "无管理权限",
                            body = "仅管理员或开发者可以进入社区管理界面。",
                        )
                    }
                    return@LazyColumn
                }
                item {
                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.currentUser?.let { CommunityAvatar(it, token, Modifier.size(48.dp)) }
                                Column {
                                    Text(if (isDeveloper) "神秘人的工作台" else "社区管理员", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("让每一次交流都安心", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Text(if (safety.unavailable) "原有举报复审可用；更新服务器后启用图片审核和社区权限管理。" else if (safety.summary.pendingCount == 0) "当前没有新的审核待办" else "${safety.summary.pendingCount} 项内容等待处理", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showImages = true }) { Text("图片 ${safety.summary.imageCount}") }
                                TextButton(onClick = { showReviews = true; viewModel.refreshReviews() }) { Text("举报 ${safety.summary.reportCount}") }
                            }
                        }
                    }
                }
                state.currentUser?.let { user -> item { ReviewNotificationSettings(user.userId) } }
                item { ManagementSectionTitle("内容与用户") }
                item {
                    ManagementCard(icon = AppIcons.CheckCircle, title = "待审核图片", subtitle = "自定义头像与帖子配图，检查后通过或拒绝", onClick = { showImages = true })
                }
                item {
                    ManagementCard(icon = AppIcons.Group, title = "社区用户与头像", subtitle = if (isDeveloper) "查看所有头像、重置头像、限制或恢复社区操作" else "按操作设置限制、调整时长与解除限制", onClick = { showSafetyUsers = true })
                }
                item {
                    ManagementCard(
                        icon = AppIcons.Report,
                        title = "待复审举报",
                        subtitle = "处理被举报并临时隐藏的内容",
                        onClick = {
                            showReviews = true
                            viewModel.refreshReviews()
                        },
                    )
                }
                item {
                    ManagementCard(
                        icon = AppIcons.Statistics,
                        title = "统计看板",
                        subtitle = "帖子、评论、举报与隔离数据",
                        onClick = {
                            showStats = true
                            viewModel.refreshStats()
                        },
                    )
                }
                if (isDeveloper) {
                    item { ManagementSectionTitle("开发者工具") }
                    item {
                        ManagementCard(
                            icon = AppIcons.CheckCircle,
                            title = "已处理复审",
                            subtitle = "查看并强行修改历史处理结果",
                            onClick = {
                                showResolvedReviews = true
                                viewModel.refreshResolvedReviews()
                            },
                        )
                    }
                    item {
                        ManagementCard(
                            icon = AppIcons.History,
                            title = "审核操作记录",
                            subtitle = "查看管理操作审计",
                            onClick = {
                                showModerationLog = true
                                viewModel.refreshModerationLog()
                            },
                        )
                    }
                    item {
                        ManagementCard(
                            icon = AppIcons.Group,
                            title = "管理员任命",
                            subtitle = "搜索、筛选用户并任命管理员",
                            onClick = {
                                showUserManagement = true
                                viewModel.refreshUsers()
                            },
                        )
                    }
                    item {
                        ManagementCard(
                            icon = AppIcons.Block,
                            title = "敏感词管理",
                            subtitle = "添加、删除、一键导入与导出",
                            onClick = {
                                showSensitiveWords = true
                                viewModel.refreshSensitiveWords()
                            },
                        )
                    }
                }
            }
        }
    }

    reviewTarget?.let { (review, confirm) ->
        CommunityResolveReviewDialog(
            review = review,
            confirmViolation = confirm,
            busy = state.reviewBusy,
            onDismiss = { reviewTarget = null },
            onConfirm = { note ->
                viewModel.resolveReview(review, confirm, note)
                reviewTarget = null
            },
        )
    }

    if (showStats) {
        CommunityStatsDialog(
            stats = state.stats,
            auditExport = state.auditExport,
            onDismiss = { viewModel.closeStats(); showStats = false },
            onExport = viewModel::exportAudit,
            onClearAudit = viewModel::clearAuditExport,
            loading = state.statsLoading,
            error = state.statsError,
            onRetry = viewModel::refreshStats,
            canExport = isDeveloper,
            auditLoading = state.auditLoading,
            auditError = state.auditError,
        )
    }

    if (showResolvedReviews) {
        CommunityResolvedReviewsDialog(
            entries = state.resolvedReviews,
            isDeveloper = isDeveloper,
            onDismiss = { viewModel.closeResolvedReviews(); showResolvedReviews = false },
            onOverride = { review, confirm -> viewModel.overrideReview(review, confirm, "") },
            loading = state.resolvedReviewsLoading,
            error = state.resolvedReviewsError,
            busy = state.busy || state.administrationBusy,
            onRetry = viewModel::refreshResolvedReviews,
        )
    }

    if (showModerationLog) {
        CommunityModerationLogDialog(
            entries = state.moderationLog,
            onDismiss = { viewModel.closeModerationLog(); showModerationLog = false },
            loading = state.moderationLogLoading,
            error = state.moderationLogError,
            onRetry = viewModel::refreshModerationLog,
        )
    }

    if (showUserManagement) {
        CommunityUserManagementDialog(
            users = state.users,
            loading = state.usersLoading,
            busy = state.busy || state.administrationBusy,
            onQueryChanged = viewModel::refreshUsers,
            onDismiss = { viewModel.closeUsers(); showUserManagement = false },
            onToggleAdmin = viewModel::setUserAdmin,
            error = state.usersError,
            busyLabel = state.administrationBusyLabel,
        )
    }

    if (showSensitiveWords) {
        CommunitySensitiveWordsDialog(
            words = state.sensitiveWords,
            total = state.sensitiveWordsTotal,
            currentPage = state.sensitiveWordsPage,
            search = state.sensitiveWordsSearch,
            loading = state.sensitiveWordsLoading,
            busy = state.busy || state.administrationBusy,
            error = state.sensitiveWordsError,
            pageSize = state.sensitiveWordsPageSize,
            onRefresh = viewModel::refreshSensitiveWords,
            onCancel = viewModel::cancelSensitiveWordsLoading,
            onDismiss = { viewModel.closeSensitiveWords(); showSensitiveWords = false },
            onAdd = viewModel::addSensitiveWord,
            onDelete = viewModel::deleteSensitiveWord,
            onImport = viewModel::importSensitiveWords,
            onSearch = viewModel::searchSensitiveWords,
            onPageChange = viewModel::loadSensitiveWordsPage,
        )
    }
}

@Composable
private fun ManagementSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ManagementCard(
    icon: IconGlyph,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun CommunityReviewList(
    reviews: List<CommunityReviewCase>,
    token: String,
    busy: Boolean,
    busyLabel: String?,
    onResolve: (CommunityReviewCase, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if ((busy || loading) && reviews.isEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(busyLabel ?: "正在加载…")
                }
            }
        } else if (error != null && reviews.isEmpty()) {
            item { com.animeow.app.ui.components.LoadErrorCard("待复审内容暂时无法读取", error, onRetry) }
        } else if (reviews.isEmpty()) {
            item { CommunityUnavailable("暂无待复审内容", "所有举报都已经处理完成。") }
        } else {
            if (error != null) item("error") { com.animeow.app.ui.components.LoadErrorCard("读取未完成", error, onRetry) }
            items(reviews, key = CommunityReviewCase::id) { review ->
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CommunityAvatar(review.author, token, Modifier.size(42.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(review.author.nickname, fontWeight = FontWeight.Bold)
                                Text(
                                    "${review.reportCount} 次举报 · ${review.quarantinedPostCount} 条临时隐藏",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        review.reports.forEach { report ->
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        communityReportReasonLabel(report.reason),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    if (report.details.isNotBlank()) Text(report.details, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "举报人：${report.reporterUsername}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        Text("隔离内容", style = MaterialTheme.typography.titleSmall)
                        review.posts.forEach { post ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        post.content.ifBlank { "（仅图片）" },
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (post.media.isNotEmpty()) {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(post.media, key = { it.id }) { media ->
                                                AsyncImage(
                                                    model = authenticatedCommunityImageModel(media.url, token),
                                                    contentDescription = "待复审图片",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
                                                )
                                            }
                                        }
                                    }
                                    Text("帖子 #${post.id}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onResolve(review, false) },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("恢复全部") }
                            Button(
                                onClick = { onResolve(review, true) },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("确认违规") }
                        }
                    }
                }
            }
        }
    }
}


internal fun moderationActionLabel(action: String): String = when (action) {
    "resolve_restore" -> "恢复帖子"
    "resolve_confirm" -> "确认违规"
    "force_override" -> "强行修改处理"
    "delete_post" -> "删除帖子"
    "pin_post" -> "置顶帖子"
    "unpin_post" -> "取消置顶"
    "feedback_status" -> "修改反馈状态"
    "feedback_reply" -> "回复反馈"
    "appoint_admin" -> "任命管理员"
    "revoke_admin" -> "取消管理员"
    else -> action
}



@Composable
internal fun CommunityStatsDialog(
    stats: CommunityStats?, auditExport: String?, onDismiss: () -> Unit,
    onExport: () -> Unit, onClearAudit: () -> Unit,
    loading: Boolean = false, error: String? = null, onRetry: () -> Unit = {},
    canExport: Boolean = false, auditLoading: Boolean = false, auditError: String? = null,
) {
    ManagementListDialog("统计看板", onDismiss, bottomBar = {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.End) {
            if (canExport) {
                if (auditExport != null || auditLoading) TextButton(onClick = onClearAudit) { Text("收起导出") }
                else TextButton(onClick = onExport) { Text("导出审计") }
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) item { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item { com.animeow.app.ui.components.LoadErrorCard("统计暂时无法读取", error, onRetry) }
            stats?.let { s -> item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("帖子：${s.postCount}"); Text("评论：${s.commentCount}")
                    Text("点赞：${s.likeCount}"); Text("收藏：${s.favoriteCount}")
                    Text("屏蔽：${s.blockCount}"); Text("用户：${s.userCount}")
                    Text("待处理举报：${s.pendingReports}"); Text("隔离记录：${s.quarantineCount}")
                    Text("平均举报处理耗时：${s.avgReportResolutionSeconds} 秒")
                }
            } }
            if (canExport && auditLoading) item { Text("正在读取审计…") }
            if (canExport && auditError != null) item { com.animeow.app.ui.components.LoadErrorCard("审计暂时无法读取", auditError, onExport) }
            if (canExport && auditExport != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text("审计导出结果（JSON）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    SelectionContainer { Text(auditExport, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
