package com.animeow.app.ui.importer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.BangumiImportCandidate
import com.animeow.app.data.BangumiImportConflictStrategy
import com.animeow.app.data.BangumiImportMatchKind
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BangumiImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BangumiImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    val contentEnter = motionFadeIn()
    val contentExit = motionFadeOut()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("导入 Bangumi 收藏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                            Text("从公开收藏快速搬家", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "只读取公开可见的动画收藏。导入前会先展示新增、同名与回收站项目，不会直接修改本地资料库。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bangumi 用户名或 UID") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = { viewModel.inspect(username) },
                            enabled = state !is BangumiImportUiState.Loading && state !is BangumiImportUiState.Importing,
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "读取收藏")
                        }
                    },
                )
            }

            item {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { contentEnter togetherWith contentExit },
                    label = "bangumi-import-state",
                ) { current ->
                    when (current) {
                        BangumiImportUiState.Idle -> HintCard("输入用户名后先进行预检。公开收藏最多读取 1000 项。")
                        is BangumiImportUiState.Loading -> BusyCard("正在读取 ${current.username} 的公开收藏")
                        is BangumiImportUiState.Importing -> BusyCard("正在以单次事务导入 ${current.preview.candidates.size} 项")
                        is BangumiImportUiState.Error -> ResultCard(
                            title = "暂时无法继续",
                            body = current.message,
                            action = "重新开始",
                            onAction = viewModel::reset,
                        )
                        is BangumiImportUiState.Success -> ResultCard(
                            title = "收藏已导入",
                            body = "新增 ${current.summary.addedCount} · 合并 ${current.summary.mergedCount} · " +
                                "恢复 ${current.summary.restoredCount} · 跳过 ${current.summary.skippedCount} · " +
                                "新建标签 ${current.summary.importedTagCount}",
                            action = "继续导入",
                            onAction = viewModel::reset,
                        )
                        is BangumiImportUiState.Ready -> PreviewSummary(
                            state = current,
                            onStrategySelected = viewModel::selectStrategy,
                            onToggleAll = viewModel::toggleAll,
                            onImportTagsChanged = viewModel::setImportTags,
                            onImport = viewModel::confirmImport,
                        )
                    }
                }
            }

            val ready = state as? BangumiImportUiState.Ready
            if (ready != null) {
                items(
                    items = ready.preview.candidates,
                    key = { it.item.subjectId },
                ) { candidate ->
                    CandidateCard(
                        candidate = candidate,
                        selected = candidate.item.subjectId in ready.selectedSubjectIds,
                        onSelectedChanged = { viewModel.toggleSubject(candidate.item.subjectId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewSummary(
    state: BangumiImportUiState.Ready,
    onStrategySelected: (BangumiImportConflictStrategy) -> Unit,
    onToggleAll: () -> Unit,
    onImportTagsChanged: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    val preview = state.preview
    val selectedCount = state.selectedSubjectIds.size
    ElevatedCard(modifier = Modifier.fillMaxWidth().motionAnimateContentSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "找到 ${preview.candidates.size} 项",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "新增 ${preview.newCount} · 已存在 ${preview.duplicateCount} · 回收站 ${preview.trashedCount}" +
                    if (preview.truncated) " · 公开收藏共 ${preview.reportedTotal} 项，本次达到读取上限" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选 $selectedCount / ${preview.candidates.size}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onToggleAll) {
                    Text(if (selectedCount == preview.candidates.size) "取消全选" else "全选")
                }
            }
            Text("重复项策略", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(BangumiImportConflictStrategy.entries) { strategy ->
                    FilterChip(
                        selected = state.strategy == strategy,
                        onClick = { onStrategySelected(strategy) },
                        label = { Text(strategy.displayName) },
                    )
                }
            }
            Text(state.strategy.description, style = MaterialTheme.typography.bodySmall)
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = state.importTags,
                    role = Role.Switch,
                    onValueChange = onImportTagsChanged,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Switch(checked = state.importTags, onCheckedChange = null)
                Column {
                    Text("导入 Bangumi 标签", fontWeight = FontWeight.SemiBold)
                    Text("关闭后只导入作品资料", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedCount > 0,
            ) {
                Text("导入 $selectedCount 项")
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: BangumiImportCandidate,
    selected: Boolean,
    onSelectedChanged: (Boolean) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = selected,
            role = Role.Checkbox,
            onValueChange = onSelectedChanged,
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    candidate.item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
            )
            Checkbox(checked = selected, onCheckedChange = null)
            Text(
                    candidate.item.progressText + candidate.existingTitle?.let { " · 本地：$it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                candidate.matchKind.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = when (candidate.matchKind) {
                    BangumiImportMatchKind.NEW -> MaterialTheme.colorScheme.primary
                    BangumiImportMatchKind.TRASHED -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun BusyCard(message: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator()
            Text(message)
        }
    }
}

@Composable
private fun HintCard(message: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction) { Text(action) }
        }
    }
}
