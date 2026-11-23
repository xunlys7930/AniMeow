package com.animeow.app.ui.community

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.animeow.app.data.community.CommunityInboxFilter
import com.animeow.app.data.community.CommunityNotification
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionAnimationSpec

@Composable
fun CommunityInboxButton(unreadCount: Int, onClick: () -> Unit) {
    var previousUnread by remember { mutableStateOf(unreadCount) }
    var notificationPulse by remember { mutableStateOf(0) }
    LaunchedEffect(unreadCount) {
        if (unreadCount > previousUnread) notificationPulse++
        previousUnread = unreadCount
    }
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (unreadCount > 0) Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) } }) {
            Icon(AppIcons.Notification, contentDescription = if (unreadCount > 0) "互动消息，$unreadCount 条未读" else "互动消息",
                animationTrigger = notificationPulse)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityInboxScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onPostClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    viewModel: CommunityInboxViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("互动消息") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "返回") } },
                actions = {
                    TextButton(onClick = viewModel::markAllRead, enabled = state.unreadCount > 0 && !state.markingRead) { Text("全部已读") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CommunityInboxContent(state, viewModel::selectFilter, viewModel::refresh, viewModel::loadMore, onLogin,
            onOpen = { viewModel.markRead(it); onPostClick(it.postId) }, onUserClick = onUserClick, modifier = Modifier.padding(padding))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityInboxContent(
    state: CommunityInboxUiState,
    onFilter: (CommunityInboxFilter) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLogin: () -> Unit,
    onOpen: (CommunityNotification) -> Unit,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(isRefreshing = state.loading && state.items.isNotEmpty(), onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "filters") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommunityInboxFilter.entries.forEach { filter ->
                        FilterChip(state.filter == filter, { onFilter(filter) }, { Text(filter.label) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (state.session == null) item {
                InboxEmptyState("登录后查看互动消息", "谁点赞、评论或收藏了你的内容，都可以在这里找到。") {
                    FilledTonalButton(onClick = onLogin) { Text("登录账号") }
                }
            } else {
                if (state.items.isEmpty() && state.loading) item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                state.error?.let { error -> item(key = "error") {
                    InboxEmptyState(if (state.available) "消息加载失败" else "消息服务暂未启用", error) {
                        TextButton(onClick = onRefresh, enabled = !state.loading) { Text("重新加载") }
                    }
                } }
                if (state.loaded && state.items.isEmpty() && !state.loading && state.error == null) item {
                    InboxEmptyState("暂时没有${if (state.filter == CommunityInboxFilter.ALL) "互动" else state.filter.label}消息", "收到新的互动时，这里会为你保留记录。") {}
                }
                items(state.items, key = { it.id }) { notification ->
                    InboxNotificationCard(
                        item = notification,
                        token = state.session.token,
                        onOpen = { onOpen(notification) },
                        onUser = { onUserClick(notification.actor.userId) },
                        modifier = motionAnimateItem(),
                    )
                }
                if (state.nextBeforeId != null) item(key = "more") {
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") }
                }
            }
        }
    }
}

@Composable
private fun InboxEmptyState(title: String, description: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(AppIcons.Notification, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
private fun InboxNotificationCard(
    item: CommunityNotification,
    token: String,
    onOpen: () -> Unit,
    onUser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColor by animateColorAsState(
        targetValue = if (item.isRead) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        },
        animationSpec = motionAnimationSpec(220),
        label = "inboxCardColor",
    )
    Surface(
        modifier = modifier.fillMaxWidth().motionAnimateContentSize().animatedPressClick(onClickLabel = "查看相关帖子", onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = cardColor,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(role = Role.Button, onClickLabel = "查看${item.actor.nickname}的主页", onClick = onUser)
                .semantics { contentDescription = "查看${item.actor.nickname}的主页" }, contentAlignment = Alignment.Center) {
                if (item.actor.avatarUrl.isNullOrBlank()) Text(item.actor.nickname.take(1), fontWeight = FontWeight.Bold)
                else AsyncImage(authenticatedCommunityImageModel(item.actor.avatarUrl, token), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.actor.nickname, Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClickLabel = "查看${item.actor.nickname}的主页", onClick = onUser),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!item.isRead) Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val icon = when (item.kind) {
                        "post_like", "comment_like" -> AppIcons.Heart
                        "favorite" -> AppIcons.Star
                        else -> AppIcons.Chat
                    }
                    Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(item.actionLabel, style = MaterialTheme.typography.bodyMedium)
                }
                if (item.commentExcerpt.isNotBlank()) Text(item.commentExcerpt, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(item.postExcerpt.ifBlank { "图片帖子" }, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)).padding(10.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCommunityTime(item.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
