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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.tracker.AdvancedLibraryFilters
import com.animeow.app.ui.tracker.LibraryDisplayItem
import com.animeow.app.ui.tracker.LibrarySort
import com.animeow.app.ui.tracker.TrackerHomeSections
import com.animeow.app.ui.tracker.TrackerViewModel

private val legacyLayouts = listOf(
    HomeLayout.BENTO,
    HomeLayout.CARD_FEED,
    HomeLayout.POSTER_WALL,
)

@Composable
fun LegacyTrackerScreen(
    settings: AppearanceSettings,
    onAnimeClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onAddAnime: () -> Unit,
    onHomeLayoutSelected: (HomeLayout) -> Unit,
    onContentDensitySelected: (ContentDensity) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrackerViewModel = viewModel(),
) {
    val libraryItems by viewModel.libraryItems.collectAsStateWithLifecycle()
    val homeSections by viewModel.homeSections.collectAsStateWithLifecycle()
    val statusOptions by viewModel.statusOptions.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedStatus by viewModel.status.collectAsStateWithLifecycle()
    val selectedSort by viewModel.sort.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val advanced by viewModel.advanced.collectAsStateWithLifecycle()
    val years by viewModel.availableYears.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    var showFilter by remember { mutableStateOf(false) }
    var showDisplay by remember { mutableStateOf(false) }

    val activeFilters = advanced.tagIds.size + advanced.years.size +
        (if (advanced.subjectType != "all") 1 else 0) +
        (if (selectedStatus != "全部") 1 else 0)
    val layout = settings.homeLayout.takeIf(legacyLayouts::contains) ?: HomeLayout.BENTO

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LegacyPageHeader(
                    title = "追番",
                    subtitle = "${libraryItems.sumOf { it.memberAnimeIds.size }} 部作品",
                    actions = {
                        IconButton(onClick = { onHomeLayoutSelected(HomeLayout.CARD_FEED) }) {
                            Icon(Icons.Outlined.ViewAgenda, contentDescription = "分区视图")
                        }
                        IconButton(onClick = { onHomeLayoutSelected(HomeLayout.BENTO) }) {
                            Icon(Icons.Outlined.ViewModule, contentDescription = "聚合视图")
                        }
                        IconButton(onClick = { showDisplay = true }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "显示设置")
                        }
                    },
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    placeholder = { Text("搜索标题、系列或别名") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "清空搜索")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        FilterChip(
                            selected = activeFilters > 0,
                            onClick = { showFilter = true },
                            leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                            label = { Text(if (activeFilters > 0) "筛选 $activeFilters" else "筛选") },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = viewModel::toggleSortDirection,
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
                            label = { Text("${if (sortAscending) "↑" else "↓"} ${selectedSort.displayName}") },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                val next = statusOptions.let { options ->
                                    val index = options.indexOf(selectedStatus).coerceAtLeast(0)
                                    options.getOrElse((index + 1) % options.size.coerceAtLeast(1)) { "全部" }
                                }
                                viewModel.setStatus(next)
                            },
                            label = { Text(selectedStatus) },
                        )
                    }
                }
            }

            when {
                libraryItems.isEmpty() -> item {
                    LegacyEmptyLibrary(onAddAnime)
                }
                layout == HomeLayout.POSTER_WALL -> {
                    item {
                        LegacySectionTitle(
                            icon = Icons.Outlined.GridView,
                            title = "全部作品 (${libraryItems.size})",
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    legacyPosterRows(
                        items = libraryItems,
                        onAnimeClick = onAnimeClick,
                        onSeriesClick = onSeriesClick,
                    )
                }
                layout == HomeLayout.CARD_FEED -> {
                    legacySectionedContent(
                        homeSections = homeSections,
                        allItems = libraryItems,
                        onAnimeClick = onAnimeClick,
                        onSeriesClick = onSeriesClick,
                    )
                }
                else -> {
                    legacyBentoContent(
                        homeSections = homeSections,
                        allItems = libraryItems,
                        onAnimeClick = onAnimeClick,
                        onSeriesClick = onSeriesClick,
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAddAnime,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 22.dp),
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text("添加作品", fontWeight = FontWeight.Bold) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        )
    }

    if (showFilter) {
        LegacyLibraryFilterSheet(
            statusOptions = statusOptions,
            selectedStatus = selectedStatus,
            selectedSort = selectedSort,
            sortAscending = sortAscending,
            advanced = advanced,
            years = years,
            tags = tags.map { it.id to it.name },
            onStatus = viewModel::setStatus,
            onSort = viewModel::setSort,
            onToggleSort = viewModel::toggleSortDirection,
            onSubjectType = viewModel::setSubjectType,
            onToggleYear = viewModel::toggleYear,
            onToggleTag = viewModel::toggleTagFilter,
            onReset = {
                viewModel.setStatus("全部")
                viewModel.clearAdvancedFilters()
                viewModel.resetSort()
            },
            onDismiss = { showFilter = false },
        )
    }

    if (showDisplay) {
        StableModalBottomSheet(onDismissRequest = { showDisplay = false }, sheetGesturesEnabled = false) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("经典版追番布局", style = MaterialTheme.typography.headlineSmall)
                Text("在旧版的聚合、分区和海报墙之间切换。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                legacyLayouts.forEach { option ->
                    FilterChip(
                        selected = layout == option,
                        onClick = {
                            onHomeLayoutSelected(option)
                            showDisplay = false
                        },
                        leadingIcon = {
                            Icon(
                                when (option) {
                                    HomeLayout.BENTO -> Icons.Outlined.ViewModule
                                    HomeLayout.CARD_FEED -> Icons.Outlined.ViewAgenda
                                    else -> Icons.Outlined.GridView
                                },
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(
                                when (option) {
                                    HomeLayout.BENTO -> "旧版首页"
                                    HomeLayout.CARD_FEED -> "分区纵览"
                                    else -> "全部作品海报墙"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("信息密度", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContentDensity.entries.forEach { density ->
                        FilterChip(
                            selected = settings.contentDensity == density,
                            onClick = { onContentDensitySelected(density) },
                            label = { Text(density.displayName) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun LegacyEmptyLibrary(onAddAnime: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.MovieFilter, contentDescription = null, modifier = Modifier.size(46.dp))
            Text("还没有作品", style = MaterialTheme.typography.titleLarge)
            Text("添加第一部动画或书籍，旧版首页会自动生成分区。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAddAnime) { Text("添加作品") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.legacyBentoContent(
    homeSections: TrackerHomeSections,
    allItems: List<LibraryDisplayItem>,
    onAnimeClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
) {
    if (homeSections.watching.isNotEmpty()) {
        item {
            LegacySectionTitle(
                icon = Icons.Outlined.PlayCircle,
                title = "正在追",
                subtitle = "${homeSections.watching.size} 部进行中",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(homeSections.watching, key = AnimeEntity::id) { anime ->
                    LegacyWideAnimeCard(anime = anime, onClick = { onAnimeClick(anime.id) })
                }
            }
        }
    }
    if (homeSections.recentAdded.isNotEmpty()) {
        item {
            LegacySectionTitle(
                icon = Icons.Outlined.History,
                title = "最近添加",
                subtitle = "快速复看",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(homeSections.recentAdded, key = AnimeEntity::id) { anime ->
                    LegacyPosterCard(
                        title = anime.title,
                        coverUrl = anime.coverUrl,
                        anime = anime,
                        modifier = Modifier.width(146.dp),
                        onClick = { onAnimeClick(anime.id) },
                    )
                }
            }
        }
    }
    item {
        LegacySectionTitle(
            icon = Icons.Outlined.GridView,
            title = "全部作品 (${allItems.size})",
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    legacyPosterRows(allItems.take(18), onAnimeClick, onSeriesClick)
}

private fun androidx.compose.foundation.lazy.LazyListScope.legacySectionedContent(
    homeSections: TrackerHomeSections,
    allItems: List<LibraryDisplayItem>,
    onAnimeClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
) {
    val sections = listOf(
        Triple("正在追", "${homeSections.watching.size} 部进行中", homeSections.watching),
        Triple("最近添加", "快速复看", homeSections.recentAdded),
        Triple("书籍与漫画", "独立书架", homeSections.books),
    )
    sections.filter { it.third.isNotEmpty() }.forEach { (title, subtitle, animes) ->
        item {
            LegacySectionTitle(
                icon = if (title == "正在追") Icons.Outlined.PlayCircle else Icons.Outlined.History,
                title = title,
                subtitle = subtitle,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(animes, key = AnimeEntity::id) { anime ->
                    LegacyPosterCard(
                        title = anime.title,
                        coverUrl = anime.coverUrl,
                        anime = anime,
                        modifier = Modifier.width(146.dp),
                        onClick = { onAnimeClick(anime.id) },
                    )
                }
            }
        }
    }
    item {
        LegacySectionTitle(
            icon = Icons.Outlined.GridView,
            title = "全部作品 (${allItems.size})",
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    legacyPosterRows(allItems, onAnimeClick, onSeriesClick)
}

private fun androidx.compose.foundation.lazy.LazyListScope.legacyPosterRows(
    items: List<LibraryDisplayItem>,
    onAnimeClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
) {
    items.chunked(3).forEachIndexed { rowIndex, rowItems ->
        item(key = "legacy-grid-row-$rowIndex-${rowItems.firstOrNull()?.stableKey}") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    val anime = item.primaryAnime()
                    LegacyPosterCard(
                        title = item.title,
                        coverUrl = item.coverUrl,
                        anime = anime,
                        seriesCount = (item as? LibraryDisplayItem.SeriesItem)?.members?.size,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (item) {
                                is LibraryDisplayItem.AnimeItem -> onAnimeClick(item.anime.id)
                                is LibraryDisplayItem.SeriesItem -> onSeriesClick(item.series.id)
                            }
                        },
                    )
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LegacyWideAnimeCard(anime: AnimeEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(330.dp).height(190.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = anime.coverUrl,
                contentDescription = anime.title,
                modifier = Modifier.width(126.dp).fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(anime.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegacyDot(if (anime.status == "看完") LegacyGreen else LegacyBlue)
                    Text(anime.status, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text("已看 ${anime.watchedEpisodes}/${anime.totalEpisodes.coerceAtLeast(anime.watchedEpisodes)} 集")
                Box(
                    Modifier.fillMaxWidth().height(7.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                ) {
                    val ratio = if (anime.totalEpisodes > 0) anime.watchedEpisodes.toFloat() / anime.totalEpisodes else 0f
                    Box(
                        Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).height(7.dp)
                            .background(LegacyBlue, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegacyPosterCard(
    title: String,
    coverUrl: String?,
    anime: AnimeEntity?,
    modifier: Modifier,
    seriesCount: Int? = null,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.68f)) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                    ),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                anime?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        LegacyDot(if (it.status == "看完") LegacyGreen else LegacyBlue, Modifier.size(7.dp))
                        Text(it.status, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        "已${if (it.subjectType.isBookSubjectType()) "读" else "看"} ${it.watchedEpisodes}/${it.totalEpisodes.coerceAtLeast(it.watchedEpisodes)} ${if (it.subjectType.isBookSubjectType()) "话" else "集"}",
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    val rating = formatAnimeRating(it.rating).ifBlank { it.ratingGrade.orEmpty() }
                    if (rating.isNotBlank()) Text("🐾 $rating", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                if (seriesCount != null) {
                    Text("系列 $seriesCount 部", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun LibraryDisplayItem.primaryAnime(): AnimeEntity? = when (this) {
    is LibraryDisplayItem.AnimeItem -> anime
    is LibraryDisplayItem.SeriesItem -> members.maxByOrNull(AnimeEntity::id)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyLibraryFilterSheet(
    statusOptions: List<String>,
    selectedStatus: String,
    selectedSort: LibrarySort,
    sortAscending: Boolean,
    advanced: AdvancedLibraryFilters,
    years: List<String>,
    tags: List<Pair<Long, String>>,
    onStatus: (String) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleSort: () -> Unit,
    onSubjectType: (String) -> Unit,
    onToggleYear: (String) -> Unit,
    onToggleTag: (Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选追番", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onReset) { Text("重置") }
                }
            }
            item {
                LegacyFilterGroup("状态") {
                    statusOptions.forEach { status ->
                        FilterChip(
                            selected = selectedStatus == status,
                            onClick = { onStatus(status) },
                            label = { Text(status) },
                        )
                    }
                }
            }
            item {
                LegacyFilterGroup("作品类型") {
                    listOf("all" to "全部", "anime" to "番剧", "book" to "小说 / 漫画").forEach { (key, label) ->
                        FilterChip(
                            selected = advanced.subjectType == key,
                            onClick = { onSubjectType(key) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                LegacyFilterGroup("排序") {
                    LibrarySort.entries.forEach { sort ->
                        FilterChip(
                            selected = selectedSort == sort,
                            onClick = { onSort(sort) },
                            label = { Text(sort.displayName) },
                        )
                    }
                    AssistChip(onClick = onToggleSort, label = { Text(if (sortAscending) "升序" else "降序") })
                }
            }
            if (years.isNotEmpty()) item {
                LegacyFilterGroup("年份") {
                    years.take(16).forEach { year ->
                        FilterChip(
                            selected = year in advanced.years,
                            onClick = { onToggleYear(year) },
                            label = { Text(year) },
                        )
                    }
                }
            }
            if (tags.isNotEmpty()) item {
                LegacyFilterGroup("标签") {
                    tags.take(24).forEach { (id, name) ->
                        FilterChip(
                            selected = id in advanced.tagIds,
                            onClick = { onToggleTag(id) },
                            label = { Text(name) },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("清空条件") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("查看结果") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyFilterGroup(
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
