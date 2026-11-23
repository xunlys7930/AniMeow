package com.animeow.app.ui.anime

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.DetailProgressMode
import com.animeow.app.ui.theme.LocalDetailProgressMode

@Composable
internal fun DetailPoster(anime: AnimeEntity, modifier: Modifier, onCoverClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .clickable(enabled = !anime.coverUrl.isNullOrBlank(), role = Role.Button,
            onClickLabel = "查看封面大图", onClick = onCoverClick), contentAlignment = Alignment.Center) {
        if (anime.coverUrl.isNullOrBlank()) {
            Text(anime.title.take(2), color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        } else {
            AsyncImage(anime.coverUrl, "《${anime.title}》封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun MinimalDetailHeader(anime: AnimeEntity, onCoverClick: () -> Unit) {
    val isLandscape = anime.coverOrientation == "landscape"
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("作品档案  /  ${subjectTypeDisplayName(anime.subjectType)}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        if (isLandscape) {
            DetailPoster(anime, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onCoverClick)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(anime.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                anime.originalTitle?.takeIf { it.isNotBlank() && it != anime.title }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(anime.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    anime.originalTitle?.takeIf { it.isNotBlank() && it != anime.title }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(anime.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                DetailPoster(anime, Modifier.width(66.dp).aspectRatio(com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, 66f / 96f)), onCoverClick)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun DashboardDetailHeader(anime: AnimeEntity, onCoverClick: () -> Unit) {
    val isLandscape = anime.coverOrientation == "landscape"
    DetailSurface(Modifier.fillMaxWidth()) {
        if (isLandscape) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailPoster(anime, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onCoverClick)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(listOf(subjectTypeDisplayName(anime.subjectType), anime.status).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    anime.originalTitle?.takeIf { it.isNotBlank() && it != anime.title }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                DetailPoster(anime, Modifier.width(56.dp).aspectRatio(com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, 56f / 80f)), onCoverClick)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(listOf(subjectTypeDisplayName(anime.subjectType), anime.status).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    anime.originalTitle?.takeIf { it.isNotBlank() && it != anime.title }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TheaterDetailHeader(anime: AnimeEntity, onCoverClick: () -> Unit) {
    val stageColor = Color(0xFF141B29)
    val isLandscape = anime.coverOrientation == "landscape"
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(stageColor)) {
        Box(Modifier.fillMaxWidth().height(286.dp)) {
            if (!anime.coverUrl.isNullOrBlank()) {
                AsyncImage(anime.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, stageColor))))
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = if (isLandscape) 24.dp else 116.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (isLandscape) {
                DetailPoster(anime, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onCoverClick)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(subjectTypeDisplayName(anime.subjectType), color = Color(0xFFC6D1E6), style = MaterialTheme.typography.labelMedium)
                    Text(anime.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    anime.originalTitle?.takeIf { it.isNotBlank() && it != anime.title }?.let {
                        Text(it, color = Color(0xFFBFC9DA), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(anime.status, color = Color.White, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                    DetailPoster(anime, Modifier.width(98.dp).aspectRatio(com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, 98f / 146f)), onCoverClick)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(subjectTypeDisplayName(anime.subjectType), color = Color(0xFFC6D1E6), style = MaterialTheme.typography.labelMedium)
                        Text(anime.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        anime.originalTitle?.takeIf { it.isNotBlank() && it != anime.title }?.let {
                            Text(it, color = Color(0xFFBFC9DA), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(anime.status, color = Color.White, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
            val subtitle = listOfNotNull(anime.airDate?.take(4)?.takeIf { it.isNotBlank() },
                anime.mediaFormat?.takeIf { it.isNotBlank() }, anime.studio?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
            if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xFFC6D1E6), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun DistinctDetailProgress(anime: AnimeEntity, layout: DetailLayout, onEdit: () -> Unit, onCheckIn: () -> Unit,
    episodeEntry: (@Composable () -> Unit)? = null) {
    val hasTotal = anime.totalEpisodes > 0
    val target = if (hasTotal) (anime.watchedEpisodes.toFloat() / anime.totalEpisodes).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(target, motionAnimationSpec(420), label = "detail-layout-progress")
    val canIncrement = !hasTotal || anime.watchedEpisodes < anime.totalEpisodes
    val combined = LocalDetailProgressMode.current == DetailProgressMode.COMBINED
    val progressLabel = detailProgressLabel(anime.watchedEpisodes, anime.totalEpisodes, LocalEpisodeNumbering.current)
    if (layout == DetailLayout.DASHBOARD) {
        DetailSurface(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("观看进度", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (combined) ProgressEditAffordance(onEdit)
                }
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), strokeWidth = 7.dp,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${anime.watchedEpisodes}", style = if (anime.watchedEpisodes < 10000) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text(if (LocalEpisodeNumbering.current != null) "本季已看" else "已看集数", style = MaterialTheme.typography.labelSmall)
                    }
                }
                EpisodeNumberingSummary(anime)
                if (combined) {
                    CombinedProgressButton(anime, onCheckIn, onEdit, Modifier.fillMaxWidth())
                } else {
                    Text(progressLabel, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Button(onClick = onCheckIn, enabled = canIncrement, modifier = Modifier.fillMaxWidth()) { Text("+1 集") }
                    TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) { Text("修改进度") }
                }
                episodeEntry?.invoke()
            }
        }
        return
    }
    DetailSurface(Modifier.fillMaxWidth(), tonalContainerColor = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("观看进度", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (!combined) Text(progressLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if (combined) {
                    ProgressEditAffordance(onEdit)
                } else if (layout == DetailLayout.MINIMAL) {
                    FilledIconButton(onClick = onCheckIn, enabled = canIncrement) { AppIcon(AppIcons.Add, "记录下一集") }
                }
            }
            EpisodeNumberingSummary(anime)
            if (hasTotal) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (combined) {
                CombinedProgressButton(anime, onCheckIn, onEdit, Modifier.fillMaxWidth())
            } else if (layout == DetailLayout.THEATER) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Button(onClick = onCheckIn, enabled = canIncrement, modifier = Modifier.fillMaxWidth()) {
                        AppIcon(AppIcons.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (canIncrement) "记录下一集" else "已记录全部集数")
                    }
                    TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("修改进度") }
                }
            } else TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) { Text("修改进度") }
            episodeEntry?.invoke()
        }
    }
}

@Composable
internal fun DashboardWatchDates(anime: AnimeEntity, onEdit: () -> Unit) {
    val book = anime.subjectType.isBookSubjectType()
    DetailSurface(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (book) "阅读足迹" else "追番足迹", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            DateMilestone(if (book) "开始阅读" else "开始观看", anime.watchStartDate)
            DateMilestone(if (book) "完成阅读" else "完成观看", anime.watchFinishDate)
            DateMilestone("加入资料库", anime.createdAt)
            TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) { Text("编辑日期") }
        }
    }
}

@Composable
private fun DateMilestone(label: String, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(3.dp, 32.dp).clip(RoundedCornerShape(2.dp)).background(
            if (value.isNullOrBlank()) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value?.take(10)?.takeIf { it.isNotBlank() } ?: "尚未记录", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun DetailMetadataHighlights(anime: AnimeEntity) {
    val rating = normalizeAnimeRatingGrade(anime.ratingGrade) ?: formatAnimeRating(anime.rating).takeIf { it.isNotBlank() } ?: "—"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("资料评分" to rating, "总集数" to anime.totalEpisodes.takeIf { it > 0 }?.toString().orEmpty().ifBlank { "—" },
            "首播年份" to anime.airDate?.take(4).orEmpty().ifBlank { "—" }).forEach { (label, value) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
