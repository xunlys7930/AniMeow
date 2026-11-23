@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.animeow.app.ui.community

import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import com.animeow.app.ui.components.PageNavigationBar
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.community.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun ReviewNotificationSettings(userId: Long) {
    val context = LocalContext.current
    var enabled by remember(userId) { mutableStateOf(CommunityReviewNotifications.enabled(context, userId)) }
    var allowed by remember { mutableStateOf(CommunityReviewNotifications.canNotify(context)) }
    val scope = rememberCoroutineScope()
    var diagnostic by remember(userId) { mutableStateOf(CommunityReviewNotifications.diagnostics(context, userId)) }
    var checking by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        allowed = CommunityReviewNotifications.canNotify(context)
        diagnostic = CommunityReviewNotifications.diagnostics(context, userId)
        if (enabled && allowed) CommunityReviewNotifications.reschedule(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = CommunityReviewNotifications.canNotify(context)
        if (allowed) CommunityReviewNotifications.reschedule(context)
    }
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Notification, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("审核待办通知", fontWeight = FontWeight.SemiBold)
                    Text("有新图片或举报需要处理时提醒", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, modifier = Modifier.semantics { contentDescription = "审核待办通知" }, onCheckedChange = {
                    enabled = it; CommunityReviewNotifications.setEnabled(context, userId, it)
                    if (it && Build.VERSION.SDK_INT >= 33 && !allowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
            }
            Text("后台约每 15 分钟检查一次，相同待办只提醒一次。系统省电策略可能延后通知；打开工作台可立即查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled && !allowed) TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("允许审核通知") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("系统通知设置") }
            Text(diagnostic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = enabled && !checking, onClick = {
                    checking = true
                    scope.launch {
                        try {
                            com.animeow.app.util.runCatchingCancellable { CommunityReviewNotifications.check(context) }
                            diagnostic = CommunityReviewNotifications.diagnostics(context, userId)
                        } finally { checking = false }
                    }
                }) { Text(if (checking) "检查中…" else "立即检查") }
                TextButton(onClick = { diagnostic = CommunityReviewNotifications.sendTest(context, userId) }) { Text("测试通知显示") }
            }
        }
    }
}

@Composable
internal fun CommunityImageReviewScreen(onBack: () -> Unit, viewModel: CommunitySafetyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var decision by remember { mutableStateOf<Pair<CommunityImageReview, Boolean>?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    DisposableEffect(viewModel) { onDispose { viewModel.cancelPageLoad() } }
    LaunchedEffect(state.imagePage, state.imageFilter) { listState.scrollToItem(0) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.loadImages() }
    SafetyScaffold("图片审核", onBack, state, viewModel::clearMessage, { viewModel.loadImages() }, bottomBar = {
        Surface { PageNavigationBar(state.imagePage, state.imageLastPage, state.nextImageId != null,
            state.loading || state.busy, viewModel::loadImagePage, Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            status = if (state.loading) "正在读取第 ${state.locatingPage ?: state.imagePage} 页…" else "本页 ${state.images.size} 项 · 点击页码跳转",
            onCancel = if (state.loading) viewModel::cancelPageLoad else null) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (state.automatic) "自动检测已接入，需要复核的图片会留在这里。" else "当前为人工待审：图片审核通过后才公开。服务器接入自动审核后会优先自动检测。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("pending" to "待处理", "approved" to "已通过", "rejected" to "已拒绝").forEach { (key, label) ->
                        FilterChip(selected = state.imageFilter == key, onClick = { viewModel.loadImages(key) }, enabled = !state.busy, label = { Text(label) })
                    }
                }
            }
            if (state.unavailable) item { Text("服务器尚未支持图片审核，请更新服务器后再使用。原有举报复审仍可使用。") }
            else if (state.images.isEmpty() && !state.loading) item { SafetyEmptyState("这里暂时没有${if (state.imageFilter == "pending") "待审" else "已处理"}图片") }
            items(state.images, key = { it.id }) { review ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CommunityAvatar(review.author, viewModel.token, Modifier.size(44.dp))
                            Column(Modifier.weight(1f)) {
                                Text(review.author.nickname, fontWeight = FontWeight.Bold)
                                Text("${if (review.targetType == "avatar") "自定义头像" else "帖子图片"} · ${formatCommunityTime(review.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("#${review.id}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (review.content.isNotBlank()) Text(review.content, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(review.images) { image ->
                                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                                    AsyncImage(authenticatedCommunityImageModel(image, viewModel.token), contentDescription = "待审图片，点击查看完整图片",
                                        contentScale = ContentScale.Fit, modifier = Modifier.size(132.dp).clickable { preview = image })
                                }
                            }
                        }
                        Text(review.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.imageFilter == "pending") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { decision = review to false }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("拒绝") }
                            FilledTonalButton(onClick = { decision = review to true }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("通过") }
                        }
                    }
                }
            }
        }
    }
    decision?.let { (review, approved) ->
        SafetyReasonDialog(if (approved) "通过这组图片？" else "拒绝这组图片？", if (approved) "确认图片适合公开展示后再通过。" else "拒绝后不会对外公开；头像会继续使用原来的图片。",
            onDismiss = { decision = null }, onConfirm = { note -> viewModel.resolveImage(review, approved, note); decision = null })
    }
    preview?.let { image ->
        AlertDialog(onDismissRequest = { preview = null }, confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } },
            text = { AsyncImage(authenticatedCommunityImageModel(image, viewModel.token), "完整待审图片", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) })
    }
}

@Composable
internal fun CommunitySafetyUsersScreen(onBack: () -> Unit, viewModel: CommunitySafetyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.userQuery) }
    var restrictionTarget by remember { mutableStateOf<CommunitySafetyUser?>(null) }
    var avatarTarget by remember { mutableStateOf<CommunitySafetyUser?>(null) }
    var avatarPreview by remember { mutableStateOf<CommunityProfile?>(null) }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelPageLoad() } }
    val listState = rememberLazyListState()
    LaunchedEffect(state.userPage, state.userQuery) { listState.scrollToItem(0) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.loadUsers() }
    SafetyScaffold("社区用户与头像", onBack, state, viewModel::clearMessage, { viewModel.loadUsers(query) }, bottomBar = {
        Surface { PageNavigationBar(state.userPage, state.userLastPage, state.nextUserId != null,
            state.loading || state.busy, viewModel::loadUserPage, Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            status = if (state.loading) "正在读取第 ${state.locatingPage ?: state.userPage} 页…" else "本页 ${state.users.size} 位 · 点击页码跳转",
            onCancel = if (state.loading) viewModel::cancelPageLoad else null) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(query, { query = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索昵称、用户名或 ID") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.loadUsers(query) }),
                    trailingIcon = { IconButton(onClick = { viewModel.loadUsers(query) }, enabled = !state.busy) { Icon(AppIcons.Search, "搜索用户") } })
                Text("限制只针对所选社区操作，云备份、恢复和作品管理继续可用。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.unavailable) item { Text("服务器尚未支持社区用户限制，请更新服务器后使用。") }
            else if (state.users.isEmpty() && !state.loading) item { SafetyEmptyState("没有找到匹配的用户") }
            items(state.users, key = { it.profile.userId }) { user ->
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CommunityAvatar(user.profile, viewModel.token, Modifier.size(60.dp).clickable { avatarPreview = user.profile })
                            Column(Modifier.weight(1f)) {
                                Text(user.profile.nickname, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("@${user.profile.username} · ID ${user.profile.userId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (user.profile.isAdmin || user.profile.isDeveloper) Text(if (user.profile.isDeveloper) "神秘人 · 开发者" else "社区管理员", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        user.profile.restrictions.forEach { limit ->
                            Text("${limit.label} · ${limit.reason}\n${limit.expiresAt?.let { "至 ${formatCommunityTime(it)}" } ?: "长期限制"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (user.canRestrict) FilledTonalButton(onClick = { restrictionTarget = user }, enabled = !state.busy) { Text(if (user.profile.restrictions.isEmpty()) "限制权限" else "调整 / 解除限制") }
                            if (user.canResetAvatar) TextButton(onClick = { avatarTarget = user }, enabled = !state.busy) { Text("重置头像") }
                        }
                    }
                }
            }
        }
    }
    restrictionTarget?.let { user ->
        UserRestrictionDialog(user, { restrictionTarget = null }) { capabilities, reason, hours ->
            viewModel.restrict(user, capabilities, reason, hours); restrictionTarget = null
        }
    }
    avatarTarget?.let { user ->
        SafetyReasonDialog("重置 ${user.profile.nickname} 的头像？", "当前头像将恢复为默认，待审头像也会取消。用户仍可使用云端功能。", { avatarTarget = null }) { reason ->
            viewModel.resetAvatar(user, reason); avatarTarget = null
        }
    }
    avatarPreview?.let { profile ->
        AlertDialog(onDismissRequest = { avatarPreview = null }, title = { Text(profile.nickname) },
            text = { CommunityAvatar(profile, viewModel.token, Modifier.fillMaxWidth().aspectRatio(1f)) },
            confirmButton = { TextButton(onClick = { avatarPreview = null }) { Text("关闭") } })
    }
}

@Composable
private fun UserRestrictionDialog(user: CommunitySafetyUser, onDismiss: () -> Unit, onConfirm: (Set<String>, String, Int?) -> Unit) {
    var selected by remember(user.profile.userId) { mutableStateOf(user.profile.restrictions.map { it.capability }.toSet()) }
    var hours by remember { mutableStateOf<Int?>(24 * 7) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${user.profile.nickname} 的社区权限") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("勾选要限制的操作。取消全部勾选可解除限制；登录与云备份不受影响。", style = MaterialTheme.typography.bodySmall)
            CommunityCapability.entries.forEach { capability ->
                Row(Modifier.fillMaxWidth().clickable { selected = if (capability.key in selected) selected - capability.key else selected + capability.key }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(capability.key in selected, onCheckedChange = null)
                    Text(capability.label)
                }
            }
            if (selected.isNotEmpty()) {
                Text("限制时长", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "1 小时", 24 to "1 天", 168 to "7 天", 720 to "30 天", null to "长期").forEach { (value, label) ->
                        FilterChip(selected = hours == value, onClick = { hours = value }, label = { Text(label) })
                    }
                }
            }
            OutlinedTextField(reason, { reason = it.take(200) }, label = { Text("原因（用户可见）") }, minLines = 2, supportingText = { Text("${reason.length}/200") })
        } },
        confirmButton = { TextButton(onClick = { onConfirm(selected, reason.trim(), hours) }, enabled = reason.trim().length >= 2 && (selected.isNotEmpty() || user.profile.restrictions.isNotEmpty())) { Text(if (selected.isEmpty()) "解除限制" else "确认限制") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SafetyReasonDialog(title: String, description: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(description)
            OutlinedTextField(note, { note = it.take(200) }, label = { Text("处理原因 / 说明") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(note.trim()) }, enabled = note.trim().length >= 2) { Text("确认") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SafetyEmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(AppIcons.CheckCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SafetyScaffold(title: String, onBack: () -> Unit, state: CommunitySafetyUiState, onMessageConsumed: () -> Unit, onRefresh: () -> Unit,
    bottomBar: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageConsumed() } }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "返回管理工作台") } },
        actions = { IconButton(onClick = onRefresh, enabled = !state.loading && !state.busy) { Icon(AppIcons.Refresh, "刷新") } }) },
        bottomBar = bottomBar, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize()) {
            content(padding)
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding()))
        }
    }
}
