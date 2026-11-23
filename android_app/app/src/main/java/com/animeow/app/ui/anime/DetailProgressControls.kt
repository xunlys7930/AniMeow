package com.animeow.app.ui.anime

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.animeow.app.data.bangumi.EpisodeNumbering
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons

internal fun episodeProgressLabel(watched: Int, total: Int): String =
    if (total > 0) "已看 $watched / $total 集" else "已看 $watched 集"

internal val LocalEpisodeNumbering = staticCompositionLocalOf<EpisodeNumbering?> { null }

internal fun detailProgressLabel(watched: Int, total: Int, numbering: EpisodeNumbering?): String =
    numbering?.takeIf { it.usesContinuousNumbering && it.totalEpisodes == total }
        ?.numberForWatchedCount(watched)?.let { "已看到第 $it 集" }
        ?: episodeProgressLabel(watched, total)

@Composable
internal fun EpisodeNumberingSummary(anime: AnimeEntity) {
    val numbering = LocalEpisodeNumbering.current?.takeIf { it.totalEpisodes == anime.totalEpisodes } ?: return
    if (!numbering.usesContinuousNumbering) return
    Text("本季已看 ${anime.watchedEpisodes} / ${anime.totalEpisodes} 集 · 第 ${numbering.firstNumber}～${numbering.lastNumber} 集",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An opt-in shortcut. Explicit editing remains available in the card and the page's more menu. */
@Composable
internal fun CombinedProgressButton(
    anime: AnimeEntity,
    onCheckIn: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val canIncrement = (anime.totalEpisodes <= 0 || anime.watchedEpisodes < anime.totalEpisodes) &&
        anime.watchedEpisodes < Int.MAX_VALUE
    val progress = detailProgressLabel(anime.watchedEpisodes, anime.totalEpisodes, LocalEpisodeNumbering.current)
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.medium)
            .testTag("combined-progress")
            .semantics { stateDescription = progress }
            .animatedPressClick(
                onClickLabel = if (canIncrement) "记录下一集" else "修改进度",
                onLongClickLabel = "精确修改观看进度",
                onLongClick = onEdit,
                onClick = if (canIncrement) onCheckIn else onEdit,
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = if (compact) 10.dp else 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(progress, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                if (compact && LocalEpisodeNumbering.current != null) {
                    Text("本季 ${anime.watchedEpisodes} / ${anime.totalEpisodes} 集", style = MaterialTheme.typography.labelSmall)
                }
                Text(if (canIncrement) "点按 +1 · 长按修改" else "点按或长按修改进度",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (!compact) AppIcon(if (canIncrement) AppIcons.Add else AppIcons.Edit, null, Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun ProgressEditAffordance(onEdit: () -> Unit) {
    IconButton(onClick = onEdit) {
        AppIcon(AppIcons.Edit, contentDescription = "修改进度", modifier = Modifier.size(20.dp))
    }
}
