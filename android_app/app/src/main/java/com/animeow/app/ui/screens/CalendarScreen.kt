package com.animeow.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.preferences.CalendarContentDensity
import com.animeow.app.data.preferences.CalendarDisplaySettings
import com.animeow.app.data.preferences.CalendarMarkerStyle
import com.animeow.app.data.preferences.CalendarModule
import com.animeow.app.data.preferences.CalendarPagePreset
import com.animeow.app.ui.calendar.CalendarEvent
import com.animeow.app.ui.calendar.CalendarEventType
import com.animeow.app.ui.calendar.CalendarMode
import com.animeow.app.ui.calendar.CalendarUiState
import com.animeow.app.ui.calendar.CalendarViewModel
import com.animeow.app.ui.calendar.BroadcastScheduleSheet
import com.animeow.app.ui.calendar.MissingDateItem
import com.animeow.app.ui.calendar.MissingDateKind
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CalendarLayoutPreset
import com.animeow.app.util.runCatchingCancellable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(
    settings: AppearanceSettings,
    customizationRequest: Int = 0,
    modifier: Modifier = Modifier,
    onAnimeClick: (Long) -> Unit = {},
    onEditAnime: (Long) -> Unit = {},
    onCalendarLayoutPresetSelected: (CalendarLayoutPreset) -> Unit = {},
    viewModel: CalendarViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showCustomization by rememberSaveable { mutableStateOf(false) }
    var showMissingDates by rememberSaveable { mutableStateOf(false) }
    var showBroadcasts by rememberSaveable { mutableStateOf(false) }
    var handledCustomizationRequest by remember { mutableIntStateOf(customizationRequest) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val animationDuration = (220 * settings.motionLevel.durationScale).toInt().coerceAtLeast(1)

    LaunchedEffect(customizationRequest) {
        if (customizationRequest > handledCustomizationRequest) {
            handledCustomizationRequest = customizationRequest
            showCustomization = true
        }
    }
    val onDeleteRecord: (Long) -> Unit = { recordId ->
        scope.launch {
            runCatchingCancellable { viewModel.deleteWatchRecord(recordId) }
                .onSuccess { snackbarHostState.showSnackbar("观看记录已删除") }
                .onFailure { error ->
                    snackbarHostState.showSnackbar("删除失败：${error.message.orEmpty()}")
                }
        }
    }

    LaunchedEffect(settings.calendarLayoutPreset) {
        viewModel.setMode(
            when (settings.calendarLayoutPreset) {
                CalendarLayoutPreset.MONTH -> CalendarMode.MONTH
                CalendarLayoutPreset.AGENDA -> CalendarMode.AGENDA
                CalendarLayoutPreset.WEEK, CalendarLayoutPreset.WEEK_AGENDA -> CalendarMode.WEEK
            },
        )
    }
    LaunchedEffect(state.repairState.message) {
        state.repairState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearRepairMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CalendarControls(
                state = state,
                onPrevious = viewModel::previousPeriod,
                onNext = viewModel::nextPeriod,
                onToday = viewModel::jumpToday,
            )
            CalendarActionRow(
                state = state,
                onModeSelected = viewModel::setMode,
                onFiltersRequested = { showFilters = true },
                onCustomizationRequested = { showCustomization = true },
                onMissingDatesRequested = { showMissingDates = true },
                onBroadcastsRequested = { showBroadcasts = true },
            )
            AnimatedVisibility(
                visible = state.displaySettings.showLegend,
                enter = motionFadeIn(),
                exit = motionFadeOut(),
            ) {
                CalendarLegend(state.displaySettings.enabledEventTypes)
            }
            PermissionCards(
                needsNotificationPermission = needsNotificationPermission && state.hasReminders,
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            AnimatedContent(
                targetState = state.mode,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(tween(animationDuration)) togetherWith fadeOut(tween((animationDuration * 0.7f).toInt()))
                },
                label = "calendar_mode",
            ) { mode ->
                when (mode) {
                    CalendarMode.MONTH -> MonthContent(state, viewModel::selectDate, onAnimeClick, onDeleteRecord)
                    CalendarMode.WEEK -> WeekContent(state, viewModel::selectDate, onAnimeClick, onDeleteRecord)
                    CalendarMode.AGENDA -> AgendaContent(state, onAnimeClick, onDeleteRecord)
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }

    if (showFilters) {
        CalendarFilterSheet(
            settings = state.displaySettings,
            onToggle = viewModel::setEventTypeEnabled,
            onShowAll = { CalendarEventType.entries.forEach { viewModel.setEventTypeEnabled(it, true) } },
            onDismiss = { showFilters = false },
        )
    }
    if (showBroadcasts) {
        BroadcastScheduleSheet(viewModel, onAnimeClick, onDismiss = { showBroadcasts = false })
    }
    if (showCustomization) {
        CalendarCustomizationSheet(
            settings = state.displaySettings,
            currentLayoutPreset = settings.calendarLayoutPreset,
            onCalendarLayoutPresetSelected = onCalendarLayoutPresetSelected,
            onPresetSelected = viewModel::applyPreset,
            onMarkerSelected = viewModel::setMarkerStyle,
            onDensitySelected = viewModel::setDensity,
            onShowCoversChanged = viewModel::setShowCovers,
            onShowLegendChanged = viewModel::setShowLegend,
            onModuleVisibilityChanged = viewModel::setModuleVisible,
            onMoveModule = viewModel::moveModule,
            onReset = viewModel::resetDisplaySettings,
            onDismiss = { showCustomization = false },
        )
    }
    if (showMissingDates) {
        MissingDatesSheet(
            state = state,
            onRepairAirDates = viewModel::repairMissingAirDatesOnline,
            onInferWatchDates = viewModel::inferMissingWatchDates,
            onEditAnime = { animeId ->
                showMissingDates = false
                onEditAnime(animeId)
            },
            onDismiss = { showMissingDates = false },
        )
    }
}

@Composable
private fun CalendarControls(
    state: CalendarUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "上一时段")
        }
        Text(
            text = if (state.mode == CalendarMode.WEEK) {
                "${state.selectedDate.monthValue} 月 · 第 ${state.selectedDate.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())} 周"
            } else {
                state.visibleMonth.format(DateTimeFormatter.ofPattern("yyyy 年 M 月"))
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        AssistChip(
            onClick = onToday,
            label = { Text("今天") },
            leadingIcon = { Icon(Icons.Outlined.Today, contentDescription = null) },
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "下一时段")
        }
    }
}

@Composable
private fun CalendarActionRow(
    state: CalendarUiState,
    onModeSelected: (CalendarMode) -> Unit,
    onFiltersRequested: () -> Unit,
    onCustomizationRequested: () -> Unit,
    onMissingDatesRequested: () -> Unit,
    onBroadcastsRequested: () -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CalendarMode.entries.forEach { mode ->
                val selected = state.mode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(role = Role.Tab, onClick = { onModeSelected(mode) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        mode.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onBroadcastsRequested) {
                Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("追番排期")
            }
            Text(
                if (state.followingAnimes.isEmpty()) "导入新番更新星期" else "已订阅 ${state.followingAnimes.size} 部",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                IconButton(onClick = { showMore = true }) { Icon(Icons.Outlined.MoreVert, "日历更多操作") }
                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    DropdownMenuItem(text = { Text(if (state.hiddenEventTypeCount == 0) "事件筛选" else "事件筛选 · 隐藏 ${state.hiddenEventTypeCount} 类") }, onClick = { showMore = false; onFiltersRequested() }, leadingIcon = { Icon(Icons.Outlined.FilterList, null) })
                    DropdownMenuItem(text = { Text("补全日期 · ${state.missingDateItems.size} 部") }, onClick = { showMore = false; onMissingDatesRequested() }, leadingIcon = { Icon(Icons.Outlined.EventBusy, null) })
                    DropdownMenuItem(text = { Text("定制日历") }, onClick = { showMore = false; onCustomizationRequested() }, leadingIcon = { Icon(Icons.Outlined.Tune, null) })
                }
            }
        }
    }
}

@Composable
private fun CalendarLegend(enabledTypes: Set<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CalendarEventType.entries.filter { it.storageKey in enabledTypes }.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(eventColor(type)))
                Text(type.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PermissionCards(
    needsNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    if (needsNotificationPermission) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Alarm, contentDescription = null)
                Text("允许通知后，每周更新提醒才能准时显示", modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                FilledTonalButton(onClick = onRequestNotificationPermission) { Text("允许") }
            }
        }
    }
}

@Composable
private fun MonthContent(
    state: CalendarUiState,
    onDateSelected: (LocalDate) -> Unit,
    onAnimeClick: (Long) -> Unit,
    onDeleteRecord: (Long) -> Unit,
) {
    val settings = state.displaySettings
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(densitySpacing(settings.density)),
    ) {
        settings.moduleOrder.filterNot(settings.hiddenModules::contains).forEach { module ->
            when (module) {
                CalendarModule.MONTH -> item("calendar-month") {
                    CalendarMonthModule(state, onDateSelected)
                }
                CalendarModule.SCHEDULE -> {
                    item("calendar-schedule-title") {
                        ModuleTitle(
                            "${state.selectedDate.monthValue} 月 ${state.selectedDate.dayOfMonth} 日",
                            "${state.selectedEvents.size} 个事件",
                        )
                    }
                    if (state.selectedEvents.isEmpty()) {
                        item("calendar-schedule-empty") { EmptyModuleMessage("当天没有记录") }
                    } else {
                        items(state.selectedEvents, key = { "selected-${it.id}" }) { event ->
                            CalendarEventCard(event, settings, onAnimeClick = onAnimeClick, onDeleteRecord = onDeleteRecord)
                        }
                    }
                }
                CalendarModule.HISTORY -> {
                    item("calendar-history-title") {
                        ModuleTitle("那年今日", "其他年份的同一天")
                    }
                    if (state.historyEvents.isEmpty()) {
                        item("calendar-history-empty") { EmptyModuleMessage("暂时没有往年记录") }
                    } else {
                        items(state.historyEvents, key = { "history-${it.id}" }) { event ->
                            CalendarEventCard(
                                event,
                                settings,
                                showDate = true,
                                onAnimeClick = onAnimeClick,
                                onDeleteRecord = onDeleteRecord,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarMonthModule(state: CalendarUiState, onDateSelected: (LocalDate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAY_LABELS.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        MonthGrid(state, onDateSelected)
    }
}

@Composable
private fun MonthGrid(state: CalendarUiState, onDateSelected: (LocalDate) -> Unit) {
    val first = state.visibleMonth.atDay(1)
    val leading = first.dayOfWeek.value - 1
    val cells = buildList<LocalDate?> {
        repeat(leading) { add(null) }
        for (day in 1..state.visibleMonth.lengthOfMonth()) add(state.visibleMonth.atDay(day))
        while (size % 7 != 0) add(null)
    }
    val fontSizeFactor = LocalDensity.current.fontScale.coerceIn(1f, 1.75f)
    val cellHeight = when (state.displaySettings.density) {
        CalendarContentDensity.COMPACT -> 48.dp
        CalendarContentDensity.COMFORTABLE -> 58.dp
        CalendarContentDensity.RELAXED -> 66.dp
    } * fontSizeFactor
    val rows = cells.size / 7
    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        modifier = Modifier.fillMaxWidth().height(cellHeight * rows),
        userScrollEnabled = false,
    ) {
        items(cells) { date ->
            if (date == null) {
                Box(Modifier.height(cellHeight))
            } else {
                val events = state.eventsByDate[date].orEmpty()
                CalendarDayCell(
                    date = date,
                    events = events,
                    selected = date == state.selectedDate,
                    markerStyle = state.displaySettings.markerStyle,
                    height = cellHeight,
                    onClick = { onDateSelected(date) },
                )
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    events: List<CalendarEvent>,
    selected: Boolean,
    markerStyle: CalendarMarkerStyle,
    height: Dp,
    onClick: () -> Unit,
) {
    val primaryType = events.firstOrNull()?.type
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        markerStyle == CalendarMarkerStyle.TINT && primaryType != null -> eventColor(primaryType).copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .height(height)
            .padding(2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .semantics { this.selected = selected }
            .clickable(
                role = Role.Button,
                onClickLabel = "选择 $date，共 ${events.size} 个事件",
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 5.dp)
            .motionAnimateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            date.dayOfMonth.toString(),
            fontWeight = if (date == LocalDate.now() || selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        when (markerStyle) {
            CalendarMarkerStyle.TINT -> if (events.isNotEmpty()) {
                Text(events.size.toString(), style = MaterialTheme.typography.labelSmall, color = eventColor(primaryType!!))
            } else Unit
            CalendarMarkerStyle.DOTS -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                events.map(CalendarEvent::type).distinct().take(3).forEach { type ->
                    Box(Modifier.size(5.dp).clip(CircleShape).background(eventColor(type)))
                }
            }
            CalendarMarkerStyle.COUNT -> if (events.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(19.dp).clip(CircleShape).background(eventColor(primaryType!!)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        events.size.coerceAtMost(99).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.surface,
                    )
                }
            } else Unit
        }
    }
}

@Composable
private fun WeekContent(
    state: CalendarUiState,
    onDateSelected: (LocalDate) -> Unit,
    onAnimeClick: (Long) -> Unit,
    onDeleteRecord: (Long) -> Unit,
) {
    val start = state.selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val days = (0L..6L).map(start::plusDays)
    val weekEvents = state.events.filter { it.date in start..start.plusDays(6) }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(densitySpacing(state.displaySettings.density)),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEach { date ->
                    val selected = date == state.selectedDate
                    val eventCount = state.eventsByDate[date].orEmpty().size
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            )
                            .semantics { this.selected = selected }
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "选择 $date，共 $eventCount 个事件",
                                onClick = { onDateSelected(date) },
                            )
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(WEEKDAY_LABELS[date.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall)
                        Text(date.dayOfMonth.toString(), fontWeight = FontWeight.Bold)
                        Text(eventCount.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (weekEvents.isEmpty()) {
            item { EmptyModuleMessage("本周没有日历事件") }
        } else {
            items(weekEvents, key = CalendarEvent::id) { event ->
                CalendarEventCard(
                    event,
                    state.displaySettings,
                    showDate = true,
                    onAnimeClick = onAnimeClick,
                    onDeleteRecord = onDeleteRecord,
                )
            }
        }
    }
}

@Composable
private fun AgendaContent(
    state: CalendarUiState,
    onAnimeClick: (Long) -> Unit,
    onDeleteRecord: (Long) -> Unit,
) {
    val groups = state.monthEvents.groupBy(CalendarEvent::date).toSortedMap()
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(densitySpacing(state.displaySettings.density)),
    ) {
        if (groups.isEmpty()) {
            item { EmptyModuleMessage("本月没有日历事件") }
        } else {
            groups.forEach { (date, events) ->
                item("agenda-title-$date") {
                    ModuleTitle(
                        "${date.monthValue} 月 ${date.dayOfMonth} 日 · 周${WEEKDAY_LABELS[date.dayOfWeek.value - 1]}",
                        "${events.size} 个事件",
                    )
                }
                items(events, key = { "agenda-${it.id}" }) { event ->
                    CalendarEventCard(
                        event,
                        state.displaySettings,
                        onAnimeClick = onAnimeClick,
                        onDeleteRecord = onDeleteRecord,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarEventCard(
    event: CalendarEvent,
    settings: CalendarDisplaySettings,
    showDate: Boolean = false,
    onAnimeClick: (Long) -> Unit,
    onDeleteRecord: (Long) -> Unit,
) {
    val density = settings.density
    val compact = density == CalendarContentDensity.COMPACT
    val coverWidth = (46 * density.itemScale).dp
    val coverHeight = (66 * density.itemScale).dp
    var showRecordMenu by remember { mutableStateOf(false) }
    var confirmRecordDeletion by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable { onAnimeClick(event.animeId) },
    ) {
        Row(
            modifier = Modifier.padding((12 * density.spacingScale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((12 * density.spacingScale).dp),
        ) {
            if (settings.showCovers && !event.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = event.coverUrl,
                    contentDescription = event.title,
                    modifier = Modifier.size(coverWidth, coverHeight).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.size((42 * density.itemScale).dp)
                        .clip(CircleShape)
                        .background(eventColor(event.type).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(eventIcon(event.type), contentDescription = null, tint = eventColor(event.type))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        if (showDate) append("${event.date.year}/${event.date.monthValue}/${event.date.dayOfMonth} · ")
                        append(event.subtitle)
                        event.time?.let { append(" · $it") }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                )
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(eventColor(event.type)))
            if (event.watchRecordId != null) {
                Box {
                    IconButton(onClick = { showRecordMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "记录操作")
                    }
                    DropdownMenu(
                        expanded = showRecordMenu,
                        onDismissRequest = { showRecordMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("删除这条记录", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showRecordMenu = false
                                confirmRecordDeletion = true
                            },
                        )
                    }
                }
            }
        }
    }
    if (confirmRecordDeletion) {
        AlertDialog(
            onDismissRequest = { confirmRecordDeletion = false },
            title = { Text("删除观看记录") },
            text = {
                Text(
                    "确定删除《${event.title}》的“${event.subtitle}”吗？" +
                        "这只会移除日历记录，不会回退当前进度。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRecordDeletion = false
                        event.watchRecordId?.let(onDeleteRecord)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRecordDeletion = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ModuleTitle(title: String, subtitle: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyModuleMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .padding(vertical = 18.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.EventBusy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalendarFilterSheet(
    settings: CalendarDisplaySettings,
    onToggle: (CalendarEventType, Boolean) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("筛选日历事件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("筛选会同时作用于月历标记、周视图和议程，并自动保存。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarEventType.entries.forEach { type ->
                    val selected = type.storageKey in settings.enabledEventTypes
                    FilterChip(
                        selected = selected,
                        onClick = { onToggle(type, !selected) },
                        label = { Text(type.displayName) },
                        leadingIcon = { Icon(eventIcon(type), contentDescription = null) },
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onShowAll) { Text("显示全部") }
                FilledTonalButton(onClick = onDismiss) { Text("完成") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalendarCustomizationSheet(
    settings: CalendarDisplaySettings,
    currentLayoutPreset: CalendarLayoutPreset = CalendarLayoutPreset.WEEK_AGENDA,
    onCalendarLayoutPresetSelected: (CalendarLayoutPreset) -> Unit = {},
    onPresetSelected: (CalendarPagePreset) -> Unit,
    onMarkerSelected: (CalendarMarkerStyle) -> Unit,
    onDensitySelected: (CalendarContentDensity) -> Unit,
    onShowCoversChanged: (Boolean) -> Unit,
    onShowLegendChanged: (Boolean) -> Unit,
    onModuleVisibilityChanged: (CalendarModule, Boolean) -> Unit,
    onMoveModule: (CalendarModule, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight(0.9f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("定制追随日历", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("设置自动保存，只改变日历页面", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onReset) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = "恢复日历默认设置")
                    }
                }
            }
            item {
                CalendarSettingsSection("默认打开视图", Icons.Outlined.Today) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = currentLayoutPreset == CalendarLayoutPreset.MONTH,
                            onClick = { onCalendarLayoutPresetSelected(CalendarLayoutPreset.MONTH) },
                            label = { Text("月视图") },
                        )
                        FilterChip(
                            selected = currentLayoutPreset == CalendarLayoutPreset.WEEK || currentLayoutPreset == CalendarLayoutPreset.WEEK_AGENDA,
                            onClick = { onCalendarLayoutPresetSelected(CalendarLayoutPreset.WEEK) },
                            label = { Text("周视图") },
                        )
                        FilterChip(
                            selected = currentLayoutPreset == CalendarLayoutPreset.AGENDA,
                            onClick = { onCalendarLayoutPresetSelected(CalendarLayoutPreset.AGENDA) },
                            label = { Text("议程") },
                        )
                    }
                    Text(
                        "修改打开日历时默认进入的视图，与「展示定制」实时同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                CalendarSettingsSection("布局预设", Icons.Outlined.Tune) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CalendarPagePreset.entries.filterNot { it == CalendarPagePreset.CUSTOM }.forEach { preset ->
                            FilterChip(
                                selected = settings.preset == preset,
                                onClick = { onPresetSelected(preset) },
                                label = { Text(preset.displayName) },
                            )
                        }
                    }
                    Text(settings.preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                CalendarSettingsSection("信息密度", Icons.Outlined.Visibility) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CalendarContentDensity.entries.forEach { density ->
                            FilterChip(
                                selected = settings.density == density,
                                onClick = { onDensitySelected(density) },
                                label = { Text(density.displayName) },
                            )
                        }
                    }
                    Text(
                        settings.density.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                CalendarSettingsSection("日期事件标记", Icons.Outlined.Event) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CalendarMarkerStyle.entries.forEach { marker ->
                            FilterChip(
                                selected = settings.markerStyle == marker,
                                onClick = { onMarkerSelected(marker) },
                                label = { Text(marker.displayName) },
                            )
                        }
                    }
                }
            }
            item {
                CalendarSettingsSection("内容显示", Icons.Outlined.Image) {
                    CalendarSwitchRow("显示作品封面", "关闭后日程更紧凑", settings.showCovers, onShowCoversChanged)
                    CalendarSwitchRow("显示事件颜色图例", "在日历上方解释各类颜色", settings.showLegend, onShowLegendChanged)
                }
            }
            item {
                CalendarSettingsSection("模块顺序与显隐", Icons.Outlined.Tune) {
                    settings.moduleOrder.forEachIndexed { index, module ->
                        val visible = module !in settings.hiddenModules
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(module.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(module.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onMoveModule(module, -1) }, enabled = index > 0) {
                                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移")
                                }
                                IconButton(onClick = { onMoveModule(module, 1) }, enabled = index < settings.moduleOrder.lastIndex) {
                                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移")
                                }
                                Switch(checked = visible, onCheckedChange = { onModuleVisibilityChanged(module, it) })
                            }
                        }
                        if (index < settings.moduleOrder.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
            }
            item {
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
            }
        }
    }
}

@Composable
private fun CalendarSettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun CalendarSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MissingDatesSheet(
    state: CalendarUiState,
    onRepairAirDates: (Set<Long>) -> Unit,
    onInferWatchDates: (Set<Long>) -> Unit,
    onEditAnime: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = false,
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.9f)) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("待补充日期", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${state.missingDateItems.size} 部作品需要整理。自动操作只填空值，不覆盖已有日期。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onRepairAirDates(emptySet()) },
                        enabled = !state.repairState.isRunning && state.missingDateItems.any { MissingDateKind.AIR_DATE in it.missing },
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("联网补全")
                    }
                    OutlinedButton(
                        onClick = { onInferWatchDates(emptySet()) },
                        enabled = !state.repairState.isRunning && state.missingDateItems.any {
                            MissingDateKind.WATCH_START in it.missing || MissingDateKind.WATCH_FINISH in it.missing
                        },
                    ) {
                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("从打卡推断")
                    }
                }
                if (state.repairState.isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
            }
            if (state.missingDateItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Text("关键日期已经补全", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("所有作品都具备当前状态需要的日期信息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.missingDateItems, key = { it.anime.id }) { item ->
                        MissingDateCard(
                            item = item,
                            showCover = state.displaySettings.showCovers,
                            enabled = !state.repairState.isRunning,
                            onRepairAirDate = { onRepairAirDates(setOf(item.anime.id)) },
                            onInferWatchDates = { onInferWatchDates(setOf(item.anime.id)) },
                            onEdit = { onEditAnime(item.anime.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MissingDateCard(
    item: MissingDateItem,
    showCover: Boolean,
    enabled: Boolean,
    onRepairAirDate: () -> Unit,
    onInferWatchDates: () -> Unit,
    onEdit: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showCover && !item.anime.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.anime.coverUrl,
                    contentDescription = item.anime.title,
                    modifier = Modifier.size(44.dp, 62.dp).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.anime.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    item.missing.forEach { kind ->
                        Text(
                            kind.displayName,
                            modifier = Modifier.clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (MissingDateKind.AIR_DATE in item.missing) {
                IconButton(onClick = onRepairAirDate, enabled = enabled) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "联网补全放送日期")
                }
            }
            if (MissingDateKind.WATCH_START in item.missing || MissingDateKind.WATCH_FINISH in item.missing) {
                IconButton(onClick = onInferWatchDates, enabled = enabled) {
                    Icon(Icons.Outlined.AutoFixHigh, contentDescription = "从打卡记录推断")
                }
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Outlined.Edit, contentDescription = "手动编辑日期")
            }
        }
    }
}

@Composable
private fun eventColor(type: CalendarEventType): Color = when (type) {
    CalendarEventType.BROADCAST -> MaterialTheme.colorScheme.primary
    CalendarEventType.AIR_DATE -> MaterialTheme.colorScheme.tertiary
    CalendarEventType.WATCH_START -> MaterialTheme.colorScheme.primary
    CalendarEventType.WATCH_FINISH -> Color(0xFF3E9B55)
    CalendarEventType.WATCH_RECORD -> MaterialTheme.colorScheme.secondary
    CalendarEventType.REMINDER -> Color(0xFFE07A25)
}

private fun eventIcon(type: CalendarEventType) = when (type) {
    CalendarEventType.BROADCAST -> Icons.Outlined.PlayCircle
    CalendarEventType.AIR_DATE -> Icons.Outlined.Event
    CalendarEventType.WATCH_START -> Icons.Outlined.PlayCircle
    CalendarEventType.WATCH_FINISH -> Icons.Outlined.CheckCircle
    CalendarEventType.WATCH_RECORD -> Icons.Outlined.Today
    CalendarEventType.REMINDER -> Icons.Outlined.Alarm
}

private fun densitySpacing(density: CalendarContentDensity): Dp = when (density) {
    CalendarContentDensity.COMPACT -> 6.dp
    CalendarContentDensity.COMFORTABLE -> 10.dp
    CalendarContentDensity.RELAXED -> 14.dp
}

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
