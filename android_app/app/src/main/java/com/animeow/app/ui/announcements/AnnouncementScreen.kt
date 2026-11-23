package com.animeow.app.ui.announcements

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.announcements.AnnouncementNotifications
import com.animeow.app.data.announcements.AppAnnouncement
import com.animeow.app.data.announcements.AnnouncementState
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnnouncementScreen(onBack: () -> Unit, onLogin: () -> Unit, viewModel: AnnouncementViewModel = viewModel()) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("announcement") }
    var requestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var withdrawId by rememberSaveable { mutableStateOf<Long?>(null) }
    var canNotify by remember { mutableStateOf(AnnouncementNotifications.canNotify(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = AnnouncementNotifications.canNotify(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canNotify = AnnouncementNotifications.canNotify(context)
        viewModel.refresh()
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() } }
    LaunchedEffect(state.publishedSerial) {
        if (state.publishedSerial > 0) {
            showEditor = false; showPreview = false; title = ""; content = ""
            requestId = UUID.randomUUID().toString()
        }
    }
    val items = if (state.managing) state.managedItems else feed.items

    Scaffold(topBar = {
        TopAppBar(title = {
            val motionLevel = LocalMotionLevel.current
            AnimatedContent(
                targetState = state.managing,
                transitionSpec = { motionFadeThroughSpec(motionLevel, 160, 120) },
                label = "announcement_topbar_title",
            ) { managing ->
                Text(if (managing) "开发者公告后台" else "消息与公告")
            }
        }, navigationIcon = {
            IconButton(onClick = { if (state.managing) viewModel.refresh(false) else onBack() }) { AppIcon(AppIcons.Back, "返回") }
        }, actions = {
            IconButton(onClick = { viewModel.refresh() }, enabled = !state.refreshing && !state.saving) { AppIcon(AppIcons.Refresh, "刷新公告") }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        AnnouncementList(feed, state, canNotify,
            onNotifications = { enabled ->
                viewModel.setNotifications(enabled)
                if (enabled && !canNotify && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onSystemSettings = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
            onWrite = { showEditor = true }, onManage = { viewModel.refresh(true) },
            onRefresh = { viewModel.refresh() }, onMarkRead = { viewModel.markRead() },
            onOpen = { selectedId = it.id; viewModel.markRead(it.id) }, onWithdraw = { withdrawId = it.id },
            onLogin = onLogin, modifier = Modifier.padding(padding))
    }
    selectedId?.let { id ->
        items.find { it.id == id }?.let { item ->
            StableModalBottomSheet(onDismissRequest = { selectedId = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall)
                    Text("${item.kindLabel} · ${announcementTime(item.publishedAt)}", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(item.content, style = MaterialTheme.typography.bodyLarge) }
                    TextButton(onClick = { selectedId = null }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
                }
            }
        }
    }
    if (showEditor) {
        StableModalBottomSheet(onDismissRequest = { if (!state.saving) { showEditor = false; showPreview = false } }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("给喵友写一封信", style = MaterialTheme.typography.headlineSmall)
                Text("发布后所有用户都能查看；已开启通知的用户会收到提醒。", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("announcement" to "公告", "message" to "消息").forEach { (value, label) ->
                        FilterChip(kind == value, { kind = value; requestId = UUID.randomUUID().toString() }, label = { Text(label) }, enabled = !state.saving)
                    }
                }
                OutlinedTextField(title, { title = it.take(120); requestId = UUID.randomUUID().toString() }, Modifier.fillMaxWidth(),
                    label = { Text("标题") }, supportingText = { Text("${title.length} / 120") }, singleLine = true, enabled = !state.saving)
                OutlinedTextField(content, { content = it.take(4000); requestId = UUID.randomUUID().toString() }, Modifier.fillMaxWidth(),
                    label = { Text("正文") }, supportingText = { Text("${content.length} / 4000") }, minLines = 5, maxLines = 10, enabled = !state.saving)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { showEditor = false }, enabled = !state.saving) { Text("稍后再写") }
                    Button(onClick = { showPreview = true }, enabled = !state.saving && title.isNotBlank() && content.isNotBlank()) { Text("预览并发布") }
                }
            }
        }
    }
    if (showPreview) AlertDialog(
        onDismissRequest = { if (!state.saving) showPreview = false },
        title = { Text("发布预览") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title.trim(), style = MaterialTheme.typography.titleMedium)
            Text(content.trim()); Text("将向所有用户发布${if (kind == "message") "消息" else "公告"}。", color = MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { viewModel.publish(kind, title, content, requestId) }, enabled = !state.saving) { Text(if (state.saving) "发布中…" else "确认发布") } },
        dismissButton = { TextButton(onClick = { showPreview = false }, enabled = !state.saving) { Text("继续编辑") } },
    )
    withdrawId?.let { id -> AlertDialog(
        onDismissRequest = { if (!state.saving) withdrawId = null }, title = { Text("撤回这条公告？") },
        text = { Text("撤回后用户刷新列表将不再看到它；已送达的系统通知可能暂时保留。") },
        confirmButton = { TextButton(onClick = { withdrawId = null; viewModel.withdraw(id) }, enabled = !state.saving) { Text("撤回") } },
        dismissButton = { TextButton(onClick = { withdrawId = null }) { Text("保留") } },
    ) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnnouncementList(
    feed: AnnouncementState, state: AnnouncementUiState, canNotify: Boolean,
    onNotifications: (Boolean) -> Unit, onSystemSettings: () -> Unit,
    onWrite: () -> Unit, onManage: () -> Unit, onRefresh: () -> Unit, onMarkRead: () -> Unit,
    onOpen: (AppAnnouncement) -> Unit, onWithdraw: (AppAnnouncement) -> Unit, onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val announcements = if (state.managing) state.managedItems else feed.items
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "introduction") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                AppIcon(AppIcons.Chat, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("来自开发者的小喵来信", style = MaterialTheme.typography.titleMedium)
                            Text("版本消息、使用提醒与近况", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.managing) {
                        Button(onClick = onWrite, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("写消息 / 公告") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("接收公告通知", style = MaterialTheme.typography.bodyMedium)
                                Text("后台定期检查，省电时可能延迟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(feed.notificationsEnabled, onNotifications)
                        }
                        AnimatedVisibility(
                            visible = feed.notificationsEnabled && !canNotify,
                            enter = motionExpandIn(),
                            exit = motionShrinkOut(),
                        ) {
                            TextButton(onClick = onSystemSettings) { Text("开启系统通知") }
                        }
                    }
                }
            }
        }
        item(key = "actions") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.managing && announcements.isNotEmpty()) TextButton(onClick = onMarkRead, enabled = feed.unreadCount > 0) {
                    Text(if (feed.unreadCount > 0) "全部已读 · ${feed.unreadCount} 条未读" else "全部已读")
                }
                if (feed.isDeveloper && !state.managing) TextButton(onClick = onManage, enabled = !state.refreshing) { Text("开发者后台") }
            }
        }
        item(key = "loading") {
            AnimatedVisibility(visible = state.refreshing, enter = motionExpandIn(), exit = motionShrinkOut()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (state.error != null) item(key = "error") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("暂时没能收到新消息", style = MaterialTheme.typography.titleMedium)
                    Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (announcements.isNotEmpty()) Text("下面是上次保存的公告，仍可打开阅读。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRefresh, enabled = !state.refreshing) { Text("重新加载") }
                }
            }
        }
        if (announcements.isEmpty() && !state.refreshing && state.error == null) item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(AppIcons.Chat, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Text("还没有新的来信", style = MaterialTheme.typography.titleMedium)
                Text("小喵有新消息时，会在这里告诉你。\n(=^･ω･^=)", style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(announcements, key = { it.id }) { item ->
            AnnouncementCard(
                item = item,
                unread = item.id !in feed.readIds && !state.managing,
                onOpen = { onOpen(item) },
                modifier = motionAnimateItem(),
                onWithdraw = if (state.managing && !item.withdrawn) ({ onWithdraw(item) }) else null,
            )
        }
        if (!feed.isDeveloper) item(key = "account") { TextButton(onClick = onLogin) { Text("账号与登录") } }
    }
}

@Composable
internal fun AnnouncementCard(
    item: AppAnnouncement,
    unread: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onWithdraw: (() -> Unit)? = null,
) {
    val containerColor by animateColorAsState(
        targetValue = if (unread) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = motionAnimationSpec(220),
        label = "announcement_card_bg",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .motionAnimateContentSize()
            .clip(CardDefaults.shape)
            .animatedPressClick(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.kindLabel} · ${announcementTime(item.publishedAt)}${if (item.withdrawn) " · 已撤回" else if (unread) " · 未读" else ""}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(item.content, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            onWithdraw?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) { Text("撤回") } }
        }
    }
}

private fun announcementTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value.take(16).replace('T', ' '))
