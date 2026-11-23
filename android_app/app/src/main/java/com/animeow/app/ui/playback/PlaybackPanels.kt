@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.animeow.app.ui.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.data.playback.*
import com.animeow.app.data.remote.*
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.launch

@Composable
internal fun ErrorNotice(message: String, retry: () -> Unit, repair: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = retry) { Text("重试") }
                TextButton(onClick = repair) { Text("重新关联") }
            }
        }
    }
}

@Composable
internal fun PlaybackWorkHeader(state: PlaybackUiState, sources: () -> Unit, changeSource: () -> Unit) {
    val playing = state.session.takeIf { state.openingTarget == null }
    val sourceId = playing?.source ?: state.source
    val currentLine = if (playing != null) {
        state.lines.takeIf { state.source == playing.source && state.work?.id == playing.work.id }
            ?.firstOrNull { line -> line.episodes.any { it.id == playing.episode.id } }
    } else state.lines.firstOrNull { it.id == state.line }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.anime?.title ?: state.session?.work?.title ?: state.work?.title.orEmpty(), style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (state.session?.offline == true && state.openingTarget == null) "${state.session.episode.label} · ${if (state.offlineOnly) "离线播放 · 已下载" else "本集使用缓存"}"
            else listOfNotNull(state.openingTarget?.label ?: state.session?.episode?.label, state.sources.firstOrNull { it.id == sourceId }?.name ?: sourceId,
                currentLine?.name).joinToString(" · ").ifBlank { "选择观看来源" },
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.offlineOnly) OutlinedButton(onClick = sources) { Text("本地选集与下载") }
            else {
                FilledTonalButton(onClick = changeSource, modifier = Modifier.weight(1f)) { Text("更换来源") }
                OutlinedButton(onClick = sources, modifier = Modifier.weight(1f)) { Text("切换线路") }
            }
        }
    }
}

@Composable
internal fun PlaybackOpeningVideo(state: PlaybackUiState, model: PlaybackViewModel, sources: () -> Unit, modifier: Modifier) {
    Surface(modifier, color = Color.Black, contentColor = Color.White) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.anime?.title.orEmpty()} · ${state.openingTarget?.label.orEmpty()}", Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = model::cancelOpening) { Text("取消切换", color = Color.White) }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Text(if (state.busy) "正在加载 ${state.openingTarget?.label.orEmpty()}…" else "这一集未能打开", style = MaterialTheme.typography.titleSmall)
            }
            Text(state.error ?: "正在获取视频地址，画面准备好后开始播放。", maxLines = 3,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            if (!state.busy) Row {
                TextButton(onClick = model::retry) { Text("重试本集", color = Color.White) }
                TextButton(onClick = sources) { Text("切换线路", color = Color.White) }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun SourcePicker(state: PlaybackUiState, model: PlaybackViewModel, choose: (String, PlaybackWork) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (state.session != null) {
                Text("重新关联观看作品", style = MaterialTheme.typography.titleLarge)
                Text("当前播放将保留；确认并读取新作品成功后再更换关联。", style = MaterialTheme.typography.bodySmall)
            }
            Text("资料库作品", style = MaterialTheme.typography.labelLarge)
            Text(state.anime?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(state.query, model::query, Modifier.fillMaxWidth(), label = { Text("搜索关键词") }, singleLine = true,
                trailingIcon = { TextButton(onClick = { model.search() }, enabled = state.sources.isNotEmpty() && state.query.isNotBlank()) { Text("搜索") } })
            Text("修改关键词不会更改资料库作品名称", style = MaterialTheme.typography.bodySmall)
            SourceRefresh(state, model)
        }
        state.searchGroups.forEach { group ->
            item(key = "source:${group.source.id}") {
                HorizontalDivider(); Text(group.source.name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                when {
                    group.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在查找…") }
                    group.error != null -> {
                        Text(group.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { model.search(group.source.id) }) { Text("重试此来源") }
                    }
                    group.results.isEmpty() -> Text("暂未找到匹配，可修改关键词")
                    else -> Text("找到 ${group.results.size} 个候选", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(group.results, key = { "${group.source.id}:${it.id}" }) { work ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { choose(group.source.id, work) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaybackCover(work.coverUrl, group.source.id)
                    Column(Modifier.weight(1f)) { Text(work.title, style = MaterialTheme.typography.titleMedium); Text("选择后核对作品与季度", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
internal fun SourceRefresh(state: PlaybackUiState, model: PlaybackViewModel) {
    TextButton(onClick = { model.loadSources(refreshSearch = true) }, enabled = !state.sourcesLoading) {
        Text(if (state.sourcesLoading) "正在刷新来源…" else "刷新来源")
    }
    Text("来源由服务器维护，修复后刷新即可重试。", style = MaterialTheme.typography.bodySmall)
    state.sourcesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun PlaybackCover(url: String?, source: String? = null, modifier: Modifier = Modifier.size(72.dp, 104.dp), label: String = "作品封面") {
    val context = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }
    val request = remember(url, source) {
        ImageRequest.Builder(context).data(url).apply {
            if (source != null) {
                // Source images, like source videos, are read directly without account credentials.
                diskCacheKey("playback-cover:$url")
                addHeader("User-Agent", "Mozilla/5.0 (compatible; AniMeow/2.2.0)")
                when (source) {
                    "giri" -> addHeader("Referer", "https://ani.girigirilove.com/")
                    "seven" -> addHeader("Referer", "https://www.7sefun.top/")
                }
            }
        }.build()
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) AsyncImage(request, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { loaded = true }, onError = { failed = true })
        if (!loaded) {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                AppIcon(
                    if (failed) AppIcons.Error else AppIcons.Television,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Text(
                    when { url.isNullOrBlank() -> "无封面"; failed -> "加载失败"; else -> "加载中…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun ConfirmPlaybackWork(state: PlaybackUiState, source: String, work: PlaybackWork, dismiss: () -> Unit, confirm: () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().widthIn(max = 560.dp)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = dismiss) { AppIcon(AppIcons.Back, "重新选择") }
                    Text("确认观看作品", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("请确认来源作品与资料库作品一致。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PlaybackWorkIdentity("资料库作品", state.anime?.coverUrl, state.anime?.title.orEmpty(),
                        "动画${state.anime?.totalEpisodes?.takeIf { it > 0 }?.let { " · 共 $it 集" }.orEmpty()}",
                        "当前记录：已看 ${state.anime?.watchedEpisodes ?: 0} 集")
                    HorizontalDivider()
                    PlaybackWorkIdentity("来源作品", work.coverUrl, work.title,
                        state.sources.firstOrNull { it.id == source }?.name ?: source,
                        "季度／年份：来源未提供 · 集数：确认后读取", source)
                    Text("确认后记住此关联，可在来源设置中更换。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = confirm, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("确认并选集") }
                    TextButton(onClick = dismiss) { Text("重新选择") }
                }
            }
        }
    }
}

@Composable
private fun PlaybackWorkIdentity(label: String, cover: String?, title: String, line1: String, line2: String, source: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaybackCover(cover, source, Modifier.size(88.dp, 128.dp), "$label 封面")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(line1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun PlaybackPanel(panel: String, state: PlaybackUiState, model: PlaybackViewModel,
    cache: (List<PlaybackEpisode>) -> Unit, change: (String?) -> Unit, search: () -> Unit,
    choose: (String, PlaybackWork) -> Unit, modifier: Modifier) {
    when (panel) {
        "sources" -> SourcePicker(state, model, choose, modifier)
        "lines" -> SourceLinesPanel(state, model, search, modifier)
        "episodes", "downloadSelection" -> EpisodePicker(state, model, modifier, cache,
            downloadMode = panel == "downloadSelection", onDownload = { change("downloadSelection") })
        "settings" -> Box(modifier.padding(16.dp)) { PlaybackSettingsContent(state.settings, model::settings, state.upscaleStatus) }
        "downloads" -> PlaybackDownloadPanel(state, model, modifier)
        else -> TextButton(onClick = { change(null) }) { Text("关闭") }
    }
}

@Composable
private fun SourceLinesPanel(state: PlaybackUiState, model: PlaybackViewModel, search: () -> Unit, modifier: Modifier) {
    var detail by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("来源与线路", style = MaterialTheme.typography.titleLarge); Text(state.work?.title.orEmpty());
            Text(state.sources.firstOrNull { it.id == state.source }?.name ?: state.source)
            if (!state.offlineOnly) SourceRefresh(state, model)
            FilledTonalButton(onClick = search, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("更换观看来源") }
        }
        items(state.lines, key = { it.id }) { line ->
            val isSelected = line.id == state.line
            val isPlayingLine = state.session?.episode?.let { ep ->
                state.session.source == state.source && state.session.work.id == state.work?.id && line.episodes.any { it.id == ep.id }
            } == true
            val check = state.checks[line.id]
            Surface(
                onClick = { model.line(line.id) },
                shape = MaterialTheme.shapes.large,
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RadioButton(isSelected, { model.line(line.id) })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${line.name} · ${line.episodes.size} 集${if (isSelected) " · 已选" else ""}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (isPlayingLine) {
                                Text("当前播放线路", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (!state.offlineOnly) {
                            OutlinedButton(
                                onClick = {
                                    if (check?.running == true) model.cancelCheck(line.id) else model.checkLine(line)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(if (check?.running == true) "取消检查" else "检查")
                            }
                        }
                    }
                    if (check == null) {
                        Text("未检查", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                    } else {
                        Text(
                            listOf(check.phase, check.detail).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        if (check.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        else TextButton(onClick = { detail = line.id }) { Text("查看检查详情") }
                    }
                }
            }
        }
        item {
            Text("切换所选线路后，请选择对应集数；当前播放将保留。", style = MaterialTheme.typography.bodySmall)
            Text("连接检查不代表整集播放质量。", style = MaterialTheme.typography.bodySmall)
        }
    }
    detail?.let { id -> state.checks[id]?.let { check ->
        val source = state.sources.firstOrNull { it.id == state.source }
        val text = "来源：${state.source}\n来源版本：${source?.version.orEmpty().ifBlank { "未提供" }}\n规则版本：${source?.rulesVersion.orEmpty().ifBlank { "未提供" }}\n线路：${state.lines.firstOrNull { it.id == id }?.name.orEmpty()}\n${check.phase}\n${check.detail}\n检查时间：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(check.checkedAt))}\n连接检查不代表整集播放质量。"
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("线路检查详情") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("复制诊断") } },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    } }
}

@Composable
internal fun EpisodePicker(state: PlaybackUiState, model: PlaybackViewModel, modifier: Modifier,
    cache: (List<PlaybackEpisode>) -> Unit, downloadMode: Boolean = false, onDownload: () -> Unit = {}, compact: Boolean = false) {
    var reversed by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var tools by remember { mutableStateOf(false) }
    LaunchedEffect(state.source, state.work?.id, state.line) { selection = emptySet(); page = 0 }
    val episodes = state.lines.firstOrNull { it.id == state.line }?.episodes.orEmpty().let { if (reversed) it.reversed() else it }
    val shown = episodes.chunked(60).getOrNull(page).orEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val columns = if (LocalDensity.current.fontScale > 1.5f) 2 else if (LocalDensity.current.fontScale > 1.15f) 3 else if (compact) 5 else 4
    fun downloaded(id: String) = state.downloads.any { it.info.episode.id == id && it.info.source == state.source && it.info.work.id == state.work?.id && it.state == Download.STATE_COMPLETED }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (downloadMode) "缓存集数" else if (compact) "选集 · ${episodes.size} 集" else "共 ${episodes.size} 集", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (!downloadMode && !state.offlineOnly) TextButton(onClick = onDownload) { Text("缓存集数") }
                    Box {
                        TextButton(onClick = { tools = true }) { Text(if (reversed) "倒序" else "正序"); AppIcon(AppIcons.Expand, "选集工具", Modifier.size(18.dp)) }
                        DropdownMenu(tools, { tools = false }) {
                            DropdownMenuItem(text = { Text(if (reversed) "正序排列" else "倒序排列") }, onClick = { reversed = !reversed; page = 0; tools = false })
                            DropdownMenuItem(text = { Text("定位当前集") }, enabled = state.session != null, onClick = {
                                tools = false
                                scope.launch {
                                    val index = episodes.indexOfFirst { it.id == state.session?.episode?.id }.coerceAtLeast(0)
                                    page = index / 60; withFrameNanos { }; listState.animateScrollToItem(index % 60 / columns + 1)
                                }
                            })
                            if (!downloadMode && !state.offlineOnly) DropdownMenuItem(text = { Text("刷新集数") }, enabled = !state.busy,
                                onClick = { tools = false; model.reloadEpisodes() })
                        }
                    }
                }
                if (episodes.size > 60) Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    episodes.chunked(60).forEachIndexed { index, _ -> FilterChip(page == index, { page = index }, label = { Text("${index * 60 + 1}–${minOf((index + 1) * 60, episodes.size)}") }) }
                }
                if (downloadMode) TextButton(onClick = { selection = shown.filterNot { downloaded(it.id) }.map { it.id }.toSet() }) { Text("全选本段") }
                if (episodes.isEmpty()) Text("此线路暂无可用集数，可刷新或选择其他来源。")
            }
            items(shown.chunked(columns), key = { it.first().id }) { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { episode ->
                        val current = state.openingTarget?.let { it.id == episode.id } ?: (state.session?.let { it.episode.id == episode.id && it.source == state.source && it.work.id == state.work?.id } == true)
                        val selected = if (downloadMode) episode.id in selection else current
                        val watched = episode.number != null && episode.number <= (state.anime?.watchedEpisodes ?: 0)
                        val status = buildList {
                            if (state.openingTarget?.id == episode.id) add(if (state.busy) "正在打开" else "打开失败")
                            else if (current) add(state.playbackStatus)
                            if (watched && !current) add("已看")
                            if (downloaded(episode.id)) add("已下载")
                            if (downloadMode && episode.id in selection) add("已选择")
                        }.joinToString(" · ").ifEmpty { null }
                        Surface(onClick = {
                            if (downloadMode) selection = if (episode.id in selection) selection - episode.id else selection + episode.id
                            else model.play(episode)
                        }, enabled = !state.busy && (!downloadMode || !downloaded(episode.id)),
                            shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
                            border = if (!compact) androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant) else null,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else if (compact) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).heightIn(min = if (compact) 48.dp else 64.dp)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compact) 8.dp else 12.dp), horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(episode.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                if (status != null) Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item { state.downloadNotice?.let { Text(it) } }
        }
        if (downloadMode) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${selection.size} 集 · 大小待获取", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { cache(episodes.filter { it.id in selection }); selection = emptySet() }, enabled = selection.isNotEmpty() && !state.downloadBusy) { Text("加入下载") }
        }
    }
}

@Composable
internal fun ContinueWatchingContent(state: PlaybackUiState, model: PlaybackViewModel, episodes: () -> Unit, download: () -> Unit, modifier: Modifier) {
    val current = state.session ?: return
    val all = state.lines.firstOrNull { it.id == state.line }?.episodes.orEmpty()
    val next = nextPlaybackEpisode(current.episode, state.lines)
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (next != null) Button(onClick = model::nextEpisode, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("下一集 · ${next.label}") }
            else Text("当前线路暂无连续下一集")
            Row { TextButton(onClick = episodes) { Text("全部选集") }; if (!state.offlineOnly) TextButton(onClick = download) { Text("下载集数") } }
            Text("接下来", style = MaterialTheme.typography.titleMedium)
        }
        items(all.drop((all.indexOfFirst { it.id == current.episode.id } + 1).coerceAtLeast(0)).take(3), key = { it.id }) { episode ->
            TextButton(onClick = { model.play(episode) }, modifier = Modifier.fillMaxWidth()) { Text(episode.label) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackSettingsContent(settings: PlaybackSettings, update: (PlaybackSettings) -> Unit, upscaleStatus: String = "") {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("播放设置", style = MaterialTheme.typography.titleLarge)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackSwitchRow("自动记录已看进度", settings.autoMarkProgress, { update(settings.copy(autoMarkProgress = it)) })
                Text(if (settings.autoMarkProgress) "已开启 · 自动接续记录集数" else "已关闭 · 保留续播和手动记录",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("累计实际播放达到本集 90% 后自动记集，可撤销。拖动跳过不计，重看不重复增加；跳集或特别篇请手动调整。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("开关仅保存在本机；Bangumi 上报另由同步设置控制。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("观看布局", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchingLayout.entries.forEach { layout ->
                PlaybackOptionRow(layout.title + if (layout == WatchingLayout.TABBED) " · 默认" else "", layout.description,
                    settings.layout == layout) { update(settings.copy(layout = layout)) }
            }
        }
        Text("切换布局保留当前播放", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("画质增强／超分", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaybackUpscale.entries.forEach { mode ->
                PlaybackOptionRow(mode.title, mode.description, settings.upscale == mode) { update(settings.copy(upscale = mode)) }
            }
        }
        if (settings.upscale == PlaybackUpscale.AI) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.animeow.app.data.playback.PlaybackAiQuality.entries.forEach { quality ->
                    FilterChip(settings.aiQuality == quality, { update(settings.copy(aiQuality = quality)) }, label = { Text(quality.title) })
                }
            }
            Text(settings.aiQuality.description, style = MaterialTheme.typography.bodySmall)
        }
        if (upscaleStatus.isNotBlank()) Text(upscaleStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text("默认关闭。放大不等于原生高分辨率；出现卡顿可切回原画。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("倍速")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { playbackSpeeds.forEach { speed -> FilterChip(settings.speed == speed, { update(settings.copy(speed = speed)) }, label = { Text("${speed}×") }) } }
        PlaybackSwitchRow("自动下一集", settings.autoNext, { update(settings.copy(autoNext = it)) })
        Text("仅在前台、同线路有连续编号的下一集时连播。", style = MaterialTheme.typography.bodySmall)
        Text("画面")
        Row { FilterChip(!settings.crop, { update(settings.copy(crop = false)) }, label = { Text("适应") }); FilterChip(settings.crop, { update(settings.copy(crop = true)) }, label = { Text("裁剪") }) }
        Text("按钮跳转时长")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { playbackSkipSeconds.forEach { seconds -> FilterChip(settings.skipSeconds == seconds, { update(settings.copy(skipSeconds = seconds)) }, label = { Text("$seconds 秒") }) } }
        Text("双击暂停／继续；横向拖动预览，松手跳转，上滑取消。按钮跳转时长不影响横拖灵敏度。", style = MaterialTheme.typography.bodySmall)
        PlaybackSwitchRow("全屏自动锁定", settings.autoLock, { update(settings.copy(autoLock = it)) })
    }
}

@Composable
private fun PlaybackSwitchRow(title: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked,
        role = androidx.compose.ui.semantics.Role.Switch, onValueChange = update), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked, null)
    }
}

@Composable
private fun PlaybackOptionRow(title: String, description: String, selected: Boolean, choose: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = choose).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
