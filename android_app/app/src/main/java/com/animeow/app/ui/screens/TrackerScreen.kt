package com.animeow.app.ui.screens

import com.animeow.app.ui.navigation.PageAction
import com.animeow.app.ui.navigation.PageActionRequests
import com.animeow.app.ui.navigation.HandlePageAction

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

import com.animeow.app.ui.icons.AppIcons

import com.animeow.app.ui.theme.densityFor

import com.animeow.app.ui.navigation.LocalFloatingNavigationInset

import android.icu.text.Transliterator
import android.os.Build
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription
import android.view.SoundEffectConstants

import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import com.animeow.app.ui.components.motionSlideContentSpec
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

internal val LocalTrackerStatusColors = staticCompositionLocalOf<Map<String, Long>> { emptyMap() }
internal val LocalSeriesQuickEdit = staticCompositionLocalOf<((LibraryDisplayItem.SeriesItem) -> Unit)?> { null }

@Composable
internal fun BoxScope.SelectionOverlay(
    selected: Boolean,
    checkAlignment: Alignment = Alignment.TopEnd,
) {
    if (selected) {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        )
        Icon(
            AppIcons.CheckCircle,
            contentDescription = null,
            modifier = Modifier.align(checkAlignment).padding(8.dp),
            tint = Color.White,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    collectionSubjectType: String? = null,
    modifier: Modifier = Modifier,
    settings: AppearanceSettings,
    pageActions: PageActionRequests? = null,
    onAnimeClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onHomeLayoutSelected: (HomeLayout) -> Unit,
    onDetailLayoutSelected: (DetailLayout) -> Unit,
    onContentDensitySelected: (ContentDensity) -> Unit,
    onMotionLevelSelected: (MotionLevel) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onCornerScaleChanged: (Float) -> Unit,
    onGridColumnsChanged: (Int) -> Unit,
    onCoverAspectRatioSelected: (CoverAspectRatio) -> Unit,
    onCoverTitlePositionSelected: (CoverTitlePosition) -> Unit,
    onShowTitleChanged: (Boolean) -> Unit,
    onShowStatusChanged: (Boolean) -> Unit,
    onShowRatingChanged: (Boolean) -> Unit,
    onShowProgressChanged: (Boolean) -> Unit,
    onCardInfoOrderChanged: (List<com.animeow.app.ui.theme.CardInfoField>) -> Unit = {},
    onRatingBadgeColorStyleSelected: (RatingBadgeColorStyle) -> Unit,
    onRatingBadgeCustomColorSelected: (Long) -> Unit,
    onCardSwipeActionsEnabledChanged: (Boolean) -> Unit,
    onSwipeStartActionSelected: (SwipeAction) -> Unit,
    onSwipeEndActionSelected: (SwipeAction) -> Unit,
    onAnimeEdit: (Long) -> Unit,
    onAutoCompleteStatusChanged: (Boolean) -> Unit,
    onCompletionStatusSelected: (String) -> Unit,
    onAddAnime: () -> Unit = {},
    onDiscoveryRequested: () -> Unit = {},
    onImportRequested: () -> Unit = {},
    onGlobalSearchRequested: () -> Unit = {},
    onSelectionModeChanged: (Boolean) -> Unit = {},
    viewModel: TrackerViewModel = viewModel(),
) {
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { viewModel.refreshHomeDate() }
    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    LaunchedEffect(collectionSubjectType, libraryState.isLoading) {
        if (!libraryState.isLoading && collectionSubjectType != null) viewModel.setSubjectType(collectionSubjectType)
    }
    val libraryItems = libraryState.items
    val isLoading = libraryState.isLoading
    val homeSections by viewModel.homeSections.collectAsStateWithLifecycle()
    val isDefaultMode by viewModel.isDefaultMode.collectAsStateWithLifecycle()
    val showHomeModules by viewModel.showHomeModules.collectAsStateWithLifecycle()
    val statusOptions by viewModel.statusOptions.collectAsStateWithLifecycle()
    val actionStatusOptions by viewModel.actionStatusOptions.collectAsStateWithLifecycle()
    val statusCounts by viewModel.statusCounts.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedStatus by viewModel.status.collectAsStateWithLifecycle()
    val selectedSort by viewModel.sort.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val trackerSettings by viewModel.trackerSettings.collectAsStateWithLifecycle()
    val bangumiSyncStatus by viewModel.bangumiSyncStatus.collectAsStateWithLifecycle()
    val bangumiSyncEnabled by viewModel.bangumiSyncEnabled.collectAsStateWithLifecycle()
    val bangumiShowHomeStatus by viewModel.bangumiShowHomeStatus.collectAsStateWithLifecycle()
    val selectedAnimeIds by viewModel.selectedAnimeIds.collectAsStateWithLifecycle()
    val statusColors by viewModel.statusColors.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val advancedFilters by viewModel.advanced.collectAsStateWithLifecycle()
    val availableYears by viewModel.availableYears.collectAsStateWithLifecycle()
    val batchMetadataState by viewModel.batchMetadataState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showSortSheet by remember { mutableStateOf(false) }
    var quickSeriesId by remember { mutableStateOf<Long?>(null) }
    var showCustomizationSheet by remember { mutableStateOf(false) }
    val customizationSheetListState = rememberLazyListState()
    var selectionMode by remember { mutableStateOf(false) }
    LaunchedEffect(selectionMode) { onSelectionModeChanged(selectionMode) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSelectionModeChanged(false) } }
    var showBatchStatusSheet by remember { mutableStateOf(false) }
    var batchProgressIds by remember { mutableStateOf<Set<Long>?>(null) }
    var showResetUnwatched by remember { mutableStateOf(false) }
    var showBangumiSyncStatus by remember { mutableStateOf(false) }
    var showBatchTagSheet by remember { mutableStateOf(false) }
    var batchTagError by remember { mutableStateOf<String?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchCoverSyncDialog by remember { mutableStateOf(false) }
    var showBatchMetadataDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var batchOperationBusy by remember { mutableStateOf(false) }
    val motionLevel = LocalMotionLevel.current
    val haptic = LocalHapticFeedback.current
    val feedbackView = LocalView.current
    val actionFeedback = com.animeow.app.ui.components.LocalActionFeedback.current
    val spacing = (10 * settings.densityFor("tracker").scale).dp
    val visibleAnimeIds = remember(libraryItems) { libraryItems.flatMap { it.memberAnimeIds }.toSet() }
    val allVisibleSelected = visibleAnimeIds.isNotEmpty() && visibleAnimeIds.all(selectedAnimeIds::contains)
    val visibleAnimes = remember(libraryItems) { libraryItems.flatMap { item ->
        when (item) {
            is LibraryDisplayItem.AnimeItem -> listOf(item.anime)
            is LibraryDisplayItem.SeriesItem -> item.members
        }
    }.distinctBy(AnimeEntity::id) }
    val pinnedAnimes = remember(visibleAnimes) { visibleAnimes.filter { it.pinnedAt != null }
        .sortedWith(compareByDescending<AnimeEntity> { it.pinnedAt }.thenByDescending { it.id }) }
    val allSelectedPinned = selectedAnimeIds.isNotEmpty() && selectedAnimeIds.all { id -> pinnedAnimes.any { it.id == id } }

    HandlePageAction(pageActions, PageAction.CUSTOMIZE_TRACKER) { showCustomizationSheet = true }

    LaunchedEffect(batchMetadataState.message) {
        if (batchMetadataState.message != null && selectedAnimeIds.isEmpty()) selectionMode = false
    }

    fun openItem(item: LibraryDisplayItem) {
        if (selectionMode) {
            viewModel.toggleSelection(item.memberAnimeIds)
        } else {
            when (item) {
                is LibraryDisplayItem.AnimeItem -> onAnimeClick(item.anime.id)
                is LibraryDisplayItem.SeriesItem -> onSeriesClick(item.series.id)
            }
        }
    }

    fun selectItem(item: LibraryDisplayItem) {
        selectionMode = true
        viewModel.toggleSelection(item.memberAnimeIds)
        actionFeedback.longPress()
    }

    fun <T> launchBatchOperation(
        fallbackMessage: String,
        operation: suspend () -> T,
        onSuccess: suspend (T) -> Unit,
    ) {
        if (batchOperationBusy) return
        batchOperationBusy = true
        coroutineScope.launch {
            try {
                onSuccess(operation())
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                if (showBatchTagSheet) batchTagError = error.message ?: fallbackMessage
                snackbarHostState.showSnackbar(error.message?.takeIf(String::isNotBlank) ?: fallbackMessage)
            } catch (error: IllegalStateException) {
                if (showBatchTagSheet) batchTagError = error.message ?: fallbackMessage
                snackbarHostState.showSnackbar(error.message?.takeIf(String::isNotBlank) ?: fallbackMessage)
            } catch (_: Exception) {
                if (showBatchTagSheet) batchTagError = fallbackMessage
                snackbarHostState.showSnackbar(fallbackMessage)
            } finally {
                batchOperationBusy = false
            }
        }
    }

    fun updatePinned(ids: Set<Long>, pinned: Boolean) {
        launchBatchOperation("置顶设置失败", { viewModel.setPinned(ids, pinned) }) { change ->
            selectionMode = false
            viewModel.clearSelection()
            // Keep subsequent pin actions available while the undo message is visible.
            coroutineScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    if (pinned) "已置顶 ${change.before.size} 部作品" else "已取消置顶 ${change.before.size} 部作品",
                    actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    try {
                        viewModel.restorePins(change)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        snackbarHostState.showSnackbar("暂时无法撤销置顶设置，请重试")
                    }
                }
            }
        }
    }

    fun checkIn(anime: AnimeEntity) {
        coroutineScope.launch {
            val change = viewModel.incrementProgress(
                animeId = anime.id,
                autoCompleteStatus = settings.autoCompleteStatus,
                completionStatus = settings.completionStatus,
            )
            if (change == null) {
                snackbarHostState.showSnackbar("《${anime.title}》已经达到总集数")
                return@launch
            }
            actionFeedback.confirm()
            val result = snackbarHostState.showSnackbar(
                message = "《${anime.title}》已记录第 ${change.current} 集",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreProgress(change)
            }
        }
    }

    fun performSwipe(anime: AnimeEntity, action: SwipeAction) {
        when (action) {
            SwipeAction.NONE -> Unit
            SwipeAction.INCREMENT -> checkIn(anime)
            SwipeAction.EDIT -> onAnimeEdit(anime.id)
            SwipeAction.CYCLE_STATUS -> coroutineScope.launch {
                val change = viewModel.cycleStatus(anime.id) ?: return@launch
                val result = snackbarHostState.showSnackbar(
                    message = "《${anime.title}》状态：${change.previous} → ${change.current}",
                    actionLabel = "撤销",
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.restoreStatus(change)
            }
            SwipeAction.TRASH -> coroutineScope.launch {
                try {
                    if (!viewModel.moveToTrash(anime.id)) {
                        snackbarHostState.showSnackbar("作品已被移动或不存在")
                        return@launch
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = "《${anime.title}》已移入回收站",
                        actionLabel = "撤销",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed && !viewModel.restoreFromTrash(anime.id)) {
                        snackbarHostState.showSnackbar("恢复失败，作品可能已被永久删除")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    snackbarHostState.showSnackbar("移动失败，请稍后重试")
                }
            }
        }
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        viewModel.clearSelection()
    }

    val emptyLibraryContent: (@Composable () -> Unit)? = if (isLoading || libraryItems.isEmpty()) {
        {
            if (isLoading) com.animeow.app.ui.components.LibraryLoadingSkeleton()
            else EmptyLibraryCard(
                hasFilters = query.isNotBlank() || selectedStatus != "全部" || advancedFilterCount(advancedFilters) > 0,
                onClearFilters = {
                    viewModel.setSearchQuery("")
                    viewModel.setStatus("全部")
                    viewModel.clearAdvancedFilters(collectionSubjectType ?: "all")
                },
                onAddAnime = onAddAnime,
                onDiscoveryRequested = onDiscoveryRequested,
                onImportRequested = onImportRequested,
            )
        }
    } else null

    val libraryHeader: @Composable () -> Unit = {
        Column {
            LibraryToolbar(
                query = query,
                currentSort = selectedSort,
                currentSortAscending = sortAscending,
                topSpacingScale = settings.topBarSpacing.scale,
                onQueryChanged = viewModel::setSearchQuery,
                placeholder = when (collectionSubjectType) { "anime" -> "搜索我的动画"; "book" -> "搜索我的书籍"; else -> "搜索动画与书籍" },
                onSortRequested = { showSortSheet = true },
                onFilterRequested = { showFilterSheet = true },
                activeFilterCount = advancedFilterCount(if (collectionSubjectType in listOf("anime", "book")) advancedFilters.copy(subjectType = "all") else advancedFilters),
                selectionMode = selectionMode,
                onSelectionRequested = {
                    selectionMode = !selectionMode
                    if (!selectionMode) viewModel.clearSelection()
                },
            )
            StatusFilters(
                options = statusOptions,
                selected = selectedStatus,
                onSelected = viewModel::setStatus,
                counts = statusCounts,
                showCount = trackerSettings.showStatusCount,
                topSpacingScale = settings.topBarSpacing.scale,
            )
            if (!selectionMode) bangumiSyncStatus?.let { status ->
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showBangumiSyncStatus = true }) {
                        Text("Bangumi · $status")
                    }
                    IconButton(
                        onClick = {
                            viewModel.setBangumiShowHomeStatus(false)
                            coroutineScope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "已隐藏首页 Bangumi 同步提示",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.setBangumiShowHomeStatus(true)
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            com.animeow.app.ui.icons.AppIcons.Close,
                            contentDescription = "关闭首页 Bangumi 同步提示",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (advancedFilters.tagIds.isNotEmpty() || advancedFilters.years.isNotEmpty()) {
                TextButton(onClick = { showFilterSheet = true }) {
                    Text("已筛选：${advancedFilters.tagIds.size} 个标签 · ${advancedFilters.years.size} 个年份", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!isLoading && !selectionMode) com.animeow.app.ui.tracker.PinnedAnimeShelf(
                pinnedAnimes, onAnimeClick, { updatePinned(setOf(it), false) }, showPinBadges = settings.showPinBadges)
            if (showHomeModules && !isLoading && !selectionMode) com.animeow.app.ui.tracker.HomeModulesPanel(homeSections, trackerSettings.copy(showRecentPlayback = trackerSettings.showRecentPlayback && advancedFilters.subjectType != "book"), onAnimeClick, onAnimeEdit, ::checkIn)

        }
    }

    val quickSeries = libraryItems.filterIsInstance<LibraryDisplayItem.SeriesItem>().firstOrNull { it.series.id == quickSeriesId }
    if (quickSeries != null) SeriesQuickEditSheet(quickSeries, onDismiss = { quickSeriesId = null },
        onEdit = { quickSeriesId = null; onAnimeEdit(it) }, onProgress = ::checkIn,
        onOpenSeries = { quickSeriesId = null; onSeriesClick(quickSeries.series.id) })
    com.animeow.app.ui.tracker.SeriesGroupingHost(viewModel::groupDraggedAnime, onGrouped = {
        selectionMode = false
        viewModel.clearSelection()
        coroutineScope.launch { snackbarHostState.showSnackbar("已归入系列") }
    }, targets = libraryItems) {
    CompositionLocalProvider(LocalTrackerStatusColors provides statusColors,
        LocalSeriesQuickEdit provides if (selectionMode || !settings.showSeriesEditButton) null else { item: LibraryDisplayItem.SeriesItem -> quickSeriesId = item.series.id }) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = settings.homeLayout,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    motionSlideContentSpec(motionLevel, forward = true, durationMillis = 260)
                },
                label = "home_layout_transition",
            ) { layout ->
                when (layout) {
                    HomeLayout.BENTO -> BentoLayout(
                        header = libraryHeader,
                        emptyContent = emptyLibraryContent,
                        items = libraryItems,
                        homeSections = homeSections,
                        isDefaultMode = isDefaultMode,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selectedIds = selectedAnimeIds,
                        spacing = spacing,
                        onItemClick = ::openItem,
                        onItemLongClick = ::selectItem,
                        onHomeBottomScopeSelected = viewModel::setHomeBottomScope,
                        onBentoCollectionLayoutSelected = viewModel::setBentoCollectionLayout,
                        onCheckIn = ::checkIn,
                        swipeEnabled = !selectionMode && settings.cardSwipeActionsEnabled,
                        onSwipe = ::performSwipe,
                    )
                    HomeLayout.CARD_FEED -> CardFeedLayout(
                        header = libraryHeader,
                        emptyContent = emptyLibraryContent,
                        items = libraryItems,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selectedIds = selectedAnimeIds,
                        spacing = spacing,
                        onItemClick = ::openItem,
                        onItemLongClick = ::selectItem,
                        onCheckIn = ::checkIn,
                        swipeEnabled = !selectionMode && settings.cardSwipeActionsEnabled,
                        onSwipe = ::performSwipe,
                    )
                    HomeLayout.POSTER_WALL -> PosterGridLayout(
                        header = libraryHeader,
                        emptyContent = emptyLibraryContent,
                        items = libraryItems,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selectedIds = selectedAnimeIds,
                        columns = settings.gridColumns,
                        onColumnsChanged = onGridColumnsChanged,
                        spacing = spacing,
                        onItemClick = ::openItem,
                        onItemLongClick = ::selectItem,
                        swipeEnabled = !selectionMode && settings.cardSwipeActionsEnabled,
                        onSwipe = ::performSwipe,
                    )
                    HomeLayout.COMPACT_INDEX -> CompactIndexLayout(
                        header = libraryHeader,
                        emptyContent = emptyLibraryContent,
                        items = libraryItems,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selectedIds = selectedAnimeIds,
                        spacing = spacing,
                        alphabeticIndexEnabled = selectedSort == LibrarySort.TITLE,
                        alphabeticIndexAscending = sortAscending,
                        onItemClick = ::openItem,
                        onItemLongClick = ::selectItem,
                        onCheckIn = ::checkIn,
                        swipeEnabled = !selectionMode && settings.cardSwipeActionsEnabled,
                        onSwipe = ::performSwipe,
                    )
                    HomeLayout.RECOMMEND_GRID -> PosterGridLayout(
                        header = libraryHeader,
                        emptyContent = emptyLibraryContent,
                        items = libraryItems,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selectedIds = selectedAnimeIds,
                        columns = settings.gridColumns.coerceIn(2, 6),
                        onColumnsChanged = onGridColumnsChanged,
                        spacing = spacing,
                        onItemClick = ::openItem,
                        onItemLongClick = ::selectItem,
                        swipeEnabled = !selectionMode && settings.cardSwipeActionsEnabled,
                        onSwipe = ::performSwipe,
                    )
                    HomeLayout.TIME_LINE -> TimelineLayout(
                        header = libraryHeader,
                        emptyContent = emptyLibraryContent,
                        items = libraryItems,
                        settings = settings,
                        trackerSettings = trackerSettings,
                        selectedIds = selectedAnimeIds,
                        columns = settings.gridColumns.coerceIn(2, 6),
                        spacing = spacing,
                        sort = selectedSort,
                        onItemClick = ::openItem,
                        onItemLongClick = ::selectItem,
                        swipeEnabled = !selectionMode && settings.cardSwipeActionsEnabled,
                        onSwipe = ::performSwipe,
                    )
                }

            }
            AnimatedVisibility(
                visible = selectionMode,
                enter = motionFadeIn(),
                exit = motionFadeOut(),
            ) {
                Column(Modifier.padding(bottom = LocalFloatingNavigationInset.current)) {
                SelectionActions(
                    selectedCount = selectedAnimeIds.size,
                    allVisibleSelected = allVisibleSelected,
                    canSelectAll = visibleAnimeIds.isNotEmpty(),
                    onToggleSelectAll = {
                        viewModel.setSelection(if (allVisibleSelected) emptySet() else visibleAnimeIds)
                    },
                    onCancel = {
                        selectionMode = false
                        viewModel.clearSelection()
                    },
                    onChangeStatus = { showBatchStatusSheet = true },
                    onEditProgress = if (collectionSubjectType == "book") null else {
                        { batchProgressIds = selectedAnimeIds }
                    },
                    onResetUnwatched = if (collectionSubjectType == "book") null else {
                        { showResetUnwatched = true }
                    },
                    allPinned = allSelectedPinned,
                    onTogglePinned = { updatePinned(selectedAnimeIds, !allSelectedPinned) },
                    onChangeTags = { batchTagError = null; showBatchTagSheet = true },
                    onAutoMatch = { showBatchMetadataDialog = true },
                    onSyncCovers = { showBatchCoverSyncDialog = true },
                    onDelete = { showBatchDeleteDialog = true },
                )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalFloatingNavigationInset.current + 12.dp),
        )
    }

    if (showSortSheet) {
        SortSheet(
            current = selectedSort,
            ascending = sortAscending,
            onSelected = viewModel::setSort,
            onToggleDirection = viewModel::toggleSortDirection,
            onReset = viewModel::resetSort,
            onDismissRequest = { showSortSheet = false },
        )
    }
    if (showCustomizationSheet) {
        HomeCustomizationSheet(
            settings = settings,
            trackerSettings = trackerSettings,
            onApplyPosterPreset = { preset ->
                launchBatchOperation("应用卡片预设失败", { viewModel.applyCollectionPosterPreset(preset) }) { }
            },
            onRestoreEntry = { appearance, tracker ->
                launchBatchOperation("恢复显示设置失败", { viewModel.restoreHomeCustomization(appearance, tracker) }) {
                    coroutineScope.launch { snackbarHostState.showSnackbar("已恢复打开前的显示设置") }
                }
            },
            listState = customizationSheetListState,
            onLayoutSelected = onHomeLayoutSelected,
            onDetailLayoutSelected = onDetailLayoutSelected,
            onDensitySelected = onContentDensitySelected,
            onMotionSelected = onMotionLevelSelected,
            onFontScaleChanged = onFontScaleChanged,
            onCornerScaleChanged = onCornerScaleChanged,
            onGridColumnsChanged = onGridColumnsChanged,
            onCoverAspectRatioSelected = onCoverAspectRatioSelected,
            onCoverTitlePositionSelected = onCoverTitlePositionSelected,
            onShowTitleChanged = onShowTitleChanged,
            onShowStatusChanged = onShowStatusChanged,
            onShowRatingChanged = onShowRatingChanged,
            onShowProgressChanged = onShowProgressChanged,
            onCardInfoOrderChanged = onCardInfoOrderChanged,
            onRatingBadgeColorStyleSelected = onRatingBadgeColorStyleSelected,
            onRatingBadgeCustomColorSelected = onRatingBadgeCustomColorSelected,
            onGroupSeriesChanged = viewModel::setGroupSeriesOnHome,
            onShowStandaloneBookshelfChanged = viewModel::setShowStandaloneBookshelf,
            onHomeBottomScopeSelected = viewModel::setHomeBottomScope,
            onBentoCollectionLayoutSelected = viewModel::setBentoCollectionLayout,
            onShowSubjectTypeChanged = viewModel::setShowSubjectType,
            onShowContinueWatchingChanged = viewModel::setShowContinueWatching,
            onShowRecentAddedChanged = viewModel::setShowRecentAdded,
            onHomeModuleVisible = viewModel::setHomeModuleVisible,
            onMoveHomeModule = viewModel::moveHomeModule,
            onShowSeriesCountChanged = viewModel::setShowSeriesCount,
            onShowUpdateWeekdayChanged = viewModel::setShowUpdateWeekday,
            onShowCoverStatusChanged = viewModel::setShowCoverStatus,
            onStatusBadgeBackgroundChanged = viewModel::setStatusBadgeBackground,
            onStatusBadgeColorChanged = viewModel::setStatusBadgeCustomColor,
            onShowCoverRatingChanged = viewModel::setShowCoverRating,
            onShowCoverProgressChanged = viewModel::setShowCoverProgress,
            onShowCoverSubjectTypeChanged = viewModel::setShowCoverSubjectType,
            onShowCoverSeriesCountChanged = viewModel::setShowCoverSeriesCount,
            onShowCoverUpdateWeekdayChanged = viewModel::setShowCoverUpdateWeekday,
            onAutoSyncNetworkCoversChanged = viewModel::setAutoSyncNetworkCovers,
            onInfoScaleChanged = viewModel::setInfoScale,
            onInfoTextOpacityChanged = viewModel::setInfoTextOpacity,
            onInfoDarkBackgroundOpacityChanged = viewModel::setInfoDarkBackgroundOpacity,
            onInfoLightBackgroundOpacityChanged = viewModel::setInfoLightBackgroundOpacity,
            onInfoCornerDpChanged = viewModel::setInfoCornerDp,
            onCoverCornerDpChanged = viewModel::setCoverCornerDp,
            onCoverImageOpacityChanged = viewModel::setCoverImageOpacity,
            onCoverSaturationChanged = viewModel::setCoverSaturation,
            onCoverFitModeSelected = viewModel::setCoverFitMode,
            onProgressPlacementSelected = viewModel::setProgressPlacement,
            onCoverProgressAlphaChanged = viewModel::setCoverProgressAlpha,
            onCardSwipeActionsEnabledChanged = onCardSwipeActionsEnabledChanged,
            onSwipeStartActionSelected = onSwipeStartActionSelected,
            onSwipeEndActionSelected = onSwipeEndActionSelected,
            statusOptions = statusOptions.filter { it != "全部" },
            onDefaultStartStatusSelected = viewModel::setDefaultStartStatus,
            onAutoCompleteStatusChanged = onAutoCompleteStatusChanged,
            onCompletionStatusSelected = onCompletionStatusSelected,
            bangumiSyncEnabled = bangumiSyncEnabled,
            bangumiShowHomeStatus = bangumiShowHomeStatus,
            onBangumiShowHomeStatusChanged = viewModel::setBangumiShowHomeStatus,
            onDismissRequest = { showCustomizationSheet = false },
        )
    }
    if (showBangumiSyncStatus) com.animeow.app.ui.cloud.BangumiSyncStatusDialog(onDismiss = { showBangumiSyncStatus = false })
    if (showFilterSheet) {
        AdvancedFilterSheet(
            filters = advancedFilters,
            tags = tags,
            years = availableYears,
            onSubjectTypeSelected = viewModel::setSubjectType,
            onTagToggled = viewModel::toggleTagFilter,
            onTagMatchAllChanged = viewModel::setTagMatchAll,
            onYearToggled = viewModel::toggleYear,
            onYearsCleared = viewModel::clearYears,
            onClear = { viewModel.clearAdvancedFilters(collectionSubjectType ?: "all") },
            showSubjectType = collectionSubjectType !in listOf("anime", "book"),
            currentSort = selectedSort,
            ascending = sortAscending,
            onSortSelected = viewModel::setSort,
            onToggleDirection = viewModel::toggleSortDirection,
            onDisplayRequested = { showFilterSheet = false; showCustomizationSheet = true },
            onManageRequested = { showFilterSheet = false; selectionMode = true },
            onGlobalSearchRequested = { showFilterSheet = false; onGlobalSearchRequested() },
            onDismissRequest = { showFilterSheet = false },
            filterPlans = { com.animeow.app.ui.tracker.TrackerFilterPlans(viewModel, !selectionMode, collectionSubjectType) },
        )
    }
    if (batchProgressIds != null || showResetUnwatched) {
        val ids = batchProgressIds
        BatchEpisodeProgressDialog(
            count = ids?.size, busy = batchOperationBusy,
            preview = { viewModel.previewBatchEpisodeProgress(ids, it) },
            onDismiss = { batchProgressIds = null; showResetUnwatched = false },
        ) { plan ->
            launchBatchOperation("批量修改集数失败", { viewModel.applyBatchEpisodeProgress(plan) }) { appliedAt ->
                batchProgressIds = null
                showResetUnwatched = false
                coroutineScope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar("已修改 ${plan.edits.size} 部动画的集数", actionLabel = "撤销", withDismissAction = true)
                    if (result == SnackbarResult.ActionPerformed) {
                        try {
                            val restored = viewModel.undoBatchEpisodeProgress(plan, appliedAt)
                            snackbarHostState.showSnackbar("已撤销 $restored 部，${plan.edits.size - restored} 部因后续变化保留现状")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            snackbarHostState.showSnackbar("撤销失败，请重试修改集数")
                        }
                    }
                }
            }
        }
    }
    if (showBatchStatusSheet) {
        BatchStatusSheet(
            options = actionStatusOptions,
            onSelected = { status ->
                launchBatchOperation(
                    fallbackMessage = "所选作品状态更新失败",
                    operation = { viewModel.updateSelectedStatus(status) },
                ) {
                    selectionMode = false
                    showBatchStatusSheet = false
                    snackbarHostState.showSnackbar("已更新所选作品状态")
                }
            },
            onDismissRequest = { showBatchStatusSheet = false },
        )
    }
    if (showBatchTagSheet) {
        com.animeow.app.ui.components.BatchTagSheet(
            tags = tags,
            selectedCount = selectedAnimeIds.size,
            busy = batchOperationBusy,
            errorMessage = batchTagError,
            onApply = { tagIds, remove, newTags ->
                batchTagError = null
                launchBatchOperation(
                    fallbackMessage = "批量修改标签失败，请重试",
                    operation = { viewModel.updateTagsForSelected(tagIds, remove, newTags) },
                ) {
                    selectionMode = false
                    showBatchTagSheet = false
                    snackbarHostState.showSnackbar(if (remove) "已从所选作品移除标签" else "已为所选作品添加标签")
                }
            },
            onDismissRequest = { showBatchTagSheet = false },
        )
    }
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("删除 ${selectedAnimeIds.size} 部作品？") },
            text = { Text("所选作品会移入回收站，之后可以恢复或永久删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        launchBatchOperation(
                            fallbackMessage = "所选作品移入回收站失败",
                            operation = viewModel::deleteSelected,
                        ) {
                            showBatchDeleteDialog = false
                            selectionMode = false
                            snackbarHostState.showSnackbar("所选作品已移入回收站")
                        }
                    },
                    enabled = selectedAnimeIds.isNotEmpty() && !batchOperationBusy,
                ) { Text(if (batchOperationBusy) "处理中…" else "删除") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") }
            },
        )
    }
    if (showBatchCoverSyncDialog) {
        AlertDialog(
            onDismissRequest = { showBatchCoverSyncDialog = false },
            title = { Text("匿名同步封面至公共资料库？") },
            text = {
                Text("会上传所选作品的标题和网络封面地址，帮助完善公共资料库。本地封面不会上传。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        launchBatchOperation(
                            fallbackMessage = "封面同步失败，请检查网络后重试",
                            operation = viewModel::syncSelectedCovers,
                        ) { result ->
                            showBatchCoverSyncDialog = false
                            selectionMode = false
                            snackbarHostState.showSnackbar(
                                "封面同步完成：成功 ${result.success}，跳过 ${result.skipped}，失败 ${result.failed}",
                            )
                        }
                    },
                    enabled = selectedAnimeIds.isNotEmpty() && !batchOperationBusy,
                ) { Text(if (batchOperationBusy) "同步中…" else "开始同步") }
            },
            dismissButton = { TextButton(onClick = { showBatchCoverSyncDialog = false }) { Text("取消") } },
        )
    }
    if (showBatchMetadataDialog) {
        BatchMetadataOptionsDialog(
            selectedCount = selectedAnimeIds.size,
            onStart = { options ->
                showBatchMetadataDialog = false
                viewModel.startBatchMetadataMatch(options)
            },
            onDismissRequest = { showBatchMetadataDialog = false },
        )
    }
    if (batchMetadataState.isRunning || batchMetadataState.message != null) {
        BatchMetadataProgressDialog(
            state = batchMetadataState,
            onSkip = viewModel::skipCurrentMetadataMatch,
            onStop = viewModel::cancelBatchMetadataMatch,
            onRetry = viewModel::retryBatchMetadataMatch,
            onClose = viewModel::clearBatchMetadataMessage,
        )
    }
    }
    }
}

@Composable
internal fun LibraryToolbar(
    query: String,
    currentSort: LibrarySort,
    currentSortAscending: Boolean,
    topSpacingScale: Float,
    onQueryChanged: (String) -> Unit,
    placeholder: String,
    onSortRequested: () -> Unit,
    onFilterRequested: () -> Unit,
    activeFilterCount: Int,
    selectionMode: Boolean,
    onSelectionRequested: () -> Unit,
) {
    if (com.animeow.app.ui.legacy.LocalFrontendMode.current == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
        com.animeow.app.ui.legacy.ClassicLibraryToolbar(query, onQueryChanged,
            "${currentSort.displayName} · ${if (currentSortAscending) "升序" else "降序"}", onSortRequested,
            activeFilterCount, onFilterRequested, selectionMode, onSelectionRequested)
        return
    }
    com.animeow.app.ui.components.BrowseSearchField(
        query, onQueryChanged, placeholder,
        modifier = Modifier.padding(vertical = (4 * topSpacingScale).coerceAtLeast(2f).dp),
        controls = {
            if (!selectionMode) com.animeow.app.ui.components.BrowseFilterButton(
                activeFilterCount, onFilterRequested, "筛选与显示")
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionActions(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    canSelectAll: Boolean,
    onToggleSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onChangeStatus: () -> Unit,
    onEditProgress: (() -> Unit)?,
    onResetUnwatched: (() -> Unit)?,
    allPinned: Boolean,
    onTogglePinned: () -> Unit,
    onChangeTags: () -> Unit,
    onAutoMatch: () -> Unit,
    onSyncCovers: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 $selectedCount 项",
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onToggleSelectAll, enabled = canSelectAll) {
                    Text(if (allVisibleSelected) "取消全选" else "全选")
                }
                IconButton(onClick = onCancel) {
                    Icon(AppIcons.Close, contentDescription = "退出多选")
                }
            }
            Text("长按后拖动作品，可组成新系列或移入已有系列。", modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SelectionActionButton(AppIcons.Pin, if (allPinned) "取消置顶" else "置顶", selectedCount > 0, onTogglePinned, Modifier.weight(1f))
                SelectionActionButton(AppIcons.Edit, "状态", selectedCount > 0, onChangeStatus, Modifier.weight(1f))
                SelectionActionButton(AppIcons.Tag, "标签", selectedCount > 0, onChangeTags, Modifier.weight(1f))
                Box(Modifier.weight(1f)) {
                    SelectionActionButton(AppIcons.MoreVertical, "更多", selectedCount > 0 || onResetUnwatched != null,
                        { showMore = true }, Modifier.fillMaxWidth())
                    androidx.compose.material3.DropdownMenu(showMore, { showMore = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("联网补全资料") }, enabled = selectedCount > 0,
                            onClick = { showMore = false; onAutoMatch() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("同步封面") }, enabled = selectedCount > 0,
                            onClick = { showMore = false; onSyncCovers() })
                        if (onEditProgress != null) androidx.compose.material3.DropdownMenuItem(
                            text = { Text("批量修改集数") }, enabled = selectedCount > 0,
                            onClick = { showMore = false; onEditProgress() },
                        )
                        if (onResetUnwatched != null) androidx.compose.material3.DropdownMenuItem(
                            text = { Text("全部想看集数归零") },
                            onClick = { showMore = false; onResetUnwatched() },
                        )
                    }
                }
                SelectionActionButton(AppIcons.Delete, "删除", selectedCount > 0, onDelete, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun SelectionActionButton(
    icon: com.animeow.app.ui.icons.IconGlyph,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun StatusFilters(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    counts: Map<String, Int> = emptyMap(),
    showCount: Boolean = true,
    topSpacingScale: Float = 1f,
) {
    if (com.animeow.app.ui.legacy.LocalFrontendMode.current == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
        com.animeow.app.ui.legacy.ClassicStatusTabs(options, selected, onSelected, if (showCount) counts else emptyMap())
        return
    }
    val statusListState = rememberLazyListState()
    LaunchedEffect(selected, options) {
        val index = options.indexOf(selected)
        if (index < 0) return@LaunchedEffect
        val layout = snapshotFlow { statusListState.layoutInfo }.first { it.totalItemsCount > 0 }
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null || item.offset < layout.viewportStartOffset || item.offset + item.size > layout.viewportEndOffset) {
            statusListState.animateScrollToItem(index)
        }
    }
    LazyRow(
        state = statusListState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = (2 * topSpacingScale).coerceAtLeast(1f).dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(options, key = { it }) { label ->
            val active = selected == label
            Surface(
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                modifier = Modifier.clip(CircleShape).semantics { this.selected = active }
                    .clickable(role = Role.Tab, onClick = { onSelected(label) }),
            ) {
                Row(
                    Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showCount && (counts[label] ?: 0) > 0) Text((counts[label] ?: 0).toString(), style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
            }
        }
    }
}

@Composable
internal fun ActiveFilterSummary(
    filters: AdvancedLibraryFilters,
    tags: List<TagEntity>,
    onClearSubjectType: () -> Unit,
    onRemoveYear: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
    onToggleTagMode: () -> Unit,
) {
    val tagNames = remember(tags) { tags.associate { it.id to it.name } }
    AnimatedVisibility(
        visible = advancedFilterCount(filters) > 0,
        enter = motionFadeIn(),
        exit = motionFadeOut(),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filters.subjectType != "all") {
                item("subject") {
                    RemovableFilterChip(
                        label = subjectTypeLabel(filters.subjectType),
                        onRemove = onClearSubjectType,
                    )
                }
            }
            items(filters.years.sorted(), key = { "year:$it" }) { year ->
                RemovableFilterChip(label = year, onRemove = { onRemoveYear(year) })
            }
            items(filters.tagIds.sorted(), key = { "tag:$it" }) { tagId ->
                RemovableFilterChip(
                    label = tagNames[tagId] ?: "标签 $tagId",
                    onRemove = { onRemoveTag(tagId) },
                )
            }
            if (filters.tagIds.size > 1) {
                item("tag-mode") {
                    InputChip(
                        selected = true,
                        onClick = onToggleTagMode,
                        label = { Text(if (filters.matchAllTags) "标签：全部满足" else "标签：任一满足") },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemovableFilterChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = {
            Icon(
                AppIcons.Close,
                contentDescription = "移除 $label 筛选",
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun EmptyLibraryCard(hasFilters: Boolean, onClearFilters: () -> Unit,
    onAddAnime: () -> Unit = {}, onDiscoveryRequested: () -> Unit = {}, onImportRequested: () -> Unit = {}) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 16.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasFilters) Icon(AppIcons.Search, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                else androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.animeow.app.R.drawable.brand_empty_library),
                    contentDescription = null, modifier = Modifier.size(88.dp),
                )
                Text(
                    text = if (hasFilters) "没有符合条件的作品" else "资料库还是空的",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (hasFilters) {
                        "试试其他关键词，或清除筛选查看全部作品。"
                    } else {
                        "从第一部喜欢的作品开始，记录你的观看时光。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (hasFilters) FilledTonalButton(onClick = onClearFilters) { Text("清除搜索与筛选") }
                else {
                    Button(onClick = onDiscoveryRequested, modifier = Modifier.fillMaxWidth()) { Text("去发现喜欢的作品") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        TextButton(onClick = onAddAnime) { Text("手动添加") }
                        TextButton(onClick = onImportRequested) { Text("导入已有资料") }
                    }
                }
            }
        }
    }
}
