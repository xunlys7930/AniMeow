package com.animeow.app.ui.importer

import com.animeow.app.ui.icons.AppIcons

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.animeow.app.ui.icons.AppIcon as Icon
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
import com.animeow.app.data.bangumi.BangumiMergeMatch
import com.animeow.app.data.bangumi.BangumiMergeRow
import com.animeow.app.data.local.animeRatingCompactLabel
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BangumiImportScreen(
    onBack: () -> Unit,
    onMerge: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BangumiImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val boundAccount by viewModel.boundAccount.collectAsStateWithLifecycle()
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
                        Icon(AppIcons.Back, contentDescription = "返回")
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
                            Icon(AppIcons.CloudDownload, contentDescription = null)
                            Text("导入或对比收藏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            if (boundAccount != null) {
                                "已绑定 @${boundAccount}。使用完整对比选择每个字段的合并方向；也可仅导入动画收藏，或输入其他用户名预览并导入公开动画收藏。"
                            } else {
                                "只读取公开可见的动画收藏。导入前会先展示新增、同名与回收站项目，不会直接修改本地资料库。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (boundAccount != null) {
                item {
                    Button(
                        onClick = onMerge,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is BangumiImportUiState.Loading && state !is BangumiImportUiState.Importing,
                    ) { Text("对比并合并收藏") }
                    TextButton(onClick = viewModel::inspectMine, modifier = Modifier.fillMaxWidth(),
                        enabled = state !is BangumiImportUiState.Loading && state !is BangumiImportUiState.Importing) {
                        Text("仅导入动画收藏（最多 1000 项）")
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bangumi 用户名或 UID") },
                    supportingText = { Text("用户名、UID 或 bgm.tv 个人主页链接都可以，不需要带 @") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = { viewModel.inspect(username) },
                            enabled = state !is BangumiImportUiState.Loading && state !is BangumiImportUiState.Importing,
                        ) {
                            Icon(AppIcons.Refresh, contentDescription = "读取收藏")
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
                        BangumiImportUiState.Idle -> HintCard(
                            if (boundAccount != null) {
                                "完整对比会认证读取所选分类，包含私密收藏；仅导入动画与下方公开导入最多读取 1000 项，不用于判断完整收藏差异。"
                            } else {
                                "输入用户名后先进行预检。公开收藏最多读取 1000 项。"
                            },
                        )
                        is BangumiImportUiState.Loading -> BusyCard(
                            when {
                                current.username.isBlank() -> "正在读取你的 Bangumi 收藏"
                                boundAccount != null && current.username == boundAccount -> "正在读取你的 Bangumi 收藏（含私密）"
                                else -> "正在读取 ${current.username} 的公开收藏"
                            },
                        )
                        is BangumiImportUiState.Importing -> BusyCard("正在导入 ${current.preview.candidates.size} 部作品，请稍候…")
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
                        is BangumiImportUiState.Ready -> {
                            if (current.showingComparison) {
                                ComparisonSummary(current, viewModel::toggleComparison)
                            } else {
                                PreviewSummary(
                                    state = current,
                                    onStrategySelected = viewModel::selectStrategy,
                                    onToggleAll = viewModel::toggleAll,
                                    onImportTagsChanged = viewModel::setImportTags,
                                    onImport = viewModel::confirmImport,
                                    onCompare = viewModel::toggleComparison,
                                )
                            }
                        }
                    }
                }
            }

            val ready = state as? BangumiImportUiState.Ready
            if (ready?.showingComparison == true) {
                itemsIndexed(ready.comparison.orEmpty()) { _, row ->
                    ComparisonCard(row)
                }
            } else if (ready != null) {
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
    onCompare: () -> Unit,
) {
    val preview = state.preview
    val selectedCount = state.selectedSubjectIds.size
    ElevatedCard(modifier = Modifier.fillMaxWidth().motionAnimateContentSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "找到 ${preview.candidates.size} 项" + if (preview.viaToken) "（含私密收藏）" else "",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "新增 ${preview.newCount} · 已存在 ${preview.duplicateCount} · 回收站 ${preview.trashedCount}" +
                    if (preview.truncated) " · 公开收藏共 ${preview.reportedTotal} 项，本次达到读取上限" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.warning?.let {
                Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.comparison != null) {
                TextButton(onClick = onCompare) { Text("对比本机与 Bangumi（只读）") }
            }
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
private fun ComparisonSummary(state: BangumiImportUiState.Ready, onBack: () -> Unit) {
    val rows = state.comparison.orEmpty()
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("动画收藏对比", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("仅查看本次读取的快照，不会更改任何数据。只按 Bangumi 编号匹配；同名作品不会自动关联。")
            if (!state.preview.viaToken || state.preview.truncated) {
                Text("远端收藏未完整读取，未出现的作品不能视为 Bangumi 没有收藏。", color = MaterialTheme.colorScheme.error)
            }
            state.warning?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            Text("已关联 ${rows.count { it.match == BangumiMergeMatch.MATCHED }} · 本机独有 ${rows.count { it.match == BangumiMergeMatch.LOCAL_ONLY }} · Bangumi 独有 ${rows.count { it.match == BangumiMergeMatch.REMOTE_ONLY }}")
            Text("未关联 ${rows.count { it.match == BangumiMergeMatch.UNLINKED }} · 待核对 ${rows.count { it.match == BangumiMergeMatch.AMBIGUOUS }} · 远端未知 ${rows.count { it.match == BangumiMergeMatch.REMOTE_UNKNOWN }} · 回收站 ${rows.count { it.match == BangumiMergeMatch.DELETED }}", style = MaterialTheme.typography.bodySmall)
            Text("本机显示保存的评分，Bangumi 显示个人评分；进度按已看集数显示，尚未核对每一集。书籍和游戏不在本次对比范围。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onBack) { Text("返回导入预览") }
        }
    }
}

@Composable
private fun ComparisonCard(row: BangumiMergeRow) {
    val local = row.local
    val remote = row.remote
    val matchLabel = when (row.match) {
        BangumiMergeMatch.MATCHED -> "已关联"
        BangumiMergeMatch.LOCAL_ONLY -> "本机独有"
        BangumiMergeMatch.REMOTE_ONLY -> "Bangumi 独有"
        BangumiMergeMatch.UNLINKED -> "尚未关联"
        BangumiMergeMatch.DELETED -> "本机回收站"
        BangumiMergeMatch.AMBIGUOUS -> "编号重复，需核对"
        BangumiMergeMatch.REMOTE_UNKNOWN -> "远端是否收藏未知"
    }
    val remoteStatus = when (remote?.collectionType) {
        1 -> "想看"
        2 -> "看过"
        3 -> "在看"
        4 -> "搁置"
        5 -> "抛弃"
        else -> "未知"
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(local?.title ?: remote?.title.orEmpty(), fontWeight = FontWeight.SemiBold)
            Text(matchLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            if (local != null) Text("本机：${local.status} · 已看 ${local.watchedEpisodes} 集 · 评分 ${animeRatingCompactLabel(local).ifBlank { "未评分" }}")
            if (remote != null) Text("Bangumi：$remoteStatus · 已看 ${remote.watchedEpisodes} 集 · 评分 ${remote.userRate?.toString() ?: "未评分"}")
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
