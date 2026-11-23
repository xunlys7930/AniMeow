package com.animeow.app.ui.games

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.animeow.app.data.games.gameTagNames

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GameTagPicker(
    available: List<String>, initial: String = "", busy: Boolean = false, batch: Boolean = false, embedded: Boolean = false,
    onSave: (String, Boolean) -> Unit, onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(gameTagNames(initial)) }
    var created by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var remove by rememberSaveable { mutableStateOf(false) }
    val options = (available + created + selected).distinct()
    val lengthValid = selected.joinToString("，").length <= 1024
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (batch) "批量管理游戏标签" else "选择游戏标签", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (batch) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!remove, { remove = false }, label = { Text("添加标签") }, enabled = !busy)
                FilterChip(remove, { remove = true }, label = { Text("移除标签") }, enabled = !busy)
            }
            OutlinedTextField(query, { query = it.take(100) }, label = { Text("搜索或新建标签") }, enabled = !busy, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.filter { it.contains(query.trim(), true) }.forEach { name ->
                    FilterChip(name in selected, { selected = if (name in selected) selected - name else selected + name },
                        label = { Text(name) }, enabled = !busy)
                }
            }
            val name = query.trim()
            if (!remove && name.isNotBlank() && name !in options && gameTagNames(name).size == 1) TextButton(enabled = !busy, onClick = {
                created = created + name; selected = selected + name; query = ""
            }) { Text("新建并选择“$name”") }
            if (options.isEmpty() && query.isBlank()) Text("输入名称新建标签，确认后才会应用到游戏。")
            if (!lengthValid) Text("所选标签过长，请减少后再保存", color = MaterialTheme.colorScheme.error)
            Text(if (batch) "只修改所选标签，其他标签和个人记录保持不变。" else "标签与游戏记录一起保存，取消不会修改游戏。", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(enabled = !busy && lengthValid && (!batch || selected.isNotEmpty()), onClick = { onSave(selected.joinToString("，"), remove) }, modifier = Modifier.weight(1f)) { Text(if (busy) "正在保存…" else "确认") }
        }
        }
    }
    if (embedded) content() else androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { content() }
    }
}

@Composable
internal fun GameTagManager(available: List<String>, busy: Boolean, onChange: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    if (editing == null && deleting == null) AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("管理游戏标签") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("在游戏编辑或批量操作中选择、新建标签；这里可统一改名或移除。", style = MaterialTheme.typography.bodySmall)
            if (available.isEmpty()) Text("还没有游戏标签")
            available.forEach { tag -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !busy, onClick = { editing = tag; name = tag }, modifier = Modifier.weight(1f)) { Text(tag) }
                TextButton(enabled = !busy, onClick = { deleting = tag }) { Text("移除") }
            } }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("完成") } })
    editing?.let { old -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("修改标签名称") },
        text = { OutlinedTextField(name, { name = it.take(100) }, label = { Text("标签名称") }) },
        confirmButton = { TextButton(enabled = !busy && name.trim().isNotBlank() && gameTagNames(name).size == 1,
            onClick = { onChange(old, name.trim()); editing = null }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    deleting?.let { tag -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除标签“$tag”？") },
        text = { Text("将从所有游戏中移除此标签，游戏和个人记录保留。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { onChange(tag, null); deleting = null }) { Text("移除标签") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
