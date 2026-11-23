package com.animeow.app.ui.community

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.animeow.app.data.community.SensitiveWord
import com.animeow.app.ui.components.PageNavigationBar
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import kotlinx.coroutines.launch

@Composable
internal fun CommunitySensitiveWordsDialog(
    words: List<SensitiveWord>,
    total: Int,
    currentPage: Int,
    search: String,
    loading: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onImport: (List<String>) -> Unit,
    onSearch: (String) -> Unit,
    onPageChange: (Int) -> Unit,
    busy: Boolean = false,
    error: String? = null,
    pageSize: Int = 50,
    onRefresh: () -> Unit = { onPageChange(currentPage) },
    onCancel: (() -> Unit)? = null,
) {
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SensitiveWord?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val pages = ((total.coerceAtLeast(0) + pageSize.coerceAtLeast(1) - 1) / pageSize.coerceAtLeast(1)).coerceAtLeast(1)
    LaunchedEffect(currentPage, search) { list.scrollToItem(0) }
    ManagementListDialog("敏感词管理", onDismiss, bottomBar = {
        Surface(shadowElevation = 3.dp) {
            PageNavigationBar(currentPage, pages, currentPage < pages, loading || busy, onPageChange,
                Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
                status = if (loading) "正在读取敏感词…" else "${if (search.isBlank()) "共" else "匹配"} $total 条 · 每页 $pageSize 条",
                onCancel = if (loading) onCancel else null)
        }
    }, actions = {
        IconButton(onClick = onRefresh, enabled = !loading && !busy) { Icon(AppIcons.Refresh, "刷新敏感词") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, onSearch, Modifier.fillMaxWidth().testTag("sensitive-search"),
                    enabled = !busy, singleLine = true, label = { Text("搜索敏感词") },
                    leadingIcon = { Icon(AppIcons.Search, null) },
                    trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { onSearch("") }) { Icon(AppIcons.Close, "清空搜索") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onRefresh() }))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { editor = "add"; draft = "" }, enabled = !loading && !busy) { Text("添加") }
                    OutlinedButton(onClick = { editor = "import"; draft = "" }, enabled = !loading && !busy) { Text("批量导入") }
                    TextButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("敏感词", words.joinToString("\n") { it.word })))
                            Toast.makeText(context, "已复制本页 ${words.size} 个敏感词", Toast.LENGTH_SHORT).show()
                        }
                    }, enabled = words.isNotEmpty() && !loading && !busy) { Text("复制本页") }
                }
                if (error != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRefresh, enabled = !loading && !busy) { Text("重试") }
                    }
                }
            }
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("sensitive-list"), state = list,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                if (words.isEmpty() && !loading && error == null) item {
                    Text(if (search.isBlank()) "还没有敏感词，可以添加或批量导入。" else "没有匹配的敏感词，试试其他关键词。",
                        Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(words, key = SensitiveWord::id) { word ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sensitive-word-${word.id}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(word.word, Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 2.dp))
                        IconButton(onClick = { deleting = word }, enabled = !loading && !busy) { Icon(AppIcons.Delete, "删除敏感词 ${word.word}") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                }
            }
        }
    }
    if (editor != null) {
        val importing = editor == "import"
        val parsed = remember(draft, importing) {
            if (importing) draft.split(Regex("[\\r\\n,，;；]")).map(String::trim).filter(String::isNotBlank).distinct()
            else listOf(draft.trim()).filter(String::isNotBlank)
        }
        val valid = parsed.isNotEmpty() && parsed.size <= 5000 && parsed.all { it.length <= 64 }
        AlertDialog(onDismissRequest = { editor = null }, title = { Text(if (importing) "批量导入敏感词" else "添加敏感词") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (importing) "每行一个词，也可用逗号或分号分隔。重复词会自动跳过。" else "添加后会用于检查社区内容。")
                OutlinedTextField(draft, { draft = it.take(if (importing) 325000 else 64) },
                    Modifier.fillMaxWidth().testTag("sensitive-editor"),
                    label = { Text(if (importing) "敏感词列表" else "敏感词") },
                    singleLine = !importing, minLines = if (importing) 4 else 1, maxLines = if (importing) 8 else 1,
                    isError = draft.isNotBlank() && !valid,
                    supportingText = { Text(if (importing) "${parsed.size} 个词 · 每次最多 5000 个，每个不超过 64 字" else "${draft.length}/64") })
            }
        }, confirmButton = {
            TextButton(onClick = { if (importing) onImport(parsed) else onAdd(parsed.single()); editor = null; draft = "" },
                enabled = valid && !loading && !busy) { Text(if (importing) "导入" else "添加") }
        }, dismissButton = { TextButton(onClick = { editor = null }) { Text("取消") } })
    }
    deleting?.let { word ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除敏感词？") },
            text = { Text("“${word.word}”将不再单独用于内容检查，已有内容不会被修改。") },
            confirmButton = { TextButton(onClick = { onDelete(word.id); deleting = null }, enabled = !loading && !busy) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
