package com.animeow.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.data.community.*
import com.animeow.app.ui.components.PageNavigationBar
import com.animeow.app.ui.components.LoadErrorCard
import kotlinx.coroutines.delay

@Composable
private fun <T> PagedManagementDialog(
    title: String,
    entries: List<T>,
    scopeLabel: String,
    onDismiss: () -> Unit,
    key: (T) -> Any,
    resetKey: String = "",
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    var requestedPage by rememberSaveable(resetKey) { mutableIntStateOf(1) }
    val pages = ((entries.size + 19) / 20).coerceAtLeast(1)
    val page = requestedPage.coerceIn(1, pages)
    val list = rememberLazyListState()
    LaunchedEffect(page, resetKey) { list.scrollToItem(0) }
    ManagementListDialog(title, onDismiss, bottomBar = {
        Surface(shadowElevation = 3.dp) {
            PageNavigationBar(page, pages, page < pages, loading, { requestedPage = it },
                Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
                status = if (loading) "正在读取…" else scopeLabel)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { header() }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null && onRetry != null) item { LoadErrorCard("读取未完成", error, onRetry) }
                if (entries.isEmpty() && !loading && error == null) item {
                    Text("没有匹配的记录", Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(entries.drop((page - 1) * 20).take(20), key = key) { entry ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content(entry) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CommunityUserManagementDialog(
    users: List<CommunityProfile>,
    onDismiss: () -> Unit,
    onToggleAdmin: (Long, Boolean) -> Unit,
    loading: Boolean = false,
    busy: Boolean = false,
    onQueryChanged: (String, String) -> Unit = { _, _ -> },
    error: String? = null,
    busyLabel: String? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("all") }
    var confirmUser by remember { mutableStateOf<CommunityProfile?>(null) }
    LaunchedEffect(query, role) { delay(300); onQueryChanged(query.trim(), role) }
    val filtered = remember(users, query, role) {
        users.filter { user ->
            (query.isBlank() || user.username.contains(query.trim(), true) || user.nickname.contains(query.trim(), true)) &&
                when (role) {
                    "admin" -> user.isAdmin
                    "developer" -> user.isDeveloper
                    "user" -> !user.isAdmin && !user.isDeveloper
                    else -> true
                }
        }
    }
    PagedManagementDialog("管理员设置", filtered, "本次查询 ${filtered.size} 位 · 每页 20 位",
        onDismiss, CommunityProfile::userId, resetKey = "$query|$role", loading = loading || busy,
        error = error, onRetry = { onQueryChanged(query.trim(), role) }, header = {
            if (busyLabel != null && busy) Text(busyLabel, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it.take(64) }, Modifier.fillMaxWidth(),
                enabled = !busy, label = { Text("搜索用户名") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "全部", "admin" to "管理员", "developer" to "开发者", "user" to "普通用户").forEach { (value, label) ->
                    FilterChip(selected = role == value, onClick = { role = value }, enabled = !busy, label = { Text(label) })
                }
            }
            Text("按用户名搜索可查找更多用户；这里每次最多显示 500 位匹配结果。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }) { user ->
        Text(user.username, fontWeight = FontWeight.SemiBold)
        Text(when { user.isDeveloper -> "神秘人 · 开发者"; user.isAdmin -> "社区管理员"; else -> "普通用户" },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { confirmUser = user }, enabled = !user.isDeveloper && !loading && !busy) {
            Text(if (user.isAdmin) "取消管理员" else "设为管理员")
        }
    }
    confirmUser?.let { user ->
        AlertDialog(onDismissRequest = { confirmUser = null }, title = { Text(if (user.isAdmin) "取消管理员？" else "设置管理员？") },
            text = { Text(if (user.isAdmin) "${user.username} 将失去社区管理权限。"
                else "${user.username} 将可以处理社区举报和图片审核，开发者专属功能仍不可用。") },
            confirmButton = { TextButton(onClick = { onToggleAdmin(user.userId, !user.isAdmin); confirmUser = null }, enabled = !busy) { Text("确认") } },
            dismissButton = { TextButton(onClick = { confirmUser = null }) { Text("取消") } })
    }
}

@Composable
internal fun CommunityModerationLogDialog(entries: List<CommunityModerationLogEntry>, onDismiss: () -> Unit,
    loading: Boolean = false, error: String? = null, onRetry: () -> Unit = {}) {
    PagedManagementDialog("审核操作记录", entries, "最近读取 ${entries.size} 条 · 每页 20 条",
        onDismiss, CommunityModerationLogEntry::id, loading = loading, error = error, onRetry = onRetry, header = {
            Text("这里显示最近的操作记录。更多历史可由开发者在统计看板导出查看。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }) { entry ->
        Text(entry.actorUsername, fontWeight = FontWeight.Bold)
        Text(moderationActionLabel(entry.action), color = MaterialTheme.colorScheme.primary)
        Text(formatCommunityTime(entry.createdAt), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        entry.note?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun CommunityResolvedReviewsDialog(
    entries: List<CommunityResolvedReview>,
    isDeveloper: Boolean,
    onDismiss: () -> Unit,
    onOverride: (CommunityResolvedReview, Boolean) -> Unit,
    loading: Boolean = false,
    error: String? = null,
    busy: Boolean = false,
    onRetry: () -> Unit = {},
) {
    PagedManagementDialog("已处理复审记录", entries, "最近读取 ${entries.size} 条 · 每页 20 条",
        onDismiss, CommunityResolvedReview::id, loading = loading, error = error, onRetry = onRetry, header = {
            Text("显示最近的复审结果。修改结论后，相关内容的公开状态会随之改变。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }) { entry ->
        Text(entry.author.nickname, fontWeight = FontWeight.Bold)
        Text(if (entry.status == "restored") "已恢复" else "已确认违规", color = MaterialTheme.colorScheme.primary)
        Text("处理人：${entry.reviewerUsername ?: "管理员"}", style = MaterialTheme.typography.labelSmall)
        entry.reviewNote?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (isDeveloper) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOverride(entry, false) }, enabled = !busy) { Text("改为恢复") }
            OutlinedButton(onClick = { onOverride(entry, true) }, enabled = !busy) { Text("改为确认违规") }
        }
    }
}
