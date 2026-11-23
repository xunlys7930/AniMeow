package com.animeow.app.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.isBookSubjectType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun WatchTimelineContent(anime: AnimeEntity, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val start = parseWatchDate(anime.watchStartDate)
    val finish = parseWatchDate(anime.watchFinishDate)
    val book = anime.subjectType.isBookSubjectType()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (book) "阅读足迹" else "追番足迹", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text(if (start == null && finish == null) "补充日期" else "编辑") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (start != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
            HorizontalDivider(Modifier.weight(1f).padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.size(8.dp).background(if (finish != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (book) "开始阅读" else "开始观看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(start?.format(WATCH_DATE_FORMAT) ?: "尚未记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (book) "完成阅读" else "完成观看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(finish?.format(WATCH_DATE_FORMAT) ?: "尚未记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        watchDurationDays(anime.watchStartDate, anime.watchFinishDate)?.let { days ->
            Text("共 $days 天的${if (book) "阅读" else "追番"}旅程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

internal fun watchDurationDays(start: String?, finish: String?): Long? {
    val first = parseWatchDate(start) ?: return null
    val last = parseWatchDate(finish) ?: return null
    return if (last.isBefore(first)) null else ChronoUnit.DAYS.between(first, last) + 1
}

private fun parseWatchDate(value: String?): LocalDate? =
    value?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private val WATCH_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")
