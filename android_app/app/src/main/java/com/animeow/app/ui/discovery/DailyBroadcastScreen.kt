package com.animeow.app.ui.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.remote.BroadcastEntry
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.ui.components.DisplayPill
import com.animeow.app.ui.components.LoadErrorCard
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.navigation.LocalFloatingNavigationInset
import com.animeow.app.ui.screens.RemoteCover
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DailyBroadcastScreen(
    onBack: () -> Unit,
    onSelect: (RemoteAnime) -> Unit,
    isAdded: (RemoteAnime) -> Boolean,
    modifier: Modifier = Modifier,
    viewModel: DailyBroadcastViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    BackHandler(onBack = onBack)
    LaunchedEffect(viewModel, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { viewModel.syncClock(); delay(30_000) }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
        bottom = maxOf(104.dp, LocalFloatingNavigationInset.current + 16.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onBack) { Text("返回发现") }
                    TextButton(onClick = { viewModel.load(true) }, enabled = !state.loading) { Text("刷新放送安排") }
                }
                Text(if (state.date == state.today) "今日放送" else "本周放送", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisplayPill(state.date.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.SIMPLIFIED_CHINESE)))
                    state.data?.let { data -> DisplayPill("已加载 ${data.entries.map { it.anime.uniqueKey }.distinct().size} 部 · ${data.entries.size} 条安排") }
                }
                Text("时间按 ${state.zone.id} 显示 · 结合 Bangumi 与 AniList 放送安排", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(broadcastWeek(state.today), key = { it.toEpochDay() }) { date ->
                        FilterChip(selected = state.date == date, onClick = { viewModel.selectDate(date) },
                            label = { Text((if (date == state.today) "今天" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE)) + " ${date.dayOfMonth}") })
                    }
                }
                Text("放送安排不代表视频平台已更新，可能延播或调整；来源未收录的安排不会出现在列表中。历史安排可能不完整。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("loading") {
            AnimatedVisibility(visible = state.loading, enter = motionExpandIn(), exit = motionShrinkOut()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.error?.let { message -> item("error") {
            LoadErrorCard("放送安排未能更新", message, { viewModel.load(true) })
        } }
        state.data?.let { data ->
            item("freshness") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("资料时间：${data.updatedAt.atZone(state.zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    data.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (data.entries.isEmpty() && !state.loading) item("empty") {
                Text(if (data.warnings.isEmpty()) "来源暂未列出这一天的放送安排，可查看本周其他日期或稍后刷新" else "暂未取得可显示的安排，请重试或查看本周其他日期",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(data.entries, key = { it.id }) { entry ->
                BroadcastCard(entry, state.now, state.zone, isAdded(entry.anime), motionAnimateItem()) { onSelect(entry.anime) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BroadcastCard(
    entry: BroadcastEntry,
    now: Instant,
    zone: java.time.ZoneId,
    added: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val anime = entry.anime
    val passed = !entry.airingAt.isAfter(now)
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardDefaults.elevatedShape)
            .animatedPressClick(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteCover(anime, Modifier.size(width = 70.dp, height = 100.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(anime.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(anime.studio, anime.episodes?.let { "共 $it 集" }).joinToString(" · ")
                if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                anime.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.airingAt.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("第 ${entry.episode} 集 · ${if (passed) "已到放送时间" else "待放送"}", style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    anime.score?.let { DisplayPill("评分 ${String.format(Locale.ROOT, "%.1f", it / 10)}") }
                    if (added) DisplayPill("已收藏")
                    anime.format?.let { DisplayPill(it) }
                }
            }
        }
    }
}
