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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NightlightRound
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.preferences.CalendarContentDensity
import com.animeow.app.data.preferences.CalendarMarkerStyle
import com.animeow.app.data.preferences.CalendarPagePreset
import com.animeow.app.ui.calendar.CalendarEvent
import com.animeow.app.ui.calendar.CalendarEventType
import com.animeow.app.ui.calendar.CalendarViewModel
import com.animeow.app.ui.calendar.BroadcastScheduleSheet
import com.animeow.app.ui.components.StableModalBottomSheet
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun LegacyCalendarScreen(
    onAnimeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCustomization by remember { mutableStateOf(false) }
    var showBroadcasts by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LegacyPageHeader(
                title = "追随日历",
                subtitle = state.displaySettings.preset.displayName,
                actions = {
                    IconButton(onClick = { showCustomization = true }) {
                        Icon(Icons.Outlined.GridView, contentDescription = "自定义日历")
                    }
                    IconButton(onClick = viewModel::jumpToday) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = "回到今天")
                    }
                },
            )
        }
        item {
            LegacyMonthCard(
                month = state.visibleMonth,
                selectedDate = state.selectedDate,
                eventsByDate = state.eventsByDate,
                markerStyle = state.displaySettings.markerStyle,
                showLegend = state.displaySettings.showLegend,
                onPrevious = viewModel::previousPeriod,
                onNext = viewModel::nextPeriod,
                onSelect = viewModel::selectDate,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            OutlinedButton(onClick = { showBroadcasts = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Icon(Icons.Outlined.CalendarMonth, null)
                Text(" 追番排期 · 已订阅 ${state.followingAnimes.size} 部")
            }
        }
        item {
            LegacySelectedDayCard(
                date = state.selectedDate,
                events = state.selectedEvents,
                showCovers = state.displaySettings.showCovers,
                onAnimeClick = onAnimeClick,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (state.historyEvents.isNotEmpty()) {
            item {
                Text(
                    "那年今日",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            state.historyEvents.take(8).forEach { event ->
                item(key = event.id) {
                    LegacyCalendarEventRow(
                        event = event,
                        showCover = state.displaySettings.showCovers,
                        onClick = { onAnimeClick(event.animeId) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }

    if (showCustomization) {
        LegacyCalendarCustomizationSheet(
            preset = state.displaySettings.preset,
            density = state.displaySettings.density,
            markerStyle = state.displaySettings.markerStyle,
            showCovers = state.displaySettings.showCovers,
            showLegend = state.displaySettings.showLegend,
            onPreset = viewModel::applyPreset,
            onDensity = viewModel::setDensity,
            onMarker = viewModel::setMarkerStyle,
            onShowCovers = viewModel::setShowCovers,
            onShowLegend = viewModel::setShowLegend,
            onReset = viewModel::resetDisplaySettings,
            onDismiss = { showCustomization = false },
        )
    }
    if (showBroadcasts) BroadcastScheduleSheet(viewModel, onAnimeClick, onDismiss = { showBroadcasts = false })
}

@Composable
private fun LegacyMonthCard(
    month: YearMonth,
    selectedDate: LocalDate,
    eventsByDate: Map<LocalDate, List<CalendarEvent>>,
    markerStyle: CalendarMarkerStyle,
    showLegend: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "上个月")
                }
                Text(
                    "${month.year}年 ${month.monthValue}月",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "下个月")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { day ->
                    Text(
                        day,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val firstOffset = month.atDay(1).dayOfWeek.value % 7
            val cells = List<LocalDate?>(firstOffset) { null } +
                (1..month.lengthOfMonth()).map(month::atDay)
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        LegacyCalendarDay(
                            date = date,
                            selected = date == selectedDate,
                            today = date == LocalDate.now(),
                            events = date?.let(eventsByDate::get).orEmpty(),
                            markerStyle = markerStyle,
                            onClick = { date?.let(onSelect) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (showLegend) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        LegacyPurple to "开播 / 出版",
                        LegacyBlue to "开始",
                        LegacyGreen to "完成",
                        LegacyOrange to "观看记录",
                    ).forEachIndexed { index, (color, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LegacyDot(color)
                            Text(" $label", style = MaterialTheme.typography.labelMedium)
                        }
                        if (index < 3) Spacer(Modifier.size(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LegacyCalendarDay(
    date: LocalDate?,
    selected: Boolean,
    today: Boolean,
    events: List<CalendarEvent>,
    markerStyle: CalendarMarkerStyle,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val eventColor = events.firstOrNull()?.type?.legacyColor()
    Box(
        modifier = modifier.height(52.dp).clickable(enabled = date != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (date != null) {
            val background = when {
                selected -> MaterialTheme.colorScheme.primary
                markerStyle == CalendarMarkerStyle.TINT && eventColor != null -> eventColor.copy(alpha = 0.14f)
                today -> LegacyBlue.copy(alpha = 0.14f)
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier.size(38.dp).background(background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected || today) FontWeight.ExtraBold else FontWeight.Medium,
                )
            }
            when {
                markerStyle == CalendarMarkerStyle.DOTS && eventColor != null ->
                    LegacyDot(eventColor, Modifier.align(Alignment.BottomCenter).size(6.dp))
                markerStyle == CalendarMarkerStyle.COUNT && events.isNotEmpty() ->
                    Text(
                        events.size.toString(),
                        modifier = Modifier.align(Alignment.TopEnd).background(eventColor ?: LegacyPurple, CircleShape)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
            }
        }
    }
}

@Composable
private fun LegacySelectedDayCard(
    date: LocalDate,
    events: List<CalendarEvent>,
    showCovers: Boolean,
    onAnimeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${date.monthValue}月${date.dayOfMonth}日 星期${date.dayOfWeek.chineseShort()}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        if (events.isEmpty()) "当天暂无记录" else "${events.size} 项日程",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (date == LocalDate.now()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(" 今天", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (events.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.NightlightRound, contentDescription = null, modifier = Modifier.size(38.dp))
                    Text("这一天很安静", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                events.forEach { event ->
                    LegacyCalendarEventRow(
                        event = event,
                        showCover = showCovers,
                        onClick = { onAnimeClick(event.animeId) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun LegacyCalendarEventRow(
    event: CalendarEvent,
    showCover: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showCover && !event.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = event.coverUrl,
                contentDescription = event.title,
                modifier = Modifier.size(58.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.size(58.dp).background(event.type.legacyColor().copy(alpha = 0.12f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) { LegacyDot(event.type.legacyColor(), Modifier.size(22.dp)) }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(event.subtitle, color = event.type.legacyColor(), fontWeight = FontWeight.Bold)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyCalendarCustomizationSheet(
    preset: CalendarPagePreset,
    density: CalendarContentDensity,
    markerStyle: CalendarMarkerStyle,
    showCovers: Boolean,
    showLegend: Boolean,
    onPreset: (CalendarPagePreset) -> Unit,
    onDensity: (CalendarContentDensity) -> Unit,
    onMarker: (CalendarMarkerStyle) -> Unit,
    onShowCovers: (Boolean) -> Unit,
    onShowLegend: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("定制追随日历", style = MaterialTheme.typography.headlineSmall)
                        Text("调整模块、事件标记与信息密度", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onReset) { Icon(Icons.Outlined.MoreVert, contentDescription = "重置") }
                }
            }
            item {
                CalendarSettingGroup("布局预设") {
                    CalendarPagePreset.entries.filterNot { it == CalendarPagePreset.CUSTOM }.forEach { option ->
                        FilterChip(
                            selected = preset == option,
                            onClick = { onPreset(option) },
                            label = { Text(option.displayName) },
                        )
                    }
                }
            }
            item {
                CalendarSettingGroup("信息密度") {
                    CalendarContentDensity.entries.forEach { option ->
                        FilterChip(
                            selected = density == option,
                            onClick = { onDensity(option) },
                            label = { Text(option.displayName) },
                        )
                    }
                }
            }
            item {
                CalendarSettingGroup("日期事件标记") {
                    CalendarMarkerStyle.entries.forEach { option ->
                        FilterChip(
                            selected = markerStyle == option,
                            onClick = { onMarker(option) },
                            label = { Text(option.displayName) },
                        )
                    }
                }
            }
            item {
                LegacyCalendarToggle("显示作品封面", showCovers, onShowCovers)
                LegacyCalendarToggle("显示事件图例", showLegend, onShowLegend)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("恢复默认") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("完成") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarSettingGroup(
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

@Composable
private fun LegacyCalendarToggle(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun CalendarEventType.legacyColor(): Color = when (this) {
    CalendarEventType.BROADCAST -> LegacyBlue
    CalendarEventType.AIR_DATE -> LegacyPurple
    CalendarEventType.WATCH_START -> LegacyBlue
    CalendarEventType.WATCH_FINISH -> LegacyGreen
    CalendarEventType.WATCH_RECORD -> LegacyOrange
    CalendarEventType.REMINDER -> Color(0xFFE24D8F)
}

private fun java.time.DayOfWeek.chineseShort(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "一"
    java.time.DayOfWeek.TUESDAY -> "二"
    java.time.DayOfWeek.WEDNESDAY -> "三"
    java.time.DayOfWeek.THURSDAY -> "四"
    java.time.DayOfWeek.FRIDAY -> "五"
    java.time.DayOfWeek.SATURDAY -> "六"
    java.time.DayOfWeek.SUNDAY -> "日"
}
