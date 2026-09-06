package com.animeow.app.ui.tags

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.animeRatingCompactLabel
import com.animeow.app.data.local.animeRatingSortValue
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.CoverTitlePosition
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagIndexScreen(
    onBack: () -> Unit,
    onTagClick: (Long) -> Unit,
    viewModel: TagIndexViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val grouped = remember(state.items, state.sort) {
        if (state.sort == TagIndexSort.INITIAL) state.items.groupBy(TagIndexItem::initial) else emptyMap()
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val headerIndices = remember(state.items, state.sort) {
        if (state.sort == TagIndexSort.INITIAL) tagGroupHeaderIndices(state.items) else emptyMap()
    }
    val activeInitial by remember(headerIndices, listState) {
        derivedStateOf {
            headerIndices.entries.lastOrNull { (_, index) -> listState.firstVisibleItemIndex >= index }?.key
                ?: headerIndices.keys.firstOrNull()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    val visibleIds = remember(state.items) { state.items.map { it.tag.id } }
    val selectedTagItems = remember(state.items, selectedIds) {
        state.items.filter { it.tag.id in selectedIds }
    }
    val allVisibleSelected = selectedIds.isNotEmpty() && visibleIds.all { it in selectedIds }

    LaunchedEffect(viewModel) {
        viewModel.undoEvents.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = "已删除 ${event.count} 个标签",
                actionLabel = "撤销",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreLastDeleted()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (selectionMode) "已选 ${selectedIds.size} 项" else "标签索引") },
                navigationIcon = {
                    IconButton(onClick = { if (selectionMode) viewModel.exitSelectionMode() else onBack() }) {
                        Icon(
                            if (selectionMode) Icons.Outlined.Clear else Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = if (selectionMode) "退出多选" else "返回",
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            if (allVisibleSelected) viewModel.clearSelection() else viewModel.selectAll(visibleIds)
                        }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "全选或取消全选")
                        }
                    } else {
                        IconButton(
                            onClick = { viewModel.enterSelectionMode() },
                            enabled = state.items.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "批量管理")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selectionMode) {
                TagBatchSelectionBar(
                    selectedCount = selectedIds.size,
                    allVisibleSelected = allVisibleSelected,
                    canSelectAll = visibleIds.isNotEmpty(),
                    onToggleSelectAll = {
                        if (allVisibleSelected) viewModel.clearSelection() else viewModel.selectAll(visibleIds)
                    },
                    onMerge = { showMergeDialog = true },
                    onBlock = { viewModel.setSelectedBlocked(true) },
                    onUnblock = { viewModel.setSelectedBlocked(false) },
                    onDelete = { showDeleteDialog = true },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = if (headerIndices.isEmpty()) 16.dp else 48.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        TagMetric("标签", state.totalTags.toString(), Modifier.weight(1f))
                        TagMetric("已使用", state.usedTags.toString(), Modifier.weight(1f))
                        TagMetric("关联", state.totalLinks.toString(), Modifier.weight(1f))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索标签") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "清空搜索")
                            }
                        }
                    } else null,
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TagIndexSort.entries, key = TagIndexSort::name) { sort ->
                        FilterChip(
                            selected = state.sort == sort,
                            onClick = { viewModel.setSort(sort) },
                            label = { Text(sort.displayName) },
                        )
                    }
                }
            }
            if (state.items.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.LocalOffer, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
                            Text(if (state.query.isBlank()) "还没有标签" else "没有匹配的标签", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            } else if (state.sort == TagIndexSort.INITIAL) {
                grouped.forEach { (initial, tags) ->
                    item("tag-group-$initial") {
                        Text(
                            initial,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    items(tags, key = { it.tag.id }) { item ->
                        TagIndexRow(
                            item = item,
                            onTagClick = onTagClick,
                            selectionMode = selectionMode,
                            selected = item.tag.id in selectedIds,
                            onToggleSelection = { viewModel.toggleSelection(item.tag.id) },
                        )
                    }
                }
            } else {
                items(state.items, key = { it.tag.id }) { item ->
                    TagIndexRow(
                        item = item,
                        onTagClick = onTagClick,
                        selectionMode = selectionMode,
                        selected = item.tag.id in selectedIds,
                        onToggleSelection = { viewModel.toggleSelection(item.tag.id) },
                    )
                }
            }
            }
            if (headerIndices.isNotEmpty()) {
                TagAlphabetRail(
                    initials = headerIndices.keys.toList(),
                    activeInitial = activeInitial,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onInitialClick = { initial ->
                        headerIndices[initial]?.let { target ->
                            scope.launch { listState.animateScrollToItem(target) }
                        }
                    },
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除标签？") },
            text = {
                Text(
                    "将删除 ${selectedIds.size} 个标签及其与作品的关联。作品本身不会被删除，此操作可在 5 秒内撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteSelected()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
    if (showMergeDialog) {
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("合并同义标签") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择要保留的标签，其它已选标签的作品关联会合并到它，并删除其它标签。")
                    if (selectedTagItems.isEmpty()) {
                        Text("没有可合并的标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        selectedTagItems.forEach { item ->
                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    showMergeDialog = false
                                    viewModel.mergeSelected(item.tag.id)
                                },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("#${item.tag.name}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    Text("保留", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMergeDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TagBatchSelectionBar(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    canSelectAll: Boolean,
    onToggleSelectAll: () -> Unit,
    onMerge: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onDelete: () -> Unit,
) {
    BottomAppBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                Text(
                    "已选 $selectedCount 项",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                TextButton(
                    onClick = onToggleSelectAll,
                    enabled = canSelectAll,
                ) {
                    Text(if (allVisibleSelected) "取消全选" else "全选")
                }
            }
            item {
                TextButton(
                    onClick = onMerge,
                    enabled = selectedCount >= 2,
                ) {
                    Text("合并")
                }
            }
            item {
                TextButton(
                    onClick = onBlock,
                    enabled = selectedCount > 0,
                ) {
                    Text("屏蔽")
                }
            }
            item {
                TextButton(
                    onClick = onUnblock,
                    enabled = selectedCount > 0,
                ) {
                    Text("取消屏蔽")
                }
            }
            item {
                FilledTonalButton(
                    onClick = onDelete,
                    enabled = selectedCount > 0,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun TagAlphabetRail(
    initials: List<String>,
    activeInitial: String?,
    modifier: Modifier = Modifier,
    onInitialClick: (String) -> Unit,
) {
    if (initials.isEmpty()) return
    BoxWithConstraints(
        modifier = modifier.fillMaxHeight().width(40.dp).padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val itemHeight = ((maxHeight - 8.dp) / initials.size).coerceIn(14.dp, 28.dp)
        Column(
            modifier = Modifier
                .width(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            initials.forEach { initial ->
                val selected = initial == activeInitial
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable { onInitialClick(initial) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initial,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = if (initial.length > 1) 8.sp else 10.sp,
                        fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TagMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun TagIndexRow(
    item: TagIndexItem,
    onTagClick: (Long) -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {},
) {
    val tagColor = Color(item.tag.color ?: DEFAULT_TAG_COLOR)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable {
            if (selectionMode) onToggleSelection() else onTagClick(item.tag.id)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(tagColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("#", color = tagColor, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.tag.name, fontWeight = FontWeight.SemiBold)
                    if (item.tag.blocked) {
                        Text(
                            "已屏蔽",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    if (item.animeCount == 0) "尚未关联作品" else "${item.animeCount} 部作品使用",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(item.animeCount.toString(), color = tagColor, fontWeight = FontWeight.Bold)
            if (!selectionMode) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private enum class TagCollectionSort(val displayName: String) {
    RECENT("最近添加"),
    TITLE("标题"),
    RATING("评分"),
    AIR_DATE("放送日期"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagCollectionScreen(
    settings: AppearanceSettings,
    onBack: () -> Unit,
    onAnimeClick: (Long) -> Unit,
    viewModel: TagCollectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(TagCollectionSort.RECENT) }
    val filtered = remember(state.animes, query, status, sort) {
        state.animes.asSequence()
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.originalTitle?.contains(query.trim(), true) == true }
            .filter { status == null || it.status == status }
            .let { sequence ->
                when (sort) {
                    TagCollectionSort.RECENT -> sequence.sortedByDescending { it.id }
                    TagCollectionSort.TITLE -> sequence.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
                    TagCollectionSort.RATING -> sequence.sortedByDescending { animeRatingSortValue(it) ?: -1 }
                    TagCollectionSort.AIR_DATE -> sequence.sortedByDescending { it.airDate.orEmpty() }
                }
            }
            .toList()
    }
    val statusColors = remember(state.statuses) { state.statuses.associate { it.name to Color(it.color) } }
    val animationDuration = (180 * settings.motionLevel.durationScale).toInt().coerceAtLeast(1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.tag?.let { "# ${it.name}" } ?: "标签作品")
                        Text("${filtered.size} / ${state.animes.size} 部", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true,
                label = { Text("在此标签中搜索") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, contentDescription = "清空") } }
                } else null,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = status == null, onClick = { status = null }, label = { Text("全部状态") })
                }
                items(state.statuses, key = { "status-${it.id}" }) { item ->
                    FilterChip(selected = status == item.name, onClick = { status = item.name }, label = { Text(item.name) })
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TagCollectionSort.entries, key = TagCollectionSort::name) { option ->
                    FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.displayName) })
                }
            }
            AnimatedContent(
                targetState = filtered.isEmpty(),
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(animationDuration)) togetherWith fadeOut(tween(animationDuration)) },
                label = "tag_collection_content",
            ) { empty ->
                if (empty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.MovieFilter, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
                            Text(if (state.animes.isEmpty()) "这个标签还没有作品" else "没有符合当前筛选的作品")
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(settings.gridColumns.coerceIn(2, 5)),
                        contentPadding = PaddingValues(
                            start = (12 * settings.contentDensity.scale).dp,
                            end = (12 * settings.contentDensity.scale).dp,
                            top = (8 * settings.contentDensity.scale).dp,
                            bottom = 32.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(gridSpacing(settings.contentDensity)),
                        verticalArrangement = Arrangement.spacedBy(gridSpacing(settings.contentDensity)),
                    ) {
                        items(filtered, key = AnimeEntity::id) { anime ->
                            TagAnimeCard(
                                anime = anime,
                                settings = settings,
                                statusColor = statusColors[anime.status] ?: MaterialTheme.colorScheme.primary,
                                onClick = { onAnimeClick(anime.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagAnimeCard(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    statusColor: Color,
    onClick: () -> Unit,
) {
    val titlePosition = settings.coverTitlePosition
    val densityScale = settings.contentDensity.scale
    Column(
        modifier = Modifier.clickable(
            role = Role.Button,
            onClickLabel = "打开《${anime.title}》详情",
            onClick = onClick,
        ),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (anime.coverUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.MovieFilter, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    AsyncImage(
                        model = anime.coverUrl,
                        contentDescription = anime.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (settings.showStatus) {
                    Text(
                        anime.status,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.9f)).padding(horizontal = 7.dp, vertical = 3.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (settings.showRating && animeRatingSortValue(anime) != null) {
                    Text(
                        listOf(settings.ratingIconStyle.symbol, animeRatingCompactLabel(anime))
                            .filter(String::isNotBlank)
                            .joinToString(" "),
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 7.dp, vertical = 3.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (settings.showProgress && anime.totalEpisodes > 0) {
                    Text(
                        "${anime.watchedEpisodes}/${anime.totalEpisodes}",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 7.dp, vertical = 3.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (titlePosition == CoverTitlePosition.OVERLAY) {
                    Box(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                            .padding(
                                start = (8 * densityScale).dp,
                                end = (8 * densityScale).dp,
                                top = (24 * densityScale).dp,
                                bottom = (8 * densityScale).dp,
                            ),
                    ) {
                        Text(anime.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (titlePosition == CoverTitlePosition.BELOW) {
            Spacer(Modifier.height((6 * densityScale).dp))
            Text(
                anime.title,
                maxLines = if (settings.contentDensity == ContentDensity.COMPACT) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun gridSpacing(density: ContentDensity) = when (density) {
    ContentDensity.COMPACT -> 6.dp
    ContentDensity.COMFORTABLE -> 10.dp
    ContentDensity.SPACIOUS -> 14.dp
}

private const val DEFAULT_TAG_COLOR = 0xFF8A4FD0
