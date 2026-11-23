package com.animeow.app.ui.reminder

import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.preferences.ReminderPreferences
import com.animeow.app.data.preferences.ReminderSettings
import com.animeow.app.data.reminder.AnimeReminderScheduler
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.util.runCatchingCancellable
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.launch

private enum class ReminderLimitMode {
    UNLIMITED,
    COUNT,
    DATE,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AnimeReminderSheet(
    anime: AnimeEntity,
    onDismiss: () -> Unit,
    onSave: suspend (day: Int?, time: String?, remainingCount: Int?, untilDate: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { ReminderPreferences(context) }
    val settings by preferences.settings.collectAsState(initial = ReminderSettings())
    var enabled by rememberSaveable(anime.id) { mutableStateOf(true) }
    var day by rememberSaveable(anime.id) { mutableIntStateOf(anime.reminderDay ?: anime.broadcastDay?.takeIf { it in 1..7 } ?: LocalDate.now().dayOfWeek.value) }
    var time by rememberSaveable(anime.id) { mutableStateOf(anime.reminderTime ?: anime.broadcastTime?.takeIf { runCatching { LocalTime.parse(it) }.isSuccess } ?: "20:00") }

    val defaultSuggestedCount = remember(anime.id) {
        if (anime.totalEpisodes > anime.watchedEpisodes) {
            (anime.totalEpisodes - anime.watchedEpisodes).coerceAtLeast(1)
        } else {
            12
        }
    }
    val initialLimit = remember(anime.id) { AnimeReminderScheduler(context).getReminderLimit(anime.id) }
    var limitMode by rememberSaveable(anime.id) {
        mutableStateOf(
            when {
                initialLimit.first != null -> ReminderLimitMode.COUNT
                !initialLimit.second.isNullOrBlank() -> ReminderLimitMode.DATE
                else -> ReminderLimitMode.UNLIMITED
            }
        )
    }
    var countLimit by rememberSaveable(anime.id) {
        // 存下来的 0 表示"设定次数已用尽"，不是用户想要的次数；重新打开时给回建议值。
        mutableIntStateOf(initialLimit.first?.takeIf { it > 0 } ?: defaultSuggestedCount)
    }
    var untilDate by rememberSaveable(anime.id) {
        mutableStateOf(
            initialLimit.second ?: LocalDate.now().plusMonths(3).toString()
        )
    }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    StableModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(AppIcons.Notification, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("追番提醒", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(anime.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(value = enabled, enabled = !busy, role = Role.Switch, onValueChange = { enabled = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("每周提醒我", Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = null, enabled = !busy)
            }
            if (enabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEachIndexed { index, label ->
                        FilterChip(selected = day == index + 1, onClick = { day = index + 1 }, label = { Text(label) }, enabled = !busy)
                    }
                }
                OutlinedButton(onClick = {
                    val initial = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(20, 0))
                    TimePickerDialog(context, { _, hour, minute -> time = "%02d:%02d".format(Locale.ROOT, hour, minute) }, initial.hour, initial.minute, true).show()
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("提醒时间 · $time", style = MaterialTheme.typography.titleMedium) }

                Text("结束条件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = limitMode == ReminderLimitMode.UNLIMITED,
                        onClick = { limitMode = ReminderLimitMode.UNLIMITED },
                        label = { Text("一直循环") },
                        enabled = !busy,
                    )
                    FilterChip(
                        selected = limitMode == ReminderLimitMode.COUNT,
                        onClick = { limitMode = ReminderLimitMode.COUNT },
                        label = { Text("指定次数") },
                        enabled = !busy,
                    )
                    FilterChip(
                        selected = limitMode == ReminderLimitMode.DATE,
                        onClick = { limitMode = ReminderLimitMode.DATE },
                        label = { Text("截止日期") },
                        enabled = !busy,
                    )
                }

                when (limitMode) {
                    ReminderLimitMode.UNLIMITED -> {
                        Text("每周到点准时提醒，直到手动关闭。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    ReminderLimitMode.COUNT -> {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("剩余提醒次数", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    val hint = if (anime.totalEpisodes > anime.watchedEpisodes) "基于当前剩余 ${anime.totalEpisodes - anime.watchedEpisodes} 集预填" else "每次送达扣减 1 次，归零自动结束；错过的提醒不扣次数"
                                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilledTonalIconButton(
                                        onClick = { if (countLimit > 1) countLimit-- },
                                        enabled = !busy && countLimit > 1,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Text("-", style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text("$countLimit 次", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                                    FilledTonalIconButton(
                                        onClick = { if (countLimit < 999) countLimit++ },
                                        enabled = !busy && countLimit < 999,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Text("+", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    }
                    ReminderLimitMode.DATE -> {
                        OutlinedButton(
                            onClick = {
                                val parsed = runCatching { LocalDate.parse(untilDate) }.getOrDefault(LocalDate.now().plusMonths(3))
                                DatePickerDialog(context, { _, year, month, dayOfMonth ->
                                    untilDate = "%04d-%02d-%02d".format(Locale.ROOT, year, month + 1, dayOfMonth)
                                }, parsed.year, parsed.monthValue - 1, parsed.dayOfMonth).show()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("提醒截至 · $untilDate", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("超过该日期后将自动停止提醒。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }

                ReminderPermissionStatus(configured = true)
                if (settings.paused) Text("所有追番提醒目前已暂停。这里保存的时间会保留，请到「我的 → 追番提醒」恢复通知。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else Text(if (settings.leadMinutes > 0) "按当前统一设置提前 ${settings.leadMinutes} 分钟通知。" else "每周到点通过系统通知提醒，可随时在这里调整。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else Text("保存后将关闭这部作品的提醒，作品和观看进度会保留。", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if (!busy) scope.launch {
                    busy = true
                    error = null
                    val finalCount = if (enabled && limitMode == ReminderLimitMode.COUNT) countLimit else null
                    val finalDate = if (enabled && limitMode == ReminderLimitMode.DATE) untilDate else null
                    runCatchingCancellable { onSave(day.takeIf { enabled }, time.takeIf { enabled }, finalCount, finalDate) }
                        .onSuccess { onDismiss() }
                        .onFailure { error = it.message ?: "提醒保存失败，请重试" }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (enabled) "保存提醒" else "确认关闭提醒")
            }
        }
    }
}

