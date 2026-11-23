package com.animeow.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleEventEffect
import com.animeow.app.data.community.WatchItem
import com.animeow.app.data.community.WatchService
import com.animeow.app.ui.playback.WatchDiscussionActivity
import com.animeow.app.ui.playback.openWatchTimestamp
import com.animeow.app.ui.playback.playbackTime
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchReferenceCard(reference: WatchItem?, referenceId: Long? = null, onRemove: (() -> Unit)? = null, overrideApi: com.animeow.app.data.community.WatchApi? = null) {
    val context = LocalContext.current
    val api = remember(context, overrideApi) { overrideApi ?: WatchService(context) }
    var refreshed by remember(reference, referenceId) { mutableStateOf<WatchItem?>(null) }
    var checked by remember(reference, referenceId) { mutableStateOf(false) }
    var sequence by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable(reference?.id, referenceId) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checked = false; refreshed = null; sequence++ }
    LaunchedEffect(reference, referenceId, sequence) {
        val id = referenceId ?: reference?.id?.toLongOrNull()
        if (id == null || reference?.unavailable == true) { checked = true; return@LaunchedEffect }
        val session = api.session()
        try { val value = api.item(id, session); if (api.session() == session) refreshed = value }
        catch (e: CancellationException) { throw e }
        catch (_: Throwable) { refreshed = null }
        checked = true
    }
    val item = refreshed?.takeUnless { it.unavailable }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text("作品评论引用", style = MaterialTheme.typography.labelMedium)
        if (item == null) Text(if (checked) "原评论暂不可查看" else "正在核对原评论…")
        else {
            Text("${item.title} · ${item.episodeTitle}", style = MaterialTheme.typography.titleSmall)
            Text(item.nickname, style = MaterialTheme.typography.bodySmall)
            if (item.spoiler && !expanded) TextButton(onClick = { expanded = true }) { Text("包含剧透，点击展开") }
            else Text(item.content.take(200) + if (item.content.length > 200) "…" else "")
            FlowRow {
                TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, "https://bgm.tv/subject/${item.subjectId}".toUri())) }) { Text("作品资料（Bangumi）") }
                item.id.toLongOrNull()?.let { commentId ->
                    TextButton(onClick = { context.startActivity(WatchDiscussionActivity.intent(context, item.subjectId, item.episodeId, commentId)) }) { Text("查看原评论") }
                }
                TextButton(onClick = { context.startActivity(WatchDiscussionActivity.intent(context, item.subjectId, 0)) }) { Text("作品总讨论") }
                if (item.episodeId > 0) TextButton(onClick = { context.startActivity(WatchDiscussionActivity.intent(context, item.subjectId, item.episodeId)) }) { Text("本集讨论") }
                item.timeMs?.let { time -> TextButton(onClick = { openWatchTimestamp(context, item.subjectId, item.episodeId, time) }) { Text("定位 ${playbackTime(time)}") } }
            }
        }
        onRemove?.let { TextButton(onClick = it) { Text("移除引用") } }
    } }
}
