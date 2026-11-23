package com.animeow.app.ui.importer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.bangumi.*
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.components.motionBarEnter
import com.animeow.app.ui.components.motionBarExit
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import org.json.JSONObject

private enum class BangumiPreviewTab { DIFF, NEW_REMOTE, UNLINKED, REMOTE_ONLY, ALL_LOCAL }

private data class BatchFieldOption(
    val direction: BangumiBatchDirection,
    val field: BangumiBatchField,
    val label: String,
    val selected: Int,
    val total: Int,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BangumiUploadScreen(
    onBack: () -> Unit,
    viewModel: BangumiUploadViewModel = viewModel(),
    onCloudAccountRequested: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable(state.kind) { mutableStateOf("") }
    var activeTab by rememberSaveable(state.kind) { mutableStateOf(BangumiPreviewTab.DIFF) }
    var statusFilter by rememberSaveable(state.kind, activeTab) { mutableStateOf<String?>(null) }
    var onlyCoreDiff by rememberSaveable(state.kind) { mutableStateOf(false) }
    var guideExpanded by rememberSaveable(state.preview == null) { mutableStateOf(state.preview == null) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    var privateNew by rememberSaveable { mutableStateOf(true) }
    var linkTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var linkNumber by rememberSaveable { mutableStateOf("") }
    var importTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var importProgress by rememberSaveable { mutableStateOf(false) }
    var bookTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var bookVolumes by rememberSaveable { mutableStateOf("") }
    var bookChapters by rememberSaveable { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSession() }
    fun back() {
        when {
            state.uploading -> confirmStop = true
            state.reading -> viewModel.cancelRead()
            else -> onBack()
        }
    }
    BackHandler(state.busy) { back() }
    val report = remember(state.report) { state.report?.let { runCatching { JSONObject(it) }.getOrNull() } }
    val reportEntries = remember(report) {
        val items = report?.optJSONArray("items") ?: return@remember emptyList()
        (0 until items.length()).map { items.getJSONObject(it) }
    }
    val uploadTotal = reportEntries.size
    val uploadCompleted = remember(reportEntries) {
        reportEntries.count { it.optString("state") in setOf("uploaded", "downloaded", "merged", "skipped", "failed", "uncertain") }
    }
    val uploadCurrentTitle = remember(reportEntries) {
        reportEntries.firstOrNull { it.optString("state") == "in_flight" }?.optString("title")?.takeIf(String::isNotBlank)
    }
    LaunchedEffect(state.preview?.createdAt) {
        privateNew = true
        confirm = false
        if (state.preview != null) {
            activeTab = BangumiPreviewTab.DIFF
            statusFilter = null
            onlyCoreDiff = false
            linkTarget = null
            bookTarget = null
        }
    }

    val preview = state.preview
    val allRows = preview?.rows.orEmpty()
    val diffCount = remember(allRows) { allRows.count { it.hasAnyDiff } }
    val newRemoteCount = remember(allRows) { allRows.count { it.isNewRemote && it.hasAnyDiff } }
    val unlinkedCount = remember(allRows) { allRows.count { it.isUnlinked } }
    val tabLocalRows = remember(allRows, activeTab) {
        when (activeTab) {
            BangumiPreviewTab.DIFF -> allRows.filter { it.hasAnyDiff }
            BangumiPreviewTab.NEW_REMOTE -> allRows.filter { it.isNewRemote && it.hasAnyDiff }
            BangumiPreviewTab.UNLINKED -> allRows.filter { it.isUnlinked }
            BangumiPreviewTab.ALL_LOCAL -> allRows
            BangumiPreviewTab.REMOTE_ONLY -> emptyList()
        }
    }
    val hasExtraOnlyRows = remember(tabLocalRows) { tabLocalRows.any { it.hasExtraOnlyDiff } }
    val distinctStatuses = remember(tabLocalRows) {
        tabLocalRows.groupingBy { it.local.status }.eachCount().entries.sortedByDescending { it.value }
    }
    val rows = remember(tabLocalRows, statusFilter, onlyCoreDiff, hasExtraOnlyRows, query) {
        val trimmed = query.trim()
        tabLocalRows.filter { row ->
            (statusFilter == null || row.local.status == statusFilter) &&
                (!onlyCoreDiff || !hasExtraOnlyRows || row.hasCoreDiff) &&
                (trimmed.isEmpty() || row.local.title.contains(trimmed, true) || row.local.subjectId?.toString()?.contains(trimmed) == true)
        }
    }
    val remoteOnlyRows = remember(preview?.remoteOnly, query) {
        val trimmed = query.trim()
        preview?.remoteOnly.orEmpty().filter {
            trimmed.isEmpty() || it.title.contains(trimmed, true) || it.subjectId.toString().contains(trimmed)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("与 Bangumi 对比合并") },
                navigationIcon = {
                    IconButton(onClick = ::back) { AppIcon(AppIcons.Back, contentDescription = "返回") }
                },
            )
        },
        bottomBar = {
            val showBottomBar = state.uploading || (preview != null && !state.busy)
            AnimatedVisibility(
                visible = showBottomBar,
                enter = motionBarEnter(),
                exit = motionBarExit(),
            ) {
                val motionLevel = LocalMotionLevel.current
                AnimatedContent(
                    targetState = state.uploading,
                    transitionSpec = { motionFadeThroughSpec(motionLevel, 180, 120) },
                    label = "bangumiUploadBottomBar",
                ) { isUploading ->
                    if (isUploading) {
                        Surface(tonalElevation = 3.dp) {
                            Column(
                                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            if (uploadTotal > 0) "同步进度：$uploadCompleted / $uploadTotal 部" else "正在准备同步队列…",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        uploadCurrentTitle?.let {
                                            Text(
                                                "正在处理《$it》",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = viewModel::stopAfterCurrent, enabled = !state.stopping) {
                                        Text(if (state.stopping) "正在停止…" else "停止后续")
                                    }
                                }
                                if (uploadTotal > 0) {
                                    LinearProgressIndicator(
                                        progress = { (uploadCompleted.toFloat() / uploadTotal.toFloat()).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    } else if (preview != null) {
                        Surface(tonalElevation = 3.dp) {
                            val uploadCount = state.choices.values.count { it.uploadSelected }
                            val adoptCount = state.choices.values.count { it.downloadSelected }
                            val newSelectedCount = preview.rows.count { it.remote == null && state.choices[it.local.id]?.selected == true }
                            val summary = if (state.selectedCount == 0) {
                                "尚未选择要合并的作品"
                            } else {
                                buildString {
                                    append("已选 ${state.selectedCount} 部（")
                                    val parts = mutableListOf<String>()
                                    if (uploadCount > 0) {
                                        parts += if (newSelectedCount > 0) "上传 $uploadCount，含新建 $newSelectedCount" else "上传 $uploadCount"
                                    }
                                    if (adoptCount > 0) parts += "采用 $adoptCount"
                                    append(parts.joinToString(" · "))
                                    append("）")
                                }
                            }
                            Column(
                                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(summary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    if (state.selectedCount > 0) {
                                        TextButton(onClick = viewModel::clearSelection) { Text("清空") }
                                    }
                                }
                                Button(
                                    onClick = { confirm = true },
                                    enabled = state.selectedCount > 0,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("核对并确认合并")
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { guideExpanded = !guideExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (preview == null) "选择每个字段的合并方向" else "合并说明与安全规则",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            AppIcon(
                                if (guideExpanded) AppIcons.Collapse else AppIcons.Expand,
                                contentDescription = if (guideExpanded) "收起说明" else "展开说明",
                            )
                        }
                        AnimatedVisibility(
                            visible = guideExpanded,
                            enter = motionExpandIn(),
                            exit = motionShrinkOut(),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "操作步骤：① 登录并连接 Bangumi 账号 → ② 点击下方「读取完整收藏对比」 → ③ 用筛选与批量操作勾选差异并确认合并。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text("先读取自己的完整收藏（含私密），每个字段可上传本机值，或采用 Bangumi 值。未选择的字段和远端独有收藏保留。")
                                Text(
                                    when (state.kind) {
                                        BangumiUploadKind.ANIME -> "支持状态、整数评分与本篇连续已看进度；跳集或映射不完整时保留进度。"
                                        BangumiUploadKind.BOOK -> "书籍卷数、话数独立记录，需逐项选择；旧通用阅读进度不会自动转换。"
                                        BangumiUploadKind.GAME -> "支持状态、整数评分、标签与短评；游戏时长保留原样。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "标签、短评需展开卡片逐项选择，批量选择不会改动它们。已有收藏的私密设置保留，新建默认仅自己可见。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BangumiUploadKind.entries.forEach { kind ->
                            FilterChip(
                                selected = state.kind == kind,
                                onClick = { viewModel.chooseKind(kind) },
                                enabled = !state.busy,
                                label = { Text(kind.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Button(
                        onClick = viewModel::read,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("读取完整收藏对比")
                    }
                }
            }

            item(key = "upload-reading-banner") {
                AnimatedVisibility(
                    visible = state.reading,
                    enter = motionExpandIn(),
                    exit = motionShrinkOut(),
                ) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.progress.ifBlank { "正在读取 Bangumi 收藏…" }, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = viewModel::cancelRead) { Text("取消读取") }
                        }
                    }
                }
            }

            item(key = "upload-progress-banner") {
                AnimatedVisibility(
                    visible = state.uploading,
                    enter = motionExpandIn(),
                    exit = motionShrinkOut(),
                ) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (uploadTotal > 0) {
                                Text("已完成 $uploadCompleted / $uploadTotal 部", fontWeight = FontWeight.SemiBold)
                                LinearProgressIndicator(
                                    progress = { (uploadCompleted.toFloat() / uploadTotal.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            uploadCurrentTitle?.let {
                                Text("当前作品：《$it》", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(if (state.stopping) "正在完成当前项，随后停止。" else "正在逐项核对并合并，请保持此页面。")
                            TextButton(onClick = viewModel::stopAfterCurrent, enabled = !state.stopping) {
                                Text("完成当前项后停止")
                            }
                        }
                    }
                }
            }

            item(key = "upload-error-banner") {
                AnimatedVisibility(
                    visible = state.error != null,
                    enter = motionExpandIn(),
                    exit = motionShrinkOut(),
                ) {
                    val error = state.error.orEmpty()
                    OutlinedCard(
                        Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            if (onCloudAccountRequested != null && (error.contains("登录") || error.contains("连接 Bangumi") || error.contains("授权"))) {
                                FilledTonalButton(onClick = onCloudAccountRequested) { Text("前往账号与同步连接 Bangumi") }
                            }
                        }
                    }
                }
            }

            if (preview == null && report != null) {
                item { ReportSummaryCard(report, reportEntries, state.uploading, !state.busy) { confirmExport = true } }
                items(reportEntries, key = { "result-${it.optLong("local_id")}" }) { entry -> ReportEntryCard(entry) }
            }

            preview?.let { currentPreview ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "已核对 @${currentPreview.account.username}（${currentPreview.account.bangumiId}）",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "远端 ${currentPreview.remoteCount} 项 · 本机 ${currentPreview.rows.size} 项（待新建 $newRemoteCount · 未关联 $unlinkedCount · 仅远端 ${currentPreview.remoteOnly.size}）；对比 10 分钟内有效。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(
                                    selected = activeTab == BangumiPreviewTab.DIFF,
                                    onClick = { activeTab = BangumiPreviewTab.DIFF },
                                    enabled = !state.busy,
                                    label = { Text("有差异 ($diffCount)") },
                                )
                                FilterChip(
                                    selected = activeTab == BangumiPreviewTab.NEW_REMOTE,
                                    onClick = { activeTab = BangumiPreviewTab.NEW_REMOTE },
                                    enabled = !state.busy,
                                    label = { Text("待新建 ($newRemoteCount)") },
                                )
                                FilterChip(
                                    selected = activeTab == BangumiPreviewTab.UNLINKED,
                                    onClick = { activeTab = BangumiPreviewTab.UNLINKED },
                                    enabled = !state.busy,
                                    label = { Text("未关联 ($unlinkedCount)") },
                                )
                                FilterChip(
                                    selected = activeTab == BangumiPreviewTab.REMOTE_ONLY,
                                    onClick = { activeTab = BangumiPreviewTab.REMOTE_ONLY },
                                    enabled = !state.busy,
                                    label = { Text("仅 Bangumi (${currentPreview.remoteOnly.size})") },
                                )
                                FilterChip(
                                    selected = activeTab == BangumiPreviewTab.ALL_LOCAL,
                                    onClick = { activeTab = BangumiPreviewTab.ALL_LOCAL },
                                    enabled = !state.busy,
                                    label = { Text("全部本机 (${currentPreview.rows.size})") },
                                )
                            }

                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("按标题或编号查找") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !state.busy,
                                trailingIcon = {
                                    AnimatedVisibility(
                                        visible = query.isNotEmpty(),
                                        enter = motionFadeIn(140),
                                        exit = motionFadeOut(120),
                                    ) {
                                        IconButton(onClick = { query = "" }) {
                                            AppIcon(AppIcons.Close, contentDescription = "清空搜索")
                                        }
                                    }
                                },
                            )

                            if (activeTab != BangumiPreviewTab.REMOTE_ONLY && (distinctStatuses.size >= 2 || hasExtraOnlyRows)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (distinctStatuses.size >= 2) {
                                        FilterChip(
                                            selected = statusFilter == null,
                                            onClick = { statusFilter = null },
                                            enabled = !state.busy,
                                            label = { Text("全部状态 (${tabLocalRows.size})") },
                                        )
                                        distinctStatuses.forEach { (status, count) ->
                                            FilterChip(
                                                selected = statusFilter == status,
                                                onClick = { statusFilter = if (statusFilter == status) null else status },
                                                enabled = !state.busy,
                                                label = { Text("$status ($count)") },
                                            )
                                        }
                                    }
                                    if (hasExtraOnlyRows) {
                                        FilterChip(
                                            selected = onlyCoreDiff,
                                            onClick = { onlyCoreDiff = !onlyCoreDiff },
                                            enabled = !state.busy,
                                            label = { Text("仅看状态/评分/进度差异") },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (activeTab != BangumiPreviewTab.REMOTE_ONLY && rows.isNotEmpty() && !state.busy) {
                    item {
                        BatchOperationsCard(
                            kind = state.kind,
                            rows = rows,
                            choices = state.choices,
                            totalSelectedCount = state.selectedCount,
                            showNewOnlyButton = activeTab != BangumiPreviewTab.NEW_REMOTE,
                            onSelectRows = viewModel::selectRows,
                            onSelectDownloadRows = viewModel::selectDownloadRows,
                            onToggleField = viewModel::toggleBatchField,
                            onClearSelection = viewModel::clearSelection,
                        )
                    }
                }

                if (activeTab == BangumiPreviewTab.REMOTE_ONLY) {
                    item {
                        Text(
                            "仅在 Bangumi 的收藏：若本机已有对应作品，请先切到「未关联」确认编号关联，避免重复添加；回收站记录不会在此恢复。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (remoteOnlyRows.isEmpty()) {
                        item {
                            Text(
                                if (query.isBlank()) "当前分类下没有仅存在于 Bangumi 的收藏。" else "没有找到匹配的远端收藏。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(remoteOnlyRows, key = { "remote-${it.subjectId}" }) { item ->
                        ElevatedCard(motionAnimateItem().fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    buildString {
                                        append("Bangumi #${item.subjectId} · ${bangumiUploadStatus(item.type, item.kind)}")
                                        if (item.rate > 0) append(" · ${item.rate} 分")
                                        if (item.kind == BangumiUploadKind.ANIME && item.progress > 0) append(" · ${item.progress} 集")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { importProgress = false; importTarget = item.subjectId },
                                    enabled = !state.busy,
                                ) {
                                    Text("添加到本机")
                                }
                            }
                        }
                    }
                } else {
                    if (rows.isEmpty()) {
                        item {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        when {
                                            query.isNotBlank() || statusFilter != null || onlyCoreDiff ->
                                                "没有符合当前筛选条件的作品。"
                                            activeTab == BangumiPreviewTab.DIFF ->
                                                "当前分类下没有待合并差异。"
                                            activeTab == BangumiPreviewTab.NEW_REMOTE ->
                                                "已关联的本机作品在 Bangumi 上都已有收藏。"
                                            activeTab == BangumiPreviewTab.UNLINKED ->
                                                "没有未关联 Bangumi 编号的本机作品。"
                                            else -> "当前分类下没有本机作品。"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (query.isNotBlank() || statusFilter != null || onlyCoreDiff) {
                                            OutlinedButton(onClick = {
                                                query = ""
                                                statusFilter = null
                                                onlyCoreDiff = false
                                            }) {
                                                Text("重置筛选条件")
                                            }
                                        }
                                        if (activeTab == BangumiPreviewTab.DIFF && unlinkedCount > 0) {
                                            FilledTonalButton(onClick = { activeTab = BangumiPreviewTab.UNLINKED }) {
                                                Text("查看未关联 ($unlinkedCount)")
                                            }
                                        }
                                        if (activeTab == BangumiPreviewTab.DIFF && currentPreview.remoteOnly.isNotEmpty()) {
                                            OutlinedButton(onClick = { activeTab = BangumiPreviewTab.REMOTE_ONLY }) {
                                                Text("查看仅 Bangumi (${currentPreview.remoteOnly.size})")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(rows, key = { "local-${it.local.id}" }) { row ->
                        UploadRowCard(
                            modifier = motionAnimateItem(),
                            row = row,
                            choice = state.choices[row.local.id] ?: BangumiUploadChoice(),
                            enabled = !state.busy,
                            isLinking = linkTarget == row.local.id,
                            linkNumber = if (linkTarget == row.local.id) linkNumber else "",
                            isEditingBook = bookTarget == row.local.id,
                            bookVolumes = if (bookTarget == row.local.id) bookVolumes else "",
                            bookChapters = if (bookTarget == row.local.id) bookChapters else "",
                            onChange = { viewModel.select(row.local.id, it) },
                            onStartLink = { linkNumber = ""; linkTarget = row.local.id },
                            onLinkNumberChange = { linkNumber = it },
                            onReadAssociation = { linkTarget = null; viewModel.proposeAssociation(row.local.id, linkNumber) },
                            onCancelLink = { linkTarget = null },
                            onStartEditBook = {
                                bookTarget = row.local.id
                                bookVolumes = row.local.bookVolumes?.toString().orEmpty()
                                bookChapters = row.local.bookChapters?.toString().orEmpty()
                            },
                            onBookVolumesChange = { bookVolumes = it },
                            onBookChaptersChange = { bookChapters = it },
                            onSaveBookProgress = { bookTarget = null; viewModel.saveBookProgress(row.local.id, bookVolumes, bookChapters) },
                            onCancelEditBook = { bookTarget = null },
                        )
                    }
                }

                if (report != null) {
                    item { ReportSummaryCard(report, reportEntries, state.uploading, !state.busy) { confirmExport = true } }
                    items(reportEntries, key = { "result-${it.optLong("local_id")}" }) { entry -> ReportEntryCard(entry) }
                }
            }
        }
    }

    state.association?.let { proposal ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAssociation,
            title = { Text("确认是同一部作品？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本机：${proposal.local.title}")
                    Text("Bangumi：${proposal.title}（#${proposal.subjectId}）")
                    Text("只保存作品关联，原有来源和记录保留，不修改 Bangumi 收藏。保存后重新读取对比，不自动上传或采用数据。")
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmAssociation) { Text("确认关联") } },
            dismissButton = { TextButton(onClick = viewModel::dismissAssociation) { Text("取消") } },
        )
    }

    if (importTarget != null && preview != null && !state.busy) {
        val target = preview.remoteOnly.firstOrNull { it.subjectId == importTarget }
        if (target != null) AlertDialog(
            onDismissRequest = { importTarget = null },
            title = { Text("将此收藏添加到本机？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${target.title}（#${target.subjectId}）")
                    Text("将以 Bangumi 的状态和整数评分创建新记录，不修改 Bangumi 收藏。已有本机作品请先关联；同名不会自动合并。")
                    Text("书籍卷话、游戏时长、标签、短评和观看历史不导入；作品资料可在添加后补全。")
                    if (target.kind == BangumiUploadKind.ANIME) {
                        UploadCheck("核对并采用本篇连续已看进度", importProgress, true) { importProgress = it }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importTarget = null; viewModel.importRemote(target.subjectId, importProgress) }) {
                    Text("确认添加")
                }
            },
            dismissButton = { TextButton(onClick = { importTarget = null }) { Text("取消") } },
        )
    }

    if (confirm && preview != null && !state.busy) {
        val newCount = preview.rows.count { it.remote == null && state.choices[it.local.id]?.selected == true }
        val clearing = preview.rows.count { state.choices[it.local.id]?.rating == true && it.local.rate == 0 && (it.remote?.rate ?: 0) > 0 }
        val reducing = preview.rows.count { state.choices[it.local.id]?.progress == true && (it.local.progress ?: 0) < (it.remote?.progress ?: 0) }
        val adopting = state.choices.values.count { it.downloadSelected }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认合并收藏？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将按所选方向合并 ${state.selectedCount} 部作品，Bangumi 账号为 @${preview.account.username}。其中 $adopting 部会采用 Bangumi 值修改本机；未选字段及远端其他收藏保留。")
                    if (adopting > 0) Text(
                        "采用远端评分会替换本机小数或字母评分；采用状态或进度可能减少记录值。只修改勾选字段，不生成观看历史。",
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (state.choices.values.any { it.tags || it.comment || it.downloadTags || it.downloadComment }) {
                        Text(
                            "本次包含标签或短评替换。空标签会清空目标作品的标签，空短评会清空目标感想；上传至公开收藏时，这些内容可能公开可见。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.choices.values.any { it.volumes || it.chapters || it.downloadVolumes || it.downloadChapters }) {
                        Text(
                            "卷数与话数按所选方向替换，可能减少或清零；不会改写旧通用阅读进度。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("评分为未评分时，勾选上传会清空远端评分。进度可能增加或减少，按本篇连续集数处理；修改不能自动撤回。")
                    if (clearing + reducing > 0) {
                        Text("本次将清空 $clearing 部作品的评分，减少 $reducing 部作品的进度。", color = MaterialTheme.colorScheme.error)
                    }
                    Text("上传期间请勿在其他设备修改同一作品，完成后会重新读取核对。", style = MaterialTheme.typography.bodySmall)
                    if (newCount > 0) {
                        Text("其中 $newCount 部将新建收藏。")
                        UploadCheck("新建收藏仅自己可见", privateNew, true) { privateNew = it }
                        if (!privateNew) Text("新建收藏将公开。", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; viewModel.upload(privateNew) }) { Text("确认合并") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("返回核对") }
            },
        )
    }

    if (confirmStop && state.uploading) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("停止后续上传？") },
        text = { Text("当前项完成后停止，已经写入的修改保留。剩余项目需要重新读取对比后继续。") },
        confirmButton = { TextButton(onClick = { confirmStop = false; viewModel.stopAfterCurrent() }) { Text("停止后续项") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续上传") } },
    )

    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text("导出上传快照？") },
        text = { Text("文件可能包含私密收藏、短评与标签快照，请妥善保管；导出不会自动发送给其他人。") },
        confirmButton = {
            TextButton(onClick = { confirmExport = false; export.launch("AniMeow-Bangumi-${System.currentTimeMillis()}.json") }) {
                Text("选择保存位置")
            }
        },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchOperationsCard(
    kind: BangumiUploadKind,
    rows: List<BangumiUploadRow>,
    choices: Map<Long, BangumiUploadChoice>,
    totalSelectedCount: Int,
    showNewOnlyButton: Boolean,
    onSelectRows: (Set<Long>, Boolean) -> Unit,
    onSelectDownloadRows: (Set<Long>) -> Unit,
    onToggleField: (Set<Long>, BangumiBatchDirection, BangumiBatchField) -> Unit,
    onClearSelection: () -> Unit,
) {
    val rowIds = remember(rows) { rows.mapTo(hashSetOf()) { it.local.id } }
    val uploadableCount = remember(rows) { rows.count { it.safeUploadChoice().selected } }
    val newLocalCount = remember(rows) { rows.count { it.isNewRemote && it.safeUploadChoice().selected } }
    val downloadableCount = remember(rows) { rows.count { it.safeDownloadChoice().selected } }

    fun fieldOptions(direction: BangumiBatchDirection): List<BatchFieldOption> {
        val fields = buildList {
            add(BangumiBatchField.STATUS to if (direction == BangumiBatchDirection.UPLOAD) "↑ 状态" else "↓ 状态")
            add(BangumiBatchField.RATING to if (direction == BangumiBatchDirection.UPLOAD) "↑ 评分" else "↓ 评分")
            if (kind == BangumiUploadKind.ANIME) {
                add(BangumiBatchField.PROGRESS to if (direction == BangumiBatchDirection.UPLOAD) "↑ 进度推进" else "↓ 进度推进")
            }
        }
        return fields.mapNotNull { (field, baseLabel) ->
            val eligible = rows.filter { it.isBatchEligible(direction, field) }
            if (eligible.isEmpty()) null
            else {
                val selected = eligible.count { choices[it.local.id]?.hasField(direction, field) == true }
                val text = if (selected in 1 until eligible.size) "$baseLabel ($selected/${eligible.size})" else "$baseLabel (${eligible.size})"
                BatchFieldOption(direction, field, text, selected, eligible.size)
            }
        }
    }

    val uploadFieldOptions = remember(rows, choices, kind) { fieldOptions(BangumiBatchDirection.UPLOAD) }
    val downloadFieldOptions = remember(rows, choices, kind) { fieldOptions(BangumiBatchDirection.DOWNLOAD) }
    if (uploadableCount == 0 && downloadableCount == 0 && totalSelectedCount == 0) return

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.motionAnimateContentSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "批量操作（当前列表 ${rows.size} 部）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (totalSelectedCount > 0) {
                    TextButton(onClick = onClearSelection) { Text("清空选择") }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (uploadableCount > 0) {
                    FilledTonalButton(onClick = { onSelectRows(rowIds, false) }) {
                        Text("全选可上传差异 ($uploadableCount)")
                    }
                }
                if (showNewOnlyButton && newLocalCount > 0) {
                    OutlinedButton(onClick = { onSelectRows(rowIds, true) }) {
                        Text("仅选待新建收藏 ($newLocalCount)")
                    }
                }
                if (downloadableCount > 0) {
                    OutlinedButton(onClick = { onSelectDownloadRows(rowIds) }) {
                        Text("全选可采用差异 ($downloadableCount)")
                    }
                }
            }

            if (uploadFieldOptions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("按字段批量上传到 Bangumi", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        uploadFieldOptions.forEach { opt ->
                            FilterChip(
                                selected = opt.selected == opt.total && opt.total > 0,
                                onClick = { onToggleField(rowIds, opt.direction, opt.field) },
                                label = { Text(opt.label) },
                            )
                        }
                    }
                }
            }

            if (downloadFieldOptions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("按字段批量采用 Bangumi 值", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        downloadFieldOptions.forEach { opt ->
                            FilterChip(
                                selected = opt.selected == opt.total && opt.total > 0,
                                onClick = { onToggleField(rowIds, opt.direction, opt.field) },
                                label = { Text(opt.label) },
                            )
                        }
                    }
                }
            }

            Text(
                "安全提示：批量操作只勾选状态、非零整数评分与向前推进的集数；清空评分、回退进度、标签、短评和书籍卷话需在下方卡片逐项勾选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportSummaryCard(
    report: JSONObject,
    entries: List<JSONObject>,
    uploading: Boolean,
    enabled: Boolean,
    onExport: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("本次合并结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("@${report.optString("username")}（${report.optLong("bangumi_id")}）", style = MaterialTheme.typography.bodySmall)
            val merged = entries.count { it.optString("state") in setOf("uploaded", "downloaded", "merged") }
            val uncertain = entries.count { it.optString("state") in setOf("uncertain", "in_flight") }
            val unfinished = entries.count { it.optString("state") !in setOf("uploaded", "downloaded", "merged", "uncertain", "in_flight") }
            Text("已核对合并 $merged · 待核实 $uncertain · 未执行或未完成 $unfinished")
            if (!uploading && report.optString("state") in setOf("running", "interrupted", "failed", "stopped")) {
                Text(
                    "上次操作未正常结束或未全部完成。请重新读取对比后再选择剩余差异，不会自动重放。",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text("修改前快照与结果仅保存在本机，清除应用数据后会丢失。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onExport, enabled = enabled) { Text("导出本次快照与结果") }
        }
    }
}

@Composable
private fun ReportEntryCard(entry: JSONObject) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.optString("title"), fontWeight = FontWeight.SemiBold)
            Text(
                when (entry.optString("state")) {
                    "uploaded" -> "已上传并读回核对"
                    "downloaded" -> "已采用 Bangumi 值"
                    "merged" -> "已按字段方向合并"
                    "skipped" -> "本次跳过"
                    "failed" -> "准备未完成"
                    "in_flight", "uncertain" -> "结果待核实，请重新读取"
                    else -> "尚未执行"
                },
            )
            entry.optString("message").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MergeNumberField(value: String, label: String, onChange: (String) -> Unit) {
    // Keep numeric labels visible at large font sizes and in narrow forms.
    Text(label, style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value,
        onChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UploadRowCard(
    row: BangumiUploadRow,
    choice: BangumiUploadChoice,
    enabled: Boolean,
    isLinking: Boolean,
    linkNumber: String,
    isEditingBook: Boolean,
    bookVolumes: String,
    bookChapters: String,
    onChange: (BangumiUploadChoice) -> Unit,
    onStartLink: () -> Unit,
    onLinkNumberChange: (String) -> Unit,
    onReadAssociation: () -> Unit,
    onCancelLink: () -> Unit,
    onStartEditBook: () -> Unit,
    onBookVolumesChange: (String) -> Unit,
    onBookChaptersChange: (String) -> Unit,
    onSaveBookProgress: () -> Unit,
    onCancelEditBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPrivate by remember(row.local, row.remote) { mutableStateOf(false) }
    val allowed = row.available
    val down = row.downloadable
    val safeUpload = remember(row) { row.safeUploadChoice() }
    val safeDownload = remember(row) { row.safeDownloadChoice() }
    val badgeText = when {
        choice.uploadSelected && choice.downloadSelected -> "双向已选 ${choice.selectedFieldCount} 项"
        choice.uploadSelected -> "将上传 ${choice.uploadFieldCount} 项"
        choice.downloadSelected -> "将采用 ${choice.downloadFieldCount} 项"
        else -> null
    }
    val borderWidth by animateDpAsState(
        targetValue = if (choice.selected) 1.5.dp else 1.dp,
        animationSpec = motionAnimationSpec(180),
        label = "uploadRowBorderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (choice.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = motionAnimationSpec(180),
        label = "uploadRowBorderColor",
    )
    val containerColor by animateColorAsState(
        targetValue = if (choice.selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        animationSpec = motionAnimationSpec(180),
        label = "uploadRowContainerColor",
    )

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(
            width = borderWidth,
            color = borderColor,
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = containerColor,
        ),
    ) {
        Column(Modifier.motionAnimateContentSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.local.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        row.local.subjectId?.let { "Bangumi #$it · ${if (row.remote == null) "将新建收藏" else "已有收藏"}" }
                            ?: "尚未关联 Bangumi 编号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (badgeText != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    buildString {
                        append("本机：${row.local.status} · ${row.local.ratingText}")
                        when (row.local.kind) {
                            BangumiUploadKind.ANIME -> row.local.progress?.let { append(" · 已看 $it 集") }
                            BangumiUploadKind.BOOK -> append(" · ${row.local.bookVolumes?.toString() ?: "未知"} 卷 / ${row.local.bookChapters?.toString() ?: "未知"} 话")
                            BangumiUploadKind.GAME -> Unit
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            row.blocked?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (row.blocked == null && (safeUpload.selected || safeDownload.selected || choice.selected)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (safeUpload.selected) {
                        val isQuickUpload = choice == safeUpload
                        FilterChip(
                            selected = isQuickUpload,
                            enabled = enabled,
                            onClick = { onChange(if (isQuickUpload) BangumiUploadChoice() else safeUpload) },
                            label = { Text("快捷全选上传") },
                        )
                    }
                    if (safeDownload.selected) {
                        val isQuickDownload = choice == safeDownload
                        FilterChip(
                            selected = isQuickDownload,
                            enabled = enabled,
                            onClick = { onChange(if (isQuickDownload) BangumiUploadChoice() else safeDownload) },
                            label = { Text("快捷全选采用") },
                        )
                    }
                    if (choice.selected) {
                        TextButton(onClick = { onChange(BangumiUploadChoice()) }, enabled = enabled) {
                            Text("取消本项")
                        }
                    }
                }
            }

            if (allowed.status || down.status) {
                val remoteStatus = bangumiUploadStatus(row.remote?.type, row.local.kind)
                val localUploadStatus = bangumiUploadStatus(row.local.type, row.local.kind)
                MergeFieldRow(
                    comparison = "状态：本机 ${row.local.status} ｜ Bangumi $remoteStatus",
                    uploadLabel = if (allowed.status) "↑ 上传：$localUploadStatus" else null,
                    uploadSelected = choice.status,
                    uploadWarning = false,
                    onToggleUpload = {
                        val next = !choice.status
                        onChange(choice.copy(status = next, downloadStatus = if (next) false else choice.downloadStatus))
                    },
                    downloadLabel = if (down.status) "↓ 采用：$remoteStatus" else null,
                    downloadSelected = choice.downloadStatus,
                    downloadWarning = false,
                    onToggleDownload = {
                        val next = !choice.downloadStatus
                        onChange(choice.copy(downloadStatus = next, status = if (next) false else choice.status))
                    },
                    enabled = enabled,
                )
            }

            if (allowed.rating || down.rating) {
                val remoteRatingText = row.remote?.rate?.takeIf { it > 0 }?.let { "$it 分" } ?: "未评分"
                val clearingRemote = row.local.rate == 0 && (row.remote?.rate ?: 0) > 0
                MergeFieldRow(
                    comparison = "评分：本机 ${row.local.ratingText} ｜ Bangumi $remoteRatingText",
                    uploadLabel = if (allowed.rating) {
                        if (clearingRemote) "↑ 上传：清空评分" else "↑ 上传：${row.local.rate} 分"
                    } else null,
                    uploadSelected = choice.rating,
                    uploadWarning = clearingRemote,
                    onToggleUpload = {
                        val next = !choice.rating
                        onChange(choice.copy(rating = next, downloadRating = if (next) false else choice.downloadRating))
                    },
                    downloadLabel = if (down.rating) {
                        val target = if (row.remote!!.rate == 0) "清空（未评分）" else "${row.remote.rate} 分"
                        "↓ 采用：$target"
                    } else null,
                    downloadSelected = choice.downloadRating,
                    downloadWarning = row.remote?.rate == 0,
                    onToggleDownload = {
                        val next = !choice.downloadRating
                        onChange(choice.copy(downloadRating = next, rating = if (next) false else choice.rating))
                    },
                    enabled = enabled,
                )
            }

            if (allowed.progress || down.progress) {
                val remoteEp = row.remote?.progress ?: 0
                val localEp = row.local.progress ?: 0
                val uploadReduces = localEp < remoteEp
                val downloadReduces = remoteEp < localEp
                MergeFieldRow(
                    comparison = "进度：本机 $localEp 集 ｜ Bangumi $remoteEp 集",
                    uploadLabel = if (allowed.progress) {
                        if (uploadReduces) "↑ 上传：回退至 $localEp 集" else "↑ 上传：$localEp 集"
                    } else null,
                    uploadSelected = choice.progress,
                    uploadWarning = uploadReduces,
                    onToggleUpload = {
                        val next = !choice.progress
                        onChange(choice.copy(progress = next, downloadProgress = if (next) false else choice.downloadProgress))
                    },
                    downloadLabel = if (down.progress) {
                        if (downloadReduces) "↓ 采用：回退至 $remoteEp 集" else "↓ 采用：$remoteEp 集"
                    } else null,
                    downloadSelected = choice.downloadProgress,
                    downloadWarning = downloadReduces,
                    onToggleDownload = {
                        val next = !choice.downloadProgress
                        onChange(choice.copy(downloadProgress = next, progress = if (next) false else choice.progress))
                    },
                    enabled = enabled,
                )
            }

            if (row.local.kind == BangumiUploadKind.BOOK) {
                if (allowed.volumes || down.volumes) {
                    val remoteVol = row.remote?.volumes ?: 0
                    val localVol = row.local.bookVolumes ?: 0
                    MergeFieldRow(
                        comparison = "卷数：本机 ${row.local.bookVolumes?.toString() ?: "未知"} 卷 ｜ Bangumi $remoteVol 卷",
                        uploadLabel = if (allowed.volumes) "↑ 上传：$localVol 卷" else null,
                        uploadSelected = choice.volumes,
                        uploadWarning = localVol < remoteVol,
                        onToggleUpload = {
                            val next = !choice.volumes
                            onChange(choice.copy(volumes = next, downloadVolumes = if (next) false else choice.downloadVolumes))
                        },
                        downloadLabel = if (down.volumes) "↓ 采用：$remoteVol 卷" else null,
                        downloadSelected = choice.downloadVolumes,
                        downloadWarning = remoteVol < localVol,
                        onToggleDownload = {
                            val next = !choice.downloadVolumes
                            onChange(choice.copy(downloadVolumes = next, volumes = if (next) false else choice.volumes))
                        },
                        enabled = enabled,
                    )
                }
                if (allowed.chapters || down.chapters) {
                    val remoteChap = row.remote?.progress ?: 0
                    val localChap = row.local.bookChapters ?: 0
                    MergeFieldRow(
                        comparison = "话数：本机 ${row.local.bookChapters?.toString() ?: "未知"} 话 ｜ Bangumi $remoteChap 话",
                        uploadLabel = if (allowed.chapters) "↑ 上传：$localChap 话" else null,
                        uploadSelected = choice.chapters,
                        uploadWarning = localChap < remoteChap,
                        onToggleUpload = {
                            val next = !choice.chapters
                            onChange(choice.copy(chapters = next, downloadChapters = if (next) false else choice.downloadChapters))
                        },
                        downloadLabel = if (down.chapters) "↓ 采用：$remoteChap 话" else null,
                        downloadSelected = choice.downloadChapters,
                        downloadWarning = remoteChap < localChap,
                        onToggleDownload = {
                            val next = !choice.downloadChapters
                            onChange(choice.copy(downloadChapters = next, chapters = if (next) false else choice.chapters))
                        },
                        enabled = enabled,
                    )
                }
            }

            if (row.blocked == null) {
                val extraDiff = allowed.tags || allowed.comment || down.tags || down.comment
                val extraSelected = choice.tags || choice.comment || choice.downloadTags || choice.downloadComment
                TextButton(onClick = { showPrivate = !showPrivate }, enabled = enabled) {
                    Text(
                        when {
                            showPrivate -> "收起标签与短评"
                            extraSelected -> "查看并选择标签与短评（已选修改）"
                            extraDiff -> "查看并选择标签与短评（与 Bangumi 有差异）"
                            else -> "查看标签与短评"
                        },
                    )
                }
                AnimatedVisibility(
                    visible = showPrivate,
                    enter = motionExpandIn(),
                    exit = motionShrinkOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("本机标签：${row.local.tags.joinToString("、").ifBlank { "无" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Bangumi 标签：${row.remote?.tags.orEmpty().joinToString("、").ifBlank { "无" }}", style = MaterialTheme.typography.bodySmall)
                        if (allowed.tags || down.tags) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (allowed.tags) {
                                    FilterChip(
                                        selected = choice.tags,
                                        enabled = enabled,
                                        onClick = {
                                            val next = !choice.tags
                                            onChange(choice.copy(tags = next, downloadTags = if (next) false else choice.downloadTags))
                                        },
                                        label = { Text("↑ 上传本机标签") },
                                    )
                                }
                                if (down.tags) {
                                    FilterChip(
                                        selected = choice.downloadTags,
                                        enabled = enabled,
                                        onClick = {
                                            val next = !choice.downloadTags
                                            onChange(choice.copy(downloadTags = next, tags = if (next) false else choice.tags))
                                        },
                                        label = { Text("↓ 采用 Bangumi 标签") },
                                    )
                                }
                            }
                        }
                        Text("本机感想：${row.local.comment.ifBlank { "无" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Bangumi 短评：${row.remote?.comment.orEmpty().ifBlank { "无" }}", style = MaterialTheme.typography.bodySmall)
                        if (allowed.comment || down.comment) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (allowed.comment) {
                                    FilterChip(
                                        selected = choice.comment,
                                        enabled = enabled,
                                        onClick = {
                                            val next = !choice.comment
                                            onChange(choice.copy(comment = next, downloadComment = if (next) false else choice.downloadComment))
                                        },
                                        label = { Text("↑ 上传本机感想") },
                                    )
                                }
                                if (down.comment) {
                                    FilterChip(
                                        selected = choice.downloadComment,
                                        enabled = enabled,
                                        onClick = {
                                            val next = !choice.downloadComment
                                            onChange(choice.copy(downloadComment = next, comment = if (next) false else choice.comment))
                                        },
                                        label = { Text("↓ 采用 Bangumi 短评") },
                                    )
                                }
                            }
                        }
                        if (!validBangumiUploadTags(row.local.tags)) {
                            Text("含空白、重复或过长的标签不能无损上传，请先整理标签。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (row.local.comment.length > 5000) {
                            Text("本机感想超过本次 5000 字限制，不会截断上传。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (row.local.rate == null) {
                Text(
                    "${row.local.ratingText}没有无损的整数对应，不能直接上传；可明确选择采用 Bangumi 评分。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.local.type == null) {
                Text(
                    "本机状态「${row.local.status}」暂不自动映射。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!allowed.selected && !down.selected && row.blocked == null) {
                Text(
                    "支持的字段没有可合并差异。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (row.local.subjectId == null && row.local.anime?.deletedAt == null && row.local.game?.deletedAt == null) {
                HorizontalDivider()
                val linkMotionLevel = LocalMotionLevel.current
                AnimatedContent(
                    targetState = isLinking,
                    transitionSpec = { motionFadeThroughSpec(linkMotionLevel, 160, 100) },
                    label = "bangumiLinkEditor",
                ) { linking ->
                    if (!linking) {
                        TextButton(onClick = onStartLink, enabled = enabled) {
                            Text("确认 Bangumi 关联")
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "请输入作品链接 subject/ 后的数字。先读取作品名称，再由你确认，不按同名自动关联。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            MergeNumberField(linkNumber, "Bangumi 作品编号", onLinkNumberChange)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = onReadAssociation, enabled = enabled) { Text("读取作品资料") }
                                TextButton(onClick = onCancelLink, enabled = enabled) { Text("取消关联输入") }
                            }
                        }
                    }
                }
            }

            if (row.local.kind == BangumiUploadKind.BOOK && row.local.anime?.deletedAt == null) {
                HorizontalDivider()
                val bookMotionLevel = LocalMotionLevel.current
                AnimatedContent(
                    targetState = isEditingBook,
                    transitionSpec = { motionFadeThroughSpec(bookMotionLevel, 160, 100) },
                    label = "bangumiBookProgressEditor",
                ) { editingBook ->
                    if (!editingBook) {
                        TextButton(onClick = onStartEditBook, enabled = enabled) {
                            Text("编辑卷话进度")
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "卷数和话数各自记录，未知可留空，0 表示明确尚未读。旧通用阅读进度保留；保存仅修改本机，重新对比后再选择同步方向。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            MergeNumberField(bookVolumes, "已读卷数", onBookVolumesChange)
                            MergeNumberField(bookChapters, "已读话数", onBookChaptersChange)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = onSaveBookProgress, enabled = enabled) { Text("保存本机卷话进度") }
                                TextButton(onClick = onCancelEditBook, enabled = enabled) { Text("取消卷话编辑") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MergeFieldRow(
    comparison: String,
    uploadLabel: String?,
    uploadSelected: Boolean,
    uploadWarning: Boolean,
    onToggleUpload: () -> Unit,
    downloadLabel: String?,
    downloadSelected: Boolean,
    downloadWarning: Boolean,
    onToggleDownload: () -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(comparison, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (uploadLabel != null) {
                FilterChip(
                    selected = uploadSelected,
                    enabled = enabled,
                    onClick = onToggleUpload,
                    label = {
                        Text(
                            uploadLabel,
                            color = if (uploadWarning && !uploadSelected) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                )
            }
            if (downloadLabel != null) {
                FilterChip(
                    selected = downloadSelected,
                    enabled = enabled,
                    onClick = onToggleDownload,
                    label = {
                        Text(
                            downloadLabel,
                            color = if (downloadWarning && !downloadSelected) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun UploadCheck(label: String, selected: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
