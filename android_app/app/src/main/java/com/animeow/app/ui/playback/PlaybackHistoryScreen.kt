package com.animeow.app.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.playback.*
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionShrinkOut
import kotlinx.coroutines.launch

/** Marks the detail activity opened from this list so back returns here. */
internal const val EXTRA_RETURN_TO_HISTORY = "playback_history_return"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackHistoryScreen(back: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AniMeowApplication
    val history = remember { PlaybackHistory(context) }
    var rows by remember { mutableStateOf(history.entries()) }
    var removing by remember { mutableStateOf<List<PlaybackHistoryEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(history) {
        val identities = PlaybackIdentities(context)
        val associations = PlaybackAssociations(context)
        app.database.libraryDao().getActiveAnimes().filter { it.subjectType == "anime" }.forEach { anime ->
            val key = identities.key(anime)
            val checkpoint = history.read(key)
            val label = associations.read(key)?.lines?.flatMap { it.episodes }?.firstOrNull { it.id == checkpoint?.episodeId }?.label.orEmpty()
            history.importExisting(key, anime, label)
        }
        rows = history.entries()
    }
    fun open(row: PlaybackHistoryEntry, fromStart: Boolean, details: Boolean = false, next: Boolean = false) { scope.launch {
        val anime = app.database.libraryDao().getAnime(row.animeId)
        if (anime == null || anime.deletedAt != null || PlaybackIdentities(context).key(anime) != row.key) {
            error = "原作品已移除或记录已变化，可移除此条历史；下载仍可在下载管理中查看。"
        } else {
            if (details) {
                context.startActivity(android.content.Intent(context, com.animeow.app.MainActivity::class.java)
                    .putExtra(com.animeow.app.data.reminder.AnimeReminderNotifier.EXTRA_ANIME_ID, row.animeId)
                    .putExtra(EXTRA_RETURN_TO_HISTORY, true))
                return@launch
            }
            if (fromStart) row.checkpoint?.let { history.save(row.key, it.copy(positionMs = 0)) }
            context.startActivity(PlaybackActivity.intent(context, row.animeId).apply {
                if (next) row.checkpoint?.let { putExtra("nextFromCheckpoint", playbackResumeToken(it)) }
            })
        }
    } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("播放历史") },
                navigationIcon = {
                    IconButton(onClick = back) {
                        com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Back, contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = { removing = rows }, enabled = rows.isNotEmpty()) { Text("清空") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "error") {
                AnimatedVisibility(visible = error != null, enter = motionExpandIn(), exit = motionShrinkOut()) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (rows.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.animeow.app.ui.icons.AppIcon(
                        com.animeow.app.ui.icons.AppIcons.History,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Text("还没有播放历史", style = MaterialTheme.typography.titleLarge)
                    Text("开始观看后，会在这里保留续播位置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = back) { Text("返回收藏") }
                }
            }
            items(rows, key = { it.key }) { row ->
                ElevatedCard(Modifier.fillMaxWidth().then(motionAnimateItem()).motionAnimateContentSize()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlaybackCover(row.cover)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOf(row.episode, row.checkpoint?.let { "${playbackTime(it.positionMs)} / ${if (it.durationMs > 0) playbackTime(it.durationMs) else "时长未知"}" }).filterNotNull().joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.updatedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(
                                onClick = { open(row, false) },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text(if (playbackCompleted(row.checkpoint)) "重播本集" else "继续观看")
                            }
                            if (remember(row) { completedPlaybackNext(row.checkpoint, PlaybackAssociations(context).read(row.key)) } != null)
                                OutlinedButton(onClick = { open(row, false, next = true) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("播放下一集") }
                            if (!playbackCompleted(row.checkpoint)) TextButton(onClick = { open(row, true) }) { Text("从头播放") }
                            TextButton(onClick = { open(row, false, true) }) { Text("查看详情") }
                            TextButton(onClick = { removing = listOf(row) }) { Text("移除") }
                        }
                    }
                }
            }
        }
    }
    removing?.let { selected -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("清除播放历史？") },
        text = { Text("仅清除这 ${selected.size} 部作品的播放历史和续播位置，保留资料库已看进度、评分和下载。") },
        confirmButton = { TextButton(onClick = { selected.forEach { history.remove(it.key) }; rows = history.entries(); removing = null }) { Text("清除历史") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("保留") } }) }
}
