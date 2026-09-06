package com.animeow.app.ui.series

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.animeow.app.data.RemoteMetadataFields
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.animeRatingSortValue
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.data.preferences.SeriesSort
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.ZoomableImageViewer
import com.animeow.app.ui.components.NewTagDialog
import com.animeow.app.ui.components.WatchStatusBadge
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.tracker.BatchMetadataOptions
import com.animeow.app.ui.tracker.BatchMetadataState
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
    val trackerSettings by trackerViewModel.trackerSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val sort = state.displaySettings.sort
    val ascending = state.displaySettings.ascending
    val columnsOverride = state.displaySettings.columnsOverride
    val statusFilter = state.displaySettings.statusFilter
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
    var showNewTagDialog by rememberSaveable { mutableStateOf(false) }

    val selected = selectedIds.isNotEmpty()
    val effectiveColumns = columnsOverride ?: appearance.gridColumns.coerceIn(2, 5)
    val sorted = remember(state.animes, sort, ascending, statusFilter, query) {
        sortSeriesAnimes(filterSeriesAnimes(state.animes, statusFilter, query), sort, ascending)
    }
    val animationItems = sorted.filter { normalizeSubjectType(it.subjectType) == "anime" }
    val readingItems = sorted.filter { normalizeSubjectType(it.subjectType) == "book" }
    val coverUrl = state.series?.customCoverUrl
        ?: state.animes.sortedByDescending(AnimeEntity::id).firstNotNullOfOrNull(AnimeEntity::coverUrl)

    BackHandler(enabled = selected) { viewModel.clearSelection() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(message) }
    }
    LaunchedEffect(batchState.message) {
        batchState.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearBatchMetadataMessage()
        }
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
                            if (selected) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = if (selected) "退出多选" else "返回",
                        )
                    }
                },
                actions = {
                    if (selected) {
                        IconButton(onClick = { viewModel.selectAll(sorted.mapTo(linkedSetOf(), AnimeEntity::id)) }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "全选或取消全选")
                        }
                    } else {
                        IconButton(onClick = { showAddSheet = true }, enabled = state.series != null) {
                            Icon(Icons.Outlined.AddCircleOutline, contentDescription = "添加已有作品")
                        }
                        IconButton(onClick = { showSearch = !showSearch; query = ""; viewModel.clearSelection() }) {
                            Icon(if (showSearch) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = if (showSearch) "关闭系列搜索" else "搜索系列内作品")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selected) {
                SeriesSelectionBar(
                    onStatus = { showStatusSheet = true },
                    onAddTag = { tagAction = TagAction.ADD },
                    onRemoveTag = { tagAction = TagAction.REMOVE },
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
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(effectiveColumns),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                item(span = StaggeredGridItemSpan.FullLine, key = "series_header") {
                    SeriesHero(
                        seriesName = state.series!!.name,
                        description = state.series!!.description,
                        coverUrl = coverUrl,
                        itemCount = state.animes.size,
                        watchingCount = state.animes.count { it.status == "在看" },
                        watched = state.animes.sumOf(AnimeEntity::watchedEpisodes),
                        total = state.animes.sumOf(AnimeEntity::totalEpisodes),
                        onCoverClick = { if (!coverUrl.isNullOrBlank()) showCoverViewer = true },
                        onEdit = { showEdit = true },
                        onChooseCover = { showCoverSheet = true },
                    )
                }
                if (showSearch) {
                    item(span = StaggeredGridItemSpan.FullLine, key = "series_search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; viewModel.clearSelection() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索这个系列的作品") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large,
                        )
                    }
                }
                item(span = StaggeredGridItemSpan.FullLine, key = "series_filters") {
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
                item(span = StaggeredGridItemSpan.FullLine, key = "series_summary") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${sorted.size} 部作品",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showDisplaySheet = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${sort.displayName} ${if (ascending) "↑" else "↓"}", maxLines = 1)
                        }
                    }
                }
                if (state.animes.isNotEmpty() && sorted.isEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine, key = "filter_empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (query.isBlank()) "这个系列还没有「$statusFilter」作品" else "没有找到匹配的作品", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { query = ""; viewModel.setStatusFilter(null) }) { Text("查看全部作品") }
                        }
                    }
                }
                if (state.animes.isEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                        ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { showAddSheet = true }) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("这个系列还没有作品", style = MaterialTheme.typography.titleMedium)
                                Text("点击添加已有作品", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (animationItems.isNotEmpty()) {
                    if (readingItems.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine, key = "animation_header") {
                        SeriesSectionHeader("动画", animationItems.size, MaterialTheme.colorScheme.primary)
                    }
                    }
                    staggeredItems(animationItems, key = AnimeEntity::id) { anime ->
                        SeriesAnimeCard(
                            anime = anime,
                            appearance = appearance,
                            trackerSettings = trackerSettings,
                            statusColor = state.statuses.firstOrNull { it.name == anime.status }?.color?.let(::Color) ?: MaterialTheme.colorScheme.primary,
                            selected = anime.id in selectedIds,
                            onClick = {
                                if (selected) viewModel.toggleSelection(anime.id) else onAnimeClick(anime.id)
                            },
                            onLongClick = {
                                if (appearance.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleSelection(anime.id)
                            },
                        )
                    }
                }
                if (readingItems.isNotEmpty()) {
                    if (animationItems.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine, key = "reading_header") {
                        SeriesSectionHeader("书籍", readingItems.size, MaterialTheme.colorScheme.tertiary)
                    }
                    }
                    staggeredItems(readingItems, key = AnimeEntity::id) { anime ->
                        SeriesAnimeCard(
                            anime = anime,
                            appearance = appearance,
                            trackerSettings = trackerSettings,
                            statusColor = state.statuses.firstOrNull { it.name == anime.status }?.color?.let(::Color) ?: MaterialTheme.colorScheme.primary,
                            selected = anime.id in selectedIds,
                            onClick = {
                                if (selected) viewModel.toggleSelection(anime.id) else onAnimeClick(anime.id)
                            },
                            onLongClick = {
                                if (appearance.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleSelection(anime.id)
                            },
                        )
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
            onConfirm = {
                viewModel.addAnimes(it)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }
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
        TagSelectionSheet(
            action = action,
            tags = state.tags,
            onCreateRequested = if (action == TagAction.ADD) ({ showNewTagDialog = true }) else null,
            onSelected = { tag ->
                if (action == TagAction.ADD) viewModel.addTagToSelected(tag.id)
                else viewModel.removeTagFromSelected(tag.id)
                tagAction = null
            },
            onDismiss = { tagAction = null },
        )
    }
    if (showNewTagDialog) {
        NewTagDialog(
            onConfirm = { name, color ->
                showNewTagDialog = false
                tagAction = null
                viewModel.createTagAndAddToSelected(name, color)
            },
            onDismiss = { showNewTagDialog = false },
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
        SeriesBatchMetadataOptionsDialog(
            selectedCount = selectedIds.size,
            onStart = {
                showMetadataOptions = false
                viewModel.startBatchMetadataMatch(it)
            },
            onDismiss = { showMetadataOptions = false },
        )
    }
    if (batchState.isRunning) {
        SeriesBatchMetadataProgressDialog(
            state = batchState,
            onSkip = viewModel::skipCurrentMetadataMatch,
            onStop = viewModel::cancelBatchMetadataMatch,
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
                        AssistChip(onClick = onEdit, label = { Text("编辑资料") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    }
                    item {
                        AssistChip(onClick = onChooseCover, label = { Text("系列封面") }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) })
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
private fun SeriesAnimeCard(
    anime: AnimeEntity,
    appearance: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
    statusColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 使用固定纵横比，确保瀑布流中封面整齐对齐
    val cardRatio = appearance.coverAspectRatio.ratio

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
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
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (!anime.coverUrl.isNullOrBlank()) {
                    AsyncImage(anime.coverUrl, anime.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                // 角标/评分信息（与首页一致的显示开关与底色）
                if (trackerSettings.showCoverStatus) {
                    WatchStatusBadge(anime.status, statusColor, trackerSettings, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                if (trackerSettings.showCoverRating && ((anime.rating != null && anime.rating > 0) || !anime.ratingGrade.isNullOrBlank())) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(seriesRatingBadgeBackground(appearance.ratingBadgeColorStyle, appearance.ratingBadgeCustomColor))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "${appearance.ratingIconStyle.symbol} ${formatAnimeRating(anime.rating, anime.ratingGrade)}",
                            color = seriesRatingBadgeText(appearance.ratingBadgeColorStyle),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (appearance.showTitle && appearance.coverTitlePosition == CoverTitlePosition.OVERLAY) {
                    Text(
                        anime.title,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.3f to Color.Black.copy(alpha = 0.45f),
                                    1f to Color.Black.copy(alpha = 0.75f),
                                ),
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (selected) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)))
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        tint = Color.White,
                    )
                }
            }
            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (appearance.showTitle && appearance.coverTitlePosition == CoverTitlePosition.BELOW) {
                    Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                }
                if (appearance.showStatus && !trackerSettings.showCoverStatus) {
                    WatchStatusBadge(anime.status, statusColor, trackerSettings, filled = appearance.statusChipFilled)
                }
                SeriesAnimeMetadataLine(anime, trackerSettings)
                if (appearance.showProgress || trackerSettings.showCoverProgress) {
                Text(
                    "${anime.watchedEpisodes}/${anime.totalEpisodes.takeIf { it > 0 } ?: "?"} ${if (normalizeSubjectType(anime.subjectType) == "book") "话/页" else "集"}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
                if (trackerSettings.showCoverProgress && anime.totalEpisodes > 0) {
                    LinearProgressIndicator(
                        progress = { (anime.watchedEpisodes.toFloat() / anime.totalEpisodes.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(MaterialTheme.shapes.small),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesSelectionBar(
    onStatus: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: () -> Unit,
    onAutoMatch: () -> Unit,
    onRemoveSeries: () -> Unit,
    onTrash: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FilledTonalButton(onClick = onStatus) { Text("状态") } }
        item { FilledTonalButton(onClick = onAddTag) { Text("标签+") } }
        item { FilledTonalButton(onClick = onRemoveTag) { Text("标签−") } }
        item { FilledTonalButton(onClick = onAutoMatch) { Icon(Icons.Outlined.AutoFixHigh, null); Spacer(Modifier.size(5.dp)); Text("联网补全") } }
        item { FilledTonalButton(onClick = onRemoveSeries) { Icon(Icons.Outlined.LinkOff, null); Spacer(Modifier.size(5.dp)); Text("移出系列") } }
        item { FilledTonalButton(onClick = onTrash) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.size(5.dp)); Text("回收站") } }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSeriesWorksSheet(
    candidates: List<AnimeEntity>,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val visible = remember(candidates, query) {
        val normalized = query.trim().lowercase(java.util.Locale.ROOT)
        candidates.filter {
            normalized.isEmpty() || it.title.lowercase(java.util.Locale.ROOT).contains(normalized) ||
                it.originalTitle.orEmpty().lowercase(java.util.Locale.ROOT).contains(normalized)
        }
    }
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("添加已有作品", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                query,
                { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                label = { Text("搜索标题") },
                singleLine = true,
            )
            LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                items(visible, key = AnimeEntity::id) { anime ->
                    ListItem(
                        headlineContent = { Text(anime.title) },
                        supportingContent = { Text("${anime.status} · ${subjectTypeDisplayName(anime.subjectType)}") },
                        leadingContent = { Checkbox(anime.id in selected, null) },
                        modifier = Modifier.toggleable(
                            value = anime.id in selected,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                selected = if (checked) selected + anime.id else selected - anime.id
                            },
                        ),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) { Text("添加 ${selected.size} 部") }
            }
        }
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagSelectionSheet(
    action: TagAction,
    tags: List<TagEntity>,
    onCreateRequested: (() -> Unit)?,
    onSelected: (TagEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Text(
                    if (action == TagAction.ADD) "批量添加标签" else "批量移除标签",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            onCreateRequested?.let { create ->
                item {
                    FilledTonalButton(
                        onClick = create,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    ) { Text("新建标签并应用") }
                }
            }
            if (tags.isEmpty()) {
                item { Text("还没有标签，请先在资料管理中创建。", modifier = Modifier.padding(20.dp)) }
            }
            items(tags, key = TagEntity::id) { tag ->
                ListItem(
                    headlineContent = { Text(tag.name) },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null, tint = Color(tag.color ?: 0xFF8A4FD0)) },
                    modifier = Modifier.clickable { onSelected(tag) },
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

@Composable
private fun SeriesBatchMetadataOptionsDialog(
    selectedCount: Int,
    onStart: (BatchMetadataOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var fields by remember { mutableStateOf(RemoteMetadataFields()) }
    var overwrite by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量联网补全") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 390.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { Text("将逐部可靠匹配 $selectedCount 部作品，默认只补空白字段。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { MetadataToggle("封面", fields.cover) { fields = fields.copy(cover = it) } }
                item { MetadataToggle("评分", fields.rating) { fields = fields.copy(rating = it) } }
                item { MetadataToggle("集数配置", fields.episodes) { fields = fields.copy(episodes = it) } }
                item { MetadataToggle("制作公司", fields.studio) { fields = fields.copy(studio = it) } }
                item { MetadataToggle("简介", fields.synopsis) { fields = fields.copy(synopsis = it) } }
                item { MetadataToggle("放送日期", fields.airDate) { fields = fields.copy(airDate = it) } }
                item { MetadataToggle("外部来源与原始标题", fields.externalLink) { fields = fields.copy(externalLink = it) } }
                item { MetadataToggle("标签（从云端匹配并同步）", fields.tags) { fields = fields.copy(tags = it) } }
                item {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = overwrite,
                            role = Role.Checkbox,
                            onValueChange = { overwrite = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(overwrite, null)
                        Column {
                            Text("覆盖已有字段")
                            Text("关闭时不会改动已有资料", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(BatchMetadataOptions(fields, overwrite)) }, enabled = fields.hasSelection) { Text("开始匹配") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MetadataToggle(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = onChanged,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null)
        Text(label)
    }
}

@Composable
private fun SeriesBatchMetadataProgressDialog(
    state: BatchMetadataState,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("正在自动匹配") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.currentTitle?.let { "正在搜索：$it" } ?: "正在准备资料源…", maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${state.completed} / ${state.total} · 更新 ${state.updated} · 未匹配 ${state.unmatched} · 失败 ${state.failed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSkip) { Text("跳过当前") } },
        dismissButton = { TextButton(onClick = onStop) { Text("停止") } },
    )
}

internal fun filterSeriesAnimes(
    items: List<AnimeEntity>,
    status: String?,
    query: String,
): List<AnimeEntity> {
    val search = query.trim()
    return items.filter { anime ->
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
private fun seriesRatingBadgeBackground(style: RatingBadgeColorStyle, customColor: Long): Color = when (style) {
    RatingBadgeColorStyle.ORANGE -> Color(0xFFFF6B00).copy(alpha = 0.9f)
    RatingBadgeColorStyle.BLACK_WHITE -> Color.Black.copy(alpha = 0.85f)
    RatingBadgeColorStyle.THEME_PRIMARY -> MaterialTheme.colorScheme.primary
    RatingBadgeColorStyle.TRANSPARENT_DARK -> Color.Black.copy(alpha = 0.5f)
    RatingBadgeColorStyle.CUSTOM -> Color(customColor)
}

@Composable
private fun seriesRatingBadgeText(style: RatingBadgeColorStyle): Color = when (style) {
    RatingBadgeColorStyle.THEME_PRIMARY -> MaterialTheme.colorScheme.onPrimary
    else -> Color.White
}

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
