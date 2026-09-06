@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.animeow.app.ui.legacy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.preferences.DiscoveryDisplayConfig
import com.animeow.app.data.remote.DiscoveryFeedMode
import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoverySort
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.ui.discovery.DiscoveryViewModel
import com.animeow.app.ui.theme.ContentDensity
import java.time.LocalDate

@Composable
fun LegacyDiscoveryScreen(
    onAnimeImported: (Long) -> Unit,
    onImageSearchRequested: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoveryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val displayed = if (state.query.isBlank()) state.featured else state.results
    var showFilters by remember { mutableStateOf(false) }
    var showCustomization by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.openAnime.collect(onAnimeImported)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LegacyPageHeader(
                title = "发现",
                subtitle = "已载入 ${displayed.size} 部作品",
                actions = {
                    IconButton(onClick = { showCustomization = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "定制发现页")
                    }
                    IconButton(onClick = onImageSearchRequested) {
                        Icon(Icons.Outlined.ImageSearch, contentDescription = "以图搜番")
                    }
                    IconButton(onClick = viewModel::refreshFeatured) {
                        Icon(Icons.Outlined.Cloud, contentDescription = "刷新资料库")
                    }
                },
            )
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("搜索网络作品") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.filters.activeCount > 0,
                    onClick = { showFilters = true },
                    leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                    label = { Text(if (state.filters.activeCount > 0) "筛选 ${state.filters.activeCount}" else "筛选") },
                )
                FilterChip(
                    selected = state.feedMode == DiscoveryFeedMode.FEATURED,
                    onClick = { viewModel.selectFeedMode(DiscoveryFeedMode.FEATURED) },
                    label = { Text("推荐") },
                )
                FilterChip(
                    selected = state.feedMode == DiscoveryFeedMode.RANKING,
                    onClick = { viewModel.selectFeedMode(DiscoveryFeedMode.RANKING) },
                    label = { Text("排行榜") },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = viewModel::retry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
            }
        }
        if (state.warnings.isNotEmpty()) item {
            Text(
                state.warnings.joinToString(" · "),
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            state.isLoading && displayed.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null && displayed.isEmpty() -> item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("资料载入失败", style = MaterialTheme.typography.titleLarge)
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = viewModel::retry) { Text("重新载入") }
                    }
                }
            }
            displayed.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    Text("没有符合条件的作品", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> legacyRemoteRows(
                items = displayed,
                columns = state.networkSettings.display.gridColumns.coerceIn(2, 4),
                display = state.networkSettings.display,
                onClick = viewModel::select,
            )
        }
    }

    if (showFilters) {
        LegacyDiscoveryFilterSheet(
            filters = state.filters,
            onFiltersChanged = viewModel::updateFilters,
            onDismiss = { showFilters = false },
        )
    }

    if (showCustomization) {
        LegacyDiscoveryCustomizationSheet(
            config = state.networkSettings.display,
            onSave = viewModel::saveDisplaySettings,
            onReset = viewModel::resetDisplaySettings,
            onDismiss = { showCustomization = false },
        )
    }

    state.selected?.let { selected ->
        LegacyRemoteDetailSheet(
            anime = selected,
            importing = state.importingKey == selected.uniqueKey,
            onImport = { viewModel.import(selected) },
            onDismiss = { viewModel.select(null) },
        )
    }

    state.duplicate?.let { duplicate ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text("资料库中已有相似作品") },
            text = { Text("“${duplicate.existing.title}”可能与“${duplicate.remote.title}”重复。") },
            confirmButton = {
                Button(onClick = viewModel::openExistingDuplicate) { Text("打开已有作品") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::forceImportDuplicate) { Text("仍然导入") }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.legacyRemoteRows(
    items: List<RemoteAnime>,
    columns: Int,
    display: DiscoveryDisplayConfig,
    onClick: (RemoteAnime) -> Unit,
) {
    val rowColumns = columns.coerceIn(2, 4)
    val spacing = (10f * display.density.scale).coerceIn(6f, 14f).dp
    items.chunked(rowColumns).forEachIndexed { index, rowItems ->
        item(key = "legacy-discovery-$index-${rowItems.firstOrNull()?.uniqueKey}") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                rowItems.forEach { anime ->
                    LegacyRemoteCard(
                        anime = anime,
                        display = display,
                        modifier = Modifier.weight(1f),
                        onClick = { onClick(anime) },
                    )
                }
                repeat(rowColumns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LegacyRemoteCard(
    anime: RemoteAnime,
    display: DiscoveryDisplayConfig,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cardPadding = (12f * display.density.scale).coerceIn(8f, 16f).dp
    val metadata = buildList {
        anime.format?.takeIf(String::isNotBlank)?.let(::add)
        if (display.showAirDate) anime.airDate?.takeIf(String::isNotBlank)?.take(4)?.let(::add)
        if (display.showSource) add(anime.source.displayName)
    }.distinct()
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            AsyncImage(
                model = anime.coverUrl,
                contentDescription = anime.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.7f))),
                ),
            )
            anime.score?.takeIf { display.showScore }?.let { score ->
                Text(
                    text = "★ ${normalizeRemoteScore(score)}",
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(Color(0xB928272C), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(Modifier.padding(cardPadding), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                anime.title,
                fontWeight = FontWeight.ExtraBold,
                maxLines = if (display.density == ContentDensity.COMPACT) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (metadata.isNotEmpty()) {
                Text(
                    metadata.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (display.showTags && anime.tags.isNotEmpty() && display.density != ContentDensity.COMPACT) {
                Text(
                    anime.tags.take(2).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LegacyRemoteDetailSheet(
    anime: RemoteAnime,
    importing: Boolean,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = anime.coverUrl,
                    contentDescription = anime.title,
                    modifier = Modifier.size(width = 126.dp, height = 184.dp).clip(MaterialTheme.shapes.large),
                    contentScale = ContentScale.Crop,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.headlineSmall)
                    anime.originalTitle?.takeIf { it != anime.title }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        listOfNotNull(anime.format, anime.airDate, anime.studio).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    anime.score?.let { Text("★ ${normalizeRemoteScore(it)}", color = LegacyOrange, fontWeight = FontWeight.Bold) }
                }
            }
            if (!anime.summary.isNullOrBlank()) {
                Text(anime.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            if (anime.tags.isNotEmpty()) {
                Text(anime.tags.take(8).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onImport, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("导入到追番")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyDiscoveryCustomizationSheet(
    config: DiscoveryDisplayConfig,
    onSave: (DiscoveryDisplayConfig) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("经典版发现页定制", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "经典版保留封面墙结构，可单独调整列数、密度与信息显示。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                DiscoveryFilterGroup("封面墙列数") {
                    (2..4).forEach { columns ->
                        FilterChip(
                            selected = config.gridColumns == columns,
                            onClick = { onSave(config.copy(gridColumns = columns)) },
                            label = { Text("$columns 列") },
                        )
                    }
                }
            }
            item {
                DiscoveryFilterGroup("信息密度") {
                    ContentDensity.entries.forEach { density ->
                        FilterChip(
                            selected = config.density == density,
                            onClick = { onSave(config.copy(density = density)) },
                            label = { Text(density.displayName) },
                        )
                    }
                }
            }
            item {
                LegacyDiscoveryToggle("显示评分", config.showScore) { onSave(config.copy(showScore = it)) }
                LegacyDiscoveryToggle("显示来源", config.showSource) { onSave(config.copy(showSource = it)) }
                LegacyDiscoveryToggle("显示放送年份", config.showAirDate) { onSave(config.copy(showAirDate = it)) }
                LegacyDiscoveryToggle("显示标签", config.showTags) { onSave(config.copy(showTags = it)) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("恢复默认") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun LegacyDiscoveryToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyDiscoveryFilterSheet(
    filters: DiscoveryFilters,
    onFiltersChanged: (DiscoveryFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentYear = LocalDate.now().year
    val years = listOf<Int?>(null) + (currentYear downTo currentYear - 10).toList() + listOf(2000, 1990)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight(0.9f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选番剧", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onFiltersChanged(DiscoveryFilters()) }) { Text("重置") }
                }
            }
            item {
                DiscoveryFilterGroup("年份") {
                    years.forEach { year ->
                        FilterChip(
                            selected = filters.year == year,
                            onClick = { onFiltersChanged(filters.copy(year = year)) },
                            label = { Text(year?.toString() ?: "全部") },
                        )
                    }
                }
            }
            item {
                DiscoveryFilterGroup("季度") {
                    listOf<Int?>(null, 1, 4, 7, 10).forEach { month ->
                        FilterChip(
                            selected = filters.seasonMonth == month,
                            onClick = { onFiltersChanged(filters.copy(seasonMonth = month)) },
                            label = { Text(month?.let { "${it}月" } ?: "全部") },
                        )
                    }
                }
            }
            item {
                DiscoveryFilterGroup("格式") {
                    listOf<String?>(null, "TV", "剧场版", "OVA", "ONA", "SP").forEach { format ->
                        FilterChip(
                            selected = filters.format == format,
                            onClick = { onFiltersChanged(filters.copy(format = format)) },
                            label = { Text(format ?: "全部") },
                        )
                    }
                }
            }
            item {
                DiscoveryFilterGroup("风格 / 改编") {
                    listOf<String?>(null, "搞笑", "恋爱", "科幻", "奇幻", "战斗", "校园", "日常", "治愈", "悬疑", "推理", "漫画改", "小说改", "游戏改").forEach { tag ->
                        FilterChip(
                            selected = filters.tag == tag,
                            onClick = { onFiltersChanged(filters.copy(tag = tag)) },
                            label = { Text(tag ?: "全部") },
                        )
                    }
                }
            }
            item {
                DiscoveryFilterGroup("排序") {
                    DiscoverySort.entries.forEach { sort ->
                        FilterChip(
                            selected = filters.sort == sort,
                            onClick = { onFiltersChanged(filters.copy(sort = sort)) },
                            label = { Text(sort.displayName) },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { onFiltersChanged(DiscoveryFilters()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("清空条件") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("查看结果") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryFilterGroup(
    title: String,
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

private fun normalizeRemoteScore(score: Double): String {
    val value = if (score > 10.0) score / 10.0 else score
    return String.format(java.util.Locale.US, "%.1f", value)
}
