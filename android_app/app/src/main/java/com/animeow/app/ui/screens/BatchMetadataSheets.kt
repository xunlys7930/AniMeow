@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.animeow.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.animeow.app.data.RemoteMetadataFields
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.tracker.*

@Composable
internal fun BatchMetadataOptionsDialog(selectedCount: Int, onStart: (BatchMetadataOptions) -> Unit, onDismissRequest: () -> Unit) {
    var fields by remember { mutableStateOf(RemoteMetadataFields()) }
    var overwrite by remember { mutableStateOf(false) }
    val windowPixels = LocalWindowInfo.current.containerSize.height
    val windowHeight = if (windowPixels > 0) with(LocalDensity.current) { windowPixels.toDp() }
        else LocalConfiguration.current.screenHeightDp.dp
    StableModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(Modifier.fillMaxWidth().heightIn(max = windowHeight * 0.86f)
            .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("批量补全资料", style = MaterialTheme.typography.headlineSmall)
            Text("已选 $selectedCount 部作品 · 逐部核对同一作品", style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1  选择写入方式", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!overwrite, { overwrite = false }, label = { Text("只补空白") })
                    FilterChip(overwrite, { overwrite = true }, label = { Text("覆盖所选字段") })
                }
                Text(if (overwrite) "所选字段的已有内容可能被替换，包含评分。请核对下方范围。" else "保留已有内容，只填写空白资料。",
                    color = if (overwrite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("2  选择补全内容", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(Triple("封面", fields.cover, { fields = fields.copy(cover = !fields.cover) }),
                        Triple("评分", fields.rating, { fields = fields.copy(rating = !fields.rating) }),
                        Triple("集数", fields.episodes, { fields = fields.copy(episodes = !fields.episodes) }),
                        Triple("制作公司", fields.studio, { fields = fields.copy(studio = !fields.studio) }),
                        Triple("简介", fields.synopsis, { fields = fields.copy(synopsis = !fields.synopsis) }),
                        Triple("放送日期", fields.airDate, { fields = fields.copy(airDate = !fields.airDate) }),
                        Triple("来源与原名", fields.externalLink, { fields = fields.copy(externalLink = !fields.externalLink) }),
                        Triple("添加来源标签", fields.tags, { fields = fields.copy(tags = !fields.tags) })).forEach { (label, checked, toggle) ->
                        FilterChip(checked, toggle, label = { Text(label) })
                    }
                }
                com.animeow.app.ui.discovery.MetadataSourcesButton()
                Text("观看进度、状态、感想、系列与已有标签保留。同名歧义或其他季度不会自动套用；未匹配项可手动选择。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { onStart(BatchMetadataOptions(fields, overwrite)) }, enabled = selectedCount > 0 && fields.hasSelection,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("开始补全 $selectedCount 部") }
            TextButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}

@Composable
internal fun BatchMetadataProgressDialog(state: BatchMetadataState, onSkip: () -> Unit, onStop: () -> Unit,
    onRetry: () -> Unit, onClose: () -> Unit) {
    StableModalBottomSheet(onDismissRequest = { if (!state.isRunning) onClose() }, sheetGesturesEnabled = !state.isRunning) {
        BatchMetadataProgressContent(state, onSkip, onStop, onRetry, onClose)
    }
}

@Composable
internal fun BatchMetadataProgressContent(state: BatchMetadataState, onSkip: () -> Unit, onStop: () -> Unit,
    onRetry: () -> Unit, onClose: () -> Unit) {
    var onlyUnresolved by remember { mutableStateOf(false) }
    val windowPixels = LocalWindowInfo.current.containerSize.height
    val windowHeight = if (windowPixels > 0) with(LocalDensity.current) { windowPixels.toDp() }
        else LocalConfiguration.current.screenHeightDp.dp
    val rows = state.results.filter { !onlyUnresolved || it.outcome.retryable }
    Column(Modifier.fillMaxWidth().heightIn(max = windowHeight * 0.86f)
        .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (state.isRunning) "正在补全资料" else "补全结果", style = MaterialTheme.typography.headlineSmall)
        if (state.isRunning) {
            Text("${state.stage} · 第 ${(state.completed + 1).coerceAtMost(state.total)} / ${state.total} 部",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(state.currentTitle ?: "正在准备资料源…", style = MaterialTheme.typography.titleMedium, maxLines = 3)
        } else Text(state.message.orEmpty(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        Text("已处理 ${state.completed} / ${state.total} 部", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BatchMetadataOutcome.entries.filter { it != BatchMetadataOutcome.PENDING }.forEach {
                Text("${it.label} ${state.count(it)}", style = MaterialTheme.typography.labelLarge,
                    color = if (it == BatchMetadataOutcome.FAILED && state.count(it) > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!state.isRunning && state.completed < state.total) Text("剩余 ${state.total - state.completed} 部未完成，可继续重试。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!onlyUnresolved, { onlyUnresolved = false }, label = { Text("全部明细") })
            FilterChip(onlyUnresolved, { onlyUnresolved = true }, label = { Text("待处理 ${state.retryIds.size}") })
        }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("batch-metadata-results"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) item { Text(if (state.results.isEmpty()) "正在准备作品清单…" else "没有待处理的作品", style = MaterialTheme.typography.bodySmall) }
            items(rows, key = { it.animeId }) { row ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall)
                        val running = state.isRunning && state.currentAnimeId == row.animeId
                        Text(if (running) state.stage else row.outcome.label, style = MaterialTheme.typography.labelMedium,
                            color = if (row.outcome == BatchMetadataOutcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (!running) Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.isRunning) {
            Text("可跳过耗时的当前作品；停止后已保存的资料保留。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onSkip, enabled = state.currentAnimeId != null && state.stage == "正在搜索并核对资料", modifier = Modifier.weight(1f)) { Text("跳过当前") }
                TextButton(onStop, modifier = Modifier.weight(1f)) { Text("停止补全") }
            }
        } else {
            if (state.retryIds.isNotEmpty()) Button(onRetry, modifier = Modifier.fillMaxWidth()) { Text("仅重试待处理 ${state.retryIds.size} 部") }
            TextButton(onClose, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
