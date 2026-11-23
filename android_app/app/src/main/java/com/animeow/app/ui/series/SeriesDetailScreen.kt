package com.animeow.app.ui.series

import com.animeow.app.ui.icons.AppIcons

import com.animeow.app.ui.screens.PosterBadges

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import com.animeow.app.ui.components.StableModalBottomSheet as ModalBottomSheet
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.animeRatingSortValue
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.data.preferences.SeriesSort
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.ZoomableImageViewer
import com.animeow.app.ui.components.BatchTagSheet
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.animeow.app.ui.components.WatchStatusBadge
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionBarEnter
import com.animeow.app.ui.components.motionBarExit
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.tracker.TrackerViewModel
import java.time.LocalDate

private enum class TagAction { ADD, REMOVE }

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun SeriesDetailScreen(
    onBack: () -> Unit,
    onAnimeClick: (Long) -> Unit,
    viewModel: SeriesDetailViewModel = viewModel(),
    appearanceViewModel: AppearanceViewModel = viewModel(),
    trackerViewModel: TrackerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val batchState by viewModel.batchMetadataState.collectAsStateWithLifecycle()
    val appearance by appearanceViewModel.settings.collectAsStateWithLifecycle()
    val pageScale = com.animeow.app.ui.theme.LocalContentDensity.current.scale
    val trackerSettings by trackerViewModel.trackerSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val feedback = com.animeow.app.ui.components.LocalActionFeedback.current
    val sort = state.displaySettings.sort
    val ascending = state.displaySettings.ascending
    val columnsOverride = state.displaySettings.columnsOverride
    val statusFilter = state.displaySettings.statusFilter
    val sourceState by viewModel.sourceWorks.collectAsStateWithLifecycle()
    var showSourceSheet by rememberSaveable { mutableStateOf(false) }
    var showRoleSheet by rememberSaveable { mutableStateOf(false) }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var showCoverSheet by rememberSaveable { mutableStateOf(false) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showDisplaySheet by rememberSaveable { mutableStateOf(false) }
    var showStatusSheet by rememberSaveable { mutableStateOf(false) }
    var tagAction by remember { mutableStateOf<TagAction?>(null) }
    var showRemoveConfirmation by rememberSaveable { mutableStateOf(false) }
    var showTrashConfirmation by rememberSaveable { mutableStateOf(false) }
    var showMetadataOptions by rememberSaveable { mutableStateOf(false) }
    var showCoverViewer by rememberSaveable { mutableStateOf(false) }
    var activeCrossMediaKey by rememberSaveable { mutableStateOf<String?>(null) }
    val tagScope = rememberCoroutineScope()
    var tagBusy by remember { mutableStateOf(false) }
    var tagError by remember { mutableStateOf<String?>(null) }

    val selected = selectedIds.isNotEmpty()
    val densityColumnAdjustment = when (appearance.pageDensities["series"]) {
        com.animeow.app.ui.theme.ContentDensity.COMPACT -> 1
        com.animeow.app.ui.theme.ContentDensity.SPACIOUS -> -1
        else -> 0
    }
    val effectiveColumns = columnsOverride ?: (appearance.gridColumns + densityColumnAdjustment).coerceIn(2, 5)
    val sorted = remember(state.animes, sort, ascending, statusFilter, query, typeFilter) {
        if (typeFilter in setOf("game", "audio")) emptyList()
        else sortSeriesAnimes(filterSeriesAnimes(state.animes, statusFilter, query, typeFilter), sort, ascending)
    }
    val filteredCrossMedia = remember(state.linkedCrossMedia, statusFilter, query, typeFilter) {
        state.linkedCrossMedia.filter { item ->
            val matchesType = when (typeFilter) {
                null -> true
                "game" -> item.kind == "GAME"
                "audio" -> item.kind == "AUDIO"
                else -> false
            }
            val matchesStatus = statusFilter == null || item.status == statusFilter
            val matchesQuery = query.isBlank() || item.title.contains(query.trim(), ignoreCase = true)
            matchesType && matchesStatus && matchesQuery
        }
    }
    val animationItems = sorted.filter { normalizeSubjectType(it.subjectType) == "anime" }
    val readingItems = sorted.filter { normalizeSubjectType(it.subjectType) == "book" }
    val gameItems = filteredCrossMedia.filter { it.kind == "GAME" }
    val audioItems = filteredCrossMedia.filter { it.kind == "AUDIO" }
    val totalSeriesMembers = state.animes.size + state.linkedCrossMedia.size
    val visibleTotalCount = sorted.size + filteredCrossMedia.size
    val coverUrl = state.series?.customCoverUrl
        ?: state.animes.sortedByDescending(AnimeEntity::id).firstNotNullOfOrNull(AnimeEntity::coverUrl)
        ?: state.linkedCrossMedia.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl

    BackHandler(enabled = selected) { viewModel.clearSelection() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(message) }
    }


    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected) "已选 ${selectedIds.size} 项" else "系列",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected) viewModel.clearSelection() else onBack() }) {
                        Icon(
                            if (selected) AppIcons.Close else AppIcons.Back,
                            contentDescription = if (selected) "退出多选" else "返回",
                        )
                    }
                },
                actions = {
                    if (selected) {
                        IconButton(onClick = { viewModel.selectAll(sorted.mapTo(linkedSetOf(), AnimeEntity::id)) }) {
                            Icon(AppIcons.SelectAll, contentDescription = "全选或取消全选")
                        }
                    } else {
                        IconButton(onClick = { showAddSheet = true }, enabled = state.series != null) {
                            Icon(AppIcons.AddCircle, contentDescription = "添加已有作品")
                        }
                        IconButton(onClick = { showSearch = !showSearch; query = ""; viewModel.clearSelection() }) {
                            Icon(if (showSearch) AppIcons.Close else AppIcons.Search, contentDescription = if (showSearch) "关闭系列搜索" else "搜索系列内作品")
                        }
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selected,
                enter = motionBarEnter(),
                exit = motionBarExit(),
            ) {
                SeriesSelectionBar(
                    onStatus = { showStatusSheet = true },
                    onRole = { showRoleSheet = true },
                    onAddTag = { tagError = null; tagAction = TagAction.ADD },
                    onRemoveTag = { tagError = null; tagAction = TagAction.REMOVE },
                    onAutoMatch = { showMetadataOptions = true },
                    onRemoveSeries = { showRemoveConfirmation = true },
                    onTrash = { showTrashConfirmation = true },
                )
            }
        },
    ) { padding ->
        if (state.series == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (!state.isLoaded) {
                    CircularProgressIndicator()
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("这个系列不存在或已被删除", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "作品资料仍会保留，可返回系列书架继续整理。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = onBack) { Text("返回系列书架") }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(effectiveColumns),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = (12 * pageScale).dp, end = (12 * pageScale).dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy((10 * pageScale).dp),
                verticalArrangement = Arrangement.spacedBy((10 * pageScale).dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "series_header") {
                    SeriesHero(
                        seriesName = state.series!!.name,
                        description = state.series!!.description,
                        coverUrl = coverUrl,
                        itemCount = totalSeriesMembers,
                        watchingCount = state.animes.count { it.status == "在看" } + state.linkedCrossMedia.count { it.status in setOf("在玩", "在听") },
                        watched = state.animes.filter { it.subjectType != "book" }.sumOf(AnimeEntity::watchedEpisodes),
                        total = state.animes.filter { it.subjectType != "book" }.sumOf(AnimeEntity::totalEpisodes),
                        onCoverClick = { if (!coverUrl.isNullOrBlank()) showCoverViewer = true },
                        onEdit = { showEdit = true },
                        onChooseCover = { showCoverSheet = true },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "series_add_actions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { showAddSheet = true }, label = { Text("从我的收藏添加") })
                        AssistChip(onClick = { showSourceSheet = true }, label = { Text("搜索／查找关联作品") })
                        if (selected) AssistChip(onClick = { showRoleSheet = true }, label = { Text("标记所选作品关系") })
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "series_type_filter") {
                    val gameTotal = state.linkedCrossMedia.count { it.kind == "GAME" }
                    val audioTotal = state.linkedCrossMedia.count { it.kind == "AUDIO" }
                    val typeOptions = buildList {
                        add("all")
                        add("anime")
                        add("book")
                        if (gameTotal > 0 || typeFilter == "game") add("game")
                        if (audioTotal > 0 || typeFilter == "audio") add("audio")
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(typeOptions) { type ->
                            val value = type.takeUnless { it == "all" }
                            FilterChip(typeFilter == value, { typeFilter = value; viewModel.clearSelection() },
                                label = {
                                    Text(
                                        when (type) {
                                            "anime" -> "动画 ${state.animes.count { it.subjectType != "book" }}"
                                            "book" -> "书籍 ${state.animes.count { it.subjectType == "book" }}"
                                            "game" -> "游戏 $gameTotal"
                                            "audio" -> "音声 $audioTotal"
                                            else -> "全部类型 $totalSeriesMembers"
                                        },
                                    )
                                })
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "series_search") {
                    AnimatedVisibility(
                        visible = showSearch,
                        enter = motionExpandIn(),
                        exit = motionShrinkOut(),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; viewModel.clearSelection() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索这个系列的作品") },
                            leadingIcon = { Icon(AppIcons.Search, null) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large,
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "series_filters") {
                    val counts = state.animes.groupingBy(AnimeEntity::status).eachCount()
                    val statusOptions = (listOf("在看") + state.statuses.map { it.name } + counts.keys + listOfNotNull(statusFilter)).distinct()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(statusFilter == null, { viewModel.setStatusFilter(null) }, label = { Text("全部 ${state.animes.size}") })
                        }
                        items(statusOptions, key = { it }) { status ->
                            FilterChip(statusFilter == status, { viewModel.setStatusFilter(status) }, label = { Text("$status ${counts[status] ?: 0}") })
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "series_summary") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$visibleTotalCount 部作品",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showDisplaySheet = true }) {
                            Icon(AppIcons.Sort, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${sort.displayName} ${if (ascending) "↑" else "↓"}", maxLines = 1)
                        }
                    }
                }
                if (totalSeriesMembers > 0 && visibleTotalCount == 0) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "filter_empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (query.isBlank()) "当前类型与状态下没有作品" else "没有找到匹配的作品", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { query = ""; typeFilter = null; viewModel.setStatusFilter(null) }) { Text("查看全部作品") }
                        }
                    }
                }
                if (totalSeriesMembers == 0) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { showAddSheet = true }) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("这个系列还没有作品", style = MaterialTheme.typography.titleMedium)
                                Text("点击添加已有动画、书籍、游戏或音声", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                val hasMultipleVisibleCategories = listOf(animationItems, readingItems, gameItems, audioItems).count { it.isNotEmpty() } > 1
                if (animationItems.isNotEmpty()) {
                    if (hasMultipleVisibleCategories) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "animation_header") {
                        SeriesSectionHeader("动画", animationItems.size, MaterialTheme.colorScheme.primary)
                    }
                    }
                    gridItems(animationItems, key = AnimeEntity::id) { anime ->
                        SeriesAnimeCard(
                            modifier = motionAnimateItem(),
                            anime = anime,
                            appearance = appearance,
                            trackerSettings = trackerSettings,
                            statusColor = state.statuses.firstOrNull { it.name == anime.status }?.color?.let(::Color) ?: MaterialTheme.colorScheme.primary,
                            selected = anime.id in selectedIds,
                            onClick = {
                                if (selected) viewModel.toggleSelection(anime.id) else onAnimeClick(anime.id)
                            },
                            onLongClick = {
                                feedback.longPress()
                                viewModel.toggleSelection(anime.id)
                            },
                        )
                    }
                }
                if (readingItems.isNotEmpty()) {
                    if (hasMultipleVisibleCategories) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "reading_header") {
                        SeriesSectionHeader("书籍", readingItems.size, MaterialTheme.colorScheme.tertiary)
                    }
                    }
                    gridItems(readingItems, key = AnimeEntity::id) { anime ->
                        SeriesAnimeCard(
                            modifier = motionAnimateItem(),
                            anime = anime,
                            appearance = appearance,
                            trackerSettings = trackerSettings,
                            statusColor = state.statuses.firstOrNull { it.name == anime.status }?.color?.let(::Color) ?: MaterialTheme.colorScheme.primary,
                            selected = anime.id in selectedIds,
                            onClick = {
                                if (selected) viewModel.toggleSelection(anime.id) else onAnimeClick(anime.id)
                            },
                            onLongClick = {
                                feedback.longPress()
                                viewModel.toggleSelection(anime.id)
                            },
                        )
                    }
                }
                if (gameItems.isNotEmpty()) {
                    if (hasMultipleVisibleCategories) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "game_header") {
                            SeriesSectionHeader("游戏", gameItems.size, MaterialTheme.colorScheme.secondary)
                        }
                    }
                    gridItems(gameItems, key = { it.key }) { item ->
                        Box(motionAnimateItem()) {
                            SeriesCrossMediaCard(
                                entry = item,
                                aspectRatio = appearance.coverAspectRatio.ratio,
                                onClick = { activeCrossMediaKey = item.key },
                            )
                        }
                    }
                }
                if (audioItems.isNotEmpty()) {
                    if (hasMultipleVisibleCategories) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "audio_header") {
                            SeriesSectionHeader("音声", audioItems.size, MaterialTheme.colorScheme.secondary)
                        }
                    }
                    gridItems(audioItems, key = { it.key }) { item ->
                        Box(motionAnimateItem()) {
                            SeriesCrossMediaCard(
                                entry = item,
                                aspectRatio = appearance.coverAspectRatio.ratio,
                                onClick = { activeCrossMediaKey = item.key },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEdit) {
        SeriesEditDialog(
            initialName = state.series?.name.orEmpty(),
            initialDescription = state.series?.description.orEmpty(),
            onSave = { name, description ->
                viewModel.saveSeriesInfo(name, description)
                showEdit = false
            },
            onDismiss = { showEdit = false },
        )
    }
    if (showCoverSheet) {
        SeriesCoverSheet(
            animes = state.animes,
            selectedUrl = state.series?.customCoverUrl,
            onSelected = {
                viewModel.setSeriesCover(it)
                showCoverSheet = false
            },
            onDismiss = { showCoverSheet = false },
        )
    }
    if (showAddSheet) {
        AddSeriesWorksSheet(
            candidates = state.availableAnimes,
            seriesNames = state.seriesNames,
            onConfirm = viewModel::addAnimes,
            onDismiss = { showAddSheet = false },
            availableGames = state.availableGames,
            availableAudioWorks = state.availableAudioWorks,
            onConfirmCrossMedia = viewModel::addCrossMedia,
        )
    }
    state.linkedCrossMedia.firstOrNull { it.key == activeCrossMediaKey }?.let { activeEntry ->
        AlertDialog(
            onDismissRequest = { activeCrossMediaKey = null },
            title = { Text("${activeEntry.kindLabel} · ${activeEntry.title}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前状态：${activeEntry.status}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("标记在当前系列中的关系：", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf<String?>(null) + com.animeow.app.data.local.SERIES_ROLES).forEach { role ->
                            FilterChip(
                                selected = activeEntry.role == role,
                                onClick = {
                                    viewModel.setCrossMediaRole(activeEntry.kind, activeEntry.workSyncId, role)
                                    activeCrossMediaKey = null
                                },
                                label = { Text(role ?: "未标注") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeCrossMediaKey = null }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.removeCrossMedia(activeEntry.kind, activeEntry.workSyncId)
                    activeCrossMediaKey = null
                }) { Text("移出当前系列", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    if (showSourceSheet) {
        SeriesSourceSheet(sourceState, state.animes, state.animes + state.availableAnimes,
            onSearch = viewModel::searchSeriesWorks, onRelated = viewModel::findSeriesRelations,
            onToggle = viewModel::toggleSourceWork, onSave = viewModel::importSourceWorks,
            onOpen = { viewModel.closeSourceWorks(); showSourceSheet = false; onAnimeClick(it) },
            onDismiss = { viewModel.closeSourceWorks(); showSourceSheet = false })
    }
    if (showRoleSheet) AlertDialog(onDismissRequest = { showRoleSheet = false },
        title = { Text("标记所选作品在系列中的关系") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("这是可选标记，不会改变成员顺序或个人记录；来源关联不会自动替你填写。")
            (listOf<String?>(null) + com.animeow.app.data.local.SERIES_ROLES).forEach { role ->
                TextButton(onClick = { viewModel.setSelectedSeriesRole(role); showRoleSheet = false }) { Text(role ?: "未标注") }
            }
        } }, confirmButton = { TextButton(onClick = { showRoleSheet = false }) { Text("取消") } })
    if (showDisplaySheet) {
        SeriesDisplaySheet(
            sort = sort,
            ascending = ascending,
            columnsOverride = columnsOverride,
            homeColumns = appearance.gridColumns.coerceIn(2, 5),
            onSortChange = viewModel::setSort,
            onDirectionChange = viewModel::setAscending,
            onColumnsChange = viewModel::setColumns,
            onDismiss = { showDisplaySheet = false },
        )
    }
    if (showStatusSheet) {
        StatusSelectionSheet(
            statuses = state.statuses,
            onSelected = {
                viewModel.setSelectedStatus(it.name)
                showStatusSheet = false
            },
            onDismiss = { showStatusSheet = false },
        )
    }
    tagAction?.let { action ->
        BatchTagSheet(
            tags = state.tags,
            selectedCount = selectedIds.size,
            busy = tagBusy,
            errorMessage = tagError,
            initialRemove = action == TagAction.REMOVE,
            onApply = { tagIds, remove, drafts ->
                if (!tagBusy) {
                    tagBusy = true
                    tagError = null
                    tagScope.launch {
                        try {
                            viewModel.updateTagsForSelected(tagIds, remove, drafts)
                            tagAction = null
                            snackbar.showSnackbar(if (remove) "已从所选作品移除标签" else "已为所选作品添加标签")
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: IllegalArgumentException) {
                            tagError = error.message ?: "批量修改标签失败"
                        } catch (error: IllegalStateException) {
                            tagError = error.message ?: "批量修改标签失败"
                        } catch (_: Exception) {
                            tagError = "批量修改标签失败，请重试"
                        } finally {
                            tagBusy = false
                        }
                    }
                }
            },
            onDismissRequest = { tagAction = null },
        )
    }
    if (showRemoveConfirmation) {
        ConfirmationDialog(
            title = "移出系列？",
            message = "${selectedIds.size} 部作品会变为独立作品，资料和进度不会改变。",
            action = "移出系列",
            onConfirm = {
                viewModel.removeSelectedFromSeries()
                showRemoveConfirmation = false
            },
            onDismiss = { showRemoveConfirmation = false },
        )
    }
    if (showTrashConfirmation) {
        ConfirmationDialog(
            title = "移入回收站？",
            message = "${selectedIds.size} 部作品会从资料库隐藏，关联数据会保留并可恢复。",
            action = "移入回收站",
            onConfirm = {
                viewModel.moveSelectedToTrash()
                showTrashConfirmation = false
            },
            onDismiss = { showTrashConfirmation = false },
        )
    }
    if (showMetadataOptions) {
        com.animeow.app.ui.screens.BatchMetadataOptionsDialog(
            selectedCount = selectedIds.size,
            onStart = {
                showMetadataOptions = false
                viewModel.startBatchMetadataMatch(it)
            },
            onDismissRequest = { showMetadataOptions = false },
        )
    }
    if (batchState.isRunning || batchState.message != null) {
        com.animeow.app.ui.screens.BatchMetadataProgressDialog(
            state = batchState,
            onSkip = viewModel::skipCurrentMetadataMatch,
            onStop = viewModel::cancelBatchMetadataMatch,
            onRetry = viewModel::retryBatchMetadataMatch,
            onClose = viewModel::clearBatchMetadataMessage,
        )
    }
    if (showCoverViewer && !coverUrl.isNullOrBlank()) {
        ZoomableImageViewer(
            imageUrl = coverUrl,
            title = state.series?.name.orEmpty(),
            onDismiss = { showCoverViewer = false },
            onModify = { showCoverSheet = true },
        )
    }
}

@Composable
private fun SeriesHero(
    seriesName: String,
    description: String?,
    coverUrl: String?,
    itemCount: Int,
    watchingCount: Int,
    watched: Int,
    total: Int,
    onCoverClick: () -> Unit,
    onEdit: () -> Unit,
    onChooseCover: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(width = 76.dp, height = 108.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary).clickable(
                        enabled = !coverUrl.isNullOrBlank(),
                        role = Role.Button,
                        onClickLabel = "查看系列封面大图",
                        onClick = onCoverClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(coverUrl, seriesName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(seriesName.take(2), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(seriesName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                description?.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "$itemCount 部作品 · $watchingCount 部在看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (watched.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        AssistChip(onClick = onEdit, label = { Text("编辑资料") }, leadingIcon = { Icon(AppIcons.Edit, null) })
                    }
                    item {
                        AssistChip(onClick = onChooseCover, label = { Text("系列封面") }, leadingIcon = { Icon(AppIcons.PhotoLibrary, null) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesSectionHeader(title: String, count: Int, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("$title ($count)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SeriesAnimeCard(
    anime: AnimeEntity,
    appearance: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
    statusColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultRatio = appearance.coverAspectRatio.ratio
    val isFramedLandscapeInPortraitGrid = anime.coverOrientation == "landscape" && defaultRatio < 1f
    val cardRatio = if (isFramedLandscapeInPortraitGrid) defaultRatio
        else com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, defaultRatio)
    val showDetails = seriesCardHasDetails(anime, appearance, trackerSettings)

    ElevatedCard(
        modifier = modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "查看《${anime.title}》",
                onLongClickLabel = if (selected) "取消选择《${anime.title}》" else "选择《${anime.title}》",
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(cardRatio)
                    .background(if (isFramedLandscapeInPortraitGrid) Color(0xFF141A24) else MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (isFramedLandscapeInPortraitGrid && !anime.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = anime.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.28f,
                    )
                    com.animeow.app.ui.screens.TrackerCoverImage(
                        anime.coverUrl,
                        anime.title,
                        Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f),
                        trackerSettings,
                    )
                } else {
                    com.animeow.app.ui.screens.TrackerCoverImage(anime.coverUrl.orEmpty(), anime.title, Modifier.fillMaxSize(), trackerSettings)
                }
                PosterBadges(anime, appearance, trackerSettings, statusColor, selected = selected, includeBottomBar = false)
                val titleOnCover = appearance.showTitle && appearance.coverTitlePosition == CoverTitlePosition.OVERLAY
                val showBottomBadgeBar = appearance.coverBadgeStyle == com.animeow.app.ui.theme.CoverBadgeStyle.BOTTOM_BAR &&
                    com.animeow.app.ui.screens.hasVisiblePosterBadges(anime, appearance, trackerSettings)
                if (titleOnCover || showBottomBadgeBar) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        if (titleOnCover) {
                            Text(
                                anime.title,
                                modifier = Modifier.fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.3f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity * 0.4f),
                                            1f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity),
                                        ),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                                maxLines = if (cardRatio > 1.2f && showBottomBadgeBar) 1 else 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (showBottomBadgeBar) {
                            com.animeow.app.ui.screens.PosterBottomBadgeBar(anime, appearance, trackerSettings, statusColor)
                        }
                    }
                }
                if (selected) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)))
                    Icon(
                        AppIcons.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        tint = Color.White,
                    )
                }
            }
            if (showDetails) Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (appearance.showTitle && appearance.coverTitlePosition == CoverTitlePosition.BELOW) {
                    Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                }
                if (appearance.showStatus && !trackerSettings.showCoverStatus) {
                    WatchStatusBadge(anime.status, statusColor, trackerSettings, filled = appearance.statusChipFilled)
                }
                anime.seriesRole?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                SeriesAnimeMetadataLine(anime, trackerSettings)
                if (appearance.showProgress && trackerSettings.showCoverProgress) {
                Text(
                    seriesProgressText(anime),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
                if (appearance.showProgress && trackerSettings.showCoverProgress && anime.totalEpisodes > 0 && normalizeSubjectType(anime.subjectType) != "book") {
                    LinearProgressIndicator(
                        progress = { (anime.watchedEpisodes.toFloat() / anime.totalEpisodes.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(MaterialTheme.shapes.small),
                    )
                }
            }
        }
    }
}

internal fun seriesCardHasDetails(
    anime: AnimeEntity,
    appearance: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
): Boolean = (appearance.showTitle && appearance.coverTitlePosition == CoverTitlePosition.BELOW) ||
    (appearance.showStatus && !trackerSettings.showCoverStatus) ||
    (appearance.showProgress && trackerSettings.showCoverProgress) ||
    trackerSettings.showCoverSubjectType ||
    (trackerSettings.showCoverUpdateWeekday && seriesAnimeUpdateWeekday(anime) != null)

@Composable
private fun SeriesSelectionBar(
    onStatus: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: () -> Unit,
    onAutoMatch: () -> Unit,
    onRole: () -> Unit,
    onRemoveSeries: () -> Unit,
    onTrash: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FilledTonalButton(onClick = onRole) { Text("系列关系") } }
        item { FilledTonalButton(onClick = onStatus) { Text("状态") } }
        item { FilledTonalButton(onClick = onAddTag) { Text("标签+") } }
        item { FilledTonalButton(onClick = onRemoveTag) { Text("标签−") } }
        item { FilledTonalButton(onClick = onAutoMatch) { Icon(AppIcons.Magic, null); Spacer(Modifier.size(5.dp)); Text("联网补全") } }
        item { FilledTonalButton(onClick = onRemoveSeries) { Icon(AppIcons.Unlink, null); Spacer(Modifier.size(5.dp)); Text("移出系列") } }
        item { FilledTonalButton(onClick = onTrash) { Icon(AppIcons.Delete, null); Spacer(Modifier.size(5.dp)); Text("回收站") } }
    }
}

@Composable
private fun SeriesEditDialog(
    initialName: String,
    initialDescription: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑系列资料") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    name,
                    { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("系列名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    description,
                    { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("系列说明") },
                    minLines = 3,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(name, description) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesCoverSheet(
    animes: List<AnimeEntity>,
    selectedUrl: String?,
    onSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择系列封面", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { onSelected(null) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("恢复自动封面")
            }
            val candidates = animes.filter { !it.coverUrl.isNullOrBlank() }
            if (candidates.isEmpty()) {
                Text("系列成员暂时没有可用封面", modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(candidates, key = AnimeEntity::id) { anime ->
                        ElevatedCard(
                            modifier = Modifier.width(112.dp).clickable(
                                role = Role.Button,
                                onClickLabel = "设为系列封面",
                                onClick = { onSelected(anime.coverUrl) },
                            ),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (selectedUrl == anime.coverUrl) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column {
                                AsyncImage(
                                    anime.coverUrl,
                                    anime.title,
                                    Modifier.fillMaxWidth().aspectRatio(112f / 148f),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(anime.title, modifier = Modifier.padding(6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCrossMediaCard(
    entry: com.animeow.app.data.local.SeriesLinkedCrossMediaEntry,
    aspectRatio: Float,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "管理《${entry.title}》系列关联", onClick = onClick),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (entry.coverUrl.isNotBlank()) {
                    AsyncImage(entry.coverUrl, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(entry.kindLabel, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                ) {
                    Text(entry.kindLabel, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(entry.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry.role?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddSeriesWorksSheet(
    candidates: List<AnimeEntity>,
    seriesNames: Map<Long, String>,
    onConfirm: suspend (Map<Long, Long?>) -> Unit,
    onDismiss: () -> Unit,
    availableGames: List<com.animeow.app.data.games.GameEntity> = emptyList(),
    availableAudioWorks: List<com.animeow.app.data.audio.AudioWork> = emptyList(),
    onConfirmCrossMedia: suspend (List<com.animeow.app.data.local.SeriesLinkedMediaItem>) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    var selectedCrossKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selected = selectedIds.toSet()
    val selectedCross = selectedCrossKeys.toSet()
    val totalSelectedCount = selected.size + selectedCross.size
    var pending by remember { mutableStateOf<Map<Long, Long?>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(expected: Map<Long, Long?>) {
        busy = true
        scope.launch {
            try {
                if (expected.isNotEmpty()) onConfirm(expected)
                if (selectedCross.isNotEmpty()) {
                    val items = selectedCross.mapNotNull { key ->
                        val parts = key.split(":", limit = 2)
                        if (parts.size == 2) com.animeow.app.data.local.SeriesLinkedMediaItem(parts[0], parts[1]) else null
                    }
                    onConfirmCrossMedia(items)
                }
                onDismiss()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = if (failure is IllegalArgumentException) failure.message else "保存失败，请重试" }
            finally { busy = false; pending = null }
        }
    }
    val visible = remember(candidates, query) {
        val normalized = query.trim().lowercase(java.util.Locale.ROOT)
        candidates.filter {
            normalized.isEmpty() || it.title.lowercase(java.util.Locale.ROOT).contains(normalized) ||
                it.originalTitle.orEmpty().lowercase(java.util.Locale.ROOT).contains(normalized)
        }
    }
    val visibleGames = remember(availableGames, query) {
        val normalized = query.trim().lowercase(java.util.Locale.ROOT)
        availableGames.filter {
            normalized.isEmpty() || it.title.lowercase(java.util.Locale.ROOT).contains(normalized) ||
                it.originalTitle.lowercase(java.util.Locale.ROOT).contains(normalized)
        }
    }
    val visibleAudio = remember(availableAudioWorks, query) {
        val normalized = query.trim().lowercase(java.util.Locale.ROOT)
        availableAudioWorks.filter {
            normalized.isEmpty() || it.title.lowercase(java.util.Locale.ROOT).contains(normalized) ||
                it.originalTitle.lowercase(java.util.Locale.ROOT).contains(normalized) ||
                it.workCode.lowercase(java.util.Locale.ROOT).contains(normalized)
        }
    }
    StableModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetGesturesEnabled = false) {
        BackHandler(busy) { }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("添加已有作品", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
            if (availableGames.isNotEmpty() || availableAudioWorks.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(tab == 0, { tab = 0 }, label = { Text("动画／书籍 (${candidates.size})") }) }
                    if (availableGames.isNotEmpty()) {
                        item { FilterChip(tab == 1, { tab = 1 }, label = { Text("游戏 (${availableGames.size})") }) }
                    }
                    if (availableAudioWorks.isNotEmpty()) {
                        item { FilterChip(tab == 2, { tab = 2 }, label = { Text("音声 (${availableAudioWorks.size})") }) }
                    }
                }
            }
            OutlinedTextField(
                query,
                { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                label = { Text("搜索标题") },
                singleLine = true,
            )
            LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                when (tab) {
                    1 -> items(visibleGames, key = { it.syncId }) { game ->
                        val key = "GAME:${game.syncId}"
                        ListItem(
                            headlineContent = { Text(game.title) },
                            supportingContent = { Text("${game.status} · 游戏") },
                            leadingContent = { Checkbox(key in selectedCross, null) },
                            modifier = Modifier.toggleable(
                                value = key in selectedCross,
                                role = Role.Checkbox,
                                enabled = !busy,
                                onValueChange = { checked ->
                                    if (!busy) selectedCrossKeys = (if (checked) selectedCross + key else selectedCross - key).toList()
                                },
                            ),
                        )
                    }
                    2 -> items(visibleAudio, key = { it.syncId }) { audio ->
                        val key = "AUDIO:${audio.syncId}"
                        ListItem(
                            headlineContent = { Text(audio.title) },
                            supportingContent = { Text("${audio.status} · ${audio.category}") },
                            leadingContent = { Checkbox(key in selectedCross, null) },
                            modifier = Modifier.toggleable(
                                value = key in selectedCross,
                                role = Role.Checkbox,
                                enabled = !busy,
                                onValueChange = { checked ->
                                    if (!busy) selectedCrossKeys = (if (checked) selectedCross + key else selectedCross - key).toList()
                                },
                            ),
                        )
                    }
                    else -> items(visible, key = AnimeEntity::id) { anime ->
                        ListItem(
                            headlineContent = { Text(anime.title) },
                            supportingContent = { Text("${anime.status} · ${subjectTypeDisplayName(anime.subjectType)}" + (anime.seriesId?.let { " · 当前系列：${seriesNames[it] ?: "未知系列"}" } ?: " · 无系列")) },
                            leadingContent = { Checkbox(anime.id in selected, null) },
                            modifier = Modifier.toggleable(
                                value = anime.id in selected,
                                role = Role.Checkbox,
                                enabled = !busy,
                                onValueChange = { checked ->
                                    if (!busy) selectedIds = (if (checked) selected + anime.id else selected - anime.id).toLongArray()
                                },
                            ),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                Button(onClick = {
                    val expected = candidates.filter { it.id in selected }.associate { it.id to it.seriesId }
                    if (expected.size != selected.size) error = "作品归属已变化，请重新选择"
                    else if (expected.values.any { it != null }) pending = expected else save(expected)
                }, enabled = totalSelectedCount > 0 && !busy) { Text(if (busy) "正在保存…" else "添加 $totalSelectedCount 部") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
    }
    pending?.let { expected -> AlertDialog(onDismissRequest = { if (!busy) pending = null },
        title = { Text("确认移入当前系列？") },
        text = { Text("所选作品中有 ${expected.values.count { it != null }} 部已属于其他系列。确认后会从原系列移出，原系列关系标记会清除，进度、评分、标签和感想保留。") },
        confirmButton = { TextButton(onClick = { save(expected) }, enabled = !busy) { Text("确认移入") } },
        dismissButton = { TextButton(onClick = { pending = null }, enabled = !busy) { Text("取消") } }) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SeriesDisplaySheet(
    sort: SeriesSort,
    ascending: Boolean,
    columnsOverride: Int?,
    homeColumns: Int,
    onSortChange: (SeriesSort) -> Unit,
    onDirectionChange: (Boolean) -> Unit,
    onColumnsChange: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("系列显示", style = MaterialTheme.typography.headlineSmall)
            Text("排序、筛选和列数会为这个系列自动记住", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("排序", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeriesSort.entries.forEach { option ->
                    FilterChip(sort == option, { onSortChange(option) }, label = { Text(option.displayName) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(ascending, { onDirectionChange(true) }, label = { Text("升序") })
                FilterChip(!ascending, { onDirectionChange(false) }, label = { Text("降序") })
            }
            HorizontalDivider()
            Text("封面列数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(columnsOverride == null, { onColumnsChange(null) }, label = { Text("跟随首页 ($homeColumns)") })
                (2..5).forEach { columns ->
                    FilterChip(columnsOverride == columns, { onColumnsChange(columns) }, label = { Text("$columns 列") })
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("完成") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSelectionSheet(
    statuses: List<WatchStatusEntity>,
    onSelected: (WatchStatusEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item { Text("批量修改状态", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall) }
            if (statuses.isEmpty()) item { Text("状态菜单均已隐藏，可在状态管理中恢复，或进入单部作品编辑页修改。", Modifier.padding(20.dp)) }
            items(statuses, key = WatchStatusEntity::id) { status ->
                ListItem(
                    headlineContent = { Text(status.name) },
                    leadingContent = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(status.color))) },
                    modifier = Modifier.clickable { onSelected(status) },
                )
            }
        }
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun filterSeriesAnimes(
    items: List<AnimeEntity>,
    status: String?,
    query: String,
    subjectType: String? = null,
): List<AnimeEntity> {
    val search = query.trim()
    return items.filter { anime ->
        (subjectType == null || normalizeSubjectType(anime.subjectType) == subjectType) &&
        (status == null || anime.status == status) &&
            (search.isEmpty() || anime.title.contains(search, ignoreCase = true) ||
                anime.originalTitle?.contains(search, ignoreCase = true) == true)
    }
}

internal fun sortSeriesAnimes(
    items: List<AnimeEntity>,
    sort: SeriesSort,
    ascending: Boolean,
): List<AnimeEntity> {
    fun <T : Comparable<T>> compareKnown(first: T?, second: T?): Int = when {
        first == null && second == null -> 0
        first == null -> 1
        second == null -> -1
        ascending -> first.compareTo(second)
        else -> second.compareTo(first)
    }
    fun date(anime: AnimeEntity): LocalDate? = anime.airDate?.take(10)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    return items.sortedWith { first, second ->
        val comparison = when (sort) {
            SeriesSort.DEFAULT -> compareKnown(first.id, second.id)
            SeriesSort.AIR_DATE -> compareKnown(date(first), date(second))
            SeriesSort.RATING -> compareKnown(animeRatingSortValue(first), animeRatingSortValue(second))
            SeriesSort.PROGRESS -> compareKnown(first.watchedEpisodes, second.watchedEpisodes)
            SeriesSort.TITLE -> compareKnown(first.title.lowercase(java.util.Locale.ROOT), second.title.lowercase(java.util.Locale.ROOT))
        }
        if (comparison == 0) first.id.compareTo(second.id) else comparison
    }
}

private fun formatAnimeRating(rating: Number?, grade: String?): String =
    grade.takeIf { !it.isNullOrBlank() }
        ?: rating?.let { "%.1f".format(it.toDouble() / 10.0) }
        ?: "暂无"

@Composable
private fun SeriesAnimeMetadataLine(anime: AnimeEntity, trackerSettings: TrackerSettings) {
    val labels = buildList {
        if (trackerSettings.showCoverSubjectType) add(subjectTypeDisplayName(anime.subjectType))
        if (trackerSettings.showCoverUpdateWeekday) seriesAnimeUpdateWeekday(anime)?.let { add(seriesWeekdayLabel(it)) }
    }
    if (labels.isNotEmpty()) {
        Text(
            labels.joinToString(" · "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun seriesAnimeUpdateWeekday(anime: AnimeEntity): Int? =
    anime.broadcastDay?.takeIf { it in 1..7 } ?: anime.reminderDay ?: anime.airDate.toSeriesAirDateWeekdayOrNull()

private fun String?.toSeriesAirDateWeekdayOrNull(): Int? {
    val normalized = this?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
    val date = runCatching { LocalDate.parse(normalized) }.getOrNull() ?: return null
    return date.dayOfWeek.value
}

private fun seriesWeekdayLabel(day: Int): String = when (day) {
    1 -> "周一更新"
    2 -> "周二更新"
    3 -> "周三更新"
    4 -> "周四更新"
    5 -> "周五更新"
    6 -> "周六更新"
    7, 0 -> "周日更新"
    else -> "每周更新"
}

internal fun seriesProgressText(anime: AnimeEntity): String =
    if (normalizeSubjectType(anime.subjectType) == "book") {
        listOfNotNull(anime.bookReadVolumes?.let { "已读 $it 卷" }, anime.bookReadChapters?.let { "已读 $it 话" })
            .joinToString(" · ").ifBlank { "阅读进度未记录" }
    } else "${anime.watchedEpisodes}/${anime.totalEpisodes.takeIf { it > 0 } ?: "?"} 集"
