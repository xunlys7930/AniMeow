package com.animeow.app.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.animeow.app.data.analysis.AnimeAnalysisSettings
import com.animeow.app.data.analysis.customModelBaseUrl
import com.animeow.app.data.analysis.customModelProviderFor
import com.animeow.app.data.analysis.customModelProviders
import com.animeow.app.data.analysis.fixedCustomModelTemperature
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.util.userSafeErrorMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CustomModelSheet(
    initial: AnimeAnalysisSettings,
    busy: Boolean,
    onSave: (AnimeAnalysisSettings, String, Boolean) -> Unit,
    onRun: (AnimeAnalysisSettings, String, Boolean) -> Unit,
    onReset: () -> Unit,
    onLoadKey: (String) -> String,
    onForgetKey: (String) -> Unit,
    onFetchModels: suspend (String, String) -> List<String>,
    onTestConnection: suspend (AnimeAnalysisSettings, String) -> Long,
    onDismiss: () -> Unit,
) {
    var endpoint by rememberSaveable(initial.endpoint) { mutableStateOf(initial.endpoint) }
    var model by rememberSaveable(initial.model) { mutableStateOf(initial.model) }
    var systemPrompt by rememberSaveable(initial.systemPrompt) { mutableStateOf(initial.systemPrompt) }
    var promptTemplate by rememberSaveable(initial.promptTemplate) { mutableStateOf(initial.promptTemplate) }
    var temperature by rememberSaveable(initial.temperature) { mutableFloatStateOf(initial.temperature) }
    var apiKey by remember { mutableStateOf("") }
    var rememberKey by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var providerName by rememberSaveable(initial.endpoint) { mutableStateOf(customModelProviderFor(initial.endpoint).name) }
    var keyEndpoint by remember { mutableStateOf<String?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var models by remember(endpoint, apiKey) { mutableStateOf(emptyList<String>()) }
    var status by remember(endpoint, model, apiKey) { mutableStateOf<String?>(null) }
    var failed by remember(endpoint, model, apiKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val endpointResult = runCatching { customModelBaseUrl(endpoint) }
    val baseUrl = endpointResult.getOrNull()
    val enabled = !busy && pending == null
    val keyReady = keyEndpoint == baseUrl
    val currentKey = if (keyReady) apiKey else ""
    val fixedTemperature = fixedCustomModelTemperature(endpoint, model)
    val current = AnimeAnalysisSettings(endpoint, model, systemPrompt, promptTemplate, temperature)

    LaunchedEffect(baseUrl) {
        apiKey = ""
        rememberKey = false
        showKey = false
        keyEndpoint = null
        if (baseUrl != null) {
            runCatching { onLoadKey(baseUrl) }
                .onSuccess {
                    apiKey = it
                    rememberKey = it.isNotBlank()
                }
                .onFailure {
                    rememberKey = true
                    status = userSafeErrorMessage(it, "已记住的 Key 读取失败，请重新填写")
                    failed = true
                }
        }
        keyEndpoint = baseUrl
    }

    StableModalBottomSheet(
        onDismissRequest = { if (showModels) showModels = false else onDismiss() },
        sheetGesturesEnabled = false,
    ) {
        if (showModels) {
            var search by remember { mutableStateOf("") }
            val filtered = models.filter { it.contains(search, ignoreCase = true) }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("选择模型", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { showModels = false }) { Text("返回配置") }
                    }
                }
                item {
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
                        label = { Text("搜索模型") }, singleLine = true)
                }
                items(filtered, key = { it }) { id ->
                    TextButton(onClick = {
                        model = id
                        showModels = false
                    }, modifier = Modifier.fillMaxWidth()) { Text(id) }
                }
                if (filtered.isEmpty()) item { Text("没有匹配的模型") }
            }
            return@StableModalBottomSheet
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
            contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("自定义模型", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            item {
                Box {
                    OutlinedButton(onClick = { providerMenu = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("模型厂商 · ${providerName} ▾")
                    }
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        customModelProviders.forEach { provider ->
                            DropdownMenuItem(text = { Text(provider.name) }, onClick = {
                                providerMenu = false
                                providerName = provider.name
                                if (provider.endpoint.isNotEmpty() && provider.endpoint != baseUrl) {
                                    endpoint = provider.endpoint
                                    model = ""
                                }
                            })
                        }
                    }
                }
            }
            item {
                OutlinedTextField(endpoint, {
                    endpoint = it
                    providerName = customModelProviderFor(it).name
                }, Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text("服务地址") }, singleLine = true,
                    isError = endpoint.isNotBlank() && baseUrl == null,
                    supportingText = {
                        Text(if (endpoint.isNotBlank() && baseUrl == null) {
                            endpointResult.exceptionOrNull()?.message.orEmpty()
                        } else "OpenAI 兼容格式，自动补全 /chat/completions，可修改为中转地址。")
                    })
            }
            item {
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text("API Key") }, singleLine = true,
                    isError = rememberKey && apiKey.isBlank(),
                    supportingText = {
                        if (rememberKey && apiKey.isBlank()) Text("请填写需要记住的 Key")
                    },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("记住 Key")
                        Text("在本机加密保存，按服务地址分别记忆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        modifier = Modifier.semantics { contentDescription = "记住 API Key" },
                        checked = rememberKey,
                        enabled = enabled && keyReady && baseUrl != null,
                        onCheckedChange = { checked ->
                            runCatching { if (!checked) onForgetKey(endpoint) }
                                .onSuccess { rememberKey = checked }
                                .onFailure {
                                    status = "Key 记忆设置修改失败，请重试"
                                    failed = true
                                }
                        },
                    )
                }
            }
            item {
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text("模型 ID") }, singleLine = true,
                    supportingText = { Text("从服务端获取后选择，也可填写模型或推理接入点 ID。") })
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = enabled && keyReady && baseUrl != null, onClick = {
                        pending = "正在获取模型…"
                        status = null
                        scope.launch {
                            try {
                                runCatchingCancellable { onFetchModels(current.endpoint, currentKey) }
                                    .onSuccess {
                                        models = it
                                        showModels = true
                                        status = "已获取 ${it.size} 个模型"
                                        failed = false
                                    }
                                    .onFailure {
                                        status = userSafeErrorMessage(it, "获取失败，可手动填写模型 ID")
                                        failed = true
                                    }
                            } finally {
                                pending = null
                            }
                        }
                    }) { Text("获取模型") }
                    if (models.isNotEmpty()) {
                        TextButton(enabled = enabled, onClick = { showModels = true }) { Text("选择模型") }
                    }
                    OutlinedButton(enabled = enabled && keyReady && baseUrl != null && model.isNotBlank() && (!rememberKey || apiKey.isNotBlank()), onClick = {
                        pending = "正在测试连接…"
                        status = null
                        scope.launch {
                            try {
                                runCatchingCancellable { onTestConnection(current, currentKey) }
                                    .onSuccess {
                                        status = "连接成功 · ${it} ms"
                                        failed = false
                                    }
                                    .onFailure {
                                        status = userSafeErrorMessage(it, "连接失败，请检查网络与配置")
                                        failed = true
                                    }
                            } finally {
                                pending = null
                            }
                        }
                    }) { Text("测试连接") }
                }
                Text("测试会向所选模型发送一条简短请求，可能产生少量费用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                pending?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.heightIn(max = 24.dp))
                        Text(it)
                    }
                }
                status?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            }
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置 · 提示词与温度") } }
            if (advanced) {
                item { OutlinedTextField(systemPrompt, { systemPrompt = it }, Modifier.fillMaxWidth(), enabled = enabled, label = { Text("系统提示词") }, minLines = 2) }
                item {
                    OutlinedTextField(promptTemplate, { promptTemplate = it }, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("用户提示词模板") }, minLines = 6,
                        supportingText = { Text("用 {stats} 插入统计摘要；不写时会自动追加。") })
                }
                item {
                    if (fixedTemperature != null) Text("此模型的温度固定为 1.00")
                    else {
                        Text("温度 ${"%.2f".format(temperature)}")
                        androidx.compose.material3.Slider(temperature, { temperature = it }, enabled = enabled, valueRange = 0f..2f)
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        onReset()
                        apiKey = ""
                        rememberKey = false
                        onDismiss()
                    }, enabled = enabled) { Text("恢复默认") }
                    OutlinedButton(onClick = { onSave(current, currentKey, rememberKey) }, enabled = enabled && keyReady && baseUrl != null && model.isNotBlank() && (!rememberKey || apiKey.isNotBlank())) { Text("保存配置") }
                    Button(onClick = {
                        onRun(current, currentKey, rememberKey)
                        onDismiss()
                    }, enabled = enabled && keyReady && baseUrl != null && model.isNotBlank() && (!rememberKey || apiKey.isNotBlank())) { Text("开始分析") }
                }
            }
        }
    }

}
