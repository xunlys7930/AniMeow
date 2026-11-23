package com.animeow.app.ui.community

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
 * 屏蔽管理：列出已屏蔽的用户并可取消屏蔽。
 * 入口在「设置 → 社区 → 屏蔽管理」与个人空间顶栏，统一使用“屏蔽管理”名称。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityBlocklistScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error = state.blocksError
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refreshBlocks() }
    androidx.compose.runtime.DisposableEffect(viewModel) { onDispose { viewModel.closeBlocks() } }
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
                title = { Text("屏蔽管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.session == null -> BlocklistHint(
                title = "还没有登录云账号",
                body = "登录后可以在这里查看和取消已屏蔽的用户。",
                modifier = Modifier.padding(padding),
            )
            state.blocksLoading && state.blocks.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null && state.blocks.isEmpty() -> com.animeow.app.ui.components.LoadErrorCard(
                "屏蔽列表暂时无法读取", error, viewModel::refreshBlocks,
                Modifier.padding(padding).padding(16.dp),
            )
            state.blocks.isEmpty() -> BlocklistHint(
                title = "还没有屏蔽任何用户",
                body = "在用户的个人空间点屏蔽按钮，可以屏蔽不想看到的人；屏蔽前会先确认。",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (error != null) item("error") { com.animeow.app.ui.components.LoadErrorCard("屏蔽列表暂时无法读取", error, viewModel::refreshBlocks) }
                if (state.busy) item("pending") { Text(state.busyLabel ?: "正在保存…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.blocks, key = { it.userId }) { user ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(user.nickname.ifBlank { user.username }, fontWeight = FontWeight.SemiBold)
                            Text("@${user.username}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { viewModel.unblockUser(user.userId) }, enabled = !state.busy) {
                            Text("取消屏蔽")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlocklistHint(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.Block, contentDescription = null, modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(0.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}
