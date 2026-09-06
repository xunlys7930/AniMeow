package com.animeow.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.DiscoveryFeedMode
import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoverySort
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.preferences.BangumiApiMode
import com.animeow.app.data.preferences.DiscoveryDisplayConfig
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryResultLayout
import com.animeow.app.ui.components.DisplayPill
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.discovery.DiscoveryViewModel
import com.animeow.app.ui.theme.ContentDensity
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    customizationRequest: Int = 0,
    onAnimeImported: (Long) -> Unit,
    onImageSearchRequested: () -> Unit,
    onCommunityRequested: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoveryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showNetworkSettings by rememberSaveable { mutableStateOf(false) }
    var showDisplaySettings by rememberSaveable { mutableStateOf(false) }
    var handledCustomizationRequest by remember { mutableIntStateOf(customizationRequest) }
    val emptyEnter = motionFadeIn(220)
    val emptyExit = motionFadeOut(150)

    LaunchedEffect(customizationRequest) {
        if (customizationRequest > handledCustomizationRequest) {
            handledCustomizationRequest = customizationRequest
            showDisplaySettings = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.openAnime.collect(onAnimeImported)
    }

    val display = state.networkSettings.display
    val displayedItems = if (state.query.isBlank()) state.featured else state.results
    Box(modifier = modifier.fillMaxSize()) {
        val gridColumns = display.gridColumns.coerceIn(2, 5)
        val baseGridSpacing = when (gridColumns) {
            5 -> 6f
            4 -> 8f
            else -> 12f
        }
        val pageSpacing = (baseGridSpacing * display.density.scale).dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = pageSpacing,
                top = 8.dp,
                end = pageSpacing,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(pageSpacing),
        ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("找番") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = false,
                    onClick = onCommunityRequested,
                    label = { Text("社区交流") },
                    leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索动画或书籍") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showFilters = true }) {
                            Icon(Icons.Outlined.FilterAlt, contentDescription = "高级筛选")
                        }
                        IconButton(onClick = onImageSearchRequested) {
                            Icon(Icons.Outlined.ImageSearch, contentDescription = "以图搜番")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        DiscoverySourceFilter.entries.filter {
                            it != DiscoverySourceFilter.SERVER || state.networkSettings.showServerSource
                        },
                    ) { source ->
                        FilterChip(
                            selected = state.sourceFilter == source,
                            onClick = { viewModel.selectSource(source) },
                            label = { Text(source.displayName) },
                        )
                    }
                }
                IconButton(onClick = { showDisplaySettings = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "自定义发现页")
                }
                IconButton(onClick = { showNetworkSettings = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "数据源设置")
                }
            }
        }
        if (state.query.isBlank()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DiscoveryFeedMode.entries, key = DiscoveryFeedMode::name) { mode ->
                        FilterChip(
                            selected = state.feedMode == mode,
                            onClick = { viewModel.selectFeedMode(mode) },
                            label = { Text(mode.displayName) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.filters.activeCount > 0,
                            onClick = { showFilters = true },
                            label = { Text(if (state.filters.activeCount > 0) "筛选 ${state.filters.activeCount}" else "高级筛选") },
                            leadingIcon = { Icon(Icons.Outlined.FilterAlt, contentDescription = null) },
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.subjectType == "anime",
                    onClick = { viewModel.selectSubjectType("anime") },
                    label = { Text("动画") },
                )
                FilterChip(
                    selected = state.subjectType == "book",
                    onClick = { viewModel.selectSubjectType("book") },
                    label = { Text("书籍") },
                )
            }
        }
        if (state.isLoading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        state.warnings.forEach { warning ->
            item {
                AssistChip(onClick = viewModel::retry, label = { Text(warning) })
            }
        }
        state.error?.let { message ->
            item { DiscoveryErrorCard(message, viewModel::retry) }
        }

        item {
            Text(
                text = when {
                    state.query.isNotBlank() -> "搜索结果"
                    state.feedMode == DiscoveryFeedMode.RANKING -> "排行榜"
                    state.subjectType == "book" -> "热门书籍"
                    state.filters.activeCount > 0 -> "筛选推荐"
                    else -> "正在流行与本周放送"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (displayedItems.isEmpty() && !state.isLoading && state.error == null) {
            item {
                AnimatedContent(
                    targetState = state.query.isBlank(),
                    transitionSpec = { emptyEnter togetherWith emptyExit },
                    label = "discovery_empty",
                ) { featured ->
                    DiscoveryEmpty(
                        if (featured) "暂时没有获取到推荐作品" else "没有找到匹配作品，试试原文标题或切换来源",
                    )
                }
            }
        } else if (display.layout == DiscoveryResultLayout.LIST) {
            items(displayedItems, key = RemoteAnime::uniqueKey) { anime ->
                RemoteResultCard(
                    anime = anime,
                    importing = state.importingKey == anime.uniqueKey,
                    display = display,
                    onClick = { viewModel.select(anime) },
                    onImport = { viewModel.import(anime) },
                )
            }
        } else {
            items(
                items = displayedItems.chunked(gridColumns),
                key = { row -> row.joinToString("|") { it.uniqueKey } },
            ) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(pageSpacing),
                ) {
                    rowItems.forEach { anime ->
                        RemoteGridCard(
                            anime = anime,
                            importing = state.importingKey == anime.uniqueKey,
                            display = display,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.select(anime) },
                            onImport = { viewModel.import(anime) },
                        )
                    }
                    repeat(gridColumns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        }
    }

    state.selected?.let { anime ->
        RemoteDetailSheet(
            anime = anime,
            importing = state.importingKey == anime.uniqueKey,
            onImport = { viewModel.import(anime) },
            onDismiss = { viewModel.select(null) },
        )
    }

    state.duplicate?.let { request ->
        val exactRemote = request.existing.externalSource == request.remote.importSource &&
            request.existing.externalId == request.remote.importId
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text(if (exactRemote) "这部作品已经添加" else "发现可能重复的作品") },
            text = {
                Text(
                    "资料库中已有《${request.existing.title}》#${request.existing.id}。" +
                        if (exactRemote) "来源 ID 完全一致。" else "你可以打开已有记录，或仍然单独添加。",
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = viewModel::openExistingDuplicate) { Text("打开已有记录") }
            },
            dismissButton = {
                Row {
                    if (!exactRemote) {
                        TextButton(onClick = viewModel::forceImportDuplicate) { Text("仍然添加") }
                    }
                    TextButton(onClick = viewModel::dismissDuplicate) { Text("取消") }
                }
            },
        )
    }

    if (showFilters) {
        DiscoveryFilterSheet(
            filters = state.filters,
            onApply = {
                viewModel.updateFilters(it)
                showFilters = false
            },
            onDismiss = { showFilters = false },
        )
    }

    if (showNetworkSettings) {
        DiscoveryNetworkSheet(
            settings = state.networkSettings,
            onSave = {
                viewModel.saveNetworkSettings(it)
                showNetworkSettings = false
            },
            onReset = viewModel::resetNetworkSettings,
            onDismiss = { showNetworkSettings = false },
        )
    }

    if (showDisplaySettings) {
        DiscoveryDisplaySheet(
            config = display,
            onSave = viewModel::saveDisplaySettings,
            onReset = {
                viewModel.resetDisplaySettings()
            },
            onDismiss = { showDisplaySettings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryFilterSheet(
    filters: DiscoveryFilters,
    onApply: (DiscoveryFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var year by remember(filters) { mutableStateOf(filters.year) }
    var season by remember(filters) { mutableStateOf(filters.seasonMonth) }
    var format by remember(filters) { mutableStateOf(filters.format) }
    var tag by remember(filters) { mutableStateOf(filters.tag.orEmpty()) }
    var minimumScore by remember(filters) { mutableStateOf(filters.minimumScore) }
    var sort by remember(filters) { mutableStateOf(filters.sort) }
    val years = listOf<Int?>(null) + ((java.time.LocalDate.now().year + 1) downTo 2015)
    val formats = listOf<String?>(null, "TV", "OVA", "剧场版", "Web", "SP")
    val tagPresets = listOf("原创", "漫画改", "小说改", "游戏改", "热血", "恋爱", "校园", "日常")

    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("发现筛选器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("同一套条件会用于搜索、推荐和排行榜。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("排序", fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DiscoverySort.entries) { option ->
                        FilterChip(sort == option, { sort = option }, label = { Text(option.displayName) })
                    }
                }
            }
            item {
                Text("年份", fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(years) { option ->
                        FilterChip(year == option, { year = option }, label = { Text(option?.toString() ?: "全部") })
                    }
                }
            }
            item {
                Text("季度", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "全部", 1 to "冬", 4 to "春", 7 to "夏", 10 to "秋").forEach { (month, label) ->
                        FilterChip(season == month, { season = month }, label = { Text(label) })
                    }
                }
            }
            item {
                Text("格式", fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(formats) { option ->
                        FilterChip(format == option, { format = option }, label = { Text(option ?: "全部") })
                    }
                }
            }
            item {
                Text("常用标签", fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tagPresets) { preset ->
                        FilterChip(tag == preset, { tag = if (tag == preset) "" else preset }, label = { Text(preset) })
                    }
                }
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义标签 / 类型") },
                    singleLine = true,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("最低评分", fontWeight = FontWeight.SemiBold)
                        Text(
                            minimumScore?.let { "%.1f / 10".format(it / 10.0) } ?: "不限",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = minimumScore != null,
                        onCheckedChange = { enabled -> minimumScore = if (enabled) 70 else null },
                    )
                }
                minimumScore?.let { value ->
                    Slider(
                        value = value / 10f,
                        onValueChange = { minimumScore = (it * 10f).roundToInt() },
                        valueRange = 0f..10f,
                        steps = 19,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { onApply(DiscoveryFilters()) }) { Text("清空") }
                    Button(onClick = {
                        onApply(
                            DiscoveryFilters(
                                year = year,
                                seasonMonth = season,
                                format = format,
                                tag = tag.trim().takeIf(String::isNotEmpty),
                                minimumScore = minimumScore,
                                sort = sort,
                            ),
                        )
                    }) { Text("应用") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryNetworkSheet(
    settings: DiscoveryNetworkSettings,
    onSave: (DiscoveryNetworkSettings) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember(settings) { mutableStateOf(settings.bangumiApiMode) }
    var apiProxy by remember(settings) { mutableStateOf(settings.bangumiApiProxyBase) }
    var imageProxyEnabled by remember(settings) { mutableStateOf(settings.useBangumiImageProxy) }
    var imageProxy by remember(settings) { mutableStateOf(settings.bangumiImageProxyBase) }
    var showServer by remember(settings) { mutableStateOf(settings.showServerSource) }
    var includeServer by remember(settings) { mutableStateOf(settings.includeServerInAllSources) }
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("数据源与代理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("设置立即作用于搜索、导入、角色和收藏迁移。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Bangumi API 策略", fontWeight = FontWeight.SemiBold)
                BangumiApiMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { mode = option },
                        label = {
                            Column {
                                Text(option.label)
                                Text(option.description, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                OutlinedTextField(apiProxy, { apiProxy = it }, Modifier.fillMaxWidth(), label = { Text("Bangumi API 代理地址") }, singleLine = true)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("原图失败后自动使用代理", fontWeight = FontWeight.SemiBold)
                        Text("代理成功后持久保存到本地，后续优先读取本地封面", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(imageProxyEnabled, { imageProxyEnabled = it })
                }
                OutlinedTextField(
                    imageProxy,
                    { imageProxy = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("备用封面代理地址") },
                    enabled = imageProxyEnabled,
                    singleLine = true,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("显示资料库服务器来源")
                        Text("关闭后隐藏服务器筛选入口，保持发现页简洁", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(showServer, {
                        showServer = it
                        if (!it) includeServer = false
                    })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("“全部来源”包含服务器")
                        Text("仍可随时单独选择服务器来源", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(includeServer, { includeServer = it }, enabled = showServer)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onReset) { Text("恢复默认") }
                    Button(onClick = {
                        onSave(
                            settings.copy(
                                bangumiApiMode = mode,
                                bangumiApiProxyBase = apiProxy,
                                useBangumiImageProxy = imageProxyEnabled,
                                bangumiImageProxyBase = imageProxy,
                                showServerSource = showServer,
                                includeServerInAllSources = showServer && includeServer,
                            ),
                        )
                    }) { Text("保存") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryDisplaySheet(
    config: DiscoveryDisplayConfig,
    onSave: (DiscoveryDisplayConfig) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(config) { mutableStateOf(config) }
    fun updateDraft(transform: (DiscoveryDisplayConfig) -> DiscoveryDisplayConfig) {
        val updated = transform(draft)
        draft = updated
        onSave(updated)
    }
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("自定义发现页", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("修改会立即保存；直接返回或下滑关闭也不会丢失。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("结果布局", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiscoveryResultLayout.entries.forEach { option ->
                        FilterChip(
                            selected = draft.layout == option,
                            onClick = { updateDraft { it.copy(layout = option) } },
                            label = { Text(option.displayName) },
                        )
                    }
                }
            }
            item {
                Text("内容密度", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContentDensity.entries.forEach { option ->
                        FilterChip(
                            selected = draft.density == option,
                            onClick = { updateDraft { it.copy(density = option) } },
                            label = { Text(option.displayName) },
                        )
                    }
                }
                Text(
                    draft.density.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (draft.layout == DiscoveryResultLayout.GRID) {
                item {
                    Text("网格列数", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (2..5).forEach { columns ->
                            FilterChip(
                                selected = draft.gridColumns == columns,
                                onClick = { updateDraft { it.copy(gridColumns = columns) } },
                                label = { Text("$columns 列") },
                            )
                        }
                    }
                    Text(
                        "默认三列；可按封面可读性和屏幕尺寸自由调整。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { DiscoveryDisplaySwitch("显示数据来源", draft.showSource) { value -> updateDraft { it.copy(showSource = value) } } }
            item { DiscoveryDisplaySwitch("显示远端评分", draft.showScore) { value -> updateDraft { it.copy(showScore = value) } } }
            item { DiscoveryDisplaySwitch("显示放送日期", draft.showAirDate) { value -> updateDraft { it.copy(showAirDate = value) } } }
            item { DiscoveryDisplaySwitch("显示标签摘要", draft.showTags) { value -> updateDraft { it.copy(showTags = value) } } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(
                        onClick = {
                            draft = DiscoveryDisplayConfig()
                            onReset()
                        },
                    ) { Text("恢复默认") }
                    Button(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryDisplaySwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RemoteResultCard(
    anime: RemoteAnime,
    importing: Boolean,
    display: DiscoveryDisplayConfig,
    onClick: () -> Unit,
    onImport: () -> Unit,
) {
    val scale = display.density.scale
    val metadata = remoteMeta(anime, display)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(
            role = Role.Button,
            onClickLabel = "查看《${anime.title}》详情",
            onClick = onClick,
        ),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding((12 * scale).dp),
            horizontalArrangement = Arrangement.spacedBy((12 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteCover(anime, Modifier.size(width = (76 * scale).dp, height = (108 * scale).dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((5 * scale).dp)) {
                Text(anime.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                anime.originalTitle?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (metadata.isNotEmpty()) {
                    Text(metadata, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                if (display.showTags && anime.tags.isNotEmpty()) {
                    Text(anime.tags.take(3).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.semantics {
                    contentDescription = if (importing) "正在加入《${anime.title}》" else "将《${anime.title}》加入资料库"
                },
            ) {
                if (importing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Add, contentDescription = null)
            }
        }
    }
}

@Composable
private fun RemoteGridCard(
    anime: RemoteAnime,
    importing: Boolean,
    display: DiscoveryDisplayConfig,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onImport: () -> Unit,
) {
    val scale = display.density.scale
    val narrowCard = display.gridColumns >= 4
    if (narrowCard) {
        ElevatedCard(
            modifier = modifier.clickable(
                role = Role.Button,
                onClickLabel = "查看《${anime.title}》详情",
                onClick = onClick,
            ),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                RemoteCover(anime, Modifier.fillMaxSize())
                Text(
                    text = anime.title,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onImport,
                    enabled = !importing,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(32.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .semantics {
                            contentDescription = if (importing) "正在加入《${anime.title}》" else "将《${anime.title}》加入资料库"
                        },
                ) {
                    if (importing) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
        return
    }
    val metadata = remoteMeta(anime, display)
    ElevatedCard(
        modifier = modifier.clickable(
            role = Role.Button,
            onClickLabel = "查看《${anime.title}》详情",
            onClick = onClick,
        ),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        RemoteCover(anime, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        Column(
            modifier = Modifier.padding((10 * scale).dp),
            verticalArrangement = Arrangement.spacedBy((5 * scale).dp),
        ) {
            Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            if (metadata.isNotEmpty()) {
                Text(
                    metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (display.showTags && anime.tags.isNotEmpty()) {
                Text(
                    anime.tags.take(2).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onImport, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                if (importing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Add, contentDescription = null)
                Text("加入")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteDetailSheet(
    anime: RemoteAnime,
    importing: Boolean,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RemoteCover(anime, Modifier.size(width = 112.dp, height = 168.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(anime.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        anime.originalTitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DisplayPill(anime.source.displayName)
                        Text(remoteMeta(anime), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            anime.summary?.let { summary ->
                item {
                    Text("简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (anime.tags.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(anime.tags) { tag -> DisplayPill(tag) }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = onImport, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                    if (importing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("加入资料库并导入标签")
                }
            }
        }
    }
}

@Composable
private fun RemoteCover(anime: RemoteAnime, modifier: Modifier) {
    Box(
        modifier = modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (!anime.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = anime.coverUrl,
                contentDescription = anime.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(anime.title.take(2), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DiscoveryErrorCard(message: String, onRetry: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text("重试")
            }
        }
    }
}

@Composable
private fun DiscoveryEmpty(message: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun remoteMeta(anime: RemoteAnime, display: DiscoveryDisplayConfig? = null): String = buildList {
    if (display?.showSource != false) add(anime.source.displayName)
    if (display?.showScore != false) anime.score?.let {
        add(if (anime.importSource == "bangumi" || it <= 10.0) "%.1f".format(it) else "${it.toInt()}%")
    }
    if (display?.showAirDate != false) anime.airDate?.take(10)?.let(::add)
    anime.episodes?.let { add("$it 集") }
    anime.format?.let(::add)
}.joinToString(" · ")
