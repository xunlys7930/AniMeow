package com.animeow.app.ui.cloud

import com.animeow.app.ui.icons.AppIcon as Icon

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.cloud.CloudFeedbackItem
import com.animeow.app.data.community.communityImageRequest
import com.animeow.app.ui.components.motionAnimateItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackPoolScreen(
    onBack: () -> Unit,
    onCloudAccountRequested: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CloudAccountViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var content by rememberSaveable { mutableStateOf("") }
    var composing by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("all") }
    val filtered = state.feedback.filter { item ->
        when (filter) {
            "mine" -> state.session?.let { item.userId == it.userId || item.username == it.username } == true
            "all" -> true
            else -> feedbackStatusGroup(item.status) == filter
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            if (it == "反馈已提交") { content = ""; composing = false }
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(), snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text("开放反馈池") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, "返回") }
            }, actions = {
                IconButton(onClick = viewModel::refreshFeedback, enabled = state.configured && !state.busy) {
                    Icon(AppIcons.Refresh, "刷新反馈池")
                }
            })
        },
        floatingActionButton = {
            if (state.configured) ExtendedFloatingActionButton(
                onClick = { if (state.session == null) onCloudAccountRequested() else composing = true },
                modifier = Modifier.semantics { contentDescription = "写反馈" },
                icon = { Icon(AppIcons.Add, null) }, text = { Text("写反馈") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f), shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Community, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("一起把 AniMeow 变得更好", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("分享想法，追踪进展，查看开发者的回应。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val filters = listOf("all" to "全部", "open" to "待处理", "adopted" to "已采用", "completed" to "已完成") +
                        (if (state.session != null) listOf("mine" to "我的") else emptyList()) +
                        (if (state.viewerIsDeveloper) listOf("hidden" to "已隐藏") else emptyList())
                    filters.forEach { (value, label) -> FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) }) }
                }
            }
            if (!state.configured) item {
                Text("反馈服务暂未开放，请稍后再来。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (state.feedbackError != null) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("暂时没能加载反馈", style = MaterialTheme.typography.titleMedium)
                        Text(state.feedbackError.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (filtered.isNotEmpty()) Text("下面保留了上次加载的内容。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = viewModel::refreshFeedback, enabled = !state.busy) { Text("重新加载") }
                    }
                }
            } else if (filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(28.dp)) else Icon(AppIcons.Community, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(if (state.busy) "正在加载反馈…" else if (filter == "all") "这里还没有反馈" else "还没有这类反馈", style = MaterialTheme.typography.titleMedium)
                    if (!state.busy) Text("你的使用感受，就是下一次改进的起点。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(filtered, key = { index, item -> if (item.id > 0) item.id else "feedback-$index" }) { _, item ->
                FeedbackCard(item, state.session?.token, state.viewerIsDeveloper, state.busy,
                    onStatus = { viewModel.setFeedbackStatus(item.id, it) }, onReply = { viewModel.replyFeedback(item.id, it) },
                    modifier = motionAnimateItem())
            }
        }
    }
    if (composing) com.animeow.app.ui.components.StableModalBottomSheet(onDismissRequest = { if (content.isNotBlank()) discard = true else composing = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("分享一个想法", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("反馈公开可见，请勿填写密码、令牌或个人隐私。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(content, { content = it.take(1000) }, Modifier.fillMaxWidth(),
                placeholder = { Text("发生了什么？你期待怎样的体验？\n遇到问题时，请附上复现步骤。") },
                minLines = 4, maxLines = 7, supportingText = { Text("${content.length}/1000") })
            Button(onClick = { viewModel.submitFeedback(content) }, enabled = !state.busy && content.trim().length in 5..1000,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "正在提交…" else "公开提交") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("保留这份草稿吗？") },
        text = { Text("稍后再次点击“写反馈”可继续编辑。") },
        confirmButton = { TextButton(onClick = { discard = false; composing = false }) { Text("保留草稿") } },
        dismissButton = { TextButton(onClick = { discard = false; composing = false; content = "" }) { Text("丢弃草稿") } })
}

@Composable
private fun FeedbackCard(
    item: CloudFeedbackItem, token: String?, canManage: Boolean, busy: Boolean,
    onStatus: (String) -> Unit, onReply: (String) -> Unit, modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var pendingStatus by remember { mutableStateOf<String?>(null) }
    var replying by remember { mutableStateOf(false) }
    var reply by rememberSaveable(item.id) { mutableStateOf(item.reply.orEmpty()) }
    val name = item.nickname.ifBlank { item.username }.ifBlank { "AniMeow 用户" }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(name.take(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    item.avatarUrl?.let { url -> AsyncImage(communityImageRequest(LocalContext.current, url, token), "${name}的头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, modifier = Modifier.weight(1f, fill = false), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.isDeveloper) Text("开发者", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(item.createdAt.replace('T', ' ').take(16), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f), shape = CircleShape) {
                    Text(feedbackStatusLabel(item.status), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (canManage) Box {
                    IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(40.dp)) { Icon(AppIcons.MoreVertical, "管理此条反馈") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("官方回复") }, onClick = { menu = false; replying = true })
                        listOf("adopted" to "采用", "completed" to "标记完成", "open" to "重新打开", "hidden" to "隐藏反馈").forEach { (status, label) ->
                            DropdownMenuItem(text = { Text(label) }, enabled = item.status != status, onClick = { menu = false; pendingStatus = status })
                        }
                    }
                }
            }
            Text(item.content, style = MaterialTheme.typography.bodyLarge)
            item.reply?.takeIf { it.isNotBlank() }?.let { message ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("开发者回应", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    pendingStatus?.let { status -> AlertDialog(onDismissRequest = { pendingStatus = null }, title = { Text("更新反馈状态？") },
        text = { Text(if (status == "hidden") "隐藏后，普通用户将不再看到这条反馈。开发者可重新打开。" else "反馈状态会公开更新为“${feedbackStatusLabel(status)}”。") },
        confirmButton = { TextButton(onClick = { onStatus(status); pendingStatus = null }, enabled = !busy) { Text("确认") } },
        dismissButton = { TextButton(onClick = { pendingStatus = null }) { Text("取消") } }) }
    if (replying) AlertDialog(onDismissRequest = { replying = false }, title = { Text("开发者回应") },
        text = { OutlinedTextField(reply, { reply = it.take(500) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, supportingText = { Text("${reply.length}/500 · 公开可见") }) },
        confirmButton = { TextButton(onClick = { onReply(reply); replying = false }, enabled = !busy && reply.isNotBlank()) { Text("发布回应") } },
        dismissButton = { TextButton(onClick = { replying = false }) { Text("取消") } })
}

private fun feedbackStatusGroup(status: String): String = when (status.lowercase()) {
    "adopted", "planned", "accepted" -> "adopted"
    "completed", "resolved", "done", "closed" -> "completed"
    "hidden" -> "hidden"
    else -> "open"
}

private fun feedbackStatusLabel(status: String): String = when (status.lowercase()) {
    "adopted", "planned", "accepted" -> "已采用"
    "hidden" -> "已隐藏"
    "completed", "resolved", "done", "closed" -> "已完成"
    "working", "in_progress" -> "处理中"
    "rejected" -> "暂不处理"
    else -> "待处理"
}
