package com.animeow.app.ui.diagnostics

import com.animeow.app.ui.icons.AppIcons

import android.content.ClipData

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.local.DiagnosticLogEntity
import com.animeow.app.ui.components.motionAnimateContentSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(viewModel::export)
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("诊断与操作轨迹") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(AppIcons.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("可选操作日志", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("默认关闭；只保存在本机，不进入本地或云端资料备份")
                            }
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = viewModel::setEnabled,
                                enabled = !state.busy,
                            )
                        }
                        Text("日志最多保留 500 条和 14 天，密码、Token、API Key 与授权头会自动脱敏。崩溃记录会在下次启动恢复。")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("诊断日志", state.copyText)),
                                        )
                                    }
                                },
                                enabled = state.logs.isNotEmpty() && !state.busy,
                            ) {
                                Icon(AppIcons.Copy, contentDescription = null)
                                Text("复制", modifier = Modifier.padding(start = 5.dp))
                            }
                            FilledTonalButton(
                                onClick = { exportLauncher.launch("AniMeow_diagnostics_${java.time.LocalDate.now()}.txt") },
                                enabled = !state.busy,
                            ) {
                                Icon(AppIcons.Download, contentDescription = null)
                                Text("导出", modifier = Modifier.padding(start = 5.dp))
                            }
                            FilledTonalButton(
                                onClick = viewModel::clear,
                                enabled = state.logs.isNotEmpty() && !state.busy,
                            ) {
                                Icon(AppIcons.DeleteSweep, contentDescription = null)
                                Text("清空", modifier = Modifier.padding(start = 5.dp))
                            }
                        }
                    }
                }
            }
            if (state.busy) item { CircularProgressIndicator(modifier = Modifier.padding(20.dp)) }
            if (!state.busy && state.logs.isEmpty()) item { Text("暂无诊断记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.logs, key = { it.id }) { log -> LogCard(log) }
        }
    }
}

@Composable
private fun LogCard(log: DiagnosticLogEntity) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.motionAnimateContentSize()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "${log.level.uppercase(java.util.Locale.ROOT)} · ${log.timestamp.replace('T', ' ').take(19)}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(log.message, fontWeight = FontWeight.SemiBold)
            if (expanded) {
                Text(log.stackTrace.orEmpty().ifBlank { "无附加信息" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
