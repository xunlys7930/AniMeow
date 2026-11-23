package com.animeow.app.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

import com.animeow.app.ui.icons.AppIcons

import com.animeow.app.ui.theme.densityFor

import com.animeow.app.ui.navigation.LocalFloatingNavigationInset

import android.icu.text.Transliterator
import android.os.Build
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import android.view.SoundEffectConstants

import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.animeow.app.ui.tracker.SeriesDragAutoScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.RemoteMetadataFields
import com.animeow.app.data.preferences.HomeBottomScope
import com.animeow.app.data.preferences.BentoCollectionLayout
import com.animeow.app.data.preferences.CoverFitMode
import com.animeow.app.data.preferences.TRACKER_START_ALL_STATUS
import com.animeow.app.data.preferences.TRACKER_START_LAST_STATUS
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.WatchStatusBadge
import com.animeow.app.ui.components.StatusBadgeControls
import com.animeow.app.data.preferences.StatusBadgeBackground
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.NewTagDialog
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.APP_FONT_SCALE_MAX
import com.animeow.app.ui.theme.APP_FONT_SCALE_MIN
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.preferences.ColorPickerDialog
import com.animeow.app.ui.tracker.LibrarySort
import com.animeow.app.ui.tracker.LibraryDisplayItem
import com.animeow.app.ui.tracker.BatchMetadataOptions
import com.animeow.app.ui.tracker.BatchMetadataState
import com.animeow.app.ui.tracker.AdvancedLibraryFilters
import com.animeow.app.ui.tracker.TrackerViewModel
import com.animeow.app.ui.tracker.TrackerHomeSections
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max

// 追番首页的各种资料库布局（聚合、卡片流、海报墙、时间线、索引）与首页横向条。
@Composable
internal fun BentoLayout(
    header: @Composable () -> Unit,
    emptyContent: (@Composable () -> Unit)?,
    items: List<LibraryDisplayItem>,
    homeSections: TrackerHomeSections,
    isDefaultMode: Boolean,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    spacing: Dp,
    onItemClick: (LibraryDisplayItem) -> Unit,
    onItemLongClick: (LibraryDisplayItem) -> Unit,
    onHomeBottomScopeSelected: (HomeBottomScope) -> Unit,
    onBentoCollectionLayoutSelected: (BentoCollectionLayout) -> Unit,
    onCheckIn: (AnimeEntity) -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val gridColumns = readableGridColumns(settings.gridColumns)
    val effectiveItems = if (isDefaultMode && trackerSettings.homeBottomScope == HomeBottomScope.ANIME_ONLY) {
        items.filter { item ->
            item is LibraryDisplayItem.SeriesItem ||
                normalizeSubjectType((item as? LibraryDisplayItem.AnimeItem)?.anime?.subjectType) == "anime"
        }
    } else {
        items
    }
    val dragScrollState = rememberLazyListState()
    SeriesDragAutoScroll(dragScrollState)
    LazyColumn(
        state = dragScrollState,
        contentPadding = PaddingValues(
            start = (16 * densityScale).dp,
            top = (4 * densityScale).dp,
            end = (16 * densityScale).dp,
            bottom = maxOf(72.dp, LocalFloatingNavigationInset.current + 16.dp),
        ),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        item(key = "library-controls") { header() }
        if (emptyContent != null) {
            item(key = "library-empty") { emptyContent() }
        } else {
            if (
                isDefaultMode &&
                trackerSettings.showStandaloneBookshelf &&
                homeSections.books.isNotEmpty()
            ) {
                item(key = "books_strip") {
                    HomePosterStrip(
                        title = "书籍",
                        subtitle = "书籍作品 · ${homeSections.books.size} 部",
                        animes = homeSections.books,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selectedIds = selectedIds,
                        onClick = { onItemClick(LibraryDisplayItem.AnimeItem(it)) },
                        onLongClick = { onItemLongClick(LibraryDisplayItem.AnimeItem(it)) },
                    )
                }
            }
            item {
                BentoCollectionHeader(
                    title = if (isDefaultMode && trackerSettings.homeBottomScope == HomeBottomScope.ANIME_ONLY) "全部动画" else "全部作品",
                    subtitle = "${effectiveItems.sumOf { it.memberAnimeIds.size }} 部",
                    scope = trackerSettings.homeBottomScope.takeIf { isDefaultMode },
                    layout = trackerSettings.bentoCollectionLayout,
                    onScopeSelected = onHomeBottomScopeSelected,
                    onLayoutSelected = onBentoCollectionLayoutSelected,
                )
            }
            when (trackerSettings.bentoCollectionLayout) {
                BentoCollectionLayout.GRID -> {
                    val columns = gridColumns
                    items(
                        items = effectiveItems.chunked(columns),
                        key = { row -> row.joinToString("|") { it.stableKey } },
                    ) { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
                        ) {
                            rowItems.forEach { item ->
                                Box(modifier = Modifier.weight(1f)) {
                                    when (item) {
                                        is LibraryDisplayItem.AnimeItem -> PosterAnimeCard(
                                            anime = item.anime,
                                            settings = settings,
                                            trackerSettings = trackerSettings,
                                            selected = item.anime.id in selectedIds,
                                            onClick = { onItemClick(item) },
                                            onLongClick = { onItemLongClick(item) },
                                            swipeEnabled = swipeEnabled,
                                            onSwipe = onSwipe,
                                        )
                                        is LibraryDisplayItem.SeriesItem -> PosterSeriesCard(
                                            item = item,
                                            settings = settings,
                                            trackerSettings = trackerSettings,
                                            selected = item.memberAnimeIds.isNotEmpty() && item.memberAnimeIds.all(selectedIds::contains),
                                            onClick = { onItemClick(item) },
                                            onLongClick = { onItemLongClick(item) },
                                        )
                                    }
                                }
                            }
                            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                BentoCollectionLayout.LIST -> items(effectiveItems, key = LibraryDisplayItem::stableKey) { item ->
                    LibraryListItemCard(
                        item = item,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selected = item.memberAnimeIds.isNotEmpty() && item.memberAnimeIds.all(selectedIds::contains),
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                        onCheckIn = onCheckIn,
                        swipeEnabled = swipeEnabled,
                        onSwipe = onSwipe,
                    )
                }
                BentoCollectionLayout.COMPACT -> items(effectiveItems, key = LibraryDisplayItem::stableKey) { item ->
                    CompactIndexRow(
                        item = item,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selectedIds = selectedIds,
                        onItemClick = onItemClick,
                        onItemLongClick = onItemLongClick,
                        onCheckIn = onCheckIn,
                        swipeEnabled = swipeEnabled,
                        onSwipe = onSwipe,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CardFeedLayout(
    header: @Composable () -> Unit,
    emptyContent: (@Composable () -> Unit)?,
    items: List<LibraryDisplayItem>,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    spacing: Dp,
    onItemClick: (LibraryDisplayItem) -> Unit,
    onItemLongClick: (LibraryDisplayItem) -> Unit,
    onCheckIn: (AnimeEntity) -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val dragScrollState = rememberLazyListState()
    SeriesDragAutoScroll(dragScrollState)
    LazyColumn(
        state = dragScrollState,
        contentPadding = PaddingValues(
            start = (16 * densityScale).dp,
            top = (8 * densityScale).dp,
            end = (16 * densityScale).dp,
            bottom = maxOf(72.dp, LocalFloatingNavigationInset.current + 16.dp),
        ),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        item(key = "library-controls") { header() }
        if (emptyContent != null) {
            item(key = "library-empty") { emptyContent() }
        } else {
            items(items, key = LibraryDisplayItem::stableKey) { item ->
                when (item) {
                    is LibraryDisplayItem.AnimeItem -> LargeAnimeCard(
                        anime = item.anime,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selected = item.anime.id in selectedIds,
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                        onCheckIn = { onCheckIn(item.anime) },
                        swipeEnabled = swipeEnabled,
                        onSwipe = onSwipe,
                    )
                    is LibraryDisplayItem.SeriesItem -> LargeSeriesCard(
                        item = item,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selected = item.memberAnimeIds.isNotEmpty() && item.memberAnimeIds.all(selectedIds::contains),
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PosterGridLayout(
    header: @Composable () -> Unit,
    emptyContent: (@Composable () -> Unit)?,
    items: List<LibraryDisplayItem>,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    columns: Int,
    onColumnsChanged: (Int) -> Unit,
    spacing: Dp,
    onItemClick: (LibraryDisplayItem) -> Unit,
    onItemLongClick: (LibraryDisplayItem) -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val dragScrollState = rememberLazyGridState()
    SeriesDragAutoScroll(dragScrollState)
    LazyVerticalGrid(
        state = dragScrollState,
        columns = GridCells.Fixed(readableGridColumns(columns)),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(settings.gridColumns) {
                var zoomAccumulator = 1f
                detectTransformGestures(panZoomLock = true) { _, _, zoom, _ ->
                    if (!zoom.isFinite() || zoom <= 0f) return@detectTransformGestures
                    zoomAccumulator *= zoom
                    when {
                        zoomAccumulator >= 1.16f -> {
                            onColumnsChanged((settings.gridColumns - 1).coerceAtLeast(2))
                            zoomAccumulator = 1f
                        }
                        zoomAccumulator <= 0.86f -> {
                            onColumnsChanged((settings.gridColumns + 1).coerceAtMost(6))
                            zoomAccumulator = 1f
                        }
                    }
                }
            },
        contentPadding = PaddingValues(
            start = (12 * densityScale).dp,
            top = (8 * densityScale).dp,
            end = (12 * densityScale).dp,
            bottom = maxOf(72.dp, LocalFloatingNavigationInset.current + 16.dp),
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
        verticalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
    ) {
        item(key = "library-controls", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (emptyContent != null) {
            item(key = "library-empty", span = { GridItemSpan(maxLineSpan) }) { emptyContent() }
        } else {
            items(items, key = LibraryDisplayItem::stableKey) { item ->
                when (item) {
                    is LibraryDisplayItem.AnimeItem -> PosterAnimeCard(
                        anime = item.anime,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selected = item.anime.id in selectedIds,
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                        swipeEnabled = swipeEnabled,
                        onSwipe = onSwipe,
                    )
                    is LibraryDisplayItem.SeriesItem -> PosterSeriesCard(
                        item = item,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selected = item.memberAnimeIds.isNotEmpty() && item.memberAnimeIds.all(selectedIds::contains),
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                    )
                }
            }
        }
    }
}

internal data class TimelineGroup(
    val label: String,
    val sortValue: String,
    val items: List<LibraryDisplayItem>,
)

@Composable
internal fun TimelineLayout(
    header: @Composable () -> Unit,
    emptyContent: (@Composable () -> Unit)?,
    items: List<LibraryDisplayItem>,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    columns: Int,
    spacing: Dp,
    sort: LibrarySort,
    onItemClick: (LibraryDisplayItem) -> Unit,
    onItemLongClick: (LibraryDisplayItem) -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val groups = remember(items, trackerSettings.timelineGroupField, sort) {
        buildTimelineGroups(items, trackerSettings.timelineGroupField, sort)
    }
    val dragScrollState = rememberLazyGridState()
    SeriesDragAutoScroll(dragScrollState)
    LazyVerticalGrid(
        state = dragScrollState,
        columns = GridCells.Fixed(readableGridColumns(columns)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = (12 * densityScale).dp,
            top = (8 * densityScale).dp,
            end = (12 * densityScale).dp,
            bottom = maxOf(72.dp, LocalFloatingNavigationInset.current + 16.dp),
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
        verticalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
    ) {
        item(key = "library-controls", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (emptyContent != null) {
            item(key = "library-empty", span = { GridItemSpan(maxLineSpan) }) { emptyContent() }
        } else {
            groups.forEach { group ->
                stickyHeader(key = "timeline_header:${group.sortValue}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = (4 * densityScale).dp, vertical = (10 * densityScale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${group.items.size} 部",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(group.items, key = LibraryDisplayItem::stableKey) { item ->
                    when (item) {
                        is LibraryDisplayItem.AnimeItem -> PosterAnimeCard(
                            anime = item.anime,
                            settings = settings,
                            trackerSettings = trackerSettings,
                            selected = item.anime.id in selectedIds,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLongClick(item) },
                            swipeEnabled = swipeEnabled,
                            onSwipe = onSwipe,
                        )
                        is LibraryDisplayItem.SeriesItem -> PosterSeriesCard(
                            item = item,
                            settings = settings,
                            trackerSettings = trackerSettings,
                            selected = item.memberAnimeIds.isNotEmpty() && item.memberAnimeIds.all(selectedIds::contains),
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLongClick(item) },
                        )
                    }
                }
            }
        }
    }
}

internal fun buildTimelineGroups(
    items: List<LibraryDisplayItem>,
    groupField: com.animeow.app.ui.theme.TimelineGroupField,
    sort: LibrarySort,
): List<TimelineGroup> {
    val grouped = linkedMapOf<String, MutableList<LibraryDisplayItem>>()
    val unknown = mutableListOf<LibraryDisplayItem>()
    items.forEach { item ->
        val time = timelineTimeOf(item, groupField, sort)
        val key = yearMonthKey(time)
        if (key != null) {
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        } else {
            unknown.add(item)
        }
    }
    val result = grouped.entries
        .sortedByDescending { it.key }
        .map { (key, groupItems) -> TimelineGroup(yearMonthLabel(key), key, groupItems) }
        .toMutableList()
    if (unknown.isNotEmpty()) {
        result.add(TimelineGroup("未标注时间", "9999-99", unknown))
    }
    return result
}

internal fun LibraryDisplayItem.memberAnimeList(): List<AnimeEntity> = when (this) {
    is LibraryDisplayItem.AnimeItem -> listOf(anime)
    is LibraryDisplayItem.SeriesItem -> members
}

internal fun timelineTimeOf(item: LibraryDisplayItem, groupField: com.animeow.app.ui.theme.TimelineGroupField, sort: LibrarySort): String? {
    val members = item.memberAnimeList()
    val effectiveField = when (groupField) {
        com.animeow.app.ui.theme.TimelineGroupField.FOLLOW_SORT -> when (sort) {
            LibrarySort.AIR_DATE -> com.animeow.app.ui.theme.TimelineGroupField.AIR_DATE
            LibrarySort.FINISH_DATE -> com.animeow.app.ui.theme.TimelineGroupField.FINISH_DATE
            LibrarySort.RECENT -> com.animeow.app.ui.theme.TimelineGroupField.ADDED_DATE
            else -> com.animeow.app.ui.theme.TimelineGroupField.AIR_DATE
        }
        else -> groupField
    }
    return when (effectiveField) {
        com.animeow.app.ui.theme.TimelineGroupField.AIR_DATE ->
            members.mapNotNull(AnimeEntity::airDate).maxOrNull()
                ?: members.mapNotNull(AnimeEntity::createdAt).maxOrNull()
        com.animeow.app.ui.theme.TimelineGroupField.FINISH_DATE ->
            members.mapNotNull(AnimeEntity::watchFinishDate).maxOrNull()
                ?: members.mapNotNull(AnimeEntity::airDate).maxOrNull()
                ?: members.mapNotNull(AnimeEntity::createdAt).maxOrNull()
        com.animeow.app.ui.theme.TimelineGroupField.ADDED_DATE ->
            members.mapNotNull(AnimeEntity::createdAt).maxOrNull()
                ?: members.mapNotNull(AnimeEntity::airDate).maxOrNull()
        com.animeow.app.ui.theme.TimelineGroupField.WATCH_START_DATE ->
            members.mapNotNull(AnimeEntity::watchStartDate).maxOrNull()
                ?: members.mapNotNull(AnimeEntity::airDate).maxOrNull()
                ?: members.mapNotNull(AnimeEntity::createdAt).maxOrNull()
        else -> members.mapNotNull(AnimeEntity::airDate).maxOrNull()
            ?: members.mapNotNull(AnimeEntity::createdAt).maxOrNull()
    }
}

internal fun yearMonthKey(value: String?): String? {
    val text = value?.trim().orEmpty()
    val parsed = text.take(10).takeIf { it.length == 10 }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (parsed != null) {
        return "${parsed.year}-${parsed.monthValue.toString().padStart(2, '0')}"
    }
    val prefix = text.take(7)
    return if (prefix.length == 7 && prefix[4] == '-') prefix else null
}

internal fun yearMonthLabel(key: String): String {
    val parts = key.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull()
    return if (parts.size == 2 && month != null) "${parts[0]}年${month}月" else key
}

@Composable
internal fun CompactIndexLayout(
    header: @Composable () -> Unit,
    emptyContent: (@Composable () -> Unit)?,
    items: List<LibraryDisplayItem>,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    spacing: Dp,
    alphabeticIndexEnabled: Boolean,
    alphabeticIndexAscending: Boolean,
    onItemClick: (LibraryDisplayItem) -> Unit,
    onItemLongClick: (LibraryDisplayItem) -> Unit,
    onCheckIn: (AnimeEntity) -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val listState = rememberLazyListState()
    SeriesDragAutoScroll(listState)
    val coroutineScope = rememberCoroutineScope()
    val indexTransliterator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createCompactIndexTransliterator()
        } else {
            null
        }
    }
    val sections = remember(items, alphabeticIndexEnabled, alphabeticIndexAscending) {
        if (!alphabeticIndexEnabled) {
            emptyList()
        } else {
            items
                .groupBy { compactIndexLabel(it.title, indexTransliterator) }
                .map { (label, sectionItems) -> CompactIndexSection(label, sectionItems) }
                .sortedWith(
                    compareBy<CompactIndexSection> { it.label == "#" }
                        .thenBy { it.label }
                        .let { comparator -> if (alphabeticIndexAscending) comparator else comparator.reversedHashLast() },
                )
        }
    }
    val sectionStartIndices = remember(sections) {
        buildMap {
            var itemIndex = 1
            sections.forEach { section ->
                put(section.label, itemIndex)
                itemIndex += section.items.size + 1
            }
        }
    }
    val activeSection by remember(sections, sectionStartIndices, listState) {
        derivedStateOf {
            val firstVisible = listState.firstVisibleItemIndex
            sections.lastOrNull { (sectionStartIndices[it.label] ?: 0) <= firstVisible }?.label
                ?: sections.firstOrNull()?.label
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = (12 * densityScale).dp,
                top = (6 * densityScale).dp,
                end = if (alphabeticIndexEnabled) 40.dp else (12 * densityScale).dp,
                bottom = maxOf(72.dp, LocalFloatingNavigationInset.current + 16.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(7.dp)),
        ) {
            item(key = "library-controls") { header() }
            if (emptyContent != null) {
                item(key = "library-empty") { emptyContent() }
            } else {
                if (alphabeticIndexEnabled) {
                    sections.forEach { section ->
                        item(key = "compact-section:${section.label}") {
                            CompactSectionHeader(section.label)
                        }
                        items(section.items, key = LibraryDisplayItem::stableKey) { item ->
                            CompactIndexRow(
                                item = item,
                                settings = settings,
                                trackerSettings = trackerSettings,
                                selectedIds = selectedIds,
                                onItemClick = onItemClick,
                                onItemLongClick = onItemLongClick,
                                onCheckIn = onCheckIn,
                                swipeEnabled = swipeEnabled,
                                onSwipe = onSwipe,
                            )
                        }
                    }
                } else {
                    items(items, key = LibraryDisplayItem::stableKey) { item ->
                        CompactIndexRow(
                            item = item,
                            settings = settings,
                            trackerSettings = trackerSettings,
                            selectedIds = selectedIds,
                            onItemClick = onItemClick,
                            onItemLongClick = onItemLongClick,
                            onCheckIn = onCheckIn,
                            swipeEnabled = swipeEnabled,
                            onSwipe = onSwipe,
                        )
                    }
                }
            }
        }

        if (alphabeticIndexEnabled && sections.isNotEmpty()) {
            CompactAlphabetRail(
                labels = sections.map(CompactIndexSection::label),
                activeLabel = activeSection,
                hapticEnabled = settings.hapticFeedback,
                soundEnabled = settings.soundFeedback,
                onLabelSelected = { label ->
                    sectionStartIndices[label]?.let { target ->
                        coroutineScope.launch { listState.animateScrollToItem(target) }
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 5.dp),
            )
        }
    }
}

internal data class CompactIndexSection(
    val label: String,
    val items: List<LibraryDisplayItem>,
)

internal fun Comparator<CompactIndexSection>.reversedHashLast(): Comparator<CompactIndexSection> =
    Comparator { first, second ->
        when {
            first.label == "#" && second.label != "#" -> 1
            first.label != "#" && second.label == "#" -> -1
            else -> compare(second, first)
        }
    }

internal fun compactIndexLabel(title: String, transliterator: ((String) -> String)?): String {
    val trimmedTitle = title.trim()
    val normalized = (transliterator?.invoke(trimmedTitle) ?: trimmedTitle).uppercase(Locale.ROOT)
    val initial = normalized.firstOrNull(Char::isLetterOrDigit) ?: return "#"
    initial.takeIf { it in 'A'..'Z' }?.let { return it.toString() }
    return legacyHanInitial(trimmedTitle.firstOrNull(Char::isLetterOrDigit))?.toString() ?: "#"
}

@RequiresApi(Build.VERSION_CODES.Q)
internal fun createCompactIndexTransliterator(): (String) -> String {
    val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII; Upper")
    return transliterator::transliterate
}

internal fun legacyHanInitial(character: Char?): Char? {
    if (character == null || character.code !in 0x4E00..0x9FFF) return null
    val bytes = character.toString().toByteArray(LEGACY_GB2312)
    if (bytes.size < 2) return null
    val code = (bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF) - 65_536
    val index = LEGACY_PINYIN_BOUNDARIES.indexOfLast { code >= it }
    return LEGACY_PINYIN_INITIALS.getOrNull(index)
}

internal val LEGACY_GB2312: Charset = Charset.forName("GB2312")
internal val LEGACY_PINYIN_BOUNDARIES = intArrayOf(
    -20_319, -20_284, -19_776, -19_219, -18_711, -18_527, -18_240, -17_923,
    -17_418, -16_475, -16_213, -15_641, -15_166, -14_923, -14_915, -14_631,
    -14_150, -14_091, -13_319, -12_839, -12_557, -11_848, -11_056,
)
internal val LEGACY_PINYIN_INITIALS = charArrayOf(
    'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
    'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z',
)

@Composable
internal fun CompactSectionHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
internal fun CompactAlphabetRail(
    labels: List<String>,
    activeLabel: String?,
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
    onLabelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = com.animeow.app.ui.components.LocalActionFeedback.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .padding(horizontal = 3.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        labels.forEach { label ->
            val active = label == activeLabel
            Box(
                modifier = Modifier
                    .size(width = 24.dp, height = 20.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "跳转到 $label",
                    ) {
                        if (hapticEnabled || soundEnabled) feedback.selection()
                        onLabelSelected(label)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
internal fun CompactIndexRow(
    item: LibraryDisplayItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    onItemClick: (LibraryDisplayItem) -> Unit,
    onItemLongClick: (LibraryDisplayItem) -> Unit,
    onCheckIn: (AnimeEntity) -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    when (item) {
        is LibraryDisplayItem.AnimeItem -> CompactAnimeRow(
            anime = item.anime,
            settings = settings,
            trackerSettings = trackerSettings,
            selected = item.anime.id in selectedIds,
            onClick = { onItemClick(item) },
            onLongClick = { onItemLongClick(item) },
            onCheckIn = { onCheckIn(item.anime) },
            swipeEnabled = swipeEnabled,
            onSwipe = onSwipe,
        )
        is LibraryDisplayItem.SeriesItem -> CompactSeriesRow(
            item = item,
            settings = settings,
            trackerSettings = trackerSettings,
            selected = item.memberAnimeIds.isNotEmpty() && item.memberAnimeIds.all(selectedIds::contains),
            onClick = { onItemClick(item) },
            onLongClick = { onItemLongClick(item) },
        )
    }
}

@Composable
internal fun HomeBottomScopeToggle(
    value: HomeBottomScope,
    onSelected: (HomeBottomScope) -> Unit,
) {
    val next = if (value == HomeBottomScope.ANIME_ONLY) HomeBottomScope.ALL else HomeBottomScope.ANIME_ONLY
    AssistChip(
        onClick = { onSelected(next) },
        label = { Text(value.displayName) },
        leadingIcon = { Icon(AppIcons.Filter, contentDescription = null) },
    )
}

@Composable
internal fun BentoCollectionLayoutToggle(
    value: BentoCollectionLayout,
    onSelected: (BentoCollectionLayout) -> Unit,
) {
    val next = BentoCollectionLayout.entries[(value.ordinal + 1) % BentoCollectionLayout.entries.size]
    AssistChip(
        onClick = { onSelected(next) },
        label = { Text(value.displayName) },
        leadingIcon = { Icon(AppIcons.Grid, contentDescription = null) },
    )
}

@Composable
internal fun WatchingAnimeStrip(
    animes: List<AnimeEntity>,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    onClick: (AnimeEntity) -> Unit,
    onLongClick: (AnimeEntity) -> Unit,
    onCheckIn: (AnimeEntity) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val itemScale = settings.densityFor("tracker").itemScale
    if (settings.frontendMode == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("继续记录", "最近记录 · ${animes.size} 部")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(animes, key = AnimeEntity::id) { anime ->
                    Box(Modifier.width((304 * itemScale).dp)) {
                        ClassicAnimeListCard(anime, settings, trackerSettings, anime.id in selectedIds,
                            { onClick(anime) }, { onLongClick(anime) }, { onCheckIn(anime) }, false, { _, _ -> })
                    }
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy((10 * densityScale).dp)) {
        SectionHeader("正在追", "按最近操作排序 · ${animes.size} 部")
        LazyRow(horizontalArrangement = Arrangement.spacedBy((10 * densityScale).dp)) {
            items(animes, key = AnimeEntity::id) { anime ->
                val selected = anime.id in selectedIds
                ElevatedCard(
                    modifier = Modifier
                        .size(width = (252 * itemScale).dp, height = (132 * itemScale).dp)
                        .semantics { this.selected = selected }
                        .animatedPressClick(
                            onLongClick = { onLongClick(anime) },
                            onClick = { onClick(anime) },
                        ),
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
                        },
                    ),
                ) {
                    Box(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .width((84 * itemScale).dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            if (!anime.coverUrl.isNullOrBlank()) {
                                TrackerCoverImage(
                                    model = anime.coverUrl,
                                    contentDescription = anime.title,
                                    modifier = Modifier.fillMaxSize(),
                                    trackerSettings = trackerSettings,
                                )
                            } else {
                                Text(anime.title.take(2), Modifier.align(Alignment.Center), fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f).fillMaxSize().padding((11 * densityScale).dp),
                            verticalArrangement = Arrangement.spacedBy((6 * densityScale).dp),
                        ) {
                            if (settings.showTitle) {
                                Text(
                                    anime.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (settings.showProgress && anime.totalEpisodes > 0) {
                                LinearProgressIndicator(
                                    progress = { (anime.watchedEpisodes.toFloat() / anime.totalEpisodes).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height((5 * itemScale).dp).clip(CircleShape),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    if (settings.showProgress) progressLabel(anime) else anime.status,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (settings.showProgress) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        trackerStatusColor(anime.status)
                                    },
                                )
                                FilledTonalButton(
                                    onClick = { onCheckIn(anime) },
                                    enabled = anime.totalEpisodes <= 0 || anime.watchedEpisodes < anime.totalEpisodes,
                                    contentPadding = PaddingValues(
                                        horizontal = (11 * densityScale.coerceAtLeast(0.82f)).dp,
                                        vertical = (6 * densityScale.coerceAtLeast(0.82f)).dp,
                                    ),
                                ) { Text("+1") }
                            }
                        }
                    }
                    SelectionOverlay(selected)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomePosterStrip(
    title: String,
    subtitle: String,
    animes: List<AnimeEntity>,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    onClick: (AnimeEntity) -> Unit,
    onLongClick: (AnimeEntity) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val itemScale = settings.densityFor("tracker").itemScale
    Column(verticalArrangement = Arrangement.spacedBy((10 * densityScale).dp)) {
        SectionHeader(title, subtitle)
        LazyRow(horizontalArrangement = Arrangement.spacedBy((10 * densityScale).dp)) {
            items(animes, key = AnimeEntity::id) { anime ->
                val selected = anime.id in selectedIds
                ElevatedCard(
                    modifier = Modifier
                        .size(width = (108 * itemScale).dp, height = (170 * itemScale).dp)
                        .semantics { this.selected = selected }
                        .animatedPressClick(
                            onLongClick = { onLongClick(anime) },
                            onClick = { onClick(anime) },
                        ),
                    shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                        },
                    ),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (!anime.coverUrl.isNullOrBlank()) {
                            TrackerCoverImage(
                                model = anime.coverUrl,
                                contentDescription = anime.title,
                                modifier = Modifier.fillMaxSize(),
                                trackerSettings = trackerSettings,
                            )
                        } else {
                            Text(
                                anime.title.take(2),
                                modifier = Modifier.align(Alignment.Center),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        SelectionOverlay(selected)
                        if (settings.showTitle) {
                            Text(
                                anime.title,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.3f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity * 0.4f),
                                            1f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity),
                                        ),
                                    )
                                    .padding((8 * densityScale).dp),
                                color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
