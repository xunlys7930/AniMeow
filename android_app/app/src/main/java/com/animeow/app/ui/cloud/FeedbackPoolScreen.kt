package com.animeow.app.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.cloud.CloudFeedbackItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackPoolScreen(
    onBack: () -> Unit,
    onCloudAccountRequested: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CloudAccountViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var content by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            if (message == "反馈已提交") content = ""
            snackbarHost.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("开放反馈池") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshFeedback, enabled = state.configured && !state.busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新反馈池")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("建议公开可见", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "这里集中展示大家提交的问题、体验意见与功能想法。请勿填写账号密码、Token 或其他隐私信息。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!state.configured) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "当前构建未配置云端服务，配置 CLOUD_API_BASE 后即可读取和提交反馈。",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else if (state.session == null) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("登录后可提交反馈", fontWeight = FontWeight.Bold)
                                Text("浏览反馈无需登录；提交时会显示你的云账号用户名。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledTonalButton(onClick = onCloudAccountRequested) { Text("去登录") }
                        }
                    }
                }
            } else {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("提交新反馈", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it.take(1000) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("请描述复现步骤、期望效果或功能用途") },
                                supportingText = { Text("${content.length}/1000") },
                                minLines = 3,
                                maxLines = 7,
                            )
                            Button(
                                onClick = { viewModel.submitFeedback(content) },
                                enabled = !state.busy && content.trim().length in 5..1000,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Icon(Icons.Outlined.Send, contentDescription = null)
                                Text("提交", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "全部反馈 · ${state.feedback.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.feedback.isEmpty()) {
                item {
                    Text(
                        if (state.busy) "正在加载反馈…" else "还没有公开反馈，欢迎提交第一条建议。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(
                    items = state.feedback,
                    key = { index, item -> if (item.id > 0) item.id else "feedback-$index" },
                ) { _, item ->
                    FeedbackCard(
                        item = item,
                        canManage = state.viewerIsDeveloper,
                        onStatus = { status -> viewModel.setFeedbackStatus(item.id, status) },
                        onReply = { reply -> viewModel.replyFeedback(item.id, reply) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackCard(
    item: CloudFeedbackItem,
    canManage: Boolean,
    onStatus: (String) -> Unit,
    onReply: (String) -> Unit,
) {
    var replying by remember { mutableStateOf(false) }
    var replyText by remember(item.id) { mutableStateOf("") }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.username.ifBlank { "匿名用户" }, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                if (item.isDeveloper) {
                    Text(
                        "神秘人",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    feedbackStatusLabel(item.status),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(item.content)
            if (!item.reply.isNullOrBlank()) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("官方回复", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(item.reply)
                    }
                }
            }
            if (item.createdAt.isNotBlank()) {
                Text(
                    item.createdAt.replace('T', ' ').take(19),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FeedbackStatusButton("采用", "adopted", item.status, onStatus)
                    FeedbackStatusButton("隐藏", "hidden", item.status, onStatus)
                    FeedbackStatusButton("完成", "completed", item.status, onStatus)
                    FeedbackStatusButton("重开", "open", item.status, onStatus)
                }
                if (!replying) {
                    FilledTonalButton(onClick = { replying = true }) { Text("回复") }
                } else {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入官方回复") },
                        minLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = { replying = false; replyText = "" }) { Text("取消") }
                        Button(
                            onClick = {
                                onReply(replyText)
                                replying = false
                                replyText = ""
                            },
                            enabled = replyText.isNotBlank(),
                        ) { Text("发送回复") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackStatusButton(
    label: String,
    status: String,
    current: String,
    onStatus: (String) -> Unit,
) {
    val selected = current.equals(status, ignoreCase = true)
    FilledTonalButton(onClick = { onStatus(status) }, enabled = !selected) { Text(label) }
}

private fun feedbackStatusLabel(status: String): String = when (status.lowercase()) {
    "adopted", "planned", "accepted" -> "已采用"
    "hidden" -> "已隐藏"
    "completed", "resolved", "done", "closed" -> "已完成"
    "working", "in_progress" -> "处理中"
    "rejected" -> "暂不处理"
    else -> "待处理"
}
