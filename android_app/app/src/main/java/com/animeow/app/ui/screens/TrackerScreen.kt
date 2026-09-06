package com.animeow.app.ui.screens

import android.icu.text.Transliterator
import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Inventory2
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.abs
import kotlin.math.max

private val LocalTrackerStatusColors = staticCompositionLocalOf<Map<String, Long>> { emptyMap() }

@Composable
private fun BoxScope.SelectionOverlay(
    selected: Boolean,
    checkAlignment: Alignment = Alignment.TopEnd,
) {
    if (selected) {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        )
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = Modifier.align(checkAlignment).padding(8.dp),
            tint = Color.White,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    settings: AppearanceSettings,
    customizationRequest: Int = 0,
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
    onRatingBadgeColorStyleSelected: (RatingBadgeColorStyle) -> Unit,
    onRatingBadgeCustomColorSelected: (Long) -> Unit,
    onCardSwipeActionsEnabledChanged: (Boolean) -> Unit,
    onSwipeStartActionSelected: (SwipeAction) -> Unit,
    onSwipeEndActionSelected: (SwipeAction) -> Unit,
    onAnimeEdit: (Long) -> Unit,
    onAutoCompleteStatusChanged: (Boolean) -> Unit,
    onCompletionStatusSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrackerViewModel = viewModel(),
) {
    val libraryItems by viewModel.libraryItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val homeSections by viewModel.homeSections.collectAsStateWithLifecycle()
    val isDefaultMode by viewModel.isDefaultMode.collectAsStateWithLifecycle()
    val statusOptions by viewModel.statusOptions.collectAsStateWithLifecycle()
    val statusCounts by viewModel.statusCounts.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedStatus by viewModel.status.collectAsStateWithLifecycle()
    val selectedSort by viewModel.sort.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val trackerSettings by viewModel.trackerSettings.collectAsStateWithLifecycle()
    val selectedAnimeIds by viewModel.selectedAnimeIds.collectAsStateWithLifecycle()
    val statusColors by viewModel.statusColors.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val advancedFilters by viewModel.advanced.collectAsStateWithLifecycle()
    val availableYears by viewModel.availableYears.collectAsStateWithLifecycle()
    val batchMetadataState by viewModel.batchMetadataState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showSortSheet by remember { mutableStateOf(false) }
    var showCustomizationSheet by remember { mutableStateOf(false) }
    val customizationSheetListState = rememberLazyListState()
    var selectionMode by remember { mutableStateOf(false) }
    var showBatchStatusSheet by remember { mutableStateOf(false) }
    var showBatchTagSheet by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchCoverSyncDialog by remember { mutableStateOf(false) }
    var showBatchMetadataDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var batchOperationBusy by remember { mutableStateOf(false) }
    var handledCustomizationRequest by remember { mutableIntStateOf(customizationRequest) }
    val motionLevel = LocalMotionLevel.current
    val haptic = LocalHapticFeedback.current
    val feedbackView = LocalView.current
    val animationDuration = (280 * motionLevel.durationScale).toInt()
    val spacing = (10 * settings.contentDensity.scale).dp
    val visibleAnimeIds = remember(libraryItems) { libraryItems.flatMap { it.memberAnimeIds }.toSet() }
    val allVisibleSelected = visibleAnimeIds.isNotEmpty() && visibleAnimeIds.all(selectedAnimeIds::contains)

    LaunchedEffect(customizationRequest) {
        if (customizationRequest > handledCustomizationRequest) {
            handledCustomizationRequest = customizationRequest
            showCustomizationSheet = true
        }
    }

    LaunchedEffect(batchMetadataState.message) {
        batchMetadataState.message?.let { message ->
            selectionMode = false
            snackbarHostState.showSnackbar(message)
            viewModel.clearBatchMetadataMessage()
        }
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
        if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (settings.soundFeedback) feedbackView.playSoundEffect(SoundEffectConstants.CLICK)
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
                snackbarHostState.showSnackbar(error.message?.takeIf(String::isNotBlank) ?: fallbackMessage)
            } catch (error: IllegalStateException) {
                snackbarHostState.showSnackbar(error.message?.takeIf(String::isNotBlank) ?: fallbackMessage)
            } catch (_: Exception) {
                snackbarHostState.showSnackbar(fallbackMessage)
            } finally {
                batchOperationBusy = false
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

    CompositionLocalProvider(LocalTrackerStatusColors provides statusColors) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LibraryToolbar(
                query = query,
                currentSort = selectedSort,
                currentSortAscending = sortAscending,
                currentLayout = settings.homeLayout,
                topSpacingScale = settings.topBarSpacing.scale,
                onQueryChanged = viewModel::setSearchQuery,
                onSortRequested = { showSortSheet = true },
                onCustomizeRequested = { showCustomizationSheet = true },
                onFilterRequested = { showFilterSheet = true },
                activeFilterCount = advancedFilterCount(advancedFilters),
                selectionMode = selectionMode,
                onSelectionRequested = {
                    selectionMode = !selectionMode
                    if (!selectionMode) viewModel.clearSelection()
                },
            )
            AnimatedVisibility(
                visible = selectionMode,
                enter = motionFadeIn(),
                exit = motionFadeOut(),
            ) {
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
                    onChangeTags = { showBatchTagSheet = true },
                    onAutoMatch = { showBatchMetadataDialog = true },
                    onSyncCovers = { showBatchCoverSyncDialog = true },
                    onDelete = { showBatchDeleteDialog = true },
                )
            }
            StatusFilters(
                options = statusOptions,
                selected = selectedStatus,
                onSelected = viewModel::setStatus,
                counts = statusCounts,
                showCount = trackerSettings.showStatusCount,
                topSpacingScale = settings.topBarSpacing.scale,
            )
            ActiveFilterSummary(
                filters = advancedFilters,
                tags = tags,
                onClearSubjectType = { viewModel.setSubjectType("all") },
                onRemoveYear = viewModel::toggleYear,
                onRemoveTag = viewModel::toggleTagFilter,
                onToggleTagMode = { viewModel.setTagMatchAll(!advancedFilters.matchAllTags) },
            )

            AnimatedContent(
                targetState = settings.homeLayout,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    (fadeIn(tween(animationDuration)) +
                        slideInHorizontally(tween(animationDuration)) { it / 8 }) togetherWith
                        (fadeOut(tween(animationDuration)) +
                            slideOutHorizontally(tween(animationDuration)) { -it / 8 })
                },
                label = "home_layout_transition",
            ) { layout ->
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                } else if (libraryItems.isEmpty()) {
                    EmptyLibraryCard(
                        hasFilters = query.isNotBlank() || selectedStatus != "全部",
                    )
                } else {
                    when (layout) {
                        HomeLayout.BENTO -> BentoLayout(
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
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            onRatingBadgeColorStyleSelected = onRatingBadgeColorStyleSelected,
            onRatingBadgeCustomColorSelected = onRatingBadgeCustomColorSelected,
            onGroupSeriesChanged = viewModel::setGroupSeriesOnHome,
            onShowStandaloneBookshelfChanged = viewModel::setShowStandaloneBookshelf,
            onHomeBottomScopeSelected = viewModel::setHomeBottomScope,
            onBentoCollectionLayoutSelected = viewModel::setBentoCollectionLayout,
            onShowSubjectTypeChanged = viewModel::setShowSubjectType,
            onShowContinueWatchingChanged = viewModel::setShowContinueWatching,
            onShowRecentAddedChanged = viewModel::setShowRecentAdded,
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
            onCardSwipeActionsEnabledChanged = onCardSwipeActionsEnabledChanged,
            onSwipeStartActionSelected = onSwipeStartActionSelected,
            onSwipeEndActionSelected = onSwipeEndActionSelected,
            statusOptions = statusOptions.filter { it != "全部" },
            onDefaultStartStatusSelected = viewModel::setDefaultStartStatus,
            onAutoCompleteStatusChanged = onAutoCompleteStatusChanged,
            onCompletionStatusSelected = onCompletionStatusSelected,
            onDismissRequest = { showCustomizationSheet = false },
        )
    }
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
            onClear = viewModel::clearAdvancedFilters,
            onDismissRequest = { showFilterSheet = false },
        )
    }
    if (showBatchStatusSheet) {
        BatchStatusSheet(
            options = statusOptions.filter { it != "全部" },
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
        BatchTagSheet(
            tags = tags,
            onAdd = { tagId ->
                launchBatchOperation(
                    fallbackMessage = "批量添加标签失败",
                    operation = { viewModel.addTagToSelected(tagId) },
                ) {
                    selectionMode = false
                    showBatchTagSheet = false
                    snackbarHostState.showSnackbar("已为所选作品添加标签")
                }
            },
            onRemove = { tagId ->
                launchBatchOperation(
                    fallbackMessage = "批量移除标签失败",
                    operation = { viewModel.removeTagFromSelected(tagId) },
                ) {
                    selectionMode = false
                    showBatchTagSheet = false
                    snackbarHostState.showSnackbar("已从所选作品移除标签")
                }
            },
            onCreateAndAdd = { name, color ->
                launchBatchOperation(
                    fallbackMessage = "新建并批量添加标签失败",
                    operation = { viewModel.createTagAndAddToSelected(name, color) },
                ) {
                    selectionMode = false
                    showBatchTagSheet = false
                    snackbarHostState.showSnackbar("已新建标签并应用到所选作品")
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
                Text("会上传所选作品的标题和网络封面 URL，帮助完善服务器资料。文件或本地自定义封面会自动跳过。")
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
    if (batchMetadataState.isRunning) {
        BatchMetadataProgressDialog(
            state = batchMetadataState,
            onSkip = viewModel::skipCurrentMetadataMatch,
            onStop = viewModel::cancelBatchMetadataMatch,
        )
    }
    }
}

@Composable
private fun LibraryToolbar(
    query: String,
    currentSort: LibrarySort,
    currentSortAscending: Boolean,
    currentLayout: HomeLayout,
    topSpacingScale: Float,
    onQueryChanged: (String) -> Unit,
    onSortRequested: () -> Unit,
    onCustomizeRequested: () -> Unit,
    onFilterRequested: () -> Unit,
    activeFilterCount: Int,
    selectionMode: Boolean,
    onSelectionRequested: () -> Unit,
) {
    val bottomPad = (6 * topSpacingScale).coerceAtLeast(0.5f).dp
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy((8 * topSpacingScale).coerceAtLeast(2f).dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isNotBlank()) {
                {
                    IconButton(onClick = { onQueryChanged("") }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "清除搜索")
                    }
                }
            } else {
                null
            },
            placeholder = { Text("搜索标题、原始标题、系列或制作公司") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = onSortRequested,
                label = { Text("${currentSort.displayName} · ${if (currentSortAscending) "升序" else "降序"}") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
            )
            AssistChip(
                onClick = onCustomizeRequested,
                label = { Text(currentLayout.displayName) },
                leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
            )
            AssistChip(
                onClick = onFilterRequested,
                label = { Text(if (activeFilterCount > 0) "筛选 $activeFilterCount" else "筛选") },
                leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
            )
            AssistChip(
                onClick = onSelectionRequested,
                label = { Text(if (selectionMode) "退出多选" else "多选") },
                leadingIcon = {
                    Icon(
                        imageVector = if (selectionMode) Icons.Outlined.Close else Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun SelectionActions(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    canSelectAll: Boolean,
    onToggleSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onChangeStatus: () -> Unit,
    onChangeTags: () -> Unit,
    onAutoMatch: () -> Unit,
    onSyncCovers: () -> Unit,
    onDelete: () -> Unit,
) {
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
                    Icon(Icons.Outlined.Close, contentDescription = "退出多选")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionActionButton(Icons.Outlined.Edit, "状态", selectedCount > 0, onChangeStatus)
                SelectionActionButton(Icons.AutoMirrored.Outlined.Label, "标签", selectedCount > 0, onChangeTags)
                SelectionActionButton(Icons.Outlined.SyncAlt, "补全", selectedCount > 0, onAutoMatch)
                SelectionActionButton(Icons.Outlined.CloudUpload, "封面", selectedCount > 0, onSyncCovers)
                SelectionActionButton(Icons.Outlined.DeleteOutline, "删除", selectedCount > 0, onDelete)
            }
        }
    }
}

@Composable
private fun SelectionActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatusFilters(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    counts: Map<String, Int> = emptyMap(),
    showCount: Boolean = true,
    topSpacingScale: Float = 1f,
) {
    val verticalPad = (2 * topSpacingScale).coerceAtLeast(0f).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = verticalPad),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { label ->
            val statusColor = if (label == "全部") MaterialTheme.colorScheme.primary else trackerStatusColor(label)
            val count = counts[label] ?: 0
            FilterChip(
                selected = selected == label,
                onClick = { onSelected(label) },
                label = {
                    Text(
                        if (showCount && count > 0) "$label $count" else label,
                    )
                },
                leadingIcon = if (selected == label) {
                    {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, CircleShape),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ActiveFilterSummary(
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
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
private fun RemovableFilterChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移除 $label 筛选",
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun EmptyLibraryCard(hasFilters: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(
            modifier = Modifier.padding(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Inventory2,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (hasFilters) "没有符合条件的作品" else "资料库还是空的",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (hasFilters) {
                        "尝试清除搜索或切换观看状态。"
                    } else {
                        "点击右下角添加作品，或前往“我的”导入原版备份。"
                    },
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
            }
        }
    }
}

@Composable
private fun BentoLayout(
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
    val densityScale = settings.contentDensity.scale
    val effectiveItems = if (isDefaultMode && trackerSettings.homeBottomScope == HomeBottomScope.ANIME_ONLY) {
        items.filter { item ->
            item is LibraryDisplayItem.SeriesItem ||
                normalizeSubjectType((item as? LibraryDisplayItem.AnimeItem)?.anime?.subjectType) == "anime"
        }
    } else {
        items
    }
    LazyColumn(
        contentPadding = PaddingValues(
            start = (16 * densityScale).dp,
            top = (4 * densityScale).dp,
            end = (16 * densityScale).dp,
            bottom = 72.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (isDefaultMode && trackerSettings.showContinueWatching && homeSections.watching.isNotEmpty()) {
            item(key = "watching_strip") {
                WatchingAnimeStrip(
                    animes = homeSections.watching,
                    settings = settings,
                    trackerSettings = trackerSettings,
                    selectedIds = selectedIds,
                    onClick = { onItemClick(LibraryDisplayItem.AnimeItem(it)) },
                    onLongClick = { onItemLongClick(LibraryDisplayItem.AnimeItem(it)) },
                    onCheckIn = onCheckIn,
                )
            }
        }
        if (isDefaultMode && trackerSettings.showRecentAdded && homeSections.recentAdded.isNotEmpty()) {
            item(key = "recent_added_strip") {
                HomePosterStrip(
                    title = "最近添加",
                    subtitle = "按添加时间 · ${homeSections.recentAdded.size} 部",
                    animes = homeSections.recentAdded,
                    settings = settings,
                    trackerSettings = trackerSettings,
                    selectedIds = selectedIds,
                    onClick = { onItemClick(LibraryDisplayItem.AnimeItem(it)) },
                    onLongClick = { onItemLongClick(LibraryDisplayItem.AnimeItem(it)) },
                )
            }
        }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(
                    title = if (isDefaultMode && trackerSettings.homeBottomScope == HomeBottomScope.ANIME_ONLY) {
                        "全部动画"
                    } else {
                        "全部作品"
                    },
                    subtitle = "${effectiveItems.sumOf { it.memberAnimeIds.size }} 部",
                    modifier = Modifier.weight(1f),
                )
                if (isDefaultMode) {
                    HomeBottomScopeToggle(
                        value = trackerSettings.homeBottomScope,
                        onSelected = onHomeBottomScopeSelected,
                    )
                }
                BentoCollectionLayoutToggle(
                    value = trackerSettings.bentoCollectionLayout,
                    onSelected = onBentoCollectionLayoutSelected,
                )
            }
        }
        when (trackerSettings.bentoCollectionLayout) {
            BentoCollectionLayout.GRID -> {
                val columns = settings.gridColumns.coerceIn(2, 6)
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

@Composable
private fun CardFeedLayout(
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
    val densityScale = settings.contentDensity.scale
    LazyColumn(
        contentPadding = PaddingValues(
            start = (16 * densityScale).dp,
            top = (8 * densityScale).dp,
            end = (16 * densityScale).dp,
            bottom = 72.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
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

@Composable
private fun PosterGridLayout(
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
    val densityScale = settings.contentDensity.scale
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 6)),
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
            bottom = 72.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
        verticalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
    ) {
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

private data class TimelineGroup(
    val label: String,
    val sortValue: String,
    val items: List<LibraryDisplayItem>,
)

@Composable
private fun TimelineLayout(
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
    val densityScale = settings.contentDensity.scale
    val groups = remember(items, trackerSettings.timelineGroupField, sort) {
        buildTimelineGroups(items, trackerSettings.timelineGroupField, sort)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 6)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = (12 * densityScale).dp,
            top = (8 * densityScale).dp,
            end = (12 * densityScale).dp,
            bottom = 72.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
        verticalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(12.dp)),
    ) {
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

private fun buildTimelineGroups(
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

private fun LibraryDisplayItem.memberAnimeList(): List<AnimeEntity> = when (this) {
    is LibraryDisplayItem.AnimeItem -> listOf(anime)
    is LibraryDisplayItem.SeriesItem -> members
}

private fun timelineTimeOf(item: LibraryDisplayItem, groupField: com.animeow.app.ui.theme.TimelineGroupField, sort: LibrarySort): String? {
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

private fun yearMonthKey(value: String?): String? {
    val text = value?.trim().orEmpty()
    val parsed = text.take(10).takeIf { it.length == 10 }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (parsed != null) {
        return "${parsed.year}-${parsed.monthValue.toString().padStart(2, '0')}"
    }
    val prefix = text.take(7)
    return if (prefix.length == 7 && prefix[4] == '-') prefix else null
}

private fun yearMonthLabel(key: String): String {
    val parts = key.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull()
    return if (parts.size == 2 && month != null) "${parts[0]}年${month}月" else key
}

@Composable
private fun CompactIndexLayout(
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
    val densityScale = settings.contentDensity.scale
    val listState = rememberLazyListState()
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
            var itemIndex = 0
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
                bottom = 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.coerceAtMost(7.dp)),
        ) {
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

private data class CompactIndexSection(
    val label: String,
    val items: List<LibraryDisplayItem>,
)

private fun Comparator<CompactIndexSection>.reversedHashLast(): Comparator<CompactIndexSection> =
    Comparator { first, second ->
        when {
            first.label == "#" && second.label != "#" -> 1
            first.label != "#" && second.label == "#" -> -1
            else -> compare(second, first)
        }
    }

private fun compactIndexLabel(title: String, transliterator: ((String) -> String)?): String {
    val trimmedTitle = title.trim()
    val normalized = (transliterator?.invoke(trimmedTitle) ?: trimmedTitle).uppercase(Locale.ROOT)
    val initial = normalized.firstOrNull(Char::isLetterOrDigit) ?: return "#"
    initial.takeIf { it in 'A'..'Z' }?.let { return it.toString() }
    return legacyHanInitial(trimmedTitle.firstOrNull(Char::isLetterOrDigit))?.toString() ?: "#"
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun createCompactIndexTransliterator(): (String) -> String {
    val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII; Upper")
    return transliterator::transliterate
}

private fun legacyHanInitial(character: Char?): Char? {
    if (character == null || character.code !in 0x4E00..0x9FFF) return null
    val bytes = character.toString().toByteArray(LEGACY_GB2312)
    if (bytes.size < 2) return null
    val code = (bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF) - 65_536
    val index = LEGACY_PINYIN_BOUNDARIES.indexOfLast { code >= it }
    return LEGACY_PINYIN_INITIALS.getOrNull(index)
}

private val LEGACY_GB2312: Charset = Charset.forName("GB2312")
private val LEGACY_PINYIN_BOUNDARIES = intArrayOf(
    -20_319, -20_284, -19_776, -19_219, -18_711, -18_527, -18_240, -17_923,
    -17_418, -16_475, -16_213, -15_641, -15_166, -14_923, -14_915, -14_631,
    -14_150, -14_091, -13_319, -12_839, -12_557, -11_848, -11_056,
)
private val LEGACY_PINYIN_INITIALS = charArrayOf(
    'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
    'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z',
)

@Composable
private fun CompactSectionHeader(label: String) {
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
private fun CompactAlphabetRail(
    labels: List<String>,
    activeLabel: String?,
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
    onLabelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
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
                        if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (soundEnabled) view.playSoundEffect(SoundEffectConstants.CLICK)
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
private fun CompactIndexRow(
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
private fun HomeBottomScopeToggle(
    value: HomeBottomScope,
    onSelected: (HomeBottomScope) -> Unit,
) {
    val next = if (value == HomeBottomScope.ANIME_ONLY) HomeBottomScope.ALL else HomeBottomScope.ANIME_ONLY
    AssistChip(
        onClick = { onSelected(next) },
        label = { Text(value.displayName) },
        leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
    )
}

@Composable
private fun BentoCollectionLayoutToggle(
    value: BentoCollectionLayout,
    onSelected: (BentoCollectionLayout) -> Unit,
) {
    val next = BentoCollectionLayout.entries[(value.ordinal + 1) % BentoCollectionLayout.entries.size]
    AssistChip(
        onClick = { onSelected(next) },
        label = { Text(value.displayName) },
        leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
    )
}

@Composable
private fun WatchingAnimeStrip(
    animes: List<AnimeEntity>,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    onClick: (AnimeEntity) -> Unit,
    onLongClick: (AnimeEntity) -> Unit,
    onCheckIn: (AnimeEntity) -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    val itemScale = settings.contentDensity.itemScale
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
private fun HomePosterStrip(
    title: String,
    subtitle: String,
    animes: List<AnimeEntity>,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selectedIds: Set<Long>,
    onClick: (AnimeEntity) -> Unit,
    onLongClick: (AnimeEntity) -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    val itemScale = settings.contentDensity.itemScale
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

@Composable
private fun LibraryListItemCard(
    item: LibraryDisplayItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckIn: (AnimeEntity) -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    when (item) {
        is LibraryDisplayItem.AnimeItem -> AnimeListCard(
            anime = item.anime,
            settings = settings,
            trackerSettings = trackerSettings,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            onCheckIn = { onCheckIn(item.anime) },
            swipeEnabled = swipeEnabled,
            onSwipe = onSwipe,
        )
        is LibraryDisplayItem.SeriesItem -> SeriesListCard(
            item = item,
            settings = settings,
            trackerSettings = trackerSettings,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun SeriesStackedCover(
    item: LibraryDisplayItem.SeriesItem,
    trackerSettings: TrackerSettings,
    modifier: Modifier = Modifier,
    showCountPill: Boolean = true,
    pillPadding: Dp = 8.dp,
) {
    val cornerShape = RoundedCornerShape(trackerSettings.coverCornerDp.dp)
    val backgroundContainer = MaterialTheme.colorScheme.primaryContainer

    val memberCovers = item.members
        .mapNotNull { it.coverUrl }
        .filter { it.isNotBlank() }
        .distinct()
    val mainCover = item.coverUrl

    val covers = remember(item.members, mainCover) {
        buildList {
            if (!mainCover.isNullOrBlank()) add(mainCover)
            memberCovers.forEach { url ->
                if (url != mainCover && size < 3) add(url)
            }
        }
    }

    Box(modifier = modifier) {
        if (covers.size >= 3) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = 12f
                        translationY = -6f
                        scaleX = 0.91f
                        scaleY = 0.91f
                        alpha = 0.55f
                    }
                    .clip(cornerShape)
                    .background(backgroundContainer),
            ) {
                TrackerCoverImage(covers[2], item.title, Modifier.fillMaxSize(), trackerSettings)
            }
        }
        if (covers.size >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = 6f
                        translationY = -3f
                        scaleX = 0.95f
                        scaleY = 0.95f
                        alpha = 0.78f
                    }
                    .clip(cornerShape)
                    .background(backgroundContainer),
            ) {
                TrackerCoverImage(covers[1], item.title, Modifier.fillMaxSize(), trackerSettings)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cornerShape)
                .background(backgroundContainer),
        ) {
            if (!mainCover.isNullOrBlank()) {
                TrackerCoverImage(mainCover, item.title, Modifier.fillMaxSize(), trackerSettings)
            } else {
                Text(
                    item.title.take(2),
                    modifier = Modifier.align(Alignment.Center),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (showCountPill && trackerSettings.showCoverSeriesCount) {
            InfoPill(
                text = seriesCountLabel(item, trackerSettings),
                settings = trackerSettings,
                modifier = Modifier.align(Alignment.TopStart).padding(pillPadding),
            )
        }
    }
}

@Composable
private fun SeriesListCard(
    item: LibraryDisplayItem.SeriesItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    val itemScale = settings.contentDensity.itemScale
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .animatedPressClick(onLongClick = onLongClick, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box {
        Row(
            modifier = Modifier.padding((12 * densityScale).dp),
            horizontalArrangement = Arrangement.spacedBy((14 * densityScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeriesStackedCover(
                item = item,
                trackerSettings = trackerSettings,
                modifier = Modifier.size((68 * itemScale).dp, (92 * itemScale).dp),
                showCountPill = false,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((5 * densityScale).dp)) {
                if (settings.showTitle) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                SeriesMetadataLine(item, trackerSettings)
                item.series.description?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            InfoPill(seriesCountLabel(item, trackerSettings), trackerSettings)
        }
        SelectionOverlay(selected, Alignment.CenterEnd)
        }
    }
}

@Composable
private fun LargeSeriesCard(
    item: LibraryDisplayItem.SeriesItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    val itemScale = settings.contentDensity.itemScale
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .animatedPressClick(onLongClick = onLongClick, onClick = onClick),
        shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((210 * itemScale).dp),
            ) {
                SeriesStackedCover(
                    item = item,
                    trackerSettings = trackerSettings,
                    modifier = Modifier.fillMaxSize(),
                    showCountPill = true,
                    pillPadding = (10 * densityScale).dp,
                )
                SelectionOverlay(selected)
            }
            Column(
                Modifier.padding((18 * densityScale).dp),
                verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
            ) {
                if (settings.showTitle) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                SeriesMetadataLine(item, trackerSettings)
                item.series.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PosterSeriesCard(
    item: LibraryDisplayItem.SeriesItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    ElevatedCard(
        modifier = Modifier.semantics { this.selected = selected }
            .animatedPressClick(onLongClick = onLongClick, onClick = onClick),
        shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(settings.coverAspectRatio.ratio),
            ) {
                SeriesStackedCover(
                    item = item,
                    trackerSettings = trackerSettings,
                    modifier = Modifier.fillMaxSize(),
                    showCountPill = true,
                    pillPadding = (7 * densityScale).dp,
                )
                if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.OVERLAY) {
                    Text(
                        item.title,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.3f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity * 0.4f),
                                    1f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity),
                                ),
                            )
                            .padding(
                                horizontal = (9 * densityScale).dp,
                                vertical = (7 * densityScale).dp,
                            ),
                        color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SelectionOverlay(selected)
            }
            val hasBelowContent = (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW) ||
                trackerSettings.showCoverProgress ||
                trackerSettings.showCoverSubjectType ||
                (trackerSettings.showCoverUpdateWeekday && item.members.any { animeUpdateWeekday(it) != null })
            if (hasBelowContent) {
                Column(
                    Modifier
                        .padding(
                            horizontal = (8 * densityScale).dp,
                            vertical = (6 * densityScale).dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy((2 * densityScale).dp),
                ) {
                    if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW) {
                        Text(
                            item.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    SeriesMetadataLine(
                        item = item,
                        settings = trackerSettings,
                        showSubjectType = trackerSettings.showCoverSubjectType,
                        showUpdateWeekday = trackerSettings.showCoverUpdateWeekday,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSeriesRow(
    item: LibraryDisplayItem.SeriesItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    val itemScale = settings.contentDensity.itemScale
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .animatedPressClick(onLongClick = onLongClick, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box {
        Row(
            Modifier.padding(
                horizontal = (10 * densityScale).dp,
                vertical = (7 * densityScale).dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((10 * densityScale).dp),
        ) {
            SeriesStackedCover(
                item = item,
                trackerSettings = trackerSettings,
                modifier = Modifier.size((38 * itemScale).dp, (52 * itemScale).dp),
                showCountPill = false,
            )
            if (settings.showTitle) {
                Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            } else {
                Spacer(Modifier.weight(1f))
            }
            InfoPill(seriesCountLabel(item, trackerSettings), trackerSettings)
        }
        SelectionOverlay(selected, Alignment.CenterEnd)
        }
    }
}

@Composable
private fun AnimeListCard(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckIn: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    val itemScale = settings.contentDensity.itemScale
    SwipeActionContainer(
        anime = anime,
        startAction = settings.swipeStartAction,
        endAction = settings.swipeEndAction,
        enabled = swipeEnabled,
        hapticEnabled = settings.hapticFeedback,
        soundEnabled = settings.soundFeedback,
        onAction = onSwipe,
    ) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .animatedPressClick(onLongClick = onLongClick, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Box {
        Row(
            modifier = Modifier.padding((12 * densityScale).dp),
            horizontalArrangement = Arrangement.spacedBy((14 * densityScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimeCover(
                anime,
                width = (68 * itemScale).dp,
                height = (92 * itemScale).dp,
                trackerSettings = trackerSettings,
            )
            Column(modifier = Modifier.weight(1f)) {
                if (settings.showTitle) {
                    Text(
                        text = anime.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                AnimeMetadataLine(anime, trackerSettings)
                Spacer(modifier = Modifier.height((5 * densityScale).dp))
                if (settings.showStatus) {
                    val sColor = trackerStatusColor(anime.status)
                    WatchStatusBadge(anime.status, sColor, trackerSettings, filled = settings.statusChipFilled, style = MaterialTheme.typography.labelMedium)
                }
                if (settings.showProgress) {
                    Text(progressLabel(anime), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (settings.showRating && (anime.rating != null || !anime.ratingGrade.isNullOrBlank())) {
                    Text(
                        text = ratingLabel(anime, settings),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            FilledTonalButton(
                onClick = onCheckIn,
                enabled = anime.totalEpisodes <= 0 || anime.watchedEpisodes < anime.totalEpisodes,
                contentPadding = PaddingValues(
                    horizontal = (14 * densityScale.coerceAtLeast(0.82f)).dp,
                    vertical = (10 * densityScale.coerceAtLeast(0.82f)).dp,
                ),
            ) { Text("+1") }
        }
        SelectionOverlay(selected, Alignment.CenterEnd)
        }
    }
    }
}

@Composable
private fun LargeAnimeCard(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckIn: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    val itemScale = settings.contentDensity.itemScale
    SwipeActionContainer(
        anime = anime,
        startAction = settings.swipeStartAction,
        endAction = settings.swipeEndAction,
        enabled = swipeEnabled,
        hapticEnabled = settings.hapticFeedback,
        soundEnabled = settings.soundFeedback,
        onAction = onSwipe,
    ) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .animatedPressClick(onLongClick = onLongClick, onClick = onClick),
        shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((210 * itemScale).dp)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                if (!anime.coverUrl.isNullOrBlank()) {
                    TrackerCoverImage(
                        model = anime.coverUrl,
                        contentDescription = anime.title,
                        modifier = Modifier.fillMaxSize(),
                        trackerSettings = trackerSettings,
                    )
                }
                SelectionOverlay(selected)
            }
            Column(
                modifier = Modifier.padding((18 * densityScale).dp),
                verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
            ) {
                if (settings.showTitle) {
                    Text(anime.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                AnimeMetadataLine(anime, trackerSettings)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        if (settings.showStatus) {
                            val sColor = trackerStatusColor(anime.status)
                            WatchStatusBadge(anime.status, sColor, trackerSettings, filled = settings.statusChipFilled)
                        }
                        if (settings.showProgress) Text(progressLabel(anime), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (settings.showRating && hasAnimeRating(anime)) {
                            Text(
                                ratingLabel(anime, settings),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    FilledTonalButton(onClick = onCheckIn) { Text("+1 集") }
                }
            }
        }
    }
    }
}

@Composable
private fun PosterAnimeCard(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    SwipeActionContainer(
        anime = anime,
        startAction = settings.swipeStartAction,
        endAction = settings.swipeEndAction,
        enabled = swipeEnabled,
        hapticEnabled = settings.hapticFeedback,
        soundEnabled = settings.soundFeedback,
        onAction = onSwipe,
    ) {
    ElevatedCard(
        modifier = Modifier.semantics { this.selected = selected }
            .animatedPressClick(onLongClick = onLongClick, onClick = onClick),
        shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(settings.coverAspectRatio.ratio)
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
                    Text(
                        anime.title.take(2),
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
                PosterBadges(anime = anime, settings = settings, trackerSettings = trackerSettings)
                if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.OVERLAY) {
                    Text(
                        anime.title,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.3f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity * 0.4f),
                                    1f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity),
                                ),
                            )
                            .padding(
                                horizontal = (9 * densityScale).dp,
                                vertical = (7 * densityScale).dp,
                            ),
                        color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SelectionOverlay(selected)
            }
            val hasBelowContent = (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW) ||
                trackerSettings.showCoverProgress ||
                trackerSettings.showCoverSubjectType ||
                (trackerSettings.showCoverUpdateWeekday && animeUpdateWeekday(anime) != null)
            if (hasBelowContent) {
                Column(
                    modifier = Modifier
                        .padding(
                            horizontal = (8 * densityScale).dp,
                            vertical = (6 * densityScale).dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy((2 * densityScale).dp),
                ) {
                    if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW) {
                        Text(
                            anime.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (trackerSettings.showCoverProgress) {
                        Text(
                            progressLabel(anime),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimeMetadataLine(
                        anime = anime,
                        settings = trackerSettings,
                        showSubjectType = trackerSettings.showCoverSubjectType,
                        showUpdateWeekday = trackerSettings.showCoverUpdateWeekday,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun BoxScope.PosterBadges(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
) {
    val showRating = trackerSettings.showCoverRating && hasAnimeRating(anime)
    if (!trackerSettings.showCoverStatus && !showRating) return
    if (settings.coverBadgeStyle == CoverBadgeStyle.BOTTOM_BAR) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = trackerSettings.infoDarkBackgroundOpacity))
                .padding(
                    horizontal = (8 * trackerSettings.infoScale).dp,
                    vertical = (5 * trackerSettings.infoScale).dp,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (trackerSettings.showCoverStatus) {
                WatchStatusBadge(anime.status, trackerStatusColor(anime.status), trackerSettings)
            }
            if (showRating) {
                Text(
                    ratingLabel(anime, settings),
                    color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                    style = scaledInfoTextStyle(trackerSettings),
                )
            }
        }
        return
    }
    val padding = when (settings.coverBadgeStyle) {
        CoverBadgeStyle.EDGE -> 0.dp
        CoverBadgeStyle.CORNER -> 4.dp
        else -> 7.dp
    }
    val shape = RoundedCornerShape(trackerSettings.infoCornerDp.dp)
    if (trackerSettings.showCoverStatus) {
        WatchStatusBadge(anime.status, trackerStatusColor(anime.status), trackerSettings, Modifier.align(Alignment.TopStart).padding(padding))
    }
    if (showRating) {
        Text(
            text = ratingLabel(anime, settings),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(padding)
                .background(ratingBadgeBackground(settings.ratingBadgeColorStyle, settings.ratingBadgeCustomColor), shape)
                .padding(
                    horizontal = (7 * trackerSettings.infoScale).dp,
                    vertical = (3 * trackerSettings.infoScale).dp,
                ),
            style = scaledInfoTextStyle(trackerSettings).copy(fontWeight = FontWeight.Bold),
            color = ratingBadgeText(settings.ratingBadgeColorStyle),
        )
    }
}

@Composable
private fun ratingBadgeBackground(style: RatingBadgeColorStyle, customColor: Long): Color = when (style) {
    RatingBadgeColorStyle.ORANGE -> Color(0xFFFF6B00).copy(alpha = 0.9f)
    RatingBadgeColorStyle.BLACK_WHITE -> Color.Black.copy(alpha = 0.85f)
    RatingBadgeColorStyle.THEME_PRIMARY -> MaterialTheme.colorScheme.primary
    RatingBadgeColorStyle.TRANSPARENT_DARK -> Color.Black.copy(alpha = 0.5f)
    RatingBadgeColorStyle.CUSTOM -> Color(customColor)
}

@Composable
private fun ratingBadgeText(style: RatingBadgeColorStyle): Color = when (style) {
    RatingBadgeColorStyle.THEME_PRIMARY -> MaterialTheme.colorScheme.onPrimary
    else -> Color.White
}

private fun ratingLabel(anime: AnimeEntity, settings: AppearanceSettings): String =
    listOf(settings.ratingIconStyle.symbol, normalizeAnimeRatingGrade(anime.ratingGrade) ?: formatAnimeRating(anime.rating))
        .filter(String::isNotBlank)
        .joinToString(" ")

private fun hasAnimeRating(anime: AnimeEntity): Boolean =
    anime.rating != null || !anime.ratingGrade.isNullOrBlank()

@Composable
private fun trackerStatusColor(status: String): Color =
    LocalTrackerStatusColors.current[status]?.let(::Color) ?: when (status.trim()) {
        "在看" -> Color(0xFF2196F3)
        "看完" -> Color(0xFF4CAF50)
        "未看", "想看" -> Color(0xFFFF9800)
        "弃坑" -> Color(0xFFF44336)
        else -> MaterialTheme.colorScheme.primary
    }

@Composable
private fun CompactAnimeRow(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckIn: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.contentDensity.scale
    val itemScale = settings.contentDensity.itemScale
    SwipeActionContainer(
        anime = anime,
        startAction = settings.swipeStartAction,
        endAction = settings.swipeEndAction,
        enabled = swipeEnabled,
        hapticEnabled = settings.hapticFeedback,
        soundEnabled = settings.soundFeedback,
        onAction = onSwipe,
    ) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .animatedPressClick(onLongClick = onLongClick, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Box {
        Row(
            modifier = Modifier.padding(
                horizontal = (10 * densityScale).dp,
                vertical = (7 * densityScale).dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((10 * densityScale).dp),
        ) {
            AnimeCover(
                anime,
                width = (38 * itemScale).dp,
                height = (52 * itemScale).dp,
                trackerSettings = trackerSettings,
            )
            Column(modifier = Modifier.weight(1f)) {
                if (settings.showTitle) {
                    Text(
                        anime.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                }
                AnimeMetadataLine(anime, trackerSettings)
            }
            if (settings.showStatus || settings.showProgress || settings.showRating && hasAnimeRating(anime)) {
                Column(horizontalAlignment = Alignment.End) {
                    if (settings.showStatus) {
                        val sColor = trackerStatusColor(anime.status)
                        WatchStatusBadge(anime.status, sColor, trackerSettings, filled = settings.statusChipFilled)
                    }
                    if (settings.showRating && hasAnimeRating(anime)) {
                        Text(
                            ratingLabel(anime, settings),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (settings.showProgress) {
                        Text(
                            if (anime.totalEpisodes > 0) "${anime.watchedEpisodes}/${anime.totalEpisodes}" else anime.watchedEpisodes.toString(),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            IconButton(
                onClick = onCheckIn,
                modifier = Modifier.size((36 * itemScale.coerceAtLeast(1f)).dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "+1 集")
            }
        }
        SelectionOverlay(selected, Alignment.CenterEnd)
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActionContainer(
    anime: AnimeEntity,
    startAction: SwipeAction,
    endAction: SwipeAction,
    enabled: Boolean,
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
    onAction: (AnimeEntity, SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled || startAction == SwipeAction.NONE && endAction == SwipeAction.NONE) {
        content()
        return
    }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val commitGuard = remember { CardSwipeCommitGuard() }
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.78f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.requireDeliberateCardSwipe(commitGuard),
        enableDismissFromStartToEnd = startAction != SwipeAction.NONE,
        enableDismissFromEndToStart = endAction != SwipeAction.NONE,
        onDismiss = { dismissValue ->
            val shouldCommit = commitGuard.isArmed
            commitGuard.isArmed = false
            val action = when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            if (shouldCommit && action != SwipeAction.NONE) {
                if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (soundEnabled) view.playSoundEffect(SoundEffectConstants.CLICK)
                onAction(anime, action)
            }
            scope.launch {
                state.snapTo(SwipeToDismissBoxValue.Settled)
            }
        },
        backgroundContent = {
            val fromStart = state.targetValue == SwipeToDismissBoxValue.StartToEnd
            val action = if (fromStart) startAction else endAction
            val color = when (action) {
                SwipeAction.INCREMENT -> MaterialTheme.colorScheme.tertiary
                SwipeAction.CYCLE_STATUS -> MaterialTheme.colorScheme.primary
                SwipeAction.EDIT -> MaterialTheme.colorScheme.secondary
                SwipeAction.TRASH -> MaterialTheme.colorScheme.error
                SwipeAction.NONE -> MaterialTheme.colorScheme.surfaceVariant
            }
            val icon = when (action) {
                SwipeAction.INCREMENT -> Icons.Outlined.AddCircleOutline
                SwipeAction.CYCLE_STATUS -> Icons.Outlined.SyncAlt
                SwipeAction.EDIT -> Icons.Outlined.Edit
                SwipeAction.TRASH -> Icons.Outlined.DeleteOutline
                SwipeAction.NONE -> Icons.Outlined.Close
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(color.copy(alpha = 0.17f))
                    .padding(horizontal = 22.dp),
                horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = color)
                Spacer(Modifier.size(7.dp))
                Text(action.displayName, color = color, fontWeight = FontWeight.SemiBold)
            }
        },
        content = { content() },
    )
}

private class CardSwipeCommitGuard {
    var isArmed: Boolean = false
}

/**
 * A fast page-navigation flick must never be enough to mutate a library item.
 * Card actions are armed only after a long, strongly horizontal drag; velocity
 * alone cannot bypass this guard as it can with SwipeToDismissBox's fling logic.
 */
private fun Modifier.requireDeliberateCardSwipe(
    guard: CardSwipeCommitGuard,
): Modifier = pointerInput(guard) {
    val minimumCommitDistance = 160.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        guard.isArmed = false
        var totalX = 0f
        var totalY = 0f
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.position - change.previousPosition
            totalX += delta.x
            totalY += delta.y
            val requiredDistance = max(size.width * 0.78f, minimumCommitDistance)
            if (
                abs(totalX) >= requiredDistance &&
                abs(totalX) > abs(totalY) * 1.5f
            ) {
                guard.isArmed = true
            }
            pressed = change.pressed
        }
    }
}

@Composable
private fun AnimeCover(
    anime: AnimeEntity,
    width: Dp,
    height: Dp,
    trackerSettings: TrackerSettings,
) {
    LibraryCover(anime.title, anime.coverUrl, width, height, trackerSettings)
}

@Composable
private fun LibraryCover(
    title: String,
    coverUrl: String?,
    width: Dp,
    height: Dp,
    trackerSettings: TrackerSettings,
) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(trackerSettings.coverCornerDp.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (!coverUrl.isNullOrBlank()) {
            TrackerCoverImage(
                model = coverUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                trackerSettings = trackerSettings,
            )
        } else {
            Text(
                text = title.take(2),
                modifier = Modifier.padding(6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TrackerCoverImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier,
    trackerSettings: TrackerSettings,
) {
    val colorFilter = remember(trackerSettings.coverSaturation) {
        ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(trackerSettings.coverSaturation) },
        )
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { alpha = trackerSettings.coverImageOpacity },
        contentScale = when (trackerSettings.coverFitMode) {
            CoverFitMode.CROP -> ContentScale.Crop
            CoverFitMode.FIT -> ContentScale.Fit
            CoverFitMode.STRETCH -> ContentScale.FillBounds
        },
        colorFilter = colorFilter,
    )
}

@Composable
private fun InfoPill(
    text: String,
    settings: TrackerSettings,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = settings.infoLightBackgroundOpacity),
                RoundedCornerShape(settings.infoCornerDp.dp),
            )
            .padding(
                horizontal = (7 * settings.infoScale).dp,
                vertical = (3 * settings.infoScale).dp,
            ),
        style = scaledInfoTextStyle(settings),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = settings.infoTextOpacity),
        maxLines = 1,
    )
}

@Composable
private fun AnimeMetadataLine(
    anime: AnimeEntity,
    settings: TrackerSettings,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showSubjectType: Boolean = settings.showSubjectType,
    showUpdateWeekday: Boolean = settings.showUpdateWeekday,
) {
    val labels = buildList {
        if (showSubjectType) add(subjectTypeLabel(anime.subjectType))
        if (showUpdateWeekday) animeUpdateWeekday(anime)?.let { add(weekdayLabel(it)) }
    }
    if (labels.isNotEmpty()) {
        Text(
            labels.joinToString(" · "),
            color = color,
            style = scaledInfoTextStyle(settings),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SeriesMetadataLine(
    item: LibraryDisplayItem.SeriesItem,
    settings: TrackerSettings,
    showSubjectType: Boolean = settings.showSubjectType,
    showUpdateWeekday: Boolean = settings.showUpdateWeekday,
) {
    val labels = buildList {
        if (showSubjectType) {
            item.members.map { subjectTypeLabel(it.subjectType) }.distinct().takeIf(List<String>::isNotEmpty)
                ?.let { add(it.joinToString("/")) }
        }
        if (showUpdateWeekday) {
            item.members.mapNotNull(::animeUpdateWeekday).distinct().sorted()
                .takeIf(List<Int>::isNotEmpty)
                ?.let { add(it.joinToString("/") { day -> weekdayLabel(day) }) }
        }
        if (item.members.isEmpty()) add("暂无成员")
    }
    if (labels.isNotEmpty()) {
        Text(
            labels.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = scaledInfoTextStyle(settings),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun scaledInfoTextStyle(settings: TrackerSettings) =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize * settings.infoScale,
        lineHeight = MaterialTheme.typography.labelSmall.lineHeight * settings.infoScale,
    )

private fun seriesCountLabel(item: LibraryDisplayItem.SeriesItem, settings: TrackerSettings): String =
    if (settings.showSeriesCount) "系列 · ${item.members.size} 部" else "系列"

private fun subjectTypeLabel(value: String): String = when (normalizeSubjectType(value)) {
    "book" -> "书籍"
    else -> "动画"
}

private fun weekdayLabel(day: Int): String = when (day) {
    1 -> "周一更新"
    2 -> "周二更新"
    3 -> "周三更新"
    4 -> "周四更新"
    5 -> "周五更新"
    6 -> "周六更新"
    7, 0 -> "周日更新"
    else -> "每周更新"
}

// Explicit broadcast schedules take precedence; older records keep their reminder/date fallback.
private fun animeUpdateWeekday(anime: AnimeEntity): Int? =
    anime.broadcastDay?.takeIf { it in 1..7 } ?: anime.reminderDay ?: anime.airDate.toAirDateWeekdayOrNull()

private fun String?.toAirDateWeekdayOrNull(): Int? {
    val normalized = this?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
    val date = runCatching { LocalDate.parse(normalized) }.getOrNull() ?: return null
    return date.dayOfWeek.value
}

@Composable
private fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BatchMetadataOptionsDialog(
    selectedCount: Int,
    onStart: (BatchMetadataOptions) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var fields by remember { mutableStateOf(RemoteMetadataFields()) }
    var overwriteExisting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("批量联网补全") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "将逐部匹配 $selectedCount 部作品。默认只补空白字段，匹配期间可跳过当前作品或停止。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetadataFieldToggle("封面", fields.cover) { fields = fields.copy(cover = it) }
                MetadataFieldToggle("评分", fields.rating) { fields = fields.copy(rating = it) }
                MetadataFieldToggle("集数配置", fields.episodes) { fields = fields.copy(episodes = it) }
                MetadataFieldToggle("制作公司", fields.studio) { fields = fields.copy(studio = it) }
                MetadataFieldToggle("简介", fields.synopsis) { fields = fields.copy(synopsis = it) }
                MetadataFieldToggle("放送日期", fields.airDate) { fields = fields.copy(airDate = it) }
                MetadataFieldToggle("外部来源与原始标题", fields.externalLink) {
                    fields = fields.copy(externalLink = it)
                }
                MetadataFieldToggle("标签（从云端匹配并同步）", fields.tags) {
                    fields = fields.copy(tags = it)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = overwriteExisting,
                        role = Role.Checkbox,
                        onValueChange = { overwriteExisting = it },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = overwriteExisting, onCheckedChange = null)
                    Column {
                        Text("覆盖已有字段")
                        Text(
                            "开启后，联网结果会替换所选字段中的现有值。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(BatchMetadataOptions(fields, overwriteExisting)) },
                enabled = selectedCount > 0 && fields.hasSelection,
            ) { Text("开始匹配") }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text("取消") } },
    )
}

@Composable
private fun MetadataFieldToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(title)
    }
}

@Composable
private fun BatchMetadataProgressDialog(
    state: BatchMetadataState,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("正在自动匹配") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    state.currentTitle?.let { "正在搜索：$it" } ?: "正在准备资料源…",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${state.completed} / ${state.total} · 更新 ${state.updated} · 未匹配 ${state.unmatched} · 失败 ${state.failed}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSkip) { Text("跳过当前") } },
        dismissButton = { TextButton(onClick = onStop) { Text("停止") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    current: LibrarySort,
    ascending: Boolean,
    onSelected: (LibrarySort) -> Unit,
    onToggleDirection: () -> Unit,
    onReset: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismissRequest, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("排序方式", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "再次点击当前项目即可切换升降序",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onReset) { Text("恢复默认") }
                }
            }
            items(LibrarySort.entries, key = LibrarySort::name) { option ->
                ListItem(
                    headlineContent = { Text(option.displayName) },
                    supportingContent = if (current == option) {
                        { Text(if (ascending) "当前：升序" else "当前：降序") }
                    } else null,
                    trailingContent = {
                        RadioButton(selected = current == option, onClick = null)
                    },
                    modifier = Modifier.clickable(
                        role = Role.RadioButton,
                        onClick = {
                            if (current == option) onToggleDirection() else onSelected(option)
                        },
                    ),
                )
            }
            item {
                FilledTonalButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                ) { Text("完成") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchStatusSheet(
    options: List<String>,
    onSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismissRequest, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item {
                Text(
                    "批量修改状态",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            items(options.ifEmpty { listOf("在看", "看完", "未看", "弃坑") }, key = { it }) { status ->
                ListItem(
                    headlineContent = { Text(status) },
                    modifier = Modifier.clickable { onSelected(status) },
                )
            }
        }
    }
}

private enum class BatchTagMode(val label: String) {
    ADD("添加"),
    REMOVE("移除"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchTagSheet(
    tags: List<TagEntity>,
    onAdd: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onCreateAndAdd: (String, Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var mode by remember { mutableStateOf(BatchTagMode.ADD) }
    var showNewTagDialog by remember { mutableStateOf(false) }
    StableModalBottomSheet(onDismissRequest = onDismissRequest, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("批量标签", style = MaterialTheme.typography.headlineSmall) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatchTagMode.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { mode = option },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
            if (mode == BatchTagMode.ADD) {
                item {
                    FilledTonalButton(
                        onClick = { showNewTagDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("新建标签并应用")
                    }
                }
            }
            if (tags.isEmpty()) {
                item {
                    Text(
                        "还没有自定义标签，请先在“我的 → 状态、标签与系列”中创建。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            } else {
                items(tags, key = TagEntity::id) { tag ->
                    ListItem(
                        headlineContent = { Text(tag.name) },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
                        modifier = Modifier.clickable {
                            if (mode == BatchTagMode.ADD) onAdd(tag.id) else onRemove(tag.id)
                        },
                    )
                }
            }
        }
    }
    if (showNewTagDialog) {
        NewTagDialog(
            onConfirm = { name, color ->
                showNewTagDialog = false
                onCreateAndAdd(name, color)
            },
            onDismiss = { showNewTagDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFilterSheet(
    filters: AdvancedLibraryFilters,
    tags: List<TagEntity>,
    years: List<String>,
    onSubjectTypeSelected: (String) -> Unit,
    onTagToggled: (Long) -> Unit,
    onTagMatchAllChanged: (Boolean) -> Unit,
    onYearToggled: (String) -> Unit,
    onYearsCleared: () -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismissRequest, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("组合筛选", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("清除") }
                    FilledTonalButton(onClick = onDismissRequest) { Text("完成") }
                }
            }
            item {
                SettingLabel("作品类型")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SUBJECT_TYPES) { (key, label) ->
                        FilterChip(
                            selected = filters.subjectType == key,
                            onClick = { onSubjectTypeSelected(key) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            if (years.isNotEmpty()) {
                item {
                    SettingLabel("放送年份")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = filters.years.isEmpty(),
                                onClick = onYearsCleared,
                                label = { Text("全部") },
                            )
                        }
                        items(years) { year ->
                            FilterChip(
                                selected = year in filters.years,
                                onClick = { onYearToggled(year) },
                                label = { Text(year) },
                            )
                        }
                    }
                }
            }
            if (tags.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingLabel("标签")
                        Spacer(Modifier.weight(1f))
                        Text(if (filters.matchAllTags) "同时包含" else "包含任一")
                        Switch(checked = filters.matchAllTags, onCheckedChange = onTagMatchAllChanged)
                    }
                }
                items(tags, key = TagEntity::id) { tag ->
                    FilterChip(
                        selected = tag.id in filters.tagIds,
                        onClick = { onTagToggled(tag.id) },
                        label = { Text(tag.name) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
                    )
                }
            }
        }
    }
}

private fun advancedFilterCount(filters: AdvancedLibraryFilters): Int =
    (if (filters.subjectType != "all") 1 else 0) +
        filters.tagIds.size +
        (if (filters.years.isNotEmpty()) 1 else 0)

private val SUBJECT_TYPES = listOf(
    "all" to "全部",
    "anime" to "动画",
    "book" to "书籍",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeCustomizationSheet(
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    listState: LazyListState,
    onLayoutSelected: (HomeLayout) -> Unit,
    onDetailLayoutSelected: (DetailLayout) -> Unit,
    onDensitySelected: (ContentDensity) -> Unit,
    onMotionSelected: (MotionLevel) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onCornerScaleChanged: (Float) -> Unit,
    onGridColumnsChanged: (Int) -> Unit,
    onCoverAspectRatioSelected: (CoverAspectRatio) -> Unit,
    onCoverTitlePositionSelected: (CoverTitlePosition) -> Unit,
    onShowTitleChanged: (Boolean) -> Unit,
    onShowStatusChanged: (Boolean) -> Unit,
    onShowRatingChanged: (Boolean) -> Unit,
    onShowProgressChanged: (Boolean) -> Unit,
    onRatingBadgeColorStyleSelected: (RatingBadgeColorStyle) -> Unit,
    onRatingBadgeCustomColorSelected: (Long) -> Unit,
    onGroupSeriesChanged: (Boolean) -> Unit,
    onShowStandaloneBookshelfChanged: (Boolean) -> Unit,
    onHomeBottomScopeSelected: (HomeBottomScope) -> Unit,
    onBentoCollectionLayoutSelected: (BentoCollectionLayout) -> Unit,
    onShowSubjectTypeChanged: (Boolean) -> Unit,
    onShowContinueWatchingChanged: (Boolean) -> Unit,
    onShowRecentAddedChanged: (Boolean) -> Unit,
    onShowSeriesCountChanged: (Boolean) -> Unit,
    onShowUpdateWeekdayChanged: (Boolean) -> Unit,
    onShowCoverStatusChanged: (Boolean) -> Unit,
    onStatusBadgeBackgroundChanged: (StatusBadgeBackground) -> Unit,
    onStatusBadgeColorChanged: (Long) -> Unit,
    onShowCoverRatingChanged: (Boolean) -> Unit,
    onShowCoverProgressChanged: (Boolean) -> Unit,
    onShowCoverSubjectTypeChanged: (Boolean) -> Unit,
    onShowCoverSeriesCountChanged: (Boolean) -> Unit,
    onShowCoverUpdateWeekdayChanged: (Boolean) -> Unit,
    onAutoSyncNetworkCoversChanged: (Boolean) -> Unit,
    onInfoScaleChanged: (Float) -> Unit,
    onInfoTextOpacityChanged: (Float) -> Unit,
    onInfoDarkBackgroundOpacityChanged: (Float) -> Unit,
    onInfoLightBackgroundOpacityChanged: (Float) -> Unit,
    onInfoCornerDpChanged: (Float) -> Unit,
    onCoverCornerDpChanged: (Float) -> Unit,
    onCoverImageOpacityChanged: (Float) -> Unit,
    onCoverSaturationChanged: (Float) -> Unit,
    onCoverFitModeSelected: (CoverFitMode) -> Unit,
    onCardSwipeActionsEnabledChanged: (Boolean) -> Unit,
    onSwipeStartActionSelected: (SwipeAction) -> Unit,
    onSwipeEndActionSelected: (SwipeAction) -> Unit,
    statusOptions: List<String>,
    onDefaultStartStatusSelected: (String) -> Unit,
    onAutoCompleteStatusChanged: (Boolean) -> Unit,
    onCompletionStatusSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismissRequest, sheetGesturesEnabled = false) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("自定义追番首页", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "所有修改实时预览并自动保存。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SheetHomePreview(settings, trackerSettings)
            }
            item {
                SettingLabel("启动时显示")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = trackerSettings.defaultStartStatus == TRACKER_START_LAST_STATUS,
                            onClick = { onDefaultStartStatusSelected(TRACKER_START_LAST_STATUS) },
                            label = { Text("上次退出前") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = trackerSettings.defaultStartStatus == TRACKER_START_ALL_STATUS,
                            onClick = { onDefaultStartStatusSelected(TRACKER_START_ALL_STATUS) },
                            label = { Text("全部") },
                        )
                    }
                    items(statusOptions) { status ->
                        FilterChip(
                            selected = trackerSettings.defaultStartStatus == status,
                            onClick = { onDefaultStartStatusSelected(status) },
                            label = { Text(status) },
                        )
                    }
                }
            }
            item { SettingLabel("首页布局") }
            items(HomeLayout.entries, key = { it.storageKey }) { layout ->
                ListItem(
                    headlineContent = { Text(layout.displayName) },
                    supportingContent = { Text(layout.description) },
                    trailingContent = { RadioButton(settings.homeLayout == layout, null) },
                    modifier = Modifier.clickable(
                        role = Role.RadioButton,
                        onClick = { onLayoutSelected(layout) },
                    ),
                )
            }
            item {
                SettingLabel("详情页布局")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DetailLayout.entries) { layout ->
                        FilterChip(
                            selected = settings.detailLayout == layout,
                            onClick = { onDetailLayoutSelected(layout) },
                            label = { Text(layout.displayName) },
                        )
                    }
                }
            }
            item {
                SettingLabel("信息密度")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ContentDensity.entries) { density ->
                        FilterChip(
                            selected = settings.contentDensity == density,
                            onClick = { onDensitySelected(density) },
                            label = { Text(density.displayName) },
                        )
                    }
                }
                Text(
                    settings.contentDensity.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SettingLabel("海报列数")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..6).forEach { columns ->
                        FilterChip(
                            selected = settings.gridColumns == columns,
                            onClick = { onGridColumnsChanged(columns) },
                            label = { Text(columns.toString()) },
                        )
                    }
                }
                Text(
                    "海报墙可直接双指缩放，列数会限制在 2–6 列。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SettingLabel("封面画幅")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CoverAspectRatio.entries) { ratio ->
                        FilterChip(
                            selected = settings.coverAspectRatio == ratio,
                            onClick = { onCoverAspectRatioSelected(ratio) },
                            label = { Text(ratio.displayName) },
                        )
                    }
                }
            }
            item {
                SettingLabel("封面标题位置")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CoverTitlePosition.entries) { position ->
                        FilterChip(
                            selected = settings.coverTitlePosition == position,
                            onClick = { onCoverTitlePositionSelected(position) },
                            label = { Text(position.displayName) },
                        )
                    }
                }
            }
            item {
                SettingLabel("封面缩放方式")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CoverFitMode.entries) { mode ->
                        FilterChip(
                            selected = trackerSettings.coverFitMode == mode,
                            onClick = { onCoverFitModeSelected(mode) },
                            label = { Text(mode.displayName) },
                        )
                    }
                }
            }
            item { SettingLabel("列表与大卡片信息") }
            item { ToggleSetting("显示作品标题", settings.showTitle, onShowTitleChanged) }
            item { ToggleSetting("显示观看状态", settings.showStatus, onShowStatusChanged) }
            item { ToggleSetting("显示评分", settings.showRating, onShowRatingChanged) }
            item { ToggleSetting("显示观看进度", settings.showProgress, onShowProgressChanged) }
            item { SettingLabel("海报封面信息") }
            item { ToggleSetting("封面显示状态", trackerSettings.showCoverStatus, onShowCoverStatusChanged) }
            item { StatusBadgeControls(trackerSettings, onStatusBadgeBackgroundChanged, onStatusBadgeColorChanged) }
            item { ToggleSetting("封面显示评分", trackerSettings.showCoverRating, onShowCoverRatingChanged) }
            item { ToggleSetting("封面显示进度", trackerSettings.showCoverProgress, onShowCoverProgressChanged) }
            item { ToggleSetting("封面显示作品类型", trackerSettings.showCoverSubjectType, onShowCoverSubjectTypeChanged) }
            item { ToggleSetting("封面显示系列数量", trackerSettings.showCoverSeriesCount, onShowCoverSeriesCountChanged) }
            item { ToggleSetting("封面显示更新星期", trackerSettings.showCoverUpdateWeekday, onShowCoverUpdateWeekdayChanged) }
            item {
                SettingLabel("评分底色")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RatingBadgeColorStyle.entries, key = { it.storageKey }) { style ->
                        FilterChip(
                            selected = settings.ratingBadgeColorStyle == style,
                            onClick = { onRatingBadgeColorStyleSelected(style) },
                            label = { Text(style.displayName) },
                        )
                    }
                }
                if (settings.ratingBadgeColorStyle == RatingBadgeColorStyle.CUSTOM) {
                    var showColorPicker by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(settings.ratingBadgeCustomColor)),
                        )
                        Column(Modifier.weight(1f)) {
                            Text("自定义颜色", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "ARGB: %08X".format(settings.ratingBadgeCustomColor.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showColorPicker = true }) { Text("调整") }
                    }
                    if (showColorPicker) {
                        ColorPickerDialog(
                            initialColor = settings.ratingBadgeCustomColor,
                            onDismiss = { showColorPicker = false },
                            onColorSelected = { color ->
                                onRatingBadgeCustomColorSelected(color)
                                showColorPicker = false
                            },
                        )
                    }
                }
            }
            item {
                ToggleSetting(
                    title = "联网匹配后自动同步封面",
                    checked = trackerSettings.autoSyncNetworkCovers,
                    onCheckedChange = onAutoSyncNetworkCoversChanged,
                    subtitle = "仅上传作品标题和 http(s) 封面 URL；默认关闭，本地封面不会上传",
                )
            }
            item { SettingLabel("内容组织") }
            item { ToggleSetting("显示继续观看", trackerSettings.showContinueWatching, onShowContinueWatchingChanged, subtitle = "直接查看系列中的在看作品，并快捷打卡") }
            item { ToggleSetting("显示最近添加", trackerSettings.showRecentAdded, onShowRecentAddedChanged, subtitle = "在智能聚合首页增加最近添加的快捷区") }
            item { ToggleSetting("首页聚合同系列作品", trackerSettings.groupSeriesOnHome, onGroupSeriesChanged) }
            item {
                ToggleSetting(
                    "显示书籍独立书架",
                    trackerSettings.showStandaloneBookshelf,
                    onShowStandaloneBookshelfChanged,
                )
            }
            item {
                SettingLabel("资料库范围")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HomeBottomScope.entries) { scope ->
                        FilterChip(
                            selected = trackerSettings.homeBottomScope == scope,
                            onClick = { onHomeBottomScopeSelected(scope) },
                            label = { Text(scope.displayName) },
                        )
                    }
                }
            }
            item {
                SettingLabel("智能聚合动画区布局")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BentoCollectionLayout.entries) { layout ->
                        FilterChip(
                            selected = trackerSettings.bentoCollectionLayout == layout,
                            onClick = { onBentoCollectionLayoutSelected(layout) },
                            label = { Text(layout.displayName) },
                        )
                    }
                }
                Text(
                    "默认使用网格；首页标题旁的布局按钮也可以快速轮换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SettingLabel("列表补充信息") }
            item { ToggleSetting("显示作品类型", trackerSettings.showSubjectType, onShowSubjectTypeChanged) }
            item { ToggleSetting("显示系列数量", trackerSettings.showSeriesCount, onShowSeriesCountChanged) }
            item { ToggleSetting("显示更新星期", trackerSettings.showUpdateWeekday, onShowUpdateWeekdayChanged) }
            item {
                SettingLabel("信息层大小 · ${(trackerSettings.infoScale * 100).toInt()}%")
                Slider(
                    value = trackerSettings.infoScale,
                    onValueChange = onInfoScaleChanged,
                    valueRange = 0.75f..1.35f,
                    steps = 11,
                )
            }
            item {
                SettingLabel("文字透明度 · ${(trackerSettings.infoTextOpacity * 100).toInt()}%")
                Slider(
                    value = trackerSettings.infoTextOpacity,
                    onValueChange = onInfoTextOpacityChanged,
                    valueRange = 0.35f..1f,
                    steps = 12,
                )
            }
            item {
                SettingLabel("黑色信息框透明度 · ${(trackerSettings.infoDarkBackgroundOpacity * 100).toInt()}%")
                Slider(
                    value = trackerSettings.infoDarkBackgroundOpacity,
                    onValueChange = onInfoDarkBackgroundOpacityChanged,
                    valueRange = 0f..1f,
                    steps = 19,
                )
            }
            item {
                SettingLabel("白色信息框透明度 · ${(trackerSettings.infoLightBackgroundOpacity * 100).toInt()}%")
                Slider(
                    value = trackerSettings.infoLightBackgroundOpacity,
                    onValueChange = onInfoLightBackgroundOpacityChanged,
                    valueRange = 0f..1f,
                    steps = 19,
                )
            }
            item {
                SettingLabel("信息层圆角 · ${trackerSettings.infoCornerDp.toInt()} dp")
                Slider(
                    value = trackerSettings.infoCornerDp,
                    onValueChange = onInfoCornerDpChanged,
                    valueRange = 0f..24f,
                    steps = 11,
                )
            }
            item {
                SettingLabel("封面独立圆角 · ${trackerSettings.coverCornerDp.toInt()} dp")
                Slider(
                    value = trackerSettings.coverCornerDp,
                    onValueChange = onCoverCornerDpChanged,
                    valueRange = 0f..32f,
                    steps = 15,
                )
            }
            item {
                SettingLabel("封面图像透明度 · ${(trackerSettings.coverImageOpacity * 100).toInt()}%")
                Slider(
                    value = trackerSettings.coverImageOpacity,
                    onValueChange = onCoverImageOpacityChanged,
                    valueRange = 0.35f..1f,
                    steps = 12,
                )
            }
            item {
                SettingLabel("封面色彩饱和度 · ${(trackerSettings.coverSaturation * 100).toInt()}%")
                Slider(
                    value = trackerSettings.coverSaturation,
                    onValueChange = onCoverSaturationChanged,
                    valueRange = 0f..1.5f,
                    steps = 14,
                )
            }
            item {
                ToggleSetting(
                    "启用卡片滑动快捷操作",
                    settings.cardSwipeActionsEnabled,
                    onCardSwipeActionsEnabledChanged,
                    subtitle = "默认关闭，避免左右切页时误加集数或改变状态",
                )
            }
            item {
                SettingLabel("右滑快捷动作")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SwipeAction.entries) { action ->
                        FilterChip(
                            selected = settings.swipeStartAction == action,
                            enabled = settings.cardSwipeActionsEnabled,
                            onClick = { onSwipeStartActionSelected(action) },
                            label = { Text(action.displayName) },
                        )
                    }
                }
            }
            item {
                SettingLabel("左滑快捷动作")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SwipeAction.entries) { action ->
                        FilterChip(
                            selected = settings.swipeEndAction == action,
                            enabled = settings.cardSwipeActionsEnabled,
                            onClick = { onSwipeEndActionSelected(action) },
                            label = { Text(action.displayName) },
                        )
                    }
                }
            }
            item {
                ToggleSetting(
                    "达到总集数后自动归纳",
                    settings.autoCompleteStatus,
                    onAutoCompleteStatusChanged,
                )
            }
            if (settings.autoCompleteStatus) {
                item {
                    SettingLabel("自动归纳状态")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(statusOptions.ifEmpty { listOf("看完") }) { status ->
                            FilterChip(
                                selected = settings.completionStatus == status,
                                onClick = { onCompletionStatusSelected(status) },
                                label = { Text(status) },
                            )
                        }
                    }
                }
            }
            item {
                SettingLabel("动画强度")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MotionLevel.entries) { level ->
                        FilterChip(
                            selected = settings.motionLevel == level,
                            onClick = { onMotionSelected(level) },
                            label = { Text(level.displayName) },
                        )
                    }
                }
            }
            item {
                SettingLabel("字体缩放 · ${(settings.fontScale * 100).toInt()}%")
                Slider(
                    value = settings.fontScale,
                    onValueChange = onFontScaleChanged,
                    valueRange = APP_FONT_SCALE_MIN..APP_FONT_SCALE_MAX,
                    steps = 11,
                )
            }
            item {
                SettingLabel("圆角强度 · ${(settings.cornerScale * 100).toInt()}%")
                Slider(
                    value = settings.cornerScale,
                    onValueChange = onCornerScaleChanged,
                    valueRange = 0.5f..1.5f,
                    steps = 9,
                )
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ToggleSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun progressLabel(anime: AnimeEntity): String =
    if (anime.totalEpisodes > 0) {
        "已看 ${anime.watchedEpisodes} / ${anime.totalEpisodes} 集"
    } else {
        "已看 ${anime.watchedEpisodes} 集"
    }

@Composable
private fun SheetHomePreview(settings: AppearanceSettings, trackerSettings: TrackerSettings) {
    val scale = settings.contentDensity.scale
    val spacing = (settings.sectionSpacing.dpValue * scale).dp
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "实时预览 · ${settings.homeLayout.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    settings.contentDensity.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Mini app bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("AniMeow", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                repeat(3) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f + it * 0.2f)))
                }
            }
            Spacer(Modifier.height(spacing))
            Text("在看", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(spacing))
            // Preview based on layout
            val sampleTitles = listOf("番剧A", "番剧B", "番剧C")
            when (settings.homeLayout) {
                HomeLayout.POSTER_WALL, HomeLayout.RECOMMEND_GRID -> {
                    val cols = settings.gridColumns.coerceIn(2, 4)
                    Row(horizontalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
                        repeat(cols) { idx ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(settings.coverAspectRatio.ratio)
                                    .clip(RoundedCornerShape((trackerSettings.coverCornerDp * scale).dp.coerceAtMost(12.dp)))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                            ) {
                                if (settings.showTitle) {
                                    Text(
                                        sampleTitles.getOrElse(idx) { "番${idx + 1}" },
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
                HomeLayout.CARD_FEED -> {
                    sampleTitles.take(2).forEach { title ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size((40 * scale).dp, (56 * scale).dp)
                                    .clip(RoundedCornerShape((trackerSettings.coverCornerDp * scale).dp.coerceAtMost(10.dp)))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                            )
                            Column(Modifier.weight(1f)) {
                                if (settings.showTitle) Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                if (settings.showStatus) Text("在看", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height((4 * scale).dp))
                    }
                }
                HomeLayout.BENTO -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().height((70 * scale).dp),
                        horizontalArrangement = Arrangement.spacedBy((5 * scale).dp),
                    ) {
                        Box(
                            Modifier.weight(1.35f).fillMaxHeight()
                                .clip(RoundedCornerShape((trackerSettings.coverCornerDp * scale).dp.coerceAtMost(10.dp)))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        )
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy((5 * scale).dp),
                        ) {
                            Box(
                                Modifier.fillMaxWidth().weight(1f)
                                    .clip(RoundedCornerShape((trackerSettings.coverCornerDp * scale).dp.coerceAtMost(10.dp)))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                            )
                            Box(
                                Modifier.fillMaxWidth().weight(1f)
                                    .clip(RoundedCornerShape((trackerSettings.coverCornerDp * scale).dp.coerceAtMost(10.dp)))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                            )
                        }
                    }
                }
                HomeLayout.COMPACT_INDEX -> {
                    sampleTitles.take(2).forEach { title ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = (2 * scale).dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier.size((26 * scale).dp).clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                            )
                            if (settings.showTitle) Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (settings.showRating) Text("★ 8.8", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HomeLayout.TIME_LINE -> {
                    Column(verticalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("2024年1月", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("3 部", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(
                            modifier = Modifier.fillMaxWidth().height(0.5.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
                            repeat(3) { idx ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(settings.coverAspectRatio.ratio)
                                        .clip(RoundedCornerShape((trackerSettings.coverCornerDp * scale).dp.coerceAtMost(10.dp)))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
