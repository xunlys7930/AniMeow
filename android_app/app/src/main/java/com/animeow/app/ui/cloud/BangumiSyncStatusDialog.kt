package com.animeow.app.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.bangumi.bangumiLastRunText
import com.animeow.app.data.bangumi.bangumiQueueStatusText

@Composable
internal fun BangumiSyncStatusDialog(onDismiss: () -> Unit, model: CloudAccountViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var requested by remember { mutableStateOf(false) }
    BangumiSyncStatusContent(
        state = state.copy(message = state.message.takeIf { requested }),
        onDismiss = onDismiss,
        onRetry = {
            requested = true
            model.syncBangumiNow()
        },
        onToggleHomeStatus = model::setBangumiShowHomeStatus,
    )
    if (requested && state.bangumiMeteredPrompt) BangumiMeteredSyncDialog(
        state.bangumiPendingCount, { model.syncBangumiNow(allowMetered = true) }, model::dismissBangumiMeteredPrompt,
    )
}

@Composable
internal fun BangumiSyncStatusContent(
    state: CloudAccountUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onToggleHomeStatus: ((Boolean) -> Unit)? = null,
) {
    val report = state.syncSettings.bangumiLastReport
    val enabled = state.syncSettings.bangumiProgressSync || state.syncSettings.bangumiRatingSync
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bangumi 同步状态") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (enabled) bangumiQueueStatusText(state.bangumiPendingCount, report) else "自动同步已关闭")
                bangumiLastRunText(report)?.let { Text(it) }
                if (state.bangumiPendingCount > 0) {
                    report?.reason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(if (state.syncSettings.bangumiSyncWifiOnly) "后台等待 Wi-Fi 等不限流量网络，或点按立即重试。" else "联网后会自动重试，也可立即重试。")
                }
                report?.droppedItems?.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                if (report != null && report.dropped > 0) Text("被拒绝的改动需核对作品关联或授权后，重新修改或通过收藏对比合并同步。")
                if (!enabled) Text("可在「我的 → 账号与同步 → Bangumi 同步」开启进度或评分同步。")
                if (enabled && onToggleHomeStatus != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("在首页显示同步状态", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关闭后隐藏首页提示，后台同步照常运行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.syncSettings.bangumiShowHomeStatus,
                            onCheckedChange = onToggleHomeStatus,
                        )
                    }
                }
                Text("批量修改集数和想看归零仅保存到本机。", style = MaterialTheme.typography.bodySmall)
                if (state.bangumiSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (enabled && state.bangumiPendingCount > 0) TextButton(onClick = onRetry, enabled = !state.busy && !state.bangumiSyncing) {
                Text(if (state.bangumiSyncing) "同步中…" else "立即重试")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
