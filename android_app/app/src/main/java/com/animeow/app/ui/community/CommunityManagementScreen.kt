package com.animeow.app.ui.community

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var showReviews by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(if (showReviews) "待复审举报" else "社区管理") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showReviews) showReviews = false else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (showReviews) {
                        IconButton(onClick = viewModel::refreshReviews, enabled = !state.busy) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
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
                busy = state.busy,
                busyLabel = state.busyLabel,
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
                item { ManagementSectionTitle("管理员工具") }
                item {
                    ManagementCard(
                        icon = Icons.Outlined.Report,
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
                        icon = Icons.Outlined.BarChart,
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
                            icon = Icons.Outlined.CheckCircle,
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
                            icon = Icons.Outlined.History,
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
                            icon = Icons.Outlined.Group,
                            title = "用户管理",
                            subtitle = "搜索、筛选用户并任命管理员",
                            onClick = {
                                showUserManagement = true
                                viewModel.refreshUsers()
                            },
                        )
                    }
                    item {
                        ManagementCard(
                            icon = Icons.Outlined.Block,
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
            busy = state.busy,
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
            onDismiss = { showStats = false },
            onExport = viewModel::exportAudit,
            onClearAudit = viewModel::clearAuditExport,
        )
    }

    if (showResolvedReviews) {
        CommunityResolvedReviewsDialog(
            entries = state.resolvedReviews,
            isDeveloper = isDeveloper,
            onDismiss = { showResolvedReviews = false },
            onOverride = { review, confirm -> viewModel.overrideReview(review, confirm, "") },
        )
    }

    if (showModerationLog) {
        CommunityModerationLogDialog(
            entries = state.moderationLog,
            onDismiss = { showModerationLog = false },
        )
    }

    if (showUserManagement) {
        CommunityUserManagementDialog(
            users = state.users,
            onDismiss = { showUserManagement = false },
            onToggleAdmin = viewModel::setUserAdmin,
        )
    }

    if (showSensitiveWords) {
        CommunitySensitiveWordsDialog(
            words = state.sensitiveWords,
            total = state.sensitiveWordsTotal,
            currentPage = state.sensitiveWordsPage,
            search = state.sensitiveWordsSearch,
            loading = state.sensitiveWordsLoading,
            onDismiss = { showSensitiveWords = false },
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
    icon: ImageVector,
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
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy && reviews.isEmpty()) {
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
        } else if (reviews.isEmpty()) {
            item { CommunityUnavailable("暂无待复审内容", "所有举报都已经处理完成。") }
        } else {
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

@Composable
internal fun CommunityModerationLogDialog(
    entries: List<CommunityModerationLogEntry>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("审核操作记录") },
        text = {
            if (entries.isEmpty()) {
                Text("暂无记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.height(360.dp)) {
                    items(entries, key = CommunityModerationLogEntry::id) { entry ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.actorUsername, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    moderationActionLabel(entry.action),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    formatCommunityTime(entry.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            entry.note?.takeIf(String::isNotBlank)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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
internal fun CommunityResolvedReviewsDialog(
    entries: List<CommunityResolvedReview>,
    isDeveloper: Boolean,
    onDismiss: () -> Unit,
    onOverride: (CommunityResolvedReview, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已处理复审记录") },
        text = {
            if (entries.isEmpty()) {
                Text("暂无已处理记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.height(360.dp)) {
                    items(entries, key = CommunityResolvedReview::id) { entry ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.author.nickname, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (entry.status == "restored") "已恢复" else "已确认违规",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    entry.reviewerUsername ?: "管理员",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            entry.reviewNote?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isDeveloper) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onOverride(entry, false) }) { Text("改为恢复") }
                                    OutlinedButton(onClick = { onOverride(entry, true) }) { Text("改为确认违规") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun CommunityUserManagementDialog(
    users: List<CommunityProfile>,
    onDismiss: () -> Unit,
    onToggleAdmin: (Long, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("all") }
    val normalized = query.trim()
    val filtered = remember(users, normalized, role) {
        users.filter { user ->
            val matchesQuery = normalized.isEmpty() ||
                user.username.contains(normalized, ignoreCase = true) ||
                user.nickname.contains(normalized, ignoreCase = true)
            val matchesRole = when (role) {
                "admin" -> user.isAdmin
                "developer" -> user.isDeveloper
                "user" -> !user.isAdmin && !user.isDeveloper
                else -> true
            }
            matchesQuery && matchesRole
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用户管理（开发者）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索用户名") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "全部", "admin" to "管理员", "developer" to "神秘人", "user" to "普通用户").forEach { (value, label) ->
                        FilterChip(
                            selected = role == value,
                            onClick = { role = value },
                            label = { Text(label) },
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        if (users.isEmpty()) "暂无用户" else "没有匹配的用户",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.height(320.dp)) {
                        items(filtered, key = CommunityProfile::userId) { user ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(user.username, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when {
                                            user.isDeveloper -> "神秘人"
                                            user.isAdmin -> "管理员"
                                            else -> "普通用户"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { onToggleAdmin(user.userId, !user.isAdmin) },
                                    enabled = !user.isDeveloper,
                                ) {
                                    Text(if (user.isAdmin) "取消管理" else "设为管理")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun CommunitySensitiveWordsDialog(
    words: List<SensitiveWord>,
    total: Int,
    currentPage: Int,
    search: String,
    loading: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onImport: (List<String>) -> Unit,
    onSearch: (String) -> Unit,
    onPageChange: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pageSize = 50
    val totalPages = (total + pageSize - 1) / pageSize
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("敏感词管理（开发者）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(64) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入敏感词") },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            onAdd(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text("添加") }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索敏感词…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val text = words.joinToString("\n") { it.word }
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("敏感词", text)))
                        }
                        Toast.makeText(context, "已复制当前页 ${words.size} 个敏感词", Toast.LENGTH_SHORT).show()
                    }, enabled = words.isNotEmpty()) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出本页")
                    }
                    OutlinedButton(onClick = { showImport = !showImport }) {
                        Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showImport) "收起导入" else "一键导入")
                    }
                }
                if (showImport) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("每行一个敏感词，也可用逗号分隔") },
                        minLines = 3,
                        maxLines = 6,
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showImport = false }) { Text("取消") }
                        TextButton(
                            onClick = {
                                val lines = importText
                                    .split(Regex("[\\r\\n,，;；]"))
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                onImport(lines)
                                showImport = false
                                importText = ""
                            },
                            enabled = importText.isNotBlank(),
                        ) { Text("确认导入") }
                    }
                }
                Text(
                    "共 $total 条" + if (totalPages > 1) " · 第 $currentPage/$totalPages 页" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (words.isEmpty()) {
                    Text("暂无敏感词", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.height(240.dp)) {
                        items(words, key = SensitiveWord::id) { word ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(word.word, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onDelete(word.id) }) { Text("删除") }
                            }
                        }
                    }
                    if (totalPages > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { onPageChange(currentPage - 1) },
                                enabled = currentPage > 1,
                            ) { Text("上一页") }
                            Text("$currentPage / $totalPages", style = MaterialTheme.typography.labelMedium)
                            TextButton(
                                onClick = { onPageChange(currentPage + 1) },
                                enabled = currentPage < totalPages,
                            ) { Text("下一页") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun CommunityStatsDialog(
    stats: CommunityStats?,
    auditExport: String?,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onClearAudit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("统计看板") },
        text = {
            val s = stats
            if (s == null) {
                Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("帖子：${s.postCount}")
                    Text("评论：${s.commentCount}")
                    Text("点赞：${s.likeCount}")
                    Text("收藏：${s.favoriteCount}")
                    Text("屏蔽：${s.blockCount}")
                    Text("用户：${s.userCount}")
                    Text("待处理举报：${s.pendingReports}")
                    Text("隔离记录：${s.quarantineCount}")
                    Text("平均举报处理耗时：${s.avgReportResolutionSeconds} 秒")
                    if (auditExport != null) {
                        HorizontalDivider()
                        Text("审计导出结果（JSON）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        SelectionContainer { Text(auditExport, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (auditExport == null) {
                    TextButton(onClick = onExport) { Text("导出审计") }
                } else {
                    TextButton(onClick = onClearAudit) { Text("收起导出") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
