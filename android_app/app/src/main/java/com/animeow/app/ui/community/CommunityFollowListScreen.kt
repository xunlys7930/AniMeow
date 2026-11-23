package com.animeow.app.ui.community

import com.animeow.app.ui.icons.AppIcons

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 关注列表：查看某位用户的「关注中 / 粉丝」名单，点击一行进入对方个人空间。
 * 旧版后端没有该接口时提示不支持，而不是报错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityFollowListScreen(
    userId: Long,
    initialDirection: String,
    onBack: () -> Unit,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val direction = state.followListDirection

    LaunchedEffect(userId, initialDirection) { viewModel.openFollowList(userId, initialDirection) }
    androidx.compose.runtime.DisposableEffect(viewModel, userId) {
        onDispose { viewModel.closeFollowList() }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("关注列表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.session == null -> FollowListHint(
                title = "还没有登录云账号",
                body = "登录后可以查看关注与粉丝名单。",
                modifier = Modifier.padding(padding),
            )
            !state.followListSupported -> FollowListHint(
                title = "当前服务器暂不支持关注列表",
                body = "服务端升级后就能在这里查看关注与粉丝名单。",
                modifier = Modifier.padding(padding),
            )
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = if (direction == "followers") 1 else 0) {
                    Tab(selected = direction == "following",
                        onClick = { viewModel.setFollowListDirection("following") },
                        text = { Text("关注中") })
                    Tab(selected = direction == "followers",
                        onClick = { viewModel.setFollowListDirection("followers") },
                        text = { Text("粉丝") })
                }
                when {
                    state.followListLoading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    state.followListError != null && state.followList.isEmpty() -> Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.followListError ?: "", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.openFollowList(userId, direction) }) { Text("重试") }
                    }
                    state.followList.isEmpty() -> FollowListHint(
                        title = if (direction == "following") "还没有关注任何人" else "还没有粉丝",
                        body = if (direction == "following") "在社区里遇到感兴趣的人，进入他的个人空间点关注。"
                        else "被人关注后会出现在这里。",
                        modifier = Modifier.weight(1f),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.followList, key = { it.userId }) { user ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onUserClick(user.userId) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(user.nickname.ifBlank { user.username }, fontWeight = FontWeight.SemiBold)
                                    Text("@${user.username}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (user.isFollowing == true) {
                                    Text("已关注", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (state.followListNextOffset != null) item("more") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                if (state.followListLoadingMore) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                } else {
                                    TextButton(onClick = { viewModel.loadMoreFollowList() }) { Text("加载更多") }
                                }
                            }
                        }
                        if (state.followListError != null) item("error") {
                            Text(state.followListError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowListHint(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.Group, contentDescription = null, modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(0.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}
