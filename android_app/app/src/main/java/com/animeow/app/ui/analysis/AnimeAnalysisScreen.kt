package com.animeow.app.ui.analysis

import com.animeow.app.ui.icons.AppIcons

import android.content.ClipData

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.local.AnimeAnalysisRecordEntity
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeAnalysisScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnimeAnalysisViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showPrompt by rememberSaveable { mutableStateOf(false) }
    var showCustomModel by rememberSaveable { mutableStateOf(false) }
    var expandedRecordId by rememberSaveable { mutableLongStateOf(-1L) }
    var knownNewestRecordId by rememberSaveable { mutableLongStateOf(state.records.firstOrNull()?.id ?: -1L) }
    var deleteRecord by remember { mutableStateOf<AnimeAnalysisRecordEntity?>(null) }

    LaunchedEffect(state.records.firstOrNull()?.id) {
        val newestId = state.records.firstOrNull()?.id ?: return@LaunchedEffect
        if (knownNewestRecordId == -1L) {
            knownNewestRecordId = newestId
            if (expandedRecordId == -1L) expandedRecordId = newestId
        } else if (newestId != knownNewestRecordId) {
            knownNewestRecordId = newestId
            expandedRecordId = newestId
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("AI 看番风格") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(AppIcons.Refresh, contentDescription = "刷新摘要")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IntroCard() }
            item { StatsPreviewCard(state) }
            item {
                ServerAnalysisCard(
                    state = state,
                    generatedToday = viewModel.generatedServerToday(),
                    onGenerate = viewModel::generateServerAnalysis,
                )
            }
            item {
                LocalModelCard(
                    enabled = state.preview.totalEntries > 0,
                    onCopyPrompt = { showPrompt = true },
                    onCustomModel = { showCustomModel = true },
                )
            }
            if (state.busy) {
                item(key = "analysis-busy-card") {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text("正在整理你的看番画像…", Modifier.padding(start = 12.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            item {
                Text("本地分析历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (state.records.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        Text("还没有分析记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(state.records, key = { it.id }) { record ->
                    AnalysisRecordCard(
                        record = record,
                        expanded = expandedRecordId == record.id,
                        onToggle = { expandedRecordId = if (expandedRecordId == record.id) -1L else record.id },
                        onCopy = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("分析结果", record.analysis.orEmpty())),
                                )
                            }
                        },
                        onDelete = { deleteRecord = record },
                    )
                }
            }
        }
    }

    if (showPrompt) {
        val prompt = viewModel.currentPrompt().orEmpty()
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            title = { Text("可复制的分析提示词") },
            text = {
                SelectionContainer {
                    Text(
                        prompt,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("分析提示词", prompt)),
                        )
                    }
                    showPrompt = false
                }) {
                    Icon(AppIcons.Copy, contentDescription = null)
                    Text("复制", Modifier.padding(start = 5.dp))
                }
            },
            dismissButton = { TextButton(onClick = { showPrompt = false }) { Text("关闭") } },
        )
    }

    if (showCustomModel) {
        CustomModelSheet(
            initial = state.settings,
            busy = state.busy,
            onSave = viewModel::saveCustomSettings,
            onRun = viewModel::generateCustomAnalysis,
            onReset = viewModel::resetCustomSettings,
            onLoadKey = viewModel::loadCustomKey,
            onForgetKey = viewModel::forgetCustomKey,
            onFetchModels = viewModel::fetchCustomModels,
            onTestConnection = viewModel::testCustomConnection,
            onDismiss = { showCustomModel = false },
        )
    }

    deleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteRecord = null },
            title = { Text("删除分析记录？") },
            text = { Text("只会删除本机历史，不会撤回已经发生的模型调用。") },
            confirmButton = {
                Button(onClick = {
                    deleteRecord = null
                    viewModel.deleteRecord(record.id)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteRecord = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun IntroCard() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(AppIcons.Sparkles, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("一份不冒犯隐私的看番画像", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("只发送汇总统计，不包含评价正文、封面地址、API Key 或完整作品清单。")
            }
        }
    }
}

@Composable
private fun StatsPreviewCard(state: AnimeAnalysisUiState) {
    val preview = state.preview
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("本次摘要", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniMetric("作品", preview.totalEntries.toString(), Modifier.weight(1f))
                MiniMetric("看完", preview.completedEntries.toString(), Modifier.weight(1f))
                MiniMetric("集数", preview.watchedEpisodes.toString(), Modifier.weight(1f))
            }
            preview.averageRating?.let { Text("平均评分 %.1f / 10".format(it)) }
            if (preview.topTags.isNotEmpty()) {
                Text(
                    "偏好标签：${preview.topTags.joinToString(" · ") { "${it.first} ${it.second}" }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ServerAnalysisCard(
    state: AnimeAnalysisUiState,
    generatedToday: Boolean,
    onGenerate: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AniMeow 云端模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val description = when {
                !state.configured -> "当前版本暂未开启云端分析服务。"
                state.session == null -> "登录云账号后即可使用，每个账号每天可生成 1 次。"
                generatedToday -> "今天已生成过云端报告，明天可以再次生成。"
                else -> "当前账号：${state.session.username} · 每天可生成 1 次，仅上传上方统计摘要。"
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = onGenerate,
                enabled = !state.busy && state.configured && state.session != null && !generatedToday && state.preview.totalEntries > 0,
            ) {
                Icon(AppIcons.Sparkles, contentDescription = null)
                Text("生成今日报告", Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LocalModelCard(enabled: Boolean, onCopyPrompt: () -> Unit, onCustomModel: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("本地提示词与自定义模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("端点、模型和提示词可以保存；API Key 只存在于本次输入框，不进入设置、日志或备份。")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCopyPrompt, enabled = enabled) { Text("复制提示词") }
                FilledTonalButton(onClick = onCustomModel, enabled = enabled) {
                    Icon(AppIcons.Hub, contentDescription = null)
                    Text("自定义模型", Modifier.padding(start = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun AnalysisRecordCard(
    record: AnimeAnalysisRecordEntity,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().motionAnimateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                    Text(record.createdAt.orEmpty().replace('T', ' ').take(19), fontWeight = FontWeight.SemiBold)
                    Text(record.model.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onCopy) { Icon(AppIcons.Copy, contentDescription = "复制分析") }
                IconButton(onClick = onDelete) { Icon(AppIcons.Delete, contentDescription = "删除记录") }
            }
            if (!expanded) {
                Text(
                    record.analysis.orEmpty().replace('\n', ' '),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = motionFadeIn(),
                exit = motionFadeOut(),
            ) {
                SelectionContainer { MarkdownAnalysisContent(record.analysis.orEmpty()) }
            }
            record.serverRecordId?.let {
                Text("服务端记录 #$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun MarkdownAnalysisContent(markdown: String) {
    val lines = remember(markdown) { markdown.lines() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            when {
                trimmed == "---" || trimmed == "***" -> {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                }
                trimmed.startsWith("### ") -> {
                    Text(
                        inlineMarkdownText(trimmed.removePrefix("### ").trim()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        inlineMarkdownText(trimmed.removePrefix("## ").trim()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        inlineMarkdownText(trimmed.removePrefix("# ").trim()),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val bulletBody = trimmed.drop(2).trim()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("·", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(inlineMarkdownText(bulletBody), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    Text(inlineMarkdownText(trimmed), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun inlineMarkdownText(text: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val startBold = text.indexOf("**", cursor)
            if (startBold < 0) {
                append(text.substring(cursor))
                break
            }
            val endBold = text.indexOf("**", startBold + 2)
            if (endBold < 0) {
                append(text.substring(cursor))
                break
            }
            if (startBold > cursor) append(text.substring(cursor, startBold))
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold))
            append(text.substring(startBold + 2, endBold))
            pop()
            cursor = endBold + 2
        }
    }
