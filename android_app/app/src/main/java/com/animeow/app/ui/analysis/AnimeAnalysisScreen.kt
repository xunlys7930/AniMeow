package com.animeow.app.ui.analysis

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.analysis.AnimeAnalysisSettings
import com.animeow.app.data.local.AnimeAnalysisRecordEntity
import com.animeow.app.ui.components.StableModalBottomSheet
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
    var deleteRecord by remember { mutableStateOf<AnimeAnalysisRecordEntity?>(null) }

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
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新摘要")
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
            if (state.busy) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("正在整理你的看番画像…", Modifier.padding(start = 12.dp))
                    }
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
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
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
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                !state.configured -> "当前构建未配置云端服务。"
                state.session == null -> "登录云账号后可使用；每个账号每天一次。"
                generatedToday -> "今天已生成过云端报告，明天可以再次使用。"
                else -> "已登录 ${state.session.username}；服务器仅接收上方汇总摘要。"
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = onGenerate,
                enabled = !state.busy && state.configured && state.session != null && !generatedToday && state.preview.totalEntries > 0,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
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
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("本地提示词与自定义模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("端点、模型和提示词可以保存；API Key 只存在于本次输入框，不进入设置、日志或备份。")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCopyPrompt, enabled = enabled) { Text("复制提示词") }
                FilledTonalButton(onClick = onCustomModel, enabled = enabled) {
                    Icon(Icons.Outlined.Hub, contentDescription = null)
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
                Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                    Text(record.createdAt.orEmpty().replace('T', ' ').take(19), fontWeight = FontWeight.SemiBold)
                    Text(record.model.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制分析") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除记录") }
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
                SelectionContainer { Text(record.analysis.orEmpty()) }
            }
            record.serverRecordId?.let {
                Text("服务端记录 #$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomModelSheet(
    initial: AnimeAnalysisSettings,
    busy: Boolean,
    onSave: (AnimeAnalysisSettings) -> Unit,
    onRun: (AnimeAnalysisSettings, String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var endpoint by rememberSaveable(initial.endpoint) { mutableStateOf(initial.endpoint) }
    var model by rememberSaveable(initial.model) { mutableStateOf(initial.model) }
    var systemPrompt by rememberSaveable(initial.systemPrompt) { mutableStateOf(initial.systemPrompt) }
    var promptTemplate by rememberSaveable(initial.promptTemplate) { mutableStateOf(initial.promptTemplate) }
    var temperature by rememberSaveable(initial.temperature) { mutableStateOf(initial.temperature) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    val current = AnimeAnalysisSettings(endpoint, model, systemPrompt, promptTemplate, temperature)

    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("自定义 OpenAI 兼容模型", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("支持以 /chat/completions 结尾的接口。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true) }
            item { OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名") }, singleLine = true) }
            item {
                OutlinedTextField(
                    apiKey,
                    { apiKey = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("API Key（不保存）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
            item { OutlinedTextField(systemPrompt, { systemPrompt = it }, Modifier.fillMaxWidth(), label = { Text("系统提示词") }, minLines = 2) }
            item {
                OutlinedTextField(
                    promptTemplate,
                    { promptTemplate = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("用户提示词模板") },
                    supportingText = { Text("用 {stats} 插入统计摘要；不写时会自动追加。") },
                    minLines = 6,
                )
            }
            item {
                Text("温度：${"%.2f".format(temperature)}")
                Slider(temperature, { temperature = it }, valueRange = 0f..2f)
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onReset, enabled = !busy) { Text("恢复默认") }
                    OutlinedButton(onClick = { onSave(current) }, enabled = !busy && endpoint.isNotBlank() && model.isNotBlank()) {
                        Text("保存配置")
                    }
                    Button(
                        onClick = {
                            onRun(current, apiKey)
                            apiKey = ""
                            onDismiss()
                        },
                        enabled = !busy && endpoint.isNotBlank() && model.isNotBlank(),
                    ) { Text("开始分析") }
                }
            }
        }
    }
}
