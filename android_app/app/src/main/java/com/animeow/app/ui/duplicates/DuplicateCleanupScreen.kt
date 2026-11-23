package com.animeow.app.ui.duplicates

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.animeRatingSummary
import com.animeow.app.data.local.allTitles
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DuplicateCleanupScreen(
    onBack: () -> Unit,
    viewModel: DuplicateCleanupViewModel = viewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val animes by viewModel.animes.collectAsStateWithLifecycle()
    val mergingGroupKey by viewModel.mergingGroupKey.collectAsStateWithLifecycle()
    val keepSelections = remember { mutableStateMapOf<String, Long>() }
    var pendingMerge by remember { mutableStateOf<DuplicateAnimeGroup?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val selectedItems = animes.filter { it.id in selectedIds }
    val manualGroup = selectedItems.takeIf { it.size >= 2 }?.let {
        DuplicateAnimeGroup("manual|${it.map(AnimeEntity::id).sorted().joinToString(",")}", "选择要保留的记录", normalizeSubjectType(it.first().subjectType), it)
    }
    val snackbar = remember { SnackbarHostState() }
    val contentEnter = motionFadeIn(220)
    val contentExit = motionFadeOut(160)
    val busy = mergingGroupKey != null

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(message) }
    }
    LaunchedEffect(animes, pendingMerge?.key) {
        if (pendingMerge?.items?.any { item -> animes.none { it.id == item.id } } == true) pendingMerge = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("查重与合并") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("同一作品的中文、日文或英文条目可以手动合并。请先核对季度和版本，动画与书籍分开处理。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { manual = !manual }, enabled = !busy) {
                        Icon(if (manual) AppIcons.Search else AppIcons.Merge, null)
                        Text(if (manual) "查看自动查重" else "手动选择合并")
                    }
                    if (manual && selectedItems.isNotEmpty()) TextButton(onClick = { selectedIds = emptyList() }, enabled = !busy) { Text("清空选择") }
                }
            }
            if (manual) {
                item {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索标题或原名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("已选 ${selectedItems.size} 项 · 至少选择两项", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                manualGroup?.let { group ->
                    item(key = "manual_preview") {
                        DuplicateGroupCard(group, keepSelections[group.key] ?: group.recommendedKeepId, !busy, mergingGroupKey == group.key,
                            { keepSelections[group.key] = it }, { pendingMerge = group })
                    }
                }
                val chosenType = selectedItems.firstOrNull()?.let { normalizeSubjectType(it.subjectType) }
                val candidates = animes.filter { anime ->
                    (chosenType == null || normalizeSubjectType(anime.subjectType) == chosenType) &&
                        (query.isBlank() || anime.allTitles().any { it.contains(query.trim(), ignoreCase = true) })
                }
                if (candidates.isEmpty()) item { Text(if (animes.isEmpty()) "资料库还没有作品" else "没有找到匹配作品，可换个名称搜索") }
                items(candidates, key = { "candidate_${it.id}" }) { anime ->
                    val selected = anime.id in selectedIds
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .clickable(enabled = !busy, role = Role.Checkbox) {
                                selectedIds = if (selected) selectedIds - anime.id else selectedIds + anime.id
                            }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(anime.title, fontWeight = FontWeight.SemiBold)
                            anime.originalTitle?.takeIf { it.isNotBlank() && it != anime.title }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("${if (normalizeSubjectType(anime.subjectType) == "book") "书籍" else "动画"} · ${anime.airDate ?: "日期未记录"} · ${anime.totalEpisodes.takeIf { it > 0 } ?: "?"} 集", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(selected, onCheckedChange = null, enabled = !busy)
                    }
                }
            } else {
                item {
                    Text(if (groups.isEmpty()) "没有发现标题、原名或来源相同的作品。不同语言的标题可以使用上方手动合并。"
                        else "发现 ${groups.size} 组可能重复的作品，核对后选择要保留的记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(groups, key = DuplicateAnimeGroup::key) { group ->
                    DuplicateGroupCard(group, keepSelections[group.key] ?: group.recommendedKeepId, !busy, mergingGroupKey == group.key,
                        { keepSelections[group.key] = it }, { pendingMerge = group })
                }
            }
        }
    }

    pendingMerge?.let { group ->
        val keepId = keepSelections[group.key] ?: group.recommendedKeepId
        val keep = group.items.first { it.id == keepId }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingMerge = null },
            title = { Text("合并这 ${group.items.size} 条记录？") },
            text = {
                Text(
                    "将保留《${keep.title}》的名称和已有评分，进度取较大值，补全日期并保留置顶。\n\n" +
                        "合并标签、观看记录、角色与角色组关联，不同评价一并保存；其他语言标题和来源关联也会保留。\n\n" +
                        "其余 ${group.items.size - 1} 项移入回收站，可随时单独恢复。",
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.merge(group, keepId)
                    },
                    enabled = !busy,
                ) {
                    if (mergingGroupKey == group.key) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在合并")
                    } else {
                        Text("合并")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMerge = null }, enabled = !busy) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateAnimeGroup,
    selectedId: Long,
    enabled: Boolean,
    busy: Boolean,
    onSelected: (Long) -> Unit,
    onMerge: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().motionAnimateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${if (group.subjectType == "book") "书籍" else "动画"} · ${group.items.size} 条记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onMerge, enabled = enabled) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("处理中")
                    } else {
                        Icon(AppIcons.Merge, contentDescription = null)
                        Text("合并")
                    }
                }
            }
            group.items.forEach { anime ->
                DuplicateItemRow(
                    anime = anime,
                    selected = selectedId == anime.id,
                    recommended = group.recommendedKeepId == anime.id,
                    enabled = enabled,
                    onClick = { onSelected(anime.id) },
                )
            }
        }
    }
}

@Composable
private fun DuplicateItemRow(
    anime: AnimeEntity,
    selected: Boolean,
    recommended: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ratingSummary = animeRatingSummary(anime)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            )
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 62.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            if (!anime.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = anime.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                "#${anime.id} · ${anime.status} · ${anime.watchedEpisodes}/${anime.totalEpisodes.takeIf { it > 0 } ?: "?"}" +
                    ratingSummary.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recommended) {
                Text("推荐保留：资料较完整", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}
