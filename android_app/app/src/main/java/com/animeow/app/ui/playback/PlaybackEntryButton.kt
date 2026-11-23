package com.animeow.app.ui.playback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.playback.PlaybackHistory
import com.animeow.app.data.playback.PlaybackIdentities
import com.animeow.app.data.playback.PlaybackAssociations
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons

@Composable
internal fun PlaybackEntryButton(anime: AnimeEntity) {
    if (normalizeSubjectType(anime.subjectType) != "anime") return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val history = remember { PlaybackHistory(context) }
    val key = remember(anime.id, anime.createdAt) { PlaybackIdentities(context).key(anime) }
    var checkpoint by remember(key) { mutableStateOf(history.read(key)) }
    val label = remember(key, checkpoint) {
        PlaybackAssociations(context).read(key)?.takeIf { it.source == checkpoint?.source && it.work.id == checkpoint?.workId }
            ?.lines?.flatMap { it.episodes }?.firstOrNull { it.id == checkpoint?.episodeId }?.label
    }
    DisposableEffect(lifecycle, key) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) checkpoint = history.read(key) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    FilledTonalButton(onClick = { context.startActivity(PlaybackActivity.intent(context, anime.id)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp).heightIn(min = 56.dp)) {
        Column {
            Text(if (checkpoint == null) "观看" else if (checkpoint!!.durationMs > 0 && checkpoint!!.positionMs >= checkpoint!!.durationMs - 1000) "重播${label ?: "上次一集"}" else "继续观看")
            checkpoint?.let { Text("${label ?: "上次播放"} · ${playbackTime(it.positionMs)}", style = MaterialTheme.typography.labelSmall) }
            if (checkpoint == null) Text("首次需要选择来源", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun WatchDiscussionEntryButton(anime: AnimeEntity) {
    if (normalizeSubjectType(anime.subjectType) != "anime") return
    val context = LocalContext.current
    OutlinedButton(
        onClick = { context.startActivity(WatchDiscussionActivity.library(context, anime.id)) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIcon(AppIcons.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            Column {
                Text("作品讨论与单集评论")
                Text("查看或发表感想、单集吐槽与社区讨论", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
