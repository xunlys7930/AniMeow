package com.animeow.app.ui.duplicates

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateCleanupScreen(
    onBack: () -> Unit,
    viewModel: DuplicateCleanupViewModel = viewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val mergingGroupKey by viewModel.mergingGroupKey.collectAsStateWithLifecycle()
    val keepSelections = remember { mutableStateMapOf<String, Long>() }
    var pendingMerge by remember { mutableStateOf<DuplicateAnimeGroup?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val contentEnter = motionFadeIn(220)
    val contentExit = motionFadeOut(160)
    val busy = mergingGroupKey != null

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(message) }
    }
    LaunchedEffect(groups, pendingMerge?.key) {
        val pendingKey = pendingMerge?.key ?: return@LaunchedEffect
        if (groups.none { it.key == pendingKey }) pendingMerge = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("番剧查重与合并") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = groups.isEmpty(),
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = { contentEnter togetherWith contentExit },
            label = "duplicate_scan",
        ) { empty ->
            if (empty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(54.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("没有发现重复作品", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "按作品类型和去空格标题持续自动扫描",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "发现 ${groups.size} 组重复项。选择要保留的主条目，其余条目会在合并后进入回收站。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(groups, key = DuplicateAnimeGroup::key) { group ->
                        val selected = keepSelections[group.key] ?: group.recommendedKeepId
                        DuplicateGroupCard(
                            group = group,
                            selectedId = selected,
                            enabled = !busy,
                            busy = mergingGroupKey == group.key,
                            onSelected = { keepSelections[group.key] = it },
                            onMerge = { pendingMerge = group },
                        )
                    }
                }
            }
        }
    }

    pendingMerge?.let { group ->
        val keepId = keepSelections[group.key] ?: group.recommendedKeepId
        val keep = group.items.first { it.id == keepId }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingMerge = null },
            title = { Text("合并 ${group.items.size} 个条目？") },
            text = {
                Text(
                    "将保留《${keep.title}》#${keep.id}，汇总最完整的进度、评分、日期、标签、" +
                        "观看记录和角色关联；其余 ${group.items.size - 1} 项进入回收站。",
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
                        "${if (group.subjectType == "book") "书籍" else "动画"} · ${group.items.size} 个条目",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onMerge, enabled = enabled) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("处理中")
                    } else {
                        Icon(Icons.Outlined.Merge, contentDescription = null)
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
