package com.animeow.app.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.importer.SpreadsheetConflictStrategy
import com.animeow.app.data.importer.SpreadsheetField
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpreadsheetTransferScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpreadsheetTransferViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contentEnter = motionFadeIn()
    val contentExit = motionFadeOut()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::inspect)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> uri?.let(viewModel::export) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("表格导入与导出") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.TableView, contentDescription = null)
                            Text("轻量 Excel 搬家工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text("支持 .xlsx 与 .csv，自动匹配常见中文/英文字段；导出文件可直接在 Excel、WPS 或 LibreOffice 编辑。")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "application/octet-stream")) },
                                enabled = state !is SpreadsheetTransferUiState.Importing && state !is SpreadsheetTransferUiState.Exporting,
                            ) {
                                Icon(Icons.Outlined.FileOpen, contentDescription = null)
                                Text("选择表格", modifier = Modifier.padding(start = 6.dp))
                            }
                            FilledTonalButton(
                                onClick = { exportLauncher.launch("AniMeow_${java.time.LocalDate.now()}.xlsx") },
                                enabled = state !is SpreadsheetTransferUiState.Importing && state !is SpreadsheetTransferUiState.Exporting,
                            ) { Text("导出 .xlsx") }
                        }
                    }
                }
            }

            item {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { contentEnter togetherWith contentExit },
                    label = "spreadsheet-state",
                ) { current ->
                    when (current) {
                        SpreadsheetTransferUiState.Idle -> MessageCard("选择一份表格开始预检，或直接导出当前资料库。")
                        SpreadsheetTransferUiState.Inspecting -> BusyCard("正在解析工作表")
                        is SpreadsheetTransferUiState.Importing -> BusyCard("正在事务导入，失败会自动回滚")
                        SpreadsheetTransferUiState.Exporting -> BusyCard("正在生成轻量 .xlsx 文件")
                        is SpreadsheetTransferUiState.Error -> ResultCard("操作未完成", current.message, viewModel::reset)
                        is SpreadsheetTransferUiState.ImportSuccess -> ResultCard(
                            "导入完成",
                            "新增 ${current.summary.addedCount} · 合并 ${current.summary.mergedCount} · 覆盖 ${current.summary.replacedCount} · " +
                                "跳过 ${current.summary.skippedCount} · 异常行 ${current.summary.errorCount} · 新建标签 ${current.summary.createdTagCount}",
                            viewModel::reset,
                        )
                        is SpreadsheetTransferUiState.ExportSuccess -> ResultCard(
                            "导出完成",
                            "已写入 ${current.summary.animeCount} 部作品和 ${current.summary.tagLinkCount} 条标签关联。",
                            viewModel::reset,
                        )
                        is SpreadsheetTransferUiState.Ready -> MappingCard(current, viewModel)
                    }
                }
            }

            val ready = state as? SpreadsheetTransferUiState.Ready
            if (ready != null) {
                item {
                    Text("数据预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                ready.preview.rows.take(8).forEachIndexed { index, row ->
                    item(key = "preview-$index") {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    row.getOrNull(ready.mapping[SpreadsheetField.TITLE] ?: -1).orEmpty().ifBlank { "（标题为空）" },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    row.joinToString(" · ").take(180),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MappingCard(
    state: SpreadsheetTransferUiState.Ready,
    viewModel: SpreadsheetTransferViewModel,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.preview.sourceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${state.preview.dataRowCount} 行数据 · 请确认列映射", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SpreadsheetField.entries.forEach { field ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(field.displayName + if (field.required) " *" else "", style = MaterialTheme.typography.labelLarge)
                    ColumnSelector(
                        headers = state.preview.headers,
                        selected = state.mapping[field],
                        allowNone = !field.required,
                        onSelected = { viewModel.setMapping(field, it) },
                    )
                }
            }
            Text("同名作品处理", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SpreadsheetConflictStrategy.entries.forEach { strategy ->
                    FilterChip(
                        selected = state.strategy == strategy,
                        onClick = { viewModel.setStrategy(strategy) },
                        label = { Text(strategy.displayName) },
                    )
                }
            }
            Text(state.strategy.description, style = MaterialTheme.typography.bodySmall)
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = viewModel::importData,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.mapping.containsKey(SpreadsheetField.TITLE),
            ) { Text("开始导入") }
        }
    }
}

@Composable
private fun ColumnSelector(
    headers: List<String>,
    selected: Int?,
    allowNone: Boolean,
    onSelected: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }) {
            Text(selected?.let(headers::getOrNull) ?: "不导入", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text("不导入") }, onClick = { expanded = false; onSelected(null) })
            }
            headers.forEachIndexed { index, header ->
                DropdownMenuItem(text = { Text(header) }, onClick = { expanded = false; onSelected(index) })
            }
        }
    }
}

@Composable
private fun BusyCard(message: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator()
            Text(message)
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultCard(title: String, message: String, onReset: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onReset) { Text("完成") }
        }
    }
}
