package com.animeow.app.ui.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.animeow.app.data.community.WatchItem
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchInteractionPanel(model: WatchViewModel, modifier: Modifier = Modifier, hasPlayback: Boolean = false,
    onShare: (WatchItem) -> Unit, onPost: (Long) -> Unit, onSeek: (Long) -> Unit, initialTab: String = "讨论") {
    val state by model.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var report by remember { mutableStateOf<WatchItem?>(null) }
    var deleting by remember { mutableStateOf<WatchItem?>(null) }
    var moderating by remember { mutableStateOf<WatchItem?>(null) }
    LaunchedEffect(state.session) { report = null; deleting = null; moderating = null }
    LaunchedEffect(tab, state.admin, state.ready) { if (tab == "管理" && state.admin) model.loadReviews(); if (tab == "管理" && state.ready && !state.admin) tab = "讨论" }
    Column(modifier.imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(state.program?.title ?: if (tab == "管理") "观看互动审核" else "弹幕与评论", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                state.program?.episodeTitle ?: if (tab == "管理") "处理弹幕、评论及举报" else "先确认作品与集数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (listOf("讨论", "弹幕", "匹配", "设置") + if (state.admin) listOf("管理") else emptyList()).forEach { title ->
                        val isSelected = tab == title
                        Surface(
                            onClick = { tab = title },
                            shape = RoundedCornerShape(17.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            modifier = Modifier.semantics { selected = isSelected },
                        ) {
                            Text(
                                title,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        state.message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary) }
        if (!state.ready) Column(Modifier.padding(16.dp)) {
            Text(state.error ?: "正在连接观看互动服务…")
            TextButton(onClick = { model.refresh() }) { Text("重新连接") }
        }
        when (tab) {
            "匹配" -> WatchMatching(state, model, Modifier.weight(1f)) {
                tab = if (hasPlayback && initialTab == "弹幕") "弹幕" else "讨论"
            }
            "设置" -> WatchSettings(state, model, Modifier.weight(1f))
            "管理" -> Column(Modifier.weight(1f)) {
                TextButton(onClick = { model.loadReviews() }, enabled = !state.reviewLoading) { Text("刷新待审内容") }
                state.reviewError?.let { Text(it, Modifier.padding(16.dp)); TextButton(onClick = { model.loadReviews() }) { Text("重新读取") } }
                if (state.reviewLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                    if (!state.reviewLoading && state.reviews.isEmpty() && state.reviewError == null) item { Text("暂无待处理内容") }
                    items(state.reviews, key = WatchItem::id) { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("${item.title} · ${item.episodeTitle}", style = MaterialTheme.typography.labelMedium)
                            Text(item.content.ifBlank { "作者已删除，此处仅处理举报记录" })
                            item.reports.forEach { Text("举报：$it", style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { moderating = item }, enabled = !state.busy) { Text("处理") }
                        }
                    }
                    if (state.reviewBefore != null) item { TextButton(onClick = { model.loadReviews(true) }, enabled = !state.reviewLoading) { Text("加载更多") } }
                }
            }
            else -> {
                val danmaku = tab == "弹幕"
                if (!danmaku) Row(Modifier.padding(horizontal = 12.dp)) {
                    FilterChip(state.overall, { model.selectOverall(true) }, label = { Text("作品总讨论") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(!state.overall, { model.selectOverall(false) }, label = { Text("本集讨论") }, enabled = (state.program?.episodeId ?: 0) > 0)
                }
                if (state.program == null) Column(Modifier.padding(16.dp)) {
                    Text("确认匹配后再读取或发布，避免发到其他作品或集数。")
                    state.matchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { tab = "匹配" }) { Text("确认作品与集数") }
                } else {
                    val error = if (danmaku) state.danmakuError else state.error
                    error?.let { Text(it, Modifier.padding(horizontal = 16.dp)); TextButton(onClick = { if (danmaku) model.reloadDanmaku() else model.loadComments() }) { Text("重新读取") } }
                    if (danmaku) state.externalError?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                    if (state.loading && !danmaku) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.parent?.takeIf { !danmaku }?.let { parent -> Row(Modifier.padding(horizontal = 16.dp)) {
                        Text("回复 ${parent.nickname.ifBlank { "该评论" }}", Modifier.weight(1f)); TextButton(onClick = model::closeReplies) { Text("返回主评论") }
                    } }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val rows = if (danmaku) state.danmaku else if (state.parent != null) state.replies else state.comments
                        state.focused?.takeUnless { danmaku }?.let { focused -> item("focus:${focused.id}") {
                            Text("来自社区的原评论", color = MaterialTheme.colorScheme.primary)
                            WatchCommentCard(focused, state.busy, onReply = { model.replies(focused) }, onDelete = { deleting = focused },
                                onReport = { report = focused }, onShare = { onShare(focused) }, onBlock = { model.block(focused) }, onHide = { model.hide(focused) }, onSeek = { focused.timeMs?.let(onSeek) })
                        } }
                        if (rows.isEmpty() && !state.loading && error == null) item { Text(if (danmaku) "当前时间附近暂无弹幕" else "暂无评论，欢迎留下感想") }
                        items(rows.filterNot { it.id == state.focused?.id }, key = WatchItem::id) { item -> WatchCommentCard(item, state.busy,
                            onReply = if (!danmaku && state.parent == null) ({ model.replies(item) }) else null,
                            onDelete = { deleting = item }, onReport = { report = item }, onShare = if (!danmaku) ({ onShare(item) }) else null,
                            onBlock = { model.block(item) }, onHide = { model.hide(item) }, onSeek = { item.timeMs?.let(onSeek) }) }
                        if (!danmaku && (if (state.parent != null) state.replyBefore else state.before) != null) item {
                            TextButton(onClick = { state.parent?.let { model.replies(it, true) } ?: model.loadComments(true) }, enabled = !state.loading) { Text("加载更多") }
                        }
                        if (!danmaku) item {
                            TextButton(onClick = model::shared) { Text("查看社区相关讨论") }
                            state.sharedPosts.forEach { id -> TextButton(onClick = { onPost(id) }) { Text("打开社区讨论 #$id") } }
                        }
                        if (danmaku && state.externalAvailable) item { Text("外部弹幕来自弹弹play开放弹幕网络 · www.dandanplay.com；此处可本地屏蔽，删除与举报仅适用于本应用内容。", style = MaterialTheme.typography.bodySmall) }
                    }
                    WatchComposer(state, model, danmaku, hasPlayback)
                }
            }
        }
    }
    deleting?.let { target -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除自己发送的内容？") },
        text = { Text("删除后不再公开显示，其他人的回复会保留。") },
        confirmButton = { TextButton(onClick = { model.remove(target); deleting = null }, enabled = !state.busy) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    report?.let { target -> WatchReasonDialog("举报内容", false, state.busy, { report = null }) { _, reason -> model.report(target, reason); report = null } }
    moderating?.let { target -> WatchReasonDialog("处理弹幕／评论", true, state.busy, { moderating = null }) { remove, reason -> model.moderate(target, remove, reason); moderating = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchCommentCard(item: WatchItem, busy: Boolean, onReply: (() -> Unit)? = null, onDelete: () -> Unit,
    onReport: () -> Unit, onShare: (() -> Unit)? = null, onBlock: () -> Unit, onHide: () -> Unit, onSeek: () -> Unit) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var blockMenu by remember { mutableStateOf(false) }
    val hasBlockOption = (item.authorId != null && item.canReport) || item.kind == "danmaku"
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.unavailable) Text("原内容已删除或暂不可查看", color = MaterialTheme.colorScheme.onSurfaceVariant) else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (item.external) "弹弹play · 外部弹幕" else item.nickname,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.external) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                item.timeMs?.let { time ->
                    Surface(
                        onClick = onSeek,
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            playbackTime(time),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (hasBlockOption) {
                    Box {
                        IconButton(onClick = { blockMenu = true }, modifier = Modifier.size(32.dp)) {
                            AppIcon(AppIcons.MoreHorizontal, "屏蔽选项", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = blockMenu, onDismissRequest = { blockMenu = false }) {
                            if (item.authorId != null && item.canReport) {
                                DropdownMenuItem(
                                    text = { Text("屏蔽作者") },
                                    enabled = !busy,
                                    onClick = { blockMenu = false; onBlock() },
                                )
                            }
                            if (item.kind == "danmaku") {
                                DropdownMenuItem(
                                    text = { Text("本地屏蔽") },
                                    onClick = { blockMenu = false; onHide() },
                                )
                            }
                        }
                    }
                }
            }
            if (item.spoiler && !expanded) {
                FilledTonalButton(
                    onClick = { expanded = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("包含剧透，点击展开", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Text(item.content, style = MaterialTheme.typography.bodyMedium)
            }
            if (onReply != null || onShare != null || item.canDelete || item.canReport) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onReply != null) TextButton(onClick = onReply, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("回复") }
                    if (onShare != null) TextButton(onClick = onShare, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("分享到社区") }
                    if (item.canDelete) TextButton(onClick = onDelete, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("删除") }
                    if (item.canReport) TextButton(onClick = onReport, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("举报") }
                }
            }
        }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchComposer(state: WatchUiState, model: WatchViewModel, danmaku: Boolean, hasPlayback: Boolean) {
    var spoiler by rememberSaveable { mutableStateOf(false) }
    var withTime by rememberSaveable { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableStateOf("") }
    val explicitTime = seconds.toLongOrNull()?.takeIf { it in 0..86400 }?.times(1000)
    val canSend = state.ready && state.session != null && !state.busy &&
        state.draft.isNotBlank() && (hasPlayback || !danmaku || explicitTime != null) &&
        (danmaku || state.parent?.unavailable != true) && state.draft.length <= (if (danmaku) 120 else 2000)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.session == null) Text("登录 AniMeow 后可发送、回复和举报；可从“我的 → 账号与同步”登录后返回。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.uncertain) Text("上次发送结果待核实，重试会先核对原提交，不会自动重复发送。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!hasPlayback && danmaku) {
                    OutlinedTextField(seconds, { seconds = it }, Modifier.fillMaxWidth(),
                        label = { Text("弹幕时间位置（秒，0–86400）") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    TextButton(onClick = { explicitTime?.let { model.sample(it + state.settings.offsetMs, true) } }, enabled = explicitTime != null) { Text("查看此时间附近") }
                }
                OutlinedTextField(state.draft, model::draft, Modifier.fillMaxWidth(), enabled = state.session != null && !state.busy,
                    shape = RoundedCornerShape(16.dp),
                    label = { Text(if (danmaku) "发送弹幕（公开，最多 120 字）" else "写评论（公开，最多 2000 字）") }, maxLines = 3)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = spoiler,
                            onClick = { spoiler = !spoiler },
                            label = { Text("剧透") },
                            modifier = Modifier.semantics { contentDescription = "标记为剧透" },
                        )
                        if (hasPlayback && !danmaku && !state.overall) {
                            FilterChip(
                                selected = withTime,
                                onClick = { withTime = !withTime },
                                label = { Text("附时间点") },
                                modifier = Modifier.semantics { contentDescription = "附带当前播放时间点" },
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { model.send(danmaku, withTime, spoiler, explicitTime) },
                        enabled = canSend,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(if (state.busy) "发送中…" else if (state.uncertain) "核实并重试" else "发送")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchMatching(state: WatchUiState, model: WatchViewModel, modifier: Modifier, onMatched: () -> Unit) {
    var query by rememberSaveable(state.matchTitle) { mutableStateOf(state.matchTitle) }
    var confirmClear by remember(state.program) { mutableStateOf(false) }
    var rematching by remember(state.program) { mutableStateOf(false) }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("清除弹幕与评论关联？") },
        text = { Text("清除本集关联及同源作品的自动匹配，下次需重新确认作品与集数。已发送的内容和观看进度保留。") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { if (model.clearMatching()) confirmClear = false }) { Text("清除关联") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
    val numbering = remember(state.candidates) { watchEpisodeNumbering(state.candidates) }
    val listState = rememberLazyListState()
    val selected = state.selectedMatchSubject
    LaunchedEffect(selected) { listState.scrollToItem(0) }
    val focus = LocalFocusManager.current
    fun searchTitle() {
        if (query.isBlank() || !state.ready) return
        focus.clearFocus()
        model.search(query)
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp)) {
        state.program?.takeIf { selected == null && !rematching }?.let { program -> item {
            OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已关联作品", style = MaterialTheme.typography.labelMedium)
                    Text(program.title, style = MaterialTheme.typography.titleSmall)
                    val number = program.number?.let { if (it % 1 == 0.0) it.toInt().toString() else it.toString() }
                    Text(when {
                        program.episodeId == 0 -> "作品总讨论"
                        program.type != 0 -> "特别篇 · ${program.episodeTitle}"
                        number != null -> "对应第 $number 集 · ${program.episodeTitle}"
                        else -> program.episodeTitle
                    })
                    Row {
                        TextButton(enabled = !state.busy, onClick = { model.returnToWatchSubjects(); rematching = true }) { Text("重新匹配") }
                        TextButton(enabled = !state.busy, onClick = { confirmClear = true }) { Text("清除关联") }
                    }
                }
            }
        } }
        item {
            if (selected != null) {
                Text("确认集数", style = MaterialTheme.typography.titleMedium)
                val title = state.candidates.firstOrNull()?.title
                    ?: state.subjects.firstOrNull { it.id == selected }?.title
                    ?: "Bangumi #$selected"
                Text(title, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = model::returnToWatchSubjects) {
                    Text(if (state.subjects.isEmpty()) "返回作品搜索" else "返回作品搜索结果")
                }
            } else {
                Text("作品与集数匹配", style = MaterialTheme.typography.titleMedium)
                Text("核对作品和集数后确认，特别篇不会按数字自动对应。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    label = { Text("作品名称、#编号或 Bangumi 链接") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchTitle() }))
                FlowRow(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(onClick = ::searchTitle, enabled = query.isNotBlank() && state.ready) { Text("查找作品") }
                    val number = query.trim().toIntOrNull()?.takeIf { it > 0 }
                    if (number != null) OutlinedButton(onClick = { focus.clearFocus(); model.searchSubject(number) }, enabled = state.ready) { Text("按编号查找") }
                    state.alternateTitles.filter { it != query }.forEach { title ->
                        AssistChip(onClick = { query = title; searchTitle() }, enabled = state.ready, label = { Text(title, maxLines = 2) })
                    }
                }
            }
            state.matchError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (selected != null) TextButton(onClick = { model.searchSubject(selected) }) { Text("重试读取集数") }
            }
            if (state.matchLoading) {
                Text(if (selected != null) "正在读取分集目录…" else "正在查找匹配…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            if (state.noSubjects) Text("没有找到对应作品，可试试原名、分季名称或 Bangumi 链接。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.candidates.isNotEmpty()) {
                Text("选择对应的集数", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                if (state.candidates.none { it.episodeId > 0 }) Text("Bangumi 暂无分集目录，目前可打开作品总讨论。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected == null && state.candidates.isEmpty()) items(state.subjects, key = { "subject:${it.id}" }) { subject ->
            Surface(
                onClick = { model.searchSubject(subject.id) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${subject.title} · #${subject.id}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    AppIcon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val suggested = state.suggestedMatch
        val programs = if (suggested == null) state.candidates else listOf(suggested) + state.candidates.filterNot { it == suggested }
        items(programs, key = { "program:${it.subjectId}:${it.episodeId}" }) { program ->
            val isSuggested = program == suggested
            Surface(
                onClick = { if (model.choose(program)) onMatched() },
                shape = MaterialTheme.shapes.medium,
                color = if (isSuggested) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (isSuggested) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                val number = program.number?.let { if (it % 1 == 0.0) it.toInt().toString() else it.toString() }
                val local = if (program.type == 0) number?.toIntOrNull()?.let { numbering?.watchedCountForNumber(it) } else null
                val label = when {
                    program.episodeId == 0 -> "作品总讨论"
                    program.type != 0 -> "特别篇 · ${program.episodeTitle}"
                    local != null -> "本季第 $local 集 · 连续第 $number 集 · ${program.episodeTitle}"
                    number != null -> "第 $number 集 · ${program.episodeTitle}"
                    else -> program.episodeTitle
                }
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isSuggested) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                            Text(
                                "推荐",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    Text(
                        if (isSuggested) "建议匹配当前集 · $label" else "${program.title} · $label",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSuggested) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        if (state.externalAvailable) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("外部弹幕匹配", style = MaterialTheme.typography.titleMedium)
                Text("弹弹play 的跨站关联可能缺失或不准确，请确认作品、季与集数后选择。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { model.searchExternal(query.ifBlank { state.program?.title.orEmpty() }) }, modifier = Modifier.padding(vertical = 6.dp)) { Text("查找外部弹幕") }
                state.externalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(state.externalWorks, key = { "external-work:${it.id}" }) { work ->
                Surface(
                    onClick = { model.externalEpisodes(work) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(work.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        AppIcon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.externalPrograms, key = { "external-program:${it.episodeId}" }) { program ->
                Surface(
                    onClick = { model.chooseExternal(program) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Text("${program.title} · ${program.number} · ${program.episodeTitle}", Modifier.padding(horizontal = 14.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else item { Text("外部弹幕暂不可用，本应用内的弹幕和评论可独立使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun WatchSettings(state: WatchUiState, model: WatchViewModel, modifier: Modifier) {
    val value = state.settings
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row { Text("显示弹幕", Modifier.weight(1f)); Switch(value.enabled, { model.settings(value.copy(enabled = it)) }, Modifier.semantics { contentDescription = "显示弹幕" }) }
        Text("字号：${value.size.toInt()}"); Slider(value.size, { model.settings(value.copy(size = it)) }, Modifier.semantics { contentDescription = "弹幕字号" }, valueRange = 12f..30f)
        Text("透明度：${(value.opacity * 100).toInt()}%"); Slider(value.opacity, { model.settings(value.copy(opacity = it)) }, Modifier.semantics { contentDescription = "弹幕透明度" }, valueRange = .3f..1f)
        Text("密度：${value.density} 行"); Slider(value.density.toFloat(), { model.settings(value.copy(density = it.toInt())) }, Modifier.semantics { contentDescription = "弹幕密度" }, valueRange = 1f..8f, steps = 6)
        Text("显示区域：屏幕上方 ${(value.area * 100).toInt()}%"); Slider(value.area, { model.settings(value.copy(area = it)) }, Modifier.semantics { contentDescription = "弹幕显示区域" }, valueRange = .25f..0.75f)
        Text("时间偏移：${value.offsetMs / 1000} 秒（正数让弹幕延后）")
        Row { TextButton(onClick = { model.settings(value.copy(offsetMs = (value.offsetMs - 1000).coerceAtLeast(-300000))) }) { Text("提前 1 秒") }
            TextButton(onClick = { model.settings(value.copy(offsetMs = (value.offsetMs + 1000).coerceAtMost(300000))) }) { Text("延后 1 秒") }
            TextButton(onClick = { model.settings(value.copy(offsetMs = 0)) }) { Text("归零") } }
        Text("偏移仅应用于当前播放版本；暂停、拖动和倍速均跟随视频时间。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WatchReasonDialog(title: String, moderation: Boolean, busy: Boolean, dismiss: () -> Unit, submit: (Boolean, String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    com.animeow.app.ui.components.StableModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("请说明原因，最多 300 字。")
            OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), label = { Text("原因") }, minLines = 2, maxLines = 5)
            TextButton(onClick = { submit(true, reason) }, enabled = !busy && reason.isNotBlank() && reason.length <= 300) { Text(if (moderation) "移除内容" else "提交举报") }
            if (moderation) TextButton(onClick = { submit(false, reason) }, enabled = !busy && reason.isNotBlank() && reason.length <= 300) { Text("保留内容") }
            TextButton(onClick = dismiss) { Text("取消") }
        }
    }
}
