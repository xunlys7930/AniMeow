package com.animeow.app.ui.screens

import com.animeow.app.ui.navigation.PageActionRequests

import com.animeow.app.ui.icons.AppIcons

import com.animeow.app.ui.navigation.LocalFloatingNavigationInset

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import com.animeow.app.ui.components.motionAnimationSpec
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.DiscoveryFeedMode
import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoverySort
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.isChineseLanguage
import com.animeow.app.data.preferences.BangumiApiMode
import com.animeow.app.data.preferences.DiscoveryDisplayConfig
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryResultLayout
import com.animeow.app.data.preferences.MetadataLookupStep
import com.animeow.app.data.preferences.SHARED_PROXY_QUOTA_NOTICE
import com.animeow.app.ui.components.DisplayPill
import com.animeow.app.ui.components.LoadErrorCard
import com.animeow.app.ui.components.ConfirmationDialog
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.discovery.DiscoveryViewModel
import com.animeow.app.ui.discovery.DiscoveryLoadingStatus
import com.animeow.app.ui.discovery.SeasonalOverviewControls
import com.animeow.app.ui.theme.ContentDensity
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DiscoveryScreen(
    modifier: Modifier = Modifier,
    pageActions: PageActionRequests? = null,
    onBackRequested: (() -> Unit)? = null,
    onGlobalSearchRequested: () -> Unit = {},
    onAnimeImported: (Long) -> Unit,
    onImageSearchRequested: () -> Unit,
    onCommunityRequested: () -> Unit,
    viewModel: DiscoveryViewModel = viewModel(),
) {
    com.animeow.app.ui.discovery.DiscoveryModuleHost(modifier, viewModel, pageActions, onBackRequested, onGlobalSearchRequested) { contentModifier ->
        CatalogDiscoveryScreen(contentModifier, onAnimeImported, onImageSearchRequested, onCommunityRequested, onGlobalSearchRequested, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CatalogDiscoveryScreen(
    modifier: Modifier,
    onAnimeImported: (Long) -> Unit,
    onImageSearchRequested: () -> Unit,
    onCommunityRequested: () -> Unit,
    onGlobalSearchRequested: () -> Unit,
    viewModel: DiscoveryViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showNetworkSettings by rememberSaveable { mutableStateOf(false) }
    var showDisplaySettings by rememberSaveable { mutableStateOf(false) }
    var showWarnings by remember { mutableStateOf(false) }
    var showBroadcast by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val emptyEnter = motionFadeIn(220)
    val emptyExit = motionFadeOut(150)


    LaunchedEffect(viewModel) {
        viewModel.openAnime.collect(onAnimeImported)
    }

    val display = state.networkSettings.display
    val searching = state.query.isNotBlank()
    val displayedItems = state.batchCandidates ?: if (state.query.isBlank()) state.featured else state.results
    val classic = com.animeow.app.ui.legacy.LocalFrontendMode.current == com.animeow.app.ui.theme.FrontendMode.LEGACY
    val classicFeature = displayedItems.firstOrNull().takeIf { classic && state.query.isBlank() && display.layout != DiscoveryResultLayout.LIST }
    if (showBroadcast && state.subjectType == "anime") {
        com.animeow.app.ui.discovery.DailyBroadcastScreen(onBack = { showBroadcast = false }, onSelect = viewModel::select,
            isAdded = { "${it.importSource}:${it.importId}" in state.localIdsByIdentity }, modifier = modifier)
    } else Box(modifier = modifier.fillMaxSize()) {
        BackHandler(enabled = state.batchMode) { viewModel.endBatch() }
        val gridColumns = display.gridColumns.coerceIn(2, 5)
        val baseGridSpacing = when (gridColumns) {
            5 -> 6f
            4 -> 8f
            else -> 12f
        }
        val pageSpacing = (baseGridSpacing * display.density.scale).dp
        val verticalSpacing = if (searching) minOf(pageSpacing, 6.dp) else pageSpacing
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = pageSpacing,
                top = if (searching) 4.dp else 8.dp,
                end = pageSpacing,
                bottom = if (state.batchMode) maxOf(310.dp, LocalFloatingNavigationInset.current + 260.dp)
                    else maxOf(104.dp, LocalFloatingNavigationInset.current + 16.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        ) {
        item(key = "search") {
            com.animeow.app.ui.components.BrowseSearchField(
                state.query, viewModel::updateQuery,
                if (state.subjectType == "book") "搜索书籍、小说或漫画" else "搜索动画资料",
                enabled = !state.batchBusy,
                searchAction = {
                    if (state.subjectType == "anime") IconButton(onClick = onImageSearchRequested) {
                        Icon(AppIcons.ImageSearch, "以图搜番")
                    }
                },
            )
        }
        stickyHeader(key = "source-and-type") {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        Text("搜索结果", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (!state.batchMode && displayedItems.isNotEmpty()) {
                            TextButton(onClick = { viewModel.beginBatch() }, enabled = state.importingKey == null) { Text("多选") }
                        }
                    } else {
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(com.animeow.app.ui.discovery.discoveryFeeds(state.subjectType)) { mode ->
                                FilterChip(state.feedMode == mode, { viewModel.selectFeedMode(mode) }, label = { Text(mode.displayName) }, shape = CircleShape)
                            }
                        }
                    }
                    com.animeow.app.ui.components.BrowseFilterButton(
                        discoveryFilterCount(state.filters, state.sourceFilter, state.feedMode),
                        { focusManager.clearFocus(); showFilters = true },
                    )
                }
            }
        }
        val scope = state.resultScope?.takeIf { state.batchCandidates == null }
        val chipFilters = if (!searching && state.feedMode == DiscoveryFeedMode.SEASONAL) state.filters.copy(year = null, seasonMonth = null) else state.filters
        val labels = discoveryFilterChips(chipFilters).map { it.label }
        if (!searching || scope != null || labels.isNotEmpty()) item(key = "applied-filters") {
            Column {
                if (searching) {
                    scope?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if (state.subjectType == "anime" && state.feedMode != DiscoveryFeedMode.SEASONAL) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { showBroadcast = true }, enabled = !state.batchBusy, label = { Text("今日放送") }, shape = CircleShape)
                        scope?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                } else {
                    if (state.feedMode == DiscoveryFeedMode.SEASONAL) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SeasonalOverviewControls(state.filters, viewModel::updateFilters)
                        AssistChip(onClick = { showBroadcast = true }, enabled = !state.batchBusy, label = { Text("今日放送") }, shape = CircleShape)
                    }
                    scope?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (labels.isNotEmpty()) TextButton(onClick = { showFilters = true }) {
                    Text(labels.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (state.isLoading) {
            item(key = "loading-progress") {
                DiscoveryLoadingStatus(state.loadingMessage, hasContent = displayedItems.isNotEmpty())
            }
            if (displayedItems.isEmpty()) item(key = "loading-placeholders") {
                com.animeow.app.ui.components.LibraryLoadingSkeleton(rows = 2)
            }
        }
        if (state.warnings.isNotEmpty() && state.error == null) item(key = "source-warning") {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("部分来源暂不可用", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showWarnings = !showWarnings }) { Text(if (showWarnings) "收起" else "详情") }
                        TextButton(onClick = { showNetworkSettings = true }) { Text("调整线路") }
                    }
                    androidx.compose.animation.AnimatedVisibility(showWarnings) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
        state.error?.let { message ->
            item(key = "load-error") {
                LoadErrorCard(
                    title = if (displayedItems.isEmpty()) "暂时没能获取作品" else "刷新暂未完成",
                    message = message,
                    onRetry = viewModel::retry,
                    secondaryActionLabel = "调整数据来源",
                    onSecondaryAction = { showNetworkSettings = true },
                )
            }
        }

        if (state.batchCandidates != null) {
            item(key = "related-candidates-banner") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "同系列关联候选（${displayedItems.size} 部）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.batchAnchorTitle?.takeIf(String::isNotBlank)?.let { anchor ->
                                Text(
                                    "基于《$anchor》整理，勾选后可一次归入同一系列",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                        TextButton(onClick = viewModel::exitRelatedCandidates, enabled = !state.batchBusy) {
                            Text("返回浏览结果")
                        }
                    }
                }
            }
        }
        if (!searching && (displayedItems.isNotEmpty() || state.isLoading || state.error == null)) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    state.feedMode == DiscoveryFeedMode.SEASONAL -> "新番资料"
                    state.feedMode == DiscoveryFeedMode.RANKING -> "排行榜"
                    state.subjectType == "book" -> "热门书籍"
                    state.filters.activeCount > 0 -> "筛选推荐"
                    else -> "发现好作品"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!state.batchMode) TextButton(onClick = { viewModel.beginBatch() }, enabled = state.importingKey == null) {
                Text("多选")
            }
            }
        }

        if (displayedItems.isEmpty() && !state.isLoading && state.error == null) {
            item {
                AnimatedContent(
                    targetState = state.query.isBlank(),
                    transitionSpec = { emptyEnter togetherWith emptyExit },
                    label = "discovery_empty",
                ) { featured ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        DiscoveryEmpty(
                            when {
                                state.feedMode == DiscoveryFeedMode.SEASONAL && !state.hasNextPage -> "暂未找到本季符合条件的作品，可切换季度或调整筛选后重试"
                                state.filters.activeCount > 0 && state.hasNextPage -> "本页没有符合筛选条件的作品，可以继续下一页或放宽条件"
                                state.filters.activeCount > 0 -> "没有找到符合当前筛选条件的作品，可以放宽条件后重试"
                                featured -> "暂时没有获取到推荐作品"
                                else -> "没有找到匹配作品，试试原文标题或切换来源"
                            },
                        )
                        if (state.filters.activeCount > 0) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showFilters = true }) { Text("调整筛选") }
                                if (state.feedMode != DiscoveryFeedMode.SEASONAL || state.filters.copy(year = null, seasonMonth = null).activeCount > 0) {
                                    TextButton(onClick = { viewModel.updateFilters(DiscoveryFilters(sort = state.filters.sort)) }) {
                                        Text(if (state.feedMode == DiscoveryFeedMode.SEASONAL) "清除额外筛选" else "清除筛选")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (display.layout == DiscoveryResultLayout.LIST) {
            items(displayedItems, key = RemoteAnime::uniqueKey) { anime ->
                val isSelected = "${anime.importSource}:${anime.importId}" in state.batchSelected
                RemoteResultCard(
                    anime = anime,
                    importing = state.importingKey == anime.uniqueKey,
                    alreadyAdded = "${anime.importSource}:${anime.importId}" in state.localIdsByIdentity,
                    display = display,
                    selectionMode = state.batchMode,
                    isSelected = isSelected,
                    onToggleSelect = { viewModel.toggleBatch(anime) },
                    onLongClick = { viewModel.beginBatch(anime) },
                    onClick = {
                        if (state.batchMode) viewModel.toggleBatch(anime)
                        else viewModel.select(anime)
                    },
                    onImport = { viewModel.import(anime) },
                )
            }
        } else {
            if (classicFeature != null) item(key = "classic-feature") {
                val isSelected = "${classicFeature.importSource}:${classicFeature.importId}" in state.batchSelected
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.animeow.app.ui.legacy.ClassicSectionHeading(state.feedMode.displayName, "${displayedItems.size} 部作品")
                    RemoteResultCard(
                        anime = classicFeature,
                        importing = state.importingKey == classicFeature.uniqueKey,
                        alreadyAdded = "${classicFeature.importSource}:${classicFeature.importId}" in state.localIdsByIdentity,
                        display = display,
                        selectionMode = state.batchMode,
                        isSelected = isSelected,
                        onToggleSelect = { viewModel.toggleBatch(classicFeature) },
                        onLongClick = { viewModel.beginBatch(classicFeature) },
                        onClick = {
                            if (state.batchMode) viewModel.toggleBatch(classicFeature)
                            else viewModel.select(classicFeature)
                        },
                        onImport = { viewModel.import(classicFeature) },
                    )
                }
            }
            items(
                items = (if (classicFeature != null) displayedItems.drop(1) else displayedItems).chunked(gridColumns),
                key = { row -> row.joinToString("|") { it.uniqueKey } },
            ) { rowItems ->
                DiscoveryGridRow(
                    items = rowItems,
                    display = display,
                    spacing = pageSpacing,
                    importingKey = state.importingKey,
                    isAdded = { "${it.importSource}:${it.importId}" in state.localIdsByIdentity },
                    selectionMode = state.batchMode,
                    isSelected = { "${it.importSource}:${it.importId}" in state.batchSelected },
                    onToggleSelect = { viewModel.toggleBatch(it) },
                    onLongClick = { viewModel.beginBatch(it) },
                    onClick = {
                        if (state.batchMode) viewModel.toggleBatch(it)
                        else viewModel.select(it)
                    },
                    onImport = { viewModel.import(it) },
                )
            }
        }
        if (state.batchCandidates == null && (state.hasNextPage || state.currentPage > 1 || (displayedItems.isNotEmpty() && state.totalPages != null))) {
            item(key = "discovery-pagination") {
                com.animeow.app.ui.components.PageNavigationBar(
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    hasNext = state.hasNextPage || (state.totalPages != null && state.currentPage < state.totalPages!!),
                    loading = state.isLoading,
                    status = if (state.totalCountExact) "共 ${state.totalCount} 部作品" else "本页 ${displayedItems.size} 部作品",
                    onPageSelected = { newPage ->
                        viewModel.changePage(newPage)
                        coroutineScope.launch { listState.scrollToItem(0) }
                    },
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        }
        if (state.batchMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .padding(bottom = LocalFloatingNavigationInset.current),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("已选择 ${state.batchSelected.size} 部作品", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("可跨页勾选，最多 60 部", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val allSelected = displayedItems.isNotEmpty() && displayedItems.all { "${it.importSource}:${it.importId}" in state.batchSelected }
                            TextButton(
                                onClick = {
                                    if (allSelected) viewModel.clearSelection()
                                    else viewModel.selectAllCurrent(displayedItems)
                                },
                                enabled = !state.batchBusy && !state.batchLoading
                            ) {
                                Text(if (allSelected) "取消全选" else "全选本页")
                            }
                            TextButton(onClick = viewModel::endBatch, enabled = !state.batchBusy) {
                                Text("完成")
                            }
                        }
                    }
                    if (state.batchLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = state.batchSeriesName,
                        onValueChange = viewModel::setBatchSeriesName,
                        label = { Text("归入系列名称（可选）") },
                        placeholder = { Text("例如：吹响吧！上低音号") },
                        enabled = !state.batchBusy && !state.batchLoading,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    val seriesSuggestions = remember(state.existingSeriesNames, state.batchSeriesName) {
                        val keyword = state.batchSeriesName.trim()
                        val matched = if (keyword.isBlank()) state.existingSeriesNames
                            else state.existingSeriesNames.filter { it.contains(keyword, ignoreCase = true) }
                        (matched.ifEmpty { state.existingSeriesNames }).take(10)
                    }
                    if (seriesSuggestions.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(seriesSuggestions, key = { it }) { seriesName ->
                                FilterChip(
                                    selected = state.batchSeriesName.trim() == seriesName,
                                    onClick = { viewModel.setBatchSeriesName(seriesName) },
                                    enabled = !state.batchBusy && !state.batchLoading,
                                    label = { Text(seriesName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("新添状态", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf("想看", "在看", "看完").forEach { statusOption ->
                            FilterChip(
                                selected = state.batchInitialStatus == statusOption,
                                onClick = { viewModel.setBatchInitialStatus(statusOption) },
                                enabled = !state.batchBusy && !state.batchLoading,
                                label = { Text(statusOption) },
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (state.batchBusy) {
                            TextButton(onClick = viewModel::cancelBatch) { Text("停止保存") }
                        }
                        FilledTonalButton(
                            onClick = viewModel::importBatch,
                            enabled = state.batchSelected.isNotEmpty() && !state.batchBusy && !state.batchLoading,
                        ) {
                            if (state.batchBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(AppIcons.Add, null)
                            Spacer(Modifier.size(6.dp))
                            Text(if (state.batchBusy) "正在批量保存…" else "批量添加 (${state.batchSelected.size})")
                        }
                    }
                    state.batchMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    state.selected?.let { anime ->
        RemoteDetailSheet(
            anime = anime,
            importing = state.importingKey == anime.uniqueKey,
                    alreadyAdded = "${anime.importSource}:${anime.importId}" in state.localIdsByIdentity,
            onImport = { viewModel.import(anime) },
            onRelated = { viewModel.prepareRelated(anime) },
            errorMessage = state.error,
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
            subjectType = state.subjectType,
            source = state.sourceFilter,
            seasonal = state.feedMode == DiscoveryFeedMode.SEASONAL,
            showServerSource = state.networkSettings.showServerSource,
            onDisplayRequested = { showFilters = false; showDisplaySettings = true },
            onNetworkRequested = { showFilters = false; showNetworkSettings = true },
            onGlobalSearchRequested = { showFilters = false; onGlobalSearchRequested() },
            onRefreshRequested = { showFilters = false; viewModel.retry() },
            onCommunityRequested = { showFilters = false; onCommunityRequested() },
            onApply = { filters, source ->
                viewModel.applyFilters(filters, source)
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
            onDismiss = { showDisplaySettings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryFilterSheet(
    filters: DiscoveryFilters,
    subjectType: String,
    source: DiscoverySourceFilter,
    seasonal: Boolean,
    showServerSource: Boolean,
    onDisplayRequested: () -> Unit,
    onNetworkRequested: () -> Unit,
    onGlobalSearchRequested: () -> Unit,
    onRefreshRequested: () -> Unit,
    onCommunityRequested: () -> Unit,
    onApply: (DiscoveryFilters, DiscoverySourceFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var sourceDraft by remember(source) { mutableStateOf(source) }
    var yearText by remember(filters) { mutableStateOf(filters.year?.toString().orEmpty()) }
    var season by remember(filters) { mutableStateOf(filters.seasonMonth) }
    var format by remember(filters) { mutableStateOf(filters.format) }
    var tag by remember(filters) { mutableStateOf(filters.tag.orEmpty()) }
    var minimumScore by remember(filters) { mutableStateOf(filters.minimumScore) }
    var sort by remember(filters) { mutableStateOf(filters.sort) }
    val years = listOf<Int?>(null) + ((java.time.LocalDate.now().year + 1) downTo 2015)
    val yearValid = isValidDiscoveryYear(yearText) && (!seasonal || yearText.trim().toIntOrNull() != null)
    val formats = listOf<String?>(null, "TV", "OVA", "剧场版", "Web", "SP")
    val tagPresets = if (subjectType == "anime") {
        listOf("原创", "漫画改", "小说改", "游戏改", "热血", "恋爱", "校园", "日常")
    } else {
        listOf("漫画", "小说", "轻小说", "恋爱", "校园", "日常", "科幻", "奇幻")
    }

    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("发现筛选器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("选择后点击应用。不同来源支持的条件与排序范围可能不同。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                if (seasonal) Text("资料来源：服务器整理的季度片单") else {
                    Text("资料来源", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiscoverySourceFilter.entries.filter { (it != DiscoverySourceFilter.SERVER || showServerSource) && (subjectType != "book" || it != DiscoverySourceFilter.ANILIST) }.forEach { option ->
                            FilterChip(sourceDraft == option, { sourceDraft = option }, label = { Text(option.displayName) })
                        }
                    }
                }
            }
            item {
                val selectedDraft = DiscoveryFilters(yearText.trim().toIntOrNull(), season, format, tag.trim().takeIf { it.isNotEmpty() }, minimumScore, sort)
                val removable = if (seasonal) selectedDraft.copy(year = null, seasonMonth = null) else selectedDraft
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    discoveryFilterChips(removable).forEach { chip ->
                        AssistChip(onClick = {
                            val next = chip.withoutFilter
                            if (!seasonal) { yearText = next.year?.toString().orEmpty(); season = next.seasonMonth }
                            format = next.format; tag = next.tag.orEmpty(); minimumScore = next.minimumScore
                        }, label = { Text(chip.label) }, trailingIcon = { Icon(AppIcons.Close, "移除${chip.label}") })
                    }
                }
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
                    items(years.filter { !seasonal || it != null }) { option ->
                        FilterChip(yearText == option?.toString().orEmpty(), { yearText = option?.toString().orEmpty() }, label = { Text(option?.toString() ?: "全部") })
                    }
                }
                OutlinedTextField(
                    value = yearText,
                    onValueChange = { yearText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (seasonal) "片单年份" else "年份（留空为全部）") },
                    supportingText = { Text(if (yearValid) "也可以输入更早年份，例如 1998" else "请输入 1～9999 之间的整数年份") },
                    isError = !yearValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true,
                )
            }
            if (subjectType == "anime") item {
                Text("季度", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "全部", 1 to "冬", 4 to "春", 7 to "夏", 10 to "秋").filter { !seasonal || it.first != null }.forEach { (month, label) ->
                        FilterChip(season == month, { season = month }, label = { Text(label) })
                    }
                }
            }
            if (subjectType == "anime") item {
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDisplayRequested) { Text("显示设置") }
                    TextButton(onClick = onRefreshRequested) { Text("刷新内容") }
                    TextButton(onClick = onNetworkRequested) { Text("数据源与访问设置") }
                    TextButton(onClick = onGlobalSearchRequested) { Text("全局搜索") }
                    TextButton(onClick = onCommunityRequested) { Text("社区交流") }
                }
            }
        }
        Surface(shadowElevation = 2.dp) {
            FlowRow(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = {
                        yearText = if (seasonal) filters.year.toString() else ""
                        season = if (seasonal) filters.seasonMonth else null
                        sourceDraft = if (seasonal) source else DiscoverySourceFilter.ALL
                        format = null
                        tag = ""
                        minimumScore = null
                        sort = DiscoverySort.TRENDING
                    }) { Text("重置") }
                    Button(enabled = yearValid, onClick = {
                        onApply(
                            DiscoveryFilters(
                                year = yearText.trim().toIntOrNull(),
                                seasonMonth = season.takeIf { subjectType == "anime" },
                                format = format.takeIf { subjectType == "anime" },
                                tag = tag.trim().takeIf(String::isNotEmpty),
                                minimumScore = minimumScore,
                                sort = sort,
                            ),
                            sourceDraft,
                        )
                    }) { Text("应用") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryNetworkSheet(
    settings: DiscoveryNetworkSettings,
    onSave: (DiscoveryNetworkSettings) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var publicRoute by rememberSaveable(settings) { mutableStateOf(settings.publicDataRoute) }
    var mode by remember(settings) { mutableStateOf(if (settings.bangumiApiMode == BangumiApiMode.OFFICIAL_FIRST) BangumiApiMode.AUTO else settings.bangumiApiMode) }
    var lookupOrder by remember(settings) { mutableStateOf(settings.metadataLookupOrder) }
    var enabledSources by remember(settings) { mutableStateOf(settings.enabledMetadataSources) }
    var lookupStrategy by remember(settings) { mutableStateOf(settings.metadataLookupStrategy) }
    var lookupTimeout by remember(settings) { mutableStateOf(settings.metadataTimeoutSeconds) }
    var aniProxy by remember(settings) { mutableStateOf(settings.aniListProxyUrl) }
    var allowAniProxy by remember(settings) { mutableStateOf(settings.allowAniListProxy) }
    var contribute by remember(settings) { mutableStateOf(settings.contributePublicMetadata) }
    var validationError by remember { mutableStateOf<String?>(null) }
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
                Text("保存后应用于搜索、导入和资料补全。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("资料访问方式", fontWeight = FontWeight.SemiBold)
                com.animeow.app.data.preferences.PublicDataRoute.entries.forEach { route ->
                    FilterChip(publicRoute == route, { publicRoute = route }, label = { Text(route.label) })
                }
                Text("默认通过 AniMeow 服务器加速获取作品、角色、榜单与封面；也可手动切换为手机直接连接外部站点。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("资料补全优先级", fontWeight = FontWeight.SemiBold)
                Text("默认优先使用服务器缓存并按所选来源补全；下方并发与代理仅在手机直连模式下生效。", style = MaterialTheme.typography.bodySmall)
                com.animeow.app.data.preferences.MetadataLookupStrategy.entries.forEach { strategy ->
                    FilterChip(lookupStrategy == strategy, { lookupStrategy = strategy }, label = { Text(strategy.label) }, enabled = publicRoute == com.animeow.app.data.preferences.PublicDataRoute.DIRECT)
                }
                lookupOrder.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${step.label}", Modifier.weight(1f))
                        Switch(step in enabledSources, onCheckedChange = { checked ->
                            enabledSources = if (checked) enabledSources + step else enabledSources - step
                        }, enabled = step !in enabledSources || enabledSources.size > 1,
                            modifier = Modifier.semantics { contentDescription = "启用${step.label}" })
                        TextButton(enabled = index > 0, onClick = {
                            lookupOrder = lookupOrder.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }, modifier = Modifier.semantics { contentDescription = "上移${step.label}" }) { Text("↑") }
                        TextButton(enabled = index < lookupOrder.lastIndex, onClick = {
                            lookupOrder = lookupOrder.toMutableList().apply { add(index + 1, removeAt(index)) }
                        }, modifier = Modifier.semantics { contentDescription = "下移${step.label}" }) { Text("↓") }
                    }
                }
                Text(SHARED_PROXY_QUOTA_NOTICE, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Text("单源等待上限：$lookupTimeout 秒", style = MaterialTheme.typography.labelLarge)
                Slider(lookupTimeout.toFloat(), { lookupTimeout = it.roundToInt() }, valueRange = 3f..12f, steps = 8)
                Text("Kitsu 主要提供日文/英文条目；TVmaze 主要提供动画剧集资料（CC BY-SA）。同名不同季不会自动合并。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("Bangumi 浏览与搜索线路", fontWeight = FontWeight.SemiBold)
                BangumiApiMode.entries.filter { it != BangumiApiMode.OFFICIAL_FIRST }.forEach { option ->
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
                DiscoveryDisplaySwitch("AniList 官方不可用时使用代理", allowAniProxy) { allowAniProxy = it }
                OutlinedTextField(aniProxy, { aniProxy = it }, Modifier.fillMaxWidth(),
                    label = { Text("AniList GraphQL 代理地址（可选）") },
                    supportingText = { Text("留空则使用默认备用线路。") },
                    enabled = allowAniProxy, singleLine = true)
            }
            item {
                DiscoveryDisplaySwitch("自动更新公共资料库", contribute) { contribute = it }
                Text("浏览或补全外部作品时，匿名补充公开资料与封面；个人评价、备注与观看记录不会上传。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("封面加载启用备用图片线路", fontWeight = FontWeight.SemiBold)
                        Text("封面较慢或加载失败时，同时尝试备用图片线路以加快显示", style = MaterialTheme.typography.bodySmall)
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
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { showResetConfirmation = true }) { Text("恢复默认") }
                    Button(onClick = {
                        val validation = runCatching {
                            if (apiProxy.isNotBlank()) com.animeow.app.data.remote.requireValidRemoteBase(apiProxy.trim())
                            if (aniProxy.isNotBlank()) com.animeow.app.data.remote.requireValidRemoteBase(aniProxy.trim())
                            if (imageProxyEnabled && imageProxy.isNotBlank()) com.animeow.app.data.remote.requireValidRemoteBase(imageProxy.trim())
                        }
                        if (validation.isFailure) {
                            validationError = validation.exceptionOrNull()?.message
                            return@Button
                        }
                        onSave(
                            settings.copy(
                                publicDataRoute = publicRoute,
                                bangumiApiMode = mode,
                                metadataLookupOrder = lookupOrder,
                                enabledMetadataSources = enabledSources,
                                metadataLookupStrategy = lookupStrategy,
                                metadataTimeoutSeconds = lookupTimeout,
                                aniListProxyUrl = aniProxy,
                                allowAniListProxy = allowAniProxy,
                                contributePublicMetadata = contribute,
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
    if (showResetConfirmation) ConfirmationDialog(
        title = "恢复默认数据源设置？",
        message = "补全顺序、代理地址与公共资料库选项将恢复默认值。已保存的自定义网络设置会被替换。",
        confirmLabel = "恢复默认",
        onConfirm = { showResetConfirmation = false; onReset() },
        onDismiss = { showResetConfirmation = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryDisplaySheet(
    config: DiscoveryDisplayConfig,
    onSave: (DiscoveryDisplayConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val packageName = LocalContext.current.packageName
    val entryConfig = remember { config }
    var draft by remember { mutableStateOf(config) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    fun updateDraft(transform: (DiscoveryDisplayConfig) -> DiscoveryDisplayConfig) {
        val updated = transform(draft)
        draft = updated
    }
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("搜索显示设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("选择预设后查看效果，确认应用时保存。评分、日期和标签可继续微调。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.layout == DiscoveryResultLayout.LIST && draft.density == ContentDensity.COMPACT,
                        onClick = { updateDraft { it.copy(layout = DiscoveryResultLayout.LIST, density = ContentDensity.COMPACT) } },
                        label = { Text("紧凑信息列表") },
                    )
                    FilterChip(
                        selected = draft.layout == DiscoveryResultLayout.GRID && draft.gridColumns == 3 && draft.density == ContentDensity.COMPACT,
                        onClick = { updateDraft { it.copy(layout = DiscoveryResultLayout.GRID, gridColumns = 3, density = ContentDensity.COMPACT) } },
                        label = { Text("紧凑封面网格") },
                    )
                }
            }
            item {
                val sample = RemoteAnime(com.animeow.app.data.remote.RemoteCatalogSource.BANGUMI, "preview", "山间来信",
                    coverUrl = "android.resource://$packageName/drawable/preview_cover", score = 8.8, episodes = 12, airDate = "2026-09-12", tags = listOf("日常", "治愈"))
                Text("实时预览", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (draft.layout == DiscoveryResultLayout.LIST) RemoteResultCard(sample, false, draft, onClick = {}, onImport = {})
                else Row(horizontalArrangement = Arrangement.spacedBy((8 * draft.density.scale).dp)) {
                    repeat(draft.gridColumns) { RemoteGridCard(sample, false, draft, Modifier.weight(1f), onClick = {}, onImport = {}) }
                }
            }
            item {
                val arrowRotation by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (showAdvanced) 180f else 0f,
                    animationSpec = motionAnimationSpec(220),
                    label = "discovery_advanced_arrow",
                )
                FilledTonalButton(
                    onClick = { showAdvanced = !showAdvanced },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(if (showAdvanced) "收起自定义选项" else "进一步自定义")
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        AppIcons.Expand,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = arrowRotation },
                    )
                }
            }
            if (showAdvanced) {
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
            item {
                Text("网格列数", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..5).forEach { columns ->
                        FilterChip(
                            selected = draft.layout == DiscoveryResultLayout.GRID && draft.gridColumns == columns,
                            onClick = { updateDraft { it.copy(layout = DiscoveryResultLayout.GRID, gridColumns = columns) } },
                            label = { Text("$columns 列") },
                        )
                    }
                }
                Text(
                    "选择列数会自动应用封面网格；默认三列，可按封面可读性和屏幕尺寸自由调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { DiscoveryDisplaySwitch("显示数据来源", draft.showSource) { value -> updateDraft { it.copy(showSource = value) } } }
            item { DiscoveryDisplaySwitch("显示远端评分", draft.showScore) { value -> updateDraft { it.copy(showScore = value) } } }
            item { DiscoveryDisplaySwitch("显示放送日期", draft.showAirDate) { value -> updateDraft { it.copy(showAirDate = value) } } }
            item { DiscoveryDisplaySwitch("显示标签摘要", draft.showTags) { value -> updateDraft { it.copy(showTags = value) } } }
            }
            item {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { draft = entryConfig }) { Text("恢复打开前的设置") }
                        TextButton(onClick = { showResetConfirmation = true }) { Text("恢复默认") }
                    }
                    Button(onClick = { onSave(draft); onDismiss() }) { Text("应用设置") }
                }
            }
        }
    }
    if (showResetConfirmation) ConfirmationDialog(
        title = "恢复默认发现页外观？",
        message = "发现页布局、密度与信息显示选项将恢复默认值。",
        confirmLabel = "恢复默认",
        onConfirm = { showResetConfirmation = false; draft = DiscoveryDisplayConfig() },
        onDismiss = { showResetConfirmation = false },
    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteResultCard(
    anime: RemoteAnime,
    importing: Boolean,
    display: DiscoveryDisplayConfig,
    onClick: () -> Unit,
    onImport: () -> Unit,
    alreadyAdded: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val scale = display.density.scale
    val metadata = remoteMeta(anime, display, includeSchedule = false)
    val haptics = LocalHapticFeedback.current
    val clickableModifier = if (selectionMode) {
        Modifier.clickable(role = Role.Checkbox, onClickLabel = if (isSelected) "取消勾选" else "勾选") {
            onToggleSelect?.invoke()
        }
    } else {
        Modifier.combinedClickable(
            role = Role.Button,
            onClickLabel = "查看《${anime.title}》详情",
            onClick = onClick,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick?.invoke()
            }
        )
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().then(clickableModifier),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selectionMode && isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        ),
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
                DiscoveryScheduleFacts(anime, display)
                if (metadata.isNotEmpty()) {
                    Text(metadata, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                if (display.showTags && anime.tags.isNotEmpty()) {
                    Text(anime.tags.take(3).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(AppIcons.Check, contentDescription = "已勾选", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    } else {
                        Box(Modifier.size(20.dp).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape))
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = onImport,
                    enabled = !importing,
                    modifier = Modifier.semantics {
                        contentDescription = if (importing) "正在加入《${anime.title}》" else if (alreadyAdded) "打开已收录的《${anime.title}》" else "将《${anime.title}》加入资料库"
                    },
                ) {
                    if (importing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(if (alreadyAdded) AppIcons.CheckCircle else AppIcons.Add, contentDescription = null)
                }
            }
        }
    }
}

@Composable
internal fun DiscoveryGridRow(
    items: List<RemoteAnime>,
    display: DiscoveryDisplayConfig,
    spacing: Dp,
    importingKey: String? = null,
    isAdded: (RemoteAnime) -> Boolean = { false },
    selectionMode: Boolean = false,
    isSelected: (RemoteAnime) -> Boolean = { false },
    onToggleSelect: ((RemoteAnime) -> Unit)? = null,
    onLongClick: ((RemoteAnime) -> Unit)? = null,
    onClick: (RemoteAnime) -> Unit,
    onImport: (RemoteAnime) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reserve only the information present in this row, keeping every action on the same baseline.
    val hasMetadata = items.any { remoteMeta(it, display, includeSchedule = false).isNotEmpty() }
    val hasTags = display.showTags && items.any { it.tags.isNotEmpty() }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEach { anime ->
            RemoteGridCard(
                anime = anime,
                importing = importingKey == anime.uniqueKey,
                display = display,
                modifier = Modifier.weight(1f),
                alreadyAdded = isAdded(anime),
                selectionMode = selectionMode,
                isSelected = isSelected(anime),
                onToggleSelect = { onToggleSelect?.invoke(anime) },
                onLongClick = { onLongClick?.invoke(anime) },
                onClick = { onClick(anime) },
                onImport = { onImport(anime) },
                reserveMetadata = hasMetadata,
                reserveTags = hasTags,
            )
        }
        repeat((display.gridColumns.coerceIn(2, 5) - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RemoteGridCard(
    anime: RemoteAnime,
    importing: Boolean,
    display: DiscoveryDisplayConfig,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onImport: () -> Unit,
    alreadyAdded: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    reserveMetadata: Boolean = remoteMeta(anime, display, includeSchedule = false).isNotEmpty(),
    reserveTags: Boolean = display.showTags && anime.tags.isNotEmpty(),
) {
    val scale = display.density.scale
    val narrowCard = display.gridColumns >= 4
    val haptics = LocalHapticFeedback.current
    val clickableModifier = if (selectionMode) {
        Modifier.clickable(role = Role.Checkbox, onClickLabel = if (isSelected) "取消勾选" else "勾选") {
            onToggleSelect?.invoke()
        }
    } else {
        Modifier.combinedClickable(
            role = Role.Button,
            onClickLabel = "查看《${anime.title}》详情",
            onClick = onClick,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick?.invoke()
            }
        )
    }
    if (narrowCard) {
        ElevatedCard(
            modifier = modifier.then(clickableModifier),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (selectionMode && isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface
            ),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                RemoteCover(anime, Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.52f), Color.Black.copy(alpha = 0.82f)),
                            ),
                        )
                        .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 5.dp),
                ) {
                    Text(
                        text = anime.title,
                        color = Color.White,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (selectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.48f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(AppIcons.Check, contentDescription = "已勾选", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        } else {
                            Box(Modifier.size(16.dp).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape))
                        }
                    }
                } else {
                    IconButton(
                        onClick = onImport,
                        enabled = !importing,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(40.dp)
                            .semantics {
                                contentDescription = if (importing) "正在加入《${anime.title}》" else if (alreadyAdded) "打开已收录的《${anime.title}》" else "将《${anime.title}》加入资料库"
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(25.dp)
                                .clip(CircleShape)
                                .background(
                                    if (alreadyAdded) MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                                    else Color.Black.copy(alpha = 0.52f),
                                )
                                .border(
                                    0.8.dp,
                                    Color.White.copy(alpha = if (alreadyAdded) 0.35f else 0.22f),
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (importing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 1.8.dp,
                                    color = Color.White,
                                )
                            } else {
                                Icon(
                                    if (alreadyAdded) AppIcons.Check else AppIcons.Add,
                                    contentDescription = null,
                                    tint = if (alreadyAdded) MaterialTheme.colorScheme.onPrimary else Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                DiscoveryScheduleFacts(anime, display, stacked = true)
            }
        }
        return
    }
    val metadata = remoteMeta(anime, display, includeSchedule = false)
    ElevatedCard(
        modifier = modifier.then(clickableModifier),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selectionMode && isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            RemoteCover(anime, Modifier.fillMaxSize())
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(AppIcons.Check, contentDescription = "已勾选", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    } else {
                        Box(Modifier.size(18.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape))
                    }
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding((8 * scale).dp),
            verticalArrangement = Arrangement.spacedBy((5 * scale).dp),
        ) {
            Text(anime.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                style = if (display.gridColumns == 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
            DiscoveryScheduleFacts(anime, display, stacked = true)
            if (reserveMetadata) {
                Text(
                    metadata,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (reserveTags) {
                Text(
                    anime.tags.take(2).joinToString(" · "),
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectionMode) {
                FilledTonalButton(
                    onClick = { onToggleSelect?.invoke() },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                ) {
                    Text(if (isSelected) "已勾选" else "勾选")
                }
            } else {
                DiscoveryImportButton(anime.title, importing, alreadyAdded, onImport)
            }
        }
    }
}

@Composable
private fun DiscoveryImportButton(title: String, importing: Boolean, alreadyAdded: Boolean, onImport: () -> Unit) {
    val label = if (importing) "加入中" else if (alreadyAdded) "已收录" else "加入"
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    OutlinedButton(
        onClick = onImport,
        enabled = !importing,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
            contentDescription = if (importing) "正在加入《$title》" else if (alreadyAdded) "打开已收录的《$title》" else "将《$title》加入资料库"
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val textWidth = with(density) { measurer.measure(label, style, softWrap = false, maxLines = 1).size.width.toDp() }
            val showIcon = maxWidth >= textWidth + 22.dp
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showIcon) {
                    if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(if (alreadyAdded) AppIcons.CheckCircle else AppIcons.Add, null, Modifier.size(18.dp))
                }
                Text(label, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
    onRelated: () -> Unit,
    errorMessage: String? = null,
    alreadyAdded: Boolean = false,
    onDismiss: () -> Unit,
) {
    val subjectId = if (anime.importSource == "bangumi") anime.importId.toLongOrNull()
        else anime.metadataReferences.firstOrNull { it.source == "bangumi" }?.id?.toLongOrNull()
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
                        DisplayPill(anime.sourceLabel)
                        if (anime.titleLanguage != null && !anime.titleLanguage.isChineseLanguage()) {
                            Text("${com.animeow.app.data.remote.remoteLanguageLabel(anime.titleLanguage)}标题 · 暂无可靠中文译名", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(remoteMeta(anime), color = MaterialTheme.colorScheme.primary)
                        Text(anime.ratingCount?.let { "$it 人评分" } ?: "评分人数暂无", style = MaterialTheme.typography.bodySmall)
                        anime.sourceRank?.let { Text("Bangumi 综合排名 #$it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            anime.summary?.let { summary ->
                item {
                    Text("简介 · ${com.animeow.app.data.remote.remoteLanguageLabel(anime.summaryLanguage)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                com.animeow.app.ui.anime.BangumiExtrasEntry(subjectId, anime.title)
            }
            if (anime.tags.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(anime.tags) { tag -> DisplayPill(tag) }
                    }
                }
            }
            item {
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                FilledTonalButton(onClick = onImport, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                    if (importing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(if (alreadyAdded) AppIcons.CheckCircle else AppIcons.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (alreadyAdded) "打开资料库中的作品" else "加入资料库并导入标签")
                }
                if (subjectId != null) TextButton(onClick = onRelated, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                    Text("查看同系列关联并多选添加")
                }
            }
        }
    }
}

@Composable
internal fun RemoteCover(anime: RemoteAnime, modifier: Modifier) {
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp),
            ) {
                Icon(
                    AppIcons.ImageSearch,
                    contentDescription = "暂无封面",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    anime.title.take(2),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryEmpty(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            AppIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

internal fun discoveryScheduleFacts(anime: RemoteAnime, showAirDate: Boolean): List<String> = buildList {
    if (anime.subjectType != "book") {
        add(anime.episodes?.takeIf { it > 0 }?.let { "$it 集" } ?: "集数暂无")
    }
    if (showAirDate) {
        add(anime.airDate?.trim()?.takeIf(String::isNotEmpty)?.take(10) ?: "日期暂无")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryScheduleFacts(anime: RemoteAnime, display: DiscoveryDisplayConfig, stacked: Boolean = false) {
    val facts = discoveryScheduleFacts(anime, display.showAirDate)
    if (facts.isEmpty()) return
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.primary
    if (stacked) {
        val date = facts.last().takeIf { display.showAirDate && it.length == 10 && it[4] == '-' && it[7] == '-' }
        Column {
            facts.forEach { fact ->
                if (fact != date) {
                    Text(fact, style = style, color = color)
                } else {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    BoxWithConstraints {
                        val dateWidth = measurer.measure(fact, style, softWrap = false).size.width
                        val label = if (dateWidth <= with(density) { maxWidth.roundToPx() }) fact
                            else fact.replaceRange(4, 5, "\n")
                        Text(label, style = style, color = color)
                    }
                }
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            facts.forEach { Text(it, style = style, color = color) }
        }
    }
}

private fun remoteMeta(
    anime: RemoteAnime,
    display: DiscoveryDisplayConfig? = null,
    includeSchedule: Boolean = true,
): String = buildList {
    if (display?.showSource != false) add(anime.source.displayName)
    if (display?.showScore != false) anime.score?.let {
        add(if (anime.importSource == "bangumi" || it <= 10.0) "%.1f".format(it) else "${it.toInt()}%")
        anime.ratingCount?.let { count -> add("$count 人评分") }
    }
    if (includeSchedule) {
        if (display?.showAirDate != false) anime.airDate?.take(10)?.let(::add)
        anime.episodes?.takeIf { anime.subjectType != "book" }?.let { add("$it 集") }
    }
    anime.format?.let(::add)
}.joinToString(" · ")
