package com.animeow.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.animeow.app.data.BatchTagDraft
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.normalizeTagKey
import com.animeow.app.data.local.simplifyTagName

@Composable
internal fun BatchTagSheet(
    tags: List<TagEntity>,
    selectedCount: Int,
    busy: Boolean,
    errorMessage: String?,
    initialRemove: Boolean = false,
    onApply: (Set<Long>, Boolean, List<BatchTagDraft>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var remove by remember { mutableStateOf(initialRemove) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var drafts by remember { mutableStateOf(emptyList<BatchTagDraft>()) }
    var showNewTag by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableLongStateOf(0xFF8A4FD0) }
    var draftError by remember { mutableStateOf<String?>(null) }
    val count = selected.size + if (remove) 0 else drafts.size
    val list = rememberLazyListState()
    LaunchedEffect(showNewTag) { if (showNewTag) list.scrollToItem(0) }

    StableModalBottomSheet(onDismissRequest = { if (!busy) onDismissRequest() }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("批量标签", style = MaterialTheme.typography.headlineSmall)
            Text("已选 $selectedCount 部作品 · $count 个标签", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !remove, onClick = { remove = false }, enabled = !busy, label = { Text("添加") })
                FilterChip(selected = remove, onClick = { remove = true }, enabled = !busy, label = { Text("移除") })
            }
            Text(if (remove) "只移除勾选标签，保留其他标签。" else "为所有所选作品添加勾选标签，保留已有标签。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!remove && !showNewTag) {
                TextButton(onClick = { showNewTag = true }, enabled = !busy) { Text("新建标签并选中") }
            }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("batch-tag-list"), state = list,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!remove && showNewTag) {
                    item(key = "new-tag-form") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(newName, { newName = it; draftError = null }, label = { Text("标签名称") },
                                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            CustomizableColorSelector(newColor, listOf(0xFF8A4FD0, 0xFF3482FF, 0xFFE85D75, 0xFF18A999),
                                onColorChanged = { newColor = it })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    val clean = simplifyTagName(newName)
                                    val key = normalizeTagKey(clean)
                                    if (key.isBlank()) {
                                        draftError = "标签名称不能为空或只有标点"
                                    } else {
                                        draftError = null
                                        val existing = tags.firstOrNull { normalizeTagKey(it.name) == key }
                                        if (existing != null) selected = selected + existing.id
                                        else if (drafts.none { normalizeTagKey(it.name) == key }) drafts = drafts + BatchTagDraft(clean, newColor)
                                        newName = ""
                                        showNewTag = false
                                    }
                                }, enabled = !busy && newName.isNotBlank()) { Text("加入本次选择") }
                                TextButton(onClick = { showNewTag = false; newName = ""; draftError = null }, enabled = !busy) { Text("取消新建") }
                            }
                        }
                    }
                }
                if (tags.isEmpty() && (remove || drafts.isEmpty())) {
                    item { Text(if (remove) "还没有可移除的标签" else "还没有标签，可以先新建标签", modifier = Modifier.padding(vertical = 16.dp)) }
                }
                items(tags, key = { "tag-${it.id}" }) { tag ->
                    Row(Modifier.fillMaxWidth().testTag("batch-tag-${tag.id}").toggleable(
                        value = tag.id in selected, enabled = !busy, role = Role.Checkbox,
                        onValueChange = { checked -> selected = if (checked) selected + tag.id else selected - tag.id },
                    ).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = tag.id in selected, onCheckedChange = null, enabled = !busy)
                        Text(tag.name, modifier = Modifier.weight(1f))
                    }
                }
                if (!remove) {
                    items(drafts, key = { "draft-${normalizeTagKey(it.name)}" }) { draft ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${draft.name}（新建）", modifier = Modifier.weight(1f))
                            TextButton(onClick = { drafts = drafts - draft }, enabled = !busy) { Text("取消新建") }
                        }
                    }
                }
            }
            (draftError ?: errorMessage)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { draftError = null; onApply(selected.toSet(), remove, if (remove) emptyList() else drafts.toList()) },
                enabled = !busy && selectedCount > 0 && count > 0 && !(showNewTag && !remove),
                modifier = Modifier.fillMaxWidth().testTag("batch-tag-apply"),
            ) { Text(if (busy) "处理中…" else "${if (remove) "移除" else "添加"} $count 个标签") }
            TextButton(onClick = onDismissRequest, enabled = !busy, modifier = Modifier.align(Alignment.End)) { Text("取消") }
        }
    }

}
