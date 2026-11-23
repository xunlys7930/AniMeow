package com.animeow.app.ui.reader

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.reader.BookReaderRepository
import com.animeow.app.data.reader.LegadoLanConfig
import com.animeow.app.data.reader.LegadoSyncApplySummary
import com.animeow.app.data.reader.LegadoWebDavConfig
import com.animeow.app.data.reader.SecureLegadoSyncStore
import com.animeow.app.data.reader.legado.LegadoBookItem
import com.animeow.app.data.reader.legado.LegadoLanClient
import com.animeow.app.data.reader.legado.LegadoMatchKind
import com.animeow.app.data.reader.legado.LegadoSyncPreviewItem
import com.animeow.app.data.reader.legado.LegadoWebDavClient
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.util.userSafeMessage
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LegadoSyncUiState(
    val loading: Boolean = false,
    val applying: Boolean = false,
    val sourceLabel: String? = null,
    val errorMessage: String? = null,
    val statusBanner: String? = null,
    val lastSyncedAt: String? = null,
    val autoMarkCompleted: Boolean = false,
    val searchQuery: String = "",
    val webDavUrl: String = "https://dav.jianguoyun.com/dav/",
    val webDavPath: String = "/legado/",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val lanHost: String = "",
    val lanPortText: String = "1122",
    val rawBooks: List<LegadoBookItem> = emptyList(),
    val previewItems: List<LegadoSyncPreviewItem> = emptyList(),
    val lastApplySummary: LegadoSyncApplySummary? = null,
)

class LegadoSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BookReaderRepository(application)
    private val secureStore = SecureLegadoSyncStore(application)

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<LegadoSyncUiState> = _state.asStateFlow()

    private fun loadInitialState(): LegadoSyncUiState {
        val webDav = runCatching { secureStore.loadWebDav() }.getOrDefault(LegadoWebDavConfig())
        val lan = runCatching { secureStore.loadLan() }.getOrDefault(LegadoLanConfig())
        return LegadoSyncUiState(
            lastSyncedAt = secureStore.loadLastSyncedAt(),
            webDavUrl = webDav.serverUrl.ifBlank { "https://dav.jianguoyun.com/dav/" },
            webDavPath = webDav.remotePath.ifBlank { "/legado/" },
            webDavUsername = webDav.username,
            webDavPassword = webDav.password,
            lanHost = lan.host,
            lanPortText = lan.port.toString(),
        )
    }

    fun updateWebDav(url: String? = null, path: String? = null, user: String? = null, pass: String? = null) {
        _state.update {
            it.copy(
                webDavUrl = url ?: it.webDavUrl,
                webDavPath = path ?: it.webDavPath,
                webDavUsername = user ?: it.webDavUsername,
                webDavPassword = pass ?: it.webDavPassword,
            )
        }
    }

    fun clearSavedWebDavCredentials() {
        runCatching { secureStore.clearWebDav() }
        _state.update {
            it.copy(
                webDavPassword = "",
                statusBanner = "已清除本机加密保存的 WebDAV 密码",
            )
        }
    }

    fun updateLan(host: String? = null, portText: String? = null) {
        _state.update {
            it.copy(
                lanHost = host ?: it.lanHost,
                lanPortText = portText ?: it.lanPortText,
            )
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setAutoMarkCompleted(enabled: Boolean) {
        _state.update { it.copy(autoMarkCompleted = enabled) }
        val raw = _state.value.rawBooks
        if (raw.isNotEmpty()) {
            viewModelScope.launch {
                val rebuilt = repository.buildLegadoPreview(raw, enabled)
                _state.update { it.copy(previewItems = rebuilt) }
            }
        }
    }

    fun loadFromLocalFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null, statusBanner = null, lastApplySummary = null) }
            runCatching {
                val preview = repository.buildLegadoPreviewFromUri(uri, _state.value.autoMarkCompleted)
                preview
            }.onSuccess { preview ->
                _state.update {
                    it.copy(
                        loading = false,
                        sourceLabel = "本地备份文件",
                        rawBooks = preview.map(LegadoSyncPreviewItem::item),
                        previewItems = preview,
                        errorMessage = if (preview.isEmpty()) "所选备份中暂无可导入的小说条目" else null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        errorMessage = error.userSafeMessage("读取《开源阅读》备份文件失败，请确认选择了 backup*.zip 或 bookshelf.json"),
                    )
                }
            }
        }
    }

    fun loadFromWebDav() {
        val current = _state.value
        val config = LegadoWebDavConfig(
            serverUrl = current.webDavUrl.trim(),
            remotePath = current.webDavPath.trim().ifBlank { "/legado/" },
            username = current.webDavUsername.trim(),
            password = current.webDavPassword,
        )
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null, statusBanner = null, lastApplySummary = null) }
            runCatching {
                val fetched = LegadoWebDavClient.fetchBookshelf(config)
                runCatching { secureStore.saveWebDav(config) }
                val preview = repository.buildLegadoPreview(fetched.books, current.autoMarkCompleted)
                fetched.sourceFileName to preview
            }.onSuccess { (fileName, preview) ->
                _state.update {
                    it.copy(
                        loading = false,
                        sourceLabel = "WebDAV（$fileName）",
                        rawBooks = preview.map(LegadoSyncPreviewItem::item),
                        previewItems = preview,
                        errorMessage = if (preview.isEmpty()) "远端备份中暂无可导入的小说条目" else null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        errorMessage = error.userSafeMessage("从 WebDAV 拉取书架失败"),
                    )
                }
            }
        }
    }

    fun loadFromLan() {
        val current = _state.value
        val port = current.lanPortText.trim().toIntOrNull() ?: 1122
        val config = LegadoLanConfig(host = current.lanHost.trim(), port = port)
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null, statusBanner = null, lastApplySummary = null) }
            runCatching {
                val books = LegadoLanClient.fetchBookshelf(config)
                runCatching { secureStore.saveLan(config) }
                val preview = repository.buildLegadoPreview(books, current.autoMarkCompleted)
                preview
            }.onSuccess { preview ->
                _state.update {
                    it.copy(
                        loading = false,
                        sourceLabel = "局域网 Web 服务（${config.host}:$port）",
                        rawBooks = preview.map(LegadoSyncPreviewItem::item),
                        previewItems = preview,
                        errorMessage = if (preview.isEmpty()) "《开源阅读》书架当前为空" else null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        errorMessage = error.userSafeMessage("连接《开源阅读》局域网服务失败"),
                    )
                }
            }
        }
    }

    fun toggleItemSelected(bookUrl: String) {
        _state.update { current ->
            current.copy(
                previewItems = current.previewItems.map { item ->
                    if (item.item.bookUrl == bookUrl) item.copy(selected = !item.selected) else item
                },
            )
        }
    }

    fun setSelectAll(selected: Boolean) {
        _state.update { current ->
            current.copy(previewItems = current.previewItems.map { it.copy(selected = selected) })
        }
    }

    fun toggleSyncProgressToMain(bookUrl: String, enabled: Boolean) {
        _state.update { current ->
            current.copy(
                previewItems = current.previewItems.map { item ->
                    if (item.item.bookUrl == bookUrl) item.copy(syncProgressToMain = enabled) else item
                },
            )
        }
    }

    fun overrideMatchedBook(bookUrl: String, targetAnimeId: Long?, targetTitle: String?) {
        _state.update { current ->
            current.copy(
                previewItems = current.previewItems.map { entry ->
                    if (entry.item.bookUrl != bookUrl) return@map entry
                    if (targetAnimeId == null) {
                        entry.copy(
                            matchKind = LegadoMatchKind.CREATE_NEW,
                            matchedAnimeId = null,
                            matchedAnimeTitle = null,
                            unitMismatchProtected = false,
                            syncProgressToMain = true,
                        )
                    } else {
                        entry.copy(
                            matchKind = LegadoMatchKind.TITLE_MATCH,
                            matchedAnimeId = targetAnimeId,
                            matchedAnimeTitle = targetTitle,
                        )
                    }
                },
            )
        }
    }

    fun applySelectedSync() {
        val items = _state.value.previewItems
        if (items.none { it.selected }) return
        viewModelScope.launch {
            _state.update { it.copy(applying = true, errorMessage = null) }
            runCatching {
                repository.applyLegadoSync(items)
            }.onSuccess { summary ->
                val nowIso = Instant.now().toString()
                secureStore.saveLastSyncedAt(nowIso)
                _state.update {
                    it.copy(
                        applying = false,
                        lastSyncedAt = nowIso,
                        lastApplySummary = summary,
                        previewItems = emptyList(),
                        rawBooks = emptyList(),
                        statusBanner = buildString {
                            append("同步完成：新增 ${summary.addedCount} 本，更新 ${summary.updatedCount} 本")
                            if (summary.unitProtectedCount > 0) {
                                append("（其中 ${summary.unitProtectedCount} 本已应用卷/章隔离保护）")
                            }
                        },
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        applying = false,
                        errorMessage = error.userSafeMessage("写入《开源阅读》同步数据失败，请重试"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LegadoSyncScreen(
    onBack: () -> Unit,
    viewModel: LegadoSyncViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sourceTab by rememberSaveable { mutableIntStateOf(0) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadFromLocalFile(uri)
    }

    val filteredPreview = remember(state.previewItems, state.searchQuery) {
        val q = state.searchQuery.trim()
        if (q.isEmpty()) state.previewItems else state.previewItems.filter {
            it.item.name.contains(q, ignoreCase = true) ||
                it.item.author.contains(q, ignoreCase = true) ||
                it.item.originName.contains(q, ignoreCase = true)
        }
    }
    val selectedCount = remember(state.previewItems) { state.previewItems.count { it.selected } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("《开源阅读》书架同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            if (state.previewItems.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("已勾选 $selectedCount / ${state.previewItems.size} 本小说", fontWeight = FontWeight.SemiBold)
                            Text(
                                "仅同步到书籍收藏，不会改动任何动画条目",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = viewModel::applySelectedSync,
                            enabled = selectedCount > 0 && !state.applying,
                        ) {
                            if (state.applying) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("确认同步")
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "同步你在《开源阅读》（Legado 3.0）中的书架与章节进度，免去手动双端登记。追番喵不内置任何第三方书源，仅读取你导出的备份或个人云盘。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.lastSyncedAt?.let { ts ->
                            Text(
                                "上次同步：${ts.take(19).replace('T', ' ')}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            state.statusBanner?.let { banner ->
                item(key = "status_banner") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(AppIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(banner, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            state.errorMessage?.let { err ->
                item(key = "error_banner") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(AppIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item(key = "source_tabs") {
                Card {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TabRow(selectedTabIndex = sourceTab) {
                            Tab(selected = sourceTab == 0, onClick = { sourceTab = 0 }, text = { Text("备份文件") })
                            Tab(selected = sourceTab == 1, onClick = { sourceTab = 1 }, text = { Text("WebDAV") })
                            Tab(selected = sourceTab == 2, onClick = { sourceTab = 2 }, text = { Text("局域网") })
                        }
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            when (sourceTab) {
                                0 -> {
                                    Text(
                                        "方式一：在《开源阅读 → 我的 → 备份与恢复》导出备份后，直接选择生成的 backupYYYY-MM-DD.zip 或 bookshelf.json：",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Button(
                                        onClick = { filePicker.launch(arrayOf("application/zip", "application/json", "*/*")) },
                                        enabled = !state.loading,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(AppIcons.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (state.loading) "正在解析书架…" else "选择 backup.zip 或 bookshelf.json")
                                    }
                                }
                                1 -> {
                                    Text(
                                        "方式二：连接坚果云等 HTTPS WebDAV 网盘，自动定位最新备份包并提取书架（应用密码在本机加密存储，不随备份外传）：",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = state.webDavUrl,
                                        onValueChange = { viewModel.updateWebDav(url = it) },
                                        label = { Text("WebDAV 服务器地址（HTTPS）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = state.webDavPath,
                                        onValueChange = { viewModel.updateWebDav(path = it) },
                                        label = { Text("备份所在远端目录（如 /legado/）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = state.webDavUsername,
                                        onValueChange = { viewModel.updateWebDav(user = it) },
                                        label = { Text("账号 / 邮箱") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = state.webDavPassword,
                                        onValueChange = { viewModel.updateWebDav(pass = it) },
                                        label = { Text("WebDAV 应用密码") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = viewModel::loadFromWebDav,
                                            enabled = !state.loading,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(if (state.loading) "正在连接 WebDAV…" else "拉取最新书架预览")
                                        }
                                        if (state.webDavPassword.isNotEmpty()) {
                                            OutlinedButton(onClick = viewModel::clearSavedWebDavCredentials) {
                                                Text("清除密码")
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    Text(
                                        "方式三：在同一 Wi-Fi 下开启《开源阅读 → 我的 → Web 服务》，填写显示的局域网地址即可拉取（若受安装包明文网络策略限制，请改用前两种方式）：",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(
                                            value = state.lanHost,
                                            onValueChange = { viewModel.updateLan(host = it) },
                                            label = { Text("局域网 IP（如 192.168.1.10）") },
                                            singleLine = true,
                                            modifier = Modifier.weight(2f),
                                        )
                                        OutlinedTextField(
                                            value = state.lanPortText,
                                            onValueChange = { viewModel.updateLan(portText = it) },
                                            label = { Text("端口") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Button(
                                        onClick = viewModel::loadFromLan,
                                        enabled = !state.loading,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(if (state.loading) "正在连接局域网服务…" else "从局域网拉取书架")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.previewItems.isNotEmpty()) {
                item(key = "preview_toolbar") {
                    val newCount = state.previewItems.count { it.matchedAnimeId == null }
                    val updateCount = state.previewItems.size - newCount
                    val protectedCount = state.previewItems.count { it.unitMismatchProtected }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "书架预检结果 · ${state.sourceLabel.orEmpty()}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(selected = true, onClick = {}, label = { Text("新增 $newCount 本") })
                            FilterChip(selected = true, onClick = {}, label = { Text("关联已有 $updateCount 本") })
                            if (protectedCount > 0) {
                                FilterChip(selected = true, onClick = {}, label = { Text("卷/章保护 $protectedCount 本") })
                            }
                        }
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::setSearchQuery,
                            label = { Text("按书名、作者或来源筛选") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { viewModel.setSelectAll(true) }) { Text("全选") }
                                TextButton(onClick = { viewModel.setSelectAll(false) }) { Text("全不选") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("读至末章标为看完", style = MaterialTheme.typography.labelMedium)
                                Switch(
                                    checked = state.autoMarkCompleted,
                                    onCheckedChange = viewModel::setAutoMarkCompleted,
                                )
                            }
                        }
                    }
                }

                items(filteredPreview, key = { it.item.bookUrl }) { entry ->
                    LegadoPreviewItemCard(
                        entry = entry,
                        onToggleSelected = { viewModel.toggleItemSelected(entry.item.bookUrl) },
                        onToggleSyncMain = { viewModel.toggleSyncProgressToMain(entry.item.bookUrl, it) },
                        onOverrideMatch = { id, title -> viewModel.overrideMatchedBook(entry.item.bookUrl, id, title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegadoPreviewItemCard(
    entry: LegadoSyncPreviewItem,
    onToggleSelected: () -> Unit,
    onToggleSyncMain: (Boolean) -> Unit,
    onOverrideMatch: (Long?, String?) -> Unit,
) {
    val book = entry.item
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.selected) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = entry.selected, onCheckedChange = { onToggleSelected() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(book.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        book.author.takeIf(String::isNotBlank),
                        book.originName.takeIf(String::isNotBlank),
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = entry.targetStatus,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            val progressDesc = if (book.isUnread) {
                "尚未开始阅读（将登记为「想看」）"
            } else {
                val totalPart = if (book.totalChapterNum > 0) " / ${book.totalChapterNum} 章" else " 章"
                val titlePart = book.durChapterTitle?.let { " · $it" }.orEmpty()
                "已读至第 ${book.readChapters}$totalPart$titlePart"
            }
            Text(progressDesc, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val matchText = if (entry.matchedAnimeId != null) {
                    "关联本地书籍：《${entry.matchedAnimeTitle}》${entry.localWatchedSummary?.let { "（$it）" }.orEmpty()}"
                } else {
                    "新建书籍收藏"
                }
                Text(
                    matchText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (entry.candidateBookIds.isNotEmpty() || entry.matchedAnimeId != null) {
                    androidx.compose.foundation.layout.Box {
                        AssistChip(
                            onClick = { menuExpanded = true },
                            label = { Text("调整关联") },
                        )
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("作为新书单独创建") },
                                onClick = {
                                    menuExpanded = false
                                    onOverrideMatch(null, null)
                                },
                            )
                            entry.candidateBookIds.forEach { (candId, candLabel) ->
                                DropdownMenuItem(
                                    text = { Text("关联：$candLabel") },
                                    onClick = {
                                        menuExpanded = false
                                        onOverrideMatch(candId, candLabel.substringBefore("（").substringBefore(" · "))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (entry.matchedAnimeId != null && (entry.unitMismatchProtected || !entry.syncProgressToMain)) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (!entry.syncProgressToMain) {
                                "卷/章隔离保护：保留主表卷数进度，仅在详情页单独展示章节"
                            } else {
                                "已改为将章节数同步至主进度条"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = entry.syncProgressToMain,
                            onCheckedChange = onToggleSyncMain,
                        )
                    }
                }
            }
        }
    }
}
