package com.animeow.app.ui.tracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.playback.*
import com.animeow.app.ui.playback.PlaybackActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun playbackEntryMatches(row: PlaybackHistoryEntry, anime: AnimeEntity?, key: (AnimeEntity) -> String): Boolean =
    anime != null && anime.id == row.animeId && anime.deletedAt == null && anime.subjectType == "anime" && key(anime) == row.key

internal fun activePlaybackEntries(rows: List<PlaybackHistoryEntry>, animes: List<AnimeEntity>, key: (AnimeEntity) -> String): List<PlaybackHistoryEntry> {
    val active = animes.filter { it.deletedAt == null && it.subjectType == "anime" }.associateBy { it.id }
    return rows.mapNotNull { row -> active[row.animeId]?.takeIf { playbackEntryMatches(row, it, key) }?.let {
        row.copy(title = it.title, cover = it.coverUrl.orEmpty())
    } }.sortedByDescending { it.updatedAt }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomePlaybackModule(onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AniMeowApplication
    val history = remember(context) { PlaybackHistory(context) }
    val identities = remember(context) { PlaybackIdentities(context) }
    val animes by remember(app) { app.libraryRepository.observeAnimes() }.collectAsStateWithLifecycle(initialValue = null)
    var rows by remember { mutableStateOf<List<PlaybackHistoryEntry>?>(null) }
    var nextEpisodes by remember { mutableStateOf(emptyMap<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(animes, refresh) {
        val currentAnimes = animes ?: return@LaunchedEffect
        try {
            val loaded = withContext(Dispatchers.IO) {
                val associations = PlaybackAssociations(context)
                currentAnimes.filter { it.subjectType == "anime" }.forEach { anime ->
                    val key = identities.key(anime)
                    val checkpoint = history.read(key) ?: return@forEach
                    val label = associations.read(key)?.lines?.flatMap { it.episodes }?.firstOrNull { it.id == checkpoint?.episodeId }?.label.orEmpty()
                    history.importExisting(key, anime, label)
                }
                val entries = activePlaybackEntries(history.entries(), currentAnimes, identities::key).take(12)
                entries to entries.mapNotNull { row -> completedPlaybackNext(row.checkpoint, associations.read(row.key))?.let { row.key to playbackResumeToken(row.checkpoint!!) } }.toMap()
            }
            rows = loaded.first; nextEpisodes = loaded.second
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "最近观看暂时无法读取，请重试" }
    }
    fun open(row: PlaybackHistoryEntry, details: Boolean, nextFrom: String? = null) { scope.launch {
        try {
            val anime = app.database.libraryDao().getAnime(row.animeId)
            if (!playbackEntryMatches(row, anime, identities::key)) {
                error = "作品记录已变化，请刷新或在播放历史中管理"; refresh++
            } else if (details) onOpen(row.animeId)
            else context.startActivity(PlaybackActivity.intent(context, row.animeId).apply { nextFrom?.let { putExtra("nextFromCheckpoint", it) } })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "暂时无法打开作品，请重试" }
    } }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("最近观看", style = MaterialTheme.typography.titleSmall)
        TextButton(
            onClick = { context.startActivity(PlaybackActivity.history(context)) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("全部播放历史", style = MaterialTheme.typography.labelMedium)
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text("重试") } }
    if (rows == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (rows?.isEmpty() == true) Text("还没有可接续的观看记录，可从作品详情开始观看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!rows.isNullOrEmpty()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = minOf(maxWidth * 0.9f, 300.dp)
            val listState = rememberLazyListState()
            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Start),
                contentPadding = PaddingValues(end = maxWidth - cardWidth),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows.orEmpty(), key = { it.key }) { row ->
                    RecentPlaybackCard(row, onOpen = { open(row, true) }, onResume = { open(row, false) },
                        modifier = Modifier.width(cardWidth),
                        onNext = nextEpisodes[row.key]?.let { from -> { open(row, false, from) } })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecentPlaybackCard(row: PlaybackHistoryEntry, onOpen: () -> Unit, onResume: () -> Unit, modifier: Modifier = Modifier, onNext: (() -> Unit)? = null) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                com.animeow.app.ui.playback.PlaybackCover(row.cover, modifier = Modifier.size(64.dp, 88.dp).clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClickLabel = "打开作品详情", onClick = onOpen)
                    .semantics { contentDescription = "打开封面作品详情：${row.title}" })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "打开作品详情", onClick = onOpen)
                        .semantics { contentDescription = "打开作品详情：${row.title}" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                row.episode.ifBlank { "上次观看" },
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("查看详情", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (playbackCompleted(row.checkpoint)) Text("本集已播完",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text(if (playbackCompleted(row.checkpoint)) "重播本集" else "继续观看")
                        }
                        if (onNext != null) TextButton(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("播放下一集") }
                    }
                }
            }
            row.checkpoint?.let { checkpoint ->
                Text("${com.animeow.app.ui.playback.playbackTime(checkpoint.positionMs)} / ${if (checkpoint.durationMs > 0) com.animeow.app.ui.playback.playbackTime(checkpoint.durationMs) else "时长未知"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (checkpoint.durationMs > 0) LinearProgressIndicator(
                    progress = { (checkpoint.positionMs.toDouble() / checkpoint.durationMs).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            }
        }
    }
}
