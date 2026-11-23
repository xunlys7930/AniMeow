package com.animeow.app.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.data.preferences.CollectionPosterPreset
import com.animeow.app.data.preferences.withCollectionPosterStyle

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
import androidx.compose.runtime.saveable.rememberSaveable
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

// 追番首页的批量操作、排序筛选与首页定制等弹窗。

@Composable
internal fun BatchEpisodeProgressDialog(
    count: Int?,
    busy: Boolean,
    preview: suspend (Int) -> com.animeow.app.data.BatchEpisodeProgress,
    onDismiss: () -> Unit,
    onSave: (com.animeow.app.data.BatchEpisodeProgress) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val progress = if (count == null) 0 else text.toIntOrNull()
    var plan by remember { mutableStateOf<com.animeow.app.data.BatchEpisodeProgress?>(null) }
    var preparedFor by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(progress, refresh) {
        plan = null
        preparedFor = null
        error = null
        if (progress != null) try {
            plan = preview(progress)
            preparedFor = progress
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "暂时无法读取修改预览，请重试"
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (count == null) "全部想看动画集数归零" else "批量修改集数") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (count == null) "范围为收藏中全部「想看」状态的动画。仅修改本机集数，保留状态、总集数和观看记录。"
                    else "已选 $count 项。仅修改本机动画，超过总集数时按各自总集数保存，已完成的动画保留完成进度。")
                if (count != null) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { character -> character in '0'..'9' }.take(10) },
                    label = { Text("已看集数") },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    singleLine = true,
                    enabled = !busy,
                )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (progress != null && plan == null && error == null) Text("正在读取修改预览…")
                plan?.takeIf { preparedFor == progress }?.let { prepared ->
                    Text(if (count == null) "将修改 ${prepared.edits.size} 部想看动画"
                        else "将修改 ${prepared.edits.size} 部动画 · ${prepared.eligibleCount - prepared.edits.size} 部无需变更")
                    if (count != null && count > prepared.eligibleCount) Text("已跳过 ${count - prepared.eligibleCount} 项书籍或已移除作品")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(prepared.edits, key = { it.before.id }) { edit ->
                            Column {
                                Text(edit.before.title, style = MaterialTheme.typography.bodyMedium)
                                val total = edit.before.totalEpisodes.takeIf { it > 0 }?.toString() ?: "未定"
                                Text("${edit.before.watchedEpisodes}/$total → ${edit.progress}/$total", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(enabled = !busy && progress != null, onClick = { refresh++ }) { Text("重新读取预览") }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && preparedFor == progress && plan?.edits?.isNotEmpty() == true,
                onClick = { plan?.let(onSave) }) { Text(if (busy) "保存中…" else "确认修改") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SortSheet(
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
internal fun BatchStatusSheet(
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
            if (options.isEmpty()) item { Text("所有状态菜单均已隐藏，可在状态管理中恢复，或进入作品编辑页修改。", Modifier.padding(20.dp)) }
            items(options, key = { it }) { status ->
                ListItem(
                    headlineContent = { Text(status) },
                    modifier = Modifier.clickable { onSelected(status) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedFilterSheet(
    filters: AdvancedLibraryFilters,
    tags: List<TagEntity>,
    years: List<String>,
    onSubjectTypeSelected: (String) -> Unit,
    onTagToggled: (Long) -> Unit,
    onTagMatchAllChanged: (Boolean) -> Unit,
    onYearToggled: (String) -> Unit,
    onYearsCleared: () -> Unit,
    onClear: () -> Unit,
    showSubjectType: Boolean,
    currentSort: LibrarySort,
    ascending: Boolean,
    onSortSelected: (LibrarySort) -> Unit,
    onToggleDirection: () -> Unit,
    onDisplayRequested: () -> Unit,
    onManageRequested: () -> Unit,
    onGlobalSearchRequested: () -> Unit,
    onDismissRequest: () -> Unit,
    filterPlans: @Composable () -> Unit = {},
) {
    var tagQuery by remember { mutableStateOf("") }
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
                    Text("筛选与显示", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("清除") }
                    FilledTonalButton(onClick = onDismissRequest) { Text("完成") }
                }
            }
            item { filterPlans() }
            item {
                Text("排序", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibrarySort.entries.forEach { option ->
                        FilterChip(currentSort == option, { if (currentSort == option) onToggleDirection() else onSortSelected(option) },
                            label = { Text(option.displayName + if (currentSort == option) (if (ascending) " ↑" else " ↓") else "") })
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDisplayRequested) { Text("显示设置") }
                    TextButton(onClick = onManageRequested) { Text("管理收藏") }
                    TextButton(onClick = onGlobalSearchRequested) { Text("全局搜索") }
                }
            }
            if (showSubjectType) item {
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
                item {
                    OutlinedTextField(tagQuery, { tagQuery = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索标签") },
                        singleLine = true, leadingIcon = { Icon(AppIcons.Search, null) })
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.filter { it.name.contains(tagQuery, true) }.forEach { tag ->
                            FilterChip(selected = tag.id in filters.tagIds, onClick = { onTagToggled(tag.id) },
                                label = { Text(tag.name) }, leadingIcon = {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(tag.color ?: 0xFF8A4FD0)))
                                })
                        }
                    }
                }
            }
        }
    }
}

internal fun advancedFilterCount(filters: AdvancedLibraryFilters): Int =
    (if (filters.subjectType != "all") 1 else 0) +
        filters.tagIds.size +
        (if (filters.years.isNotEmpty()) 1 else 0)

internal val SUBJECT_TYPES = listOf(
    "all" to "全部",
    "anime" to "动画",
    "book" to "书籍",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeCustomizationSheet(
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    onApplyPosterPreset: (CollectionPosterPreset) -> Unit,
    onRestoreEntry: (AppearanceSettings, TrackerSettings) -> Unit,
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
    onCardInfoOrderChanged: (List<com.animeow.app.ui.theme.CardInfoField>) -> Unit,
    onRatingBadgeColorStyleSelected: (RatingBadgeColorStyle) -> Unit,
    onRatingBadgeCustomColorSelected: (Long) -> Unit,
    onGroupSeriesChanged: (Boolean) -> Unit,
    onShowStandaloneBookshelfChanged: (Boolean) -> Unit,
    onHomeBottomScopeSelected: (HomeBottomScope) -> Unit,
    onBentoCollectionLayoutSelected: (BentoCollectionLayout) -> Unit,
    onShowSubjectTypeChanged: (Boolean) -> Unit,
    onShowContinueWatchingChanged: (Boolean) -> Unit,
    onShowRecentAddedChanged: (Boolean) -> Unit,
    onHomeModuleVisible: (com.animeow.app.data.preferences.HomeModule, Boolean) -> Unit,
    onMoveHomeModule: (com.animeow.app.data.preferences.HomeModule, Int) -> Unit,
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
    onProgressPlacementSelected: (com.animeow.app.data.preferences.CardProgressPlacement) -> Unit,
    onCoverProgressAlphaChanged: (Float) -> Unit = {},
    onCardSwipeActionsEnabledChanged: (Boolean) -> Unit,
    onSwipeStartActionSelected: (SwipeAction) -> Unit,
    onSwipeEndActionSelected: (SwipeAction) -> Unit,
    statusOptions: List<String>,
    onDefaultStartStatusSelected: (String) -> Unit,
    onAutoCompleteStatusChanged: (Boolean) -> Unit,
    onCompletionStatusSelected: (String) -> Unit,
    bangumiSyncEnabled: Boolean = false,
    bangumiShowHomeStatus: Boolean = true,
    onBangumiShowHomeStatusChanged: (Boolean) -> Unit = {},
    onDismissRequest: () -> Unit,
) {
    // 二级：常用项 + 「进阶设置」入口；三级：全部首页选项。两级都即时保存。
    var advanced by rememberSaveable { mutableStateOf(false) }
    val simple = !advanced
    val sheetScope = rememberCoroutineScope()
    var showCardInfoSort by remember { mutableStateOf(false) }
    var posterPreset by rememberSaveable { mutableStateOf<CollectionPosterPreset?>(null) }
    val entryAppearance = remember { settings }
    val entryTracker = remember { trackerSettings }
    StableModalBottomSheet(onDismissRequest = onDismissRequest, sheetGesturesEnabled = false) {
        BackHandler(enabled = advanced) {
            advanced = false
            sheetScope.launch { listState.scrollToItem(0) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (advanced) {
                        IconButton(onClick = {
                            advanced = false
                            sheetScope.launch { listState.scrollToItem(0) }
                        }) {
                            Icon(com.animeow.app.ui.icons.AppIcons.Back, contentDescription = "返回常用设置")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (advanced) "首页进阶设置" else "收藏显示设置", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (advanced) "全部首页选项，修改实时预览并自动保存。" else "常用选项，修改实时预览并自动保存。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { posterPreset = null; onRestoreEntry(entryAppearance, entryTracker) }) { Text("恢复打开前的设置") }
            }
            item {
                if (simple) {
                    SettingLabel("收藏卡片预设")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CollectionPosterPreset.entries.forEach { preset ->
                            FilterChip(selected = posterPreset == preset, onClick = { posterPreset = preset },
                                label = { Text(preset.displayName) })
                        }
                    }
                    posterPreset?.let { preset ->
                        val packageName = LocalContext.current.packageName
                        val sample = AnimeEntity(title = "山间来信", status = "在看", rating = 88,
                            coverUrl = "android.resource://$packageName/drawable/preview_cover",
                            watchedEpisodes = 3, totalEpisodes = 12, broadcastDay = 4)
                        Text(if (preset == CollectionPosterPreset.CLASSIC) "标题和进度叠放在封面底部，评分显示在角落。应用后切换到海报墙。"
                            else "标题显示在封面上，进度与更新日期显示在下方。应用后切换到海报墙。")
                        Box(Modifier.padding(vertical = 8.dp).width(132.dp)) {
                            PosterAnimeCard(sample, settings.withCollectionPosterStyle(), preset.apply(trackerSettings),
                                selected = false, onClick = {}, onLongClick = {}, swipeEnabled = false, onSwipe = { _, _ -> })
                        }
                        Button(onClick = { onApplyPosterPreset(preset); posterPreset = null }) { Text("应用预设") }
                    }
                }
                if (posterPreset == null || !simple) com.animeow.app.ui.preferences.HomePagePreview(settings, trackerSettings)
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
            items(HomeLayout.selectable, key = { it.storageKey }) { layout ->
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
            if (!simple) {
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
            }
            item { SettingLabel("列表与大卡片信息") }
            item { ToggleSetting("显示作品标题", settings.showTitle, onShowTitleChanged) }
            item { ToggleSetting("显示观看状态", settings.showStatus, onShowStatusChanged) }
            item { ToggleSetting("显示评分", settings.showRating, onShowRatingChanged) }
            item { ToggleSetting("显示观看进度", settings.showProgress, onShowProgressChanged) }
            if (!simple) item {
                Row(
                    modifier = Modifier.fillMaxWidth().animatedPressClick(onClick = { showCardInfoSort = true }),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("卡片信息排序")
                        Text(
                            "拖动调整标题、类型与放送、状态、进度、评分的先后",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(AppIcons.ChevronRight, contentDescription = null)
                }
            }
            if (!simple) {
            item { SettingLabel("海报封面信息") }
            item { ToggleSetting("封面显示状态", trackerSettings.showCoverStatus, onShowCoverStatusChanged) }
            item { StatusBadgeControls(trackerSettings, onStatusBadgeBackgroundChanged, onStatusBadgeColorChanged) }
            item { ToggleSetting("封面显示评分", trackerSettings.showCoverRating, onShowCoverRatingChanged) }
            item { ToggleSetting("封面显示进度", trackerSettings.showCoverProgress, onShowCoverProgressChanged) }
            item {
                SettingLabel("卡片进度位置")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.animeow.app.data.preferences.CardProgressPlacement.entries.forEach { placement ->
                        FilterChip(selected = trackerSettings.progressPlacement == placement,
                            onClick = { onProgressPlacementSelected(placement) }, label = { Text(placement.displayName) })
                    }
                }
                if (trackerSettings.progressPlacement == com.animeow.app.data.preferences.CardProgressPlacement.COVER_BOTTOM) {
                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("底部黑条透明度", style = MaterialTheme.typography.bodyMedium)
                            Text("${(trackerSettings.coverProgressAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = trackerSettings.coverProgressAlpha,
                            onValueChange = onCoverProgressAlphaChanged,
                            valueRange = 0.2f..0.9f,
                        )
                    }
                }
                Text("封面底部遵循“封面显示进度”开关；仅调整显示位置，仍可在详情页修改进度。", style = MaterialTheme.typography.bodySmall)
            }
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
                com.animeow.app.ui.preferences.RatingColorExplanation(settings.ratingBadgeColorStyle)
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
                    subtitle = "仅上传作品标题和网络封面链接；默认关闭，本地相册封面不会上传",
                )
            }
            }
            item { SettingLabel("内容组织") }
            if (!simple) item { com.animeow.app.ui.preferences.HomeModuleControls(trackerSettings, onHomeModuleVisible, onMoveHomeModule) }
            item { ToggleSetting("首页聚合同系列作品", trackerSettings.groupSeriesOnHome, onGroupSeriesChanged) }
            if (bangumiSyncEnabled) {
                item {
                    ToggleSetting(
                        title = "显示 Bangumi 同步状态",
                        checked = bangumiShowHomeStatus,
                        onCheckedChange = onBangumiShowHomeStatusChanged,
                        subtitle = "关闭后隐藏首页状态栏下方的同步提示，后台同步照常运行",
                    )
                }
            }
            if (!simple) {
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
            if (!simple) item {
                SettingLabel("圆角强度 · ${(settings.cornerScale * 100).toInt()}%")
                Slider(
                    value = settings.cornerScale,
                    onValueChange = onCornerScaleChanged,
                    valueRange = 0.5f..1.5f,
                    steps = 9,
                )
            }
            if (simple) item(key = "advanced-entry") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "打开首页进阶设置") {
                                advanced = true
                                sheetScope.launch { listState.scrollToItem(0) }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(com.animeow.app.ui.icons.AppIcons.SettingsDepth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("进阶设置", fontWeight = FontWeight.SemiBold)
                            Text(
                                "封面画幅与信息层、首页模块、书架范围、滑动操作、自动完结等",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(com.animeow.app.ui.icons.AppIcons.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }
    if (showCardInfoSort) {
        com.animeow.app.ui.components.CardInfoSortSheet(
            settings = settings,
            onOrderChanged = onCardInfoOrderChanged,
            onDismissRequest = { showCardInfoSort = false },
        )
    }
}

@Composable
internal fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun ToggleSetting(
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

internal fun progressLabel(anime: AnimeEntity): String =
    if (anime.totalEpisodes > 0) {
        "已看 ${anime.watchedEpisodes} / ${anime.totalEpisodes} 集"
    } else {
        "已看 ${anime.watchedEpisodes} 集"
    }

@Composable
internal fun SeriesQuickEditButton(item: LibraryDisplayItem.SeriesItem, modifier: Modifier = Modifier) {
    val action = LocalSeriesQuickEdit.current ?: return
    IconButton(onClick = { action(item) }, modifier = modifier.size(48.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
            Icon(AppIcons.Edit, contentDescription = "快速编辑系列「${item.title}」中的作品",
                modifier = Modifier.padding(7.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesQuickEditSheet(
    item: LibraryDisplayItem.SeriesItem,
    onDismiss: () -> Unit,
    onEdit: (Long) -> Unit,
    onProgress: (AnimeEntity) -> Unit,
    onOpenSeries: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val members = item.members.filter { query.isBlank() || it.title.contains(query, true) }
    StableModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("直接编辑作品或记录进度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onOpenSeries) { Text("打开系列") }
                }
            }
            if (item.members.size > 5) item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("查找系列中的作品") }, singleLine = true, leadingIcon = { Icon(AppIcons.Search, null) })
            }
            items(members, key = AnimeEntity::id) { anime ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AsyncImage(anime.coverUrl, null, Modifier.size(40.dp, 56.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f)) {
                            Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text("${anime.status} · ${anime.watchedEpisodes} / ${anime.totalEpisodes.takeIf { it > 0 } ?: "?"} 集", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onProgress(anime) }, enabled = anime.totalEpisodes <= 0 || anime.watchedEpisodes < anime.totalEpisodes) {
                            Icon(AppIcons.AddCircle, "为${anime.title}记录一集")
                        }
                        IconButton(onClick = { onEdit(anime.id) }) { Icon(AppIcons.Edit, "编辑${anime.title}") }
                    }
                }
            }
            if (members.isEmpty()) item { Text("没有找到对应作品", Modifier.padding(16.dp)) }
        }
    }
}
