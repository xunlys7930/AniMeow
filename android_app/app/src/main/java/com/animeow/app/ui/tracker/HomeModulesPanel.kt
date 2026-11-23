@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.animeow.app.ui.tracker

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.preferences.*

@Composable
internal fun HomeModulesPanel(sections: TrackerHomeSections, settings: TrackerSettings, onOpen: (Long) -> Unit, onEdit: (Long) -> Unit, onProgress: (AnimeEntity) -> Unit, progressLabel: String = "看过一集 +1", editLabel: String = "编辑资料") {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        if (settings.showHomeCalendar || settings.showRecentPlayback) Text("今天 · ${sections.date}", style = MaterialTheme.typography.titleMedium)
        settings.homeModuleOrder.filter(settings::shows).forEach { module ->
            if (module == HomeModule.PLAYBACK) HomePlaybackModule(onOpen)
            else if (module == HomeModule.RECAP) WeeklyRecapCard(sections.recap) else {
                val animes = when (module) {
                    HomeModule.CONTINUE -> sections.reading
                    HomeModule.RECENT -> sections.recentAdded
                    HomeModule.INCOMPLETE -> sections.incomplete
                    HomeModule.CALENDAR -> sections.todaySchedule
                    else -> emptyList()
                }
                if (animes.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(module.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        Text("${animes.size} 部", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(animes.take(12), key = { it.id }) { anime ->
                            Surface(Modifier.width(300.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = MaterialTheme.shapes.small) {
                                        AsyncImage(anime.coverUrl, null, Modifier.size(80.dp, 120.dp).clickable { onOpen(anime.id) }, contentScale = ContentScale.Crop)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(anime.title, modifier = Modifier.fillMaxWidth().clickable { onOpen(anime.id) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                        if (module == HomeModule.CALENDAR) Text("本机排期 · ${anime.reminderTime ?: "时间未设置"}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                        else Text(if (anime.subjectType.isBookSubjectType()) "阅读进度 ${anime.watchedEpisodes} / ${anime.totalEpisodes.takeIf { it > 0 } ?: "?"}" else "${anime.watchedEpisodes} / ${anime.totalEpisodes.takeIf { it > 0 } ?: "?"} 集", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = {
                                            if (module == HomeModule.CONTINUE || module == HomeModule.CALENDAR) onProgress(anime) else onEdit(anime.id)
                                        }, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text(if (module == HomeModule.CONTINUE || module == HomeModule.CALENDAR) (if (anime.subjectType.isBookSubjectType() && progressLabel == "看过一集 +1") "阅读进度 +1" else progressLabel) else editLabel)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (module == HomeModule.CALENDAR || module == HomeModule.INCOMPLETE) {
                    Text(if (module == HomeModule.CALENDAR) "今天没有排期，按自己的节奏看。" else "封面与简介已补齐。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun WeeklyRecapCard(recap: WeeklyRecap) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("我的本周回顾", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${recap.startDate} — ${recap.endDate}", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                listOf("+${recap.episodeCount}" to "记录进度", "${recap.workCount} 部" to "本周作品", "${recap.activeDays} 天" to "留下记录").forEach { (value, label) ->
                    Column { Text(value, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Text(if (recap.titles.isEmpty()) "一部好作品，什么时候开始都不晚。" else recap.titles.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val content = "我的 AniMeow 本周回顾（${recap.startDate}—${recap.endDate}）\n进度增加 ${recap.episodeCount} · ${recap.workCount} 部作品 · ${recap.activeDays} 天\n${recap.titles.joinToString("、")}"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, content), "分享本周回顾"))
            }) { Text("分享回顾") }
        }
    }
}
