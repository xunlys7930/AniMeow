@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.animeow.app.ui.playback

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.awaitCancellation
import androidx.media3.exoplayer.offline.Download
import com.animeow.app.data.playback.PlaybackDownloadRow
import com.animeow.app.data.remote.PlaybackEpisode
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons

internal fun playbackSize(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.0f KB".format(bytes / 1024.0)
}

@Composable
internal fun rememberCacheAction(model: PlaybackViewModel): (List<PlaybackEpisode>) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(emptyList<PlaybackEpisode>()) }
    var explain by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("playback_download_preferences", 0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.cacheEpisodes(pending); pending = emptyList()
    }
    if (explain) AlertDialog(onDismissRequest = { explain = false; model.cacheEpisodes(pending); pending = emptyList() },
        title = { Text("下载通知") }, text = { Text("开启通知可在后台查看下载进度。未开启时，也可在任务页查看。") },
        confirmButton = { TextButton(onClick = { explain = false; preferences.edit().putBoolean("notificationExplained", true).apply(); launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { explain = false; preferences.edit().putBoolean("notificationExplained", true).apply(); model.cacheEpisodes(pending); pending = emptyList() }) { Text("暂不开启") } })
    return { episodes ->
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notificationExplained", false)) {
            pending = episodes; explain = true
        } else model.cacheEpisodes(episodes)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackDownloadLibrary(model: PlaybackViewModel, onBack: () -> Unit) {
    PlaybackDisclaimer("downloads")
    val state by model.state.collectAsStateWithLifecycle()
    ObservePlaybackDownloads(model, state.loaded)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("下载管理") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Back, contentDescription = "返回")
                }
            },
        )
    }) { padding ->
        PlaybackDownloadPanel(state, model, Modifier.fillMaxSize().padding(padding), library = true, onBack = onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackDownloadPanel(state: PlaybackUiState, model: PlaybackViewModel, modifier: Modifier, library: Boolean = false, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val completed = tab == 1
    var cleanup by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var showResults by remember { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<List<PlaybackDownloadRow>?>(null) }
    var settings by remember { mutableStateOf(false) }
    var mobileNetwork by remember { mutableStateOf(false) }
    val visible = visibleDownloads(state.downloads, tab, group, cleanup)
    LaunchedEffect(tab, group, cleanup) { selected = emptySet() }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (group != null) "作品任务" else if (library) "空间与任务" else "下载管理", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { settings = true }) { Text("设置与空间") }
            }
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("视频占用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(playbackSize(state.downloads.sumOf { it.bytes }), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("设备可用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (state.availableBytes > 0) playbackSize(state.availableBytes) else "未知", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (group != null) TextButton(onClick = { group = null }) { Text("返回全部作品") }
            PrimaryTabRow(tab) {
                Tab(tab == 0, { tab = 0; cleanup = false }, text = { Text("下载中") })
                Tab(tab == 1, { tab = 1 }, text = { Text("已下载") })
                Tab(tab == 2, { tab = 2; cleanup = false }, text = { Text("失败") })
            }
            state.downloadNotice?.let { message ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.downloadResults.isNotEmpty()) TextButton(onClick = { showResults = true }) { Text("查看逐项重试结果") }
            if (state.downloadBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                Text("通知未开启，可在这里查看下载进度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!completed && visible.isNotEmpty()) FlowRow {
                val running = visible.filter { it.state in listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING) }
                val paused = visible.filter { it.state == Download.STATE_STOPPED }
                if (running.isNotEmpty()) TextButton(onClick = { running.forEach(model::pauseDownload) }) { Text("全部暂停") }
                if (paused.isNotEmpty()) TextButton(onClick = { paused.forEach(model::resumeDownload) }) { Text("继续已暂停任务") }
                val failed = visible.filter { it.state == Download.STATE_FAILED }
                if (failed.isNotEmpty()) TextButton(onClick = { model.retryDownloads(failed) }, enabled = !state.downloadBusy) { Text("重试全部失败（${failed.size}）") }
            }
            if (completed && visible.isNotEmpty()) {
                TextButton(onClick = { cleanup = !cleanup }) { Text(if (cleanup) "退出空间清理" else "按大小清理下载") }
                if (cleanup) {
                    Text("按占用空间从大到小排列，勾选后统一删除。", style = MaterialTheme.typography.bodySmall)
                    val chosen = visible.filter { it.id in selected }
                    TextButton(onClick = { deleting = chosen }, enabled = chosen.isNotEmpty()) { Text("清理 ${chosen.size} 集 · ${playbackSize(chosen.sumOf { it.bytes })}") }
                }
            }
            if (visible.isEmpty()) Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(if (tab == 2) AppIcons.CheckCircle else AppIcons.Download, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(when { tab == 2 -> "没有失败的下载任务"; completed -> "还没有已完成下载"; state.downloads.isEmpty() -> "还没有下载内容"; else -> "没有进行中的下载任务" }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(when {
                        tab == 2 -> "当前范围内没有需要重试的任务。"
                        completed -> "完成下载的集数会出现在这里，可直接离线播放。"
                        state.downloads.isEmpty() -> "在作品的选集区选择需要下载的集数。"
                        else -> "可切换到“已下载”查看本地视频。"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    onBack?.let { TextButton(onClick = it) { Text("返回收藏") } }
                }
            }
            }
        }
        if (library && group == null && !cleanup) {
            items(visible.groupBy { it.info.workKey }.toList(), key = { it.first }) { (key, rows) ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.clickable { group = key }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(rows.first().info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${rows.size} 集 · ${playbackSize(rows.sumOf { it.bytes })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val failed = rows.count { it.state == Download.STATE_FAILED }
                        Surface(shape = MaterialTheme.shapes.small, color = if (failed > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                            Text(if (completed) "可离线播放" else if (failed > 0) "$failed 项失败" else rows.first().status,
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                                color = if (failed > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text("查看任务", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else {
            if (visible.isNotEmpty() && !cleanup) item {
                TextButton(onClick = { deleting = visible.filter { it.state != Download.STATE_REMOVING } }) { Text(if (completed) "删除这 ${visible.size} 集下载" else "取消这 ${visible.size} 个任务") }
            }
            items(visible, key = { it.id }) { row ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (cleanup) Row(Modifier.fillMaxWidth().semantics { contentDescription = "清理下载：${row.info.title}，${row.info.episode.label}，${playbackSize(row.bytes)}" }
                        .toggleable(row.id in selected, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = {
                        selected = if (row.id in selected) selected - row.id else selected + row.id
                    }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(row.id in selected, null)
                        Text(row.info.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    }
                    Text("${row.info.episode.label} · ${row.info.lineName}", style = MaterialTheme.typography.titleMedium)
                    Text(if (row.state == Download.STATE_COMPLETED) "已下载" else row.status, color = if (row.state == Download.STATE_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${playbackSize(row.bytes)}${if (row.total > 0) " / ${playbackSize(row.total)}" else " · 总大小未知"}${if (row.bytesPerSecond > 0 && row.state == Download.STATE_DOWNLOADING) " · ${playbackSize(row.bytesPerSecond)}/s" else ""}", style = MaterialTheme.typography.bodySmall)
                    if (row.percent >= 0 && row.state == Download.STATE_DOWNLOADING && row.total > 0) LinearProgressIndicator(progress = { (row.percent / 100).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    if (!cleanup) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (row.state) {
                            Download.STATE_COMPLETED -> TextButton(onClick = {
                                if (library) context.startActivity(PlaybackActivity.intent(context, row.info.animeId).putExtra("downloadId", row.id)) else model.openDownload(row)
                            }) { Text("离线播放") }
                            Download.STATE_STOPPED -> TextButton(onClick = { model.resumeDownload(row) }) { Text("继续") }
                            Download.STATE_FAILED -> TextButton(onClick = { model.retryDownload(row) }, enabled = !state.downloadBusy) { Text("重新解析并重试") }
                            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> TextButton(onClick = { model.pauseDownload(row) }) { Text("暂停") }
                        }
                        TextButton(onClick = { deleting = listOf(row) }, enabled = !state.downloadBusy && row.state != Download.STATE_REMOVING) { Text(if (row.state == Download.STATE_COMPLETED) "删除下载" else "取消任务") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    deleting?.takeIf { it.isNotEmpty() }?.let { rows ->
        val complete = rows.all { it.state == Download.STATE_COMPLETED }
        val active = rows.any { it.id == state.session?.downloadId }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(if (complete) "删除 ${rows.size} 集下载？" else "取消 ${rows.size} 个下载任务？") },
            text = { Text((if (active) "删除将停止本地播放。\n" else "") +
                (if (complete) "将移除所选集数的本地视频。" else "将停止所选任务并移除其已下载部分。") + "占用空间 ${playbackSize(rows.sumOf { it.bytes })}。收藏、已看进度、评分和播放历史保留。", Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { rows.forEach(model::removeDownload); deleting = null }, enabled = !state.downloadBusy) { Text(if (complete) "删除 ${rows.size} 集" else "取消任务") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("下载设置与空间") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("仅不计费网络下载", Modifier.weight(1f)); Switch(state.wifiOnly, { if (!it) { settings = false; mobileNetwork = true } else model.wifiOnly(true) })
            }
            Text("下载视频 ${playbackSize(state.downloads.sumOf { it.bytes })}")
            Text("播放缓冲 ${playbackSize(state.bufferedBytes)}")
            Text("设备可用 ${if (state.availableBytes > 0) playbackSize(state.availableBytes) else "未知"}")
            TextButton(onClick = model::clearBuffer) { Text("清理播放缓冲") }
            Text("清缓冲保留已下载视频；下载视频不进入资料库备份。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { settings = false; tab = 1; cleanup = true }) { Text("管理已下载视频") } },
        dismissButton = { TextButton(onClick = { settings = false }) { Text("关闭") } })
    if (mobileNetwork) AlertDialog(onDismissRequest = { mobileNetwork = false }, title = { Text("允许使用移动网络下载？") },
        text = { Text("视频可能消耗较多流量，排队任务也会受此设置影响。") },
        confirmButton = { TextButton(onClick = { model.wifiOnly(false); mobileNetwork = false }) { Text("允许移动网络") } },
        dismissButton = { TextButton(onClick = { mobileNetwork = false }) { Text("保持不计费网络") } })
    if (showResults) AlertDialog(onDismissRequest = { showResults = false }, title = { Text("逐项重试结果") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.downloadResults, key = { it.id }) { result -> Column {
                Text("${result.title} · ${result.episode}")
                Text("${result.status} · ${result.detail}", color = if (result.status == "失败") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }, confirmButton = { TextButton(onClick = { showResults = false }) { Text("关闭") } })
}

internal fun visibleDownloads(rows: List<PlaybackDownloadRow>, tab: Int, group: String?, cleanup: Boolean): List<PlaybackDownloadRow> {
    val filtered = rows.filter { row -> (group == null || row.info.workKey == group) && when (tab) {
        1 -> row.state == Download.STATE_COMPLETED
        2 -> row.state == Download.STATE_FAILED
        else -> row.state != Download.STATE_COMPLETED
    } }
    return if (cleanup) filtered.sortedByDescending { it.bytes } else filtered
}

@Composable
internal fun ObservePlaybackDownloads(model: PlaybackViewModel, loaded: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, loaded) {
        if (loaded) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.watchDownloads()
            try { awaitCancellation() } finally { model.stopWatchingDownloads() }
        }
    }
}
