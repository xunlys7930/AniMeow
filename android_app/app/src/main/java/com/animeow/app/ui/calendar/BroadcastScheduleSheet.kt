package com.animeow.app.ui.calendar

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.animeow.app.data.broadcastWeekdayLabel
import com.animeow.app.data.hasActiveBroadcast
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.matchBroadcastAnime
import com.animeow.app.ui.components.StableModalBottomSheet
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastScheduleSheet(
    viewModel: CalendarViewModel,
    onAnimeClick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val calendar by viewModel.state.collectAsStateWithLifecycle()
    val state by viewModel.broadcastState.collectAsStateWithLifecycle()
    var importingTab by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var weekday by rememberSaveable { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val matches = remember(state.items, calendar.libraryAnimes) {
        state.items.associate { it.uniqueKey to matchBroadcastAnime(it, calendar.libraryAnimes) }
    }
    val watchingCount = matches.values.count { it.anime?.let { anime -> anime.status == "在看" && anime.broadcastDay !in 1..7 } == true }
    val filtered = remember(state.items, weekday, query) {
        state.items.filter { remote ->
            (weekday == 0 || remote.broadcastDay == weekday) &&
                (query.isBlank() || remote.title.contains(query.trim(), true) || remote.originalTitle?.contains(query.trim(), true) == true)
        }.sortedWith(compareBy({ it.broadcastDay }, { it.title }))
    }

    LaunchedEffect(Unit) { viewModel.loadBroadcastCalendar() }
    StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("追番排期", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("把想追的番，放进每一周", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭追番排期") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(importingTab, { importingTab = true }, label = { Text("发现新番") })
                FilterChip(!importingTab, { importingTab = false }, label = { Text("我的追番 ${calendar.followingAnimes.size}") })
            }
            state.message?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            if (state.isImporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (importingTab) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bangumi 提供更新星期，具体时刻可手动补充。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { viewModel.loadBroadcastCalendar(forceRefresh = true) }, enabled = !state.isLoading && !state.isImporting) {
                        Icon(Icons.Outlined.Refresh, "刷新新番排期")
                    }
                }
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索新番") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true, shape = MaterialTheme.shapes.large,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0..7).toList(), key = { it }) { day ->
                        FilterChip(weekday == day, { weekday = day }, label = { Text(if (day == 0) "全部" else broadcastWeekdayLabel(day)) })
                    }
                }
                if (state.isLoading && state.items.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (filtered.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state.isError) "排期暂时无法加载" else "这里暂时没有匹配的新番", style = MaterialTheme.typography.titleSmall)
                        if (state.isError) TextButton(onClick = { viewModel.loadBroadcastCalendar(forceRefresh = true) }) { Text("重试") }
                        if (query.isNotBlank() || weekday != 0) TextButton(onClick = { query = ""; weekday = 0 }) { Text("查看全部") }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filtered.groupBy { it.broadcastDay }.forEach { (day, animes) ->
                            if (weekday == 0) item(key = "weekday_$day") {
                                Text(broadcastWeekdayLabel(day), Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            items(animes, key = { it.uniqueKey }) { remote ->
                                val match = matches.getValue(remote.uniqueKey)
                                val subscribed = match.anime?.broadcastDay in 1..7
                                val enabled = !state.isImporting && !subscribed && !match.ambiguous
                                Row(
                                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                                        .background(if (remote.uniqueKey in state.selectedKeys) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow)
                                        .toggleable(remote.uniqueKey in state.selectedKeys, enabled = enabled, role = Role.Checkbox) { viewModel.toggleBroadcastSelection(remote.uniqueKey) }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ScheduleCover(remote.coverUrl)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(remote.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            when {
                                                subscribed -> "已订阅 · ${match.anime?.status.orEmpty()}"
                                                match.ambiguous -> "多个同名作品，请先关联资料来源"
                                                match.anime != null -> "${match.anime.status} · 已在资料库"
                                                else -> "新作品 · 加入想看"
                                            },
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Checkbox(subscribed || remote.uniqueKey in state.selectedKeys, onCheckedChange = null, enabled = enabled)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = viewModel::importSelectedBroadcasts, enabled = state.selectedKeys.isNotEmpty() && !state.isImporting, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isImporting) "正在导入…" else "导入已选 ${state.selectedKeys.size} 部")
                    }
                    TextButton(onClick = viewModel::importWatchingBroadcasts, enabled = watchingCount > 0 && !state.isImporting, modifier = Modifier.fillMaxWidth()) {
                        Text(if (watchingCount > 0) "一键导入在看作品的排期（$watchingCount 部）" else "暂未匹配到需要导入的在看作品")
                    }
                }
            } else if (calendar.followingAnimes.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有订阅排期", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { importingTab = true }) { Text("去发现新番") }
                }
            } else {
                Text("点击作品查看详情，右侧按钮调整星期和时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(calendar.followingAnimes, key = AnimeEntity::id) { anime ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable { onDismiss(); onAnimeClick(anime.id) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ScheduleCover(anime.coverUrl)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(anime.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("每${broadcastWeekdayLabel(anime.broadcastDay)} · ${anime.broadcastTime ?: "时间待定"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (!anime.hasActiveBroadcast()) Text("${anime.status} · 排期已暂停", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editingId = anime.id }, enabled = !state.isImporting) { Icon(Icons.Outlined.Tune, "调整${anime.title}的排期") }
                        }
                    }
                }
            }
        }
    }
    calendar.followingAnimes.firstOrNull { it.id == editingId }?.let { anime ->
        BroadcastScheduleEditDialog(
            anime,
            onSave = { day, time -> viewModel.updateBroadcastSchedule(anime.id, day, time); editingId = null },
            onRemove = { viewModel.updateBroadcastSchedule(anime.id, null, null); editingId = null },
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun ScheduleCover(url: String?) {
    Box(Modifier.size(width = 42.dp, height = 60.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        AsyncImage(url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BroadcastScheduleEditDialog(anime: AnimeEntity, onSave: (Int, String?) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var day by rememberSaveable(anime.id) { mutableIntStateOf(anime.broadcastDay ?: 1) }
    var time by rememberSaveable(anime.id) { mutableStateOf(anime.broadcastTime.orEmpty()) }
    val validTime = time.isBlank() || (Regex("\\d{2}:\\d{2}").matches(time.trim()) && runCatching { LocalTime.parse(time.trim()) }.isSuccess)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整更新排期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(anime.title, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { option -> FilterChip(day == option, { day = option }, label = { Text(broadcastWeekdayLabel(option)) }) }
                }
                OutlinedTextField(time, { time = it }, label = { Text("更新时间（可留空）") }, placeholder = { Text("例如 20:30") }, singleLine = true, isError = !validTime,
                    supportingText = { Text(if (validTime) "留空时仅显示更新星期" else "请填写 HH:mm 格式的有效时间") })
                TextButton(onClick = onRemove) { Text("取消这部作品的排期", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(day, time.trim().takeIf(String::isNotEmpty)) }, enabled = validTime) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
