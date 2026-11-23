package com.animeow.app.ui.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.matchesExternalReference
import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.remote.SeriesSourceWork
import com.animeow.app.ui.components.StableModalBottomSheet

internal data class SeriesSourceState(
    val items: List<SeriesSourceWork> = emptyList(), val selected: Set<String> = emptySet(),
    val loading: Boolean = false, val saving: Boolean = false, val loaded: Boolean = false,
    val error: String? = null, val message: String? = null, val origin: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesSourceSheet(
    state: SeriesSourceState, members: List<AnimeEntity>, library: List<AnimeEntity>,
    onSearch: (String) -> Unit, onRelated: (AnimeEntity) -> Unit,
    onToggle: (String) -> Unit, onSave: () -> Unit, onOpen: (Long) -> Unit, onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val locked = state.saving || state.loading
    val seeds = members.filter { it.sourceReferences().any { ref -> ref.source == "bangumi" && ref.externalId?.toLongOrNull() != null } }
    val selected = state.items.filter { it.id in state.selected }
    val existingCount = selected.count { item -> library.any { it.matchesExternalReference("bangumi", item.id) } }
    StableModalBottomSheet(onDismissRequest = { if (!state.saving) onDismiss() }, sheetGesturesEnabled = false) {
        BackHandler(state.saving) { }
        LazyColumn(Modifier.testTag("series-source-list").fillMaxWidth().navigationBarsPadding().heightIn(max = 640.dp),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("查找并加入系列", style = MaterialTheme.typography.headlineSmall) }
            item { Text("可加入不同名称的动画和书籍。其他类型可查看原站；来源关系仅供确认，不会自动归组。", style = MaterialTheme.typography.bodySmall) }
            item { OutlinedTextField(query, { query = it.take(200) }, enabled = !locked,
                label = { Text("作品名称或 Bangumi 编号") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { FilledTonalButton(onClick = { confirm = false; onSearch(query) }, enabled = !locked && query.isNotBlank()) { Text("搜索动画与书籍") } }
            if (seeds.isNotEmpty()) {
                item { Text("或选择本系列作品，查找其来源关联", style = MaterialTheme.typography.titleSmall) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seeds, key = AnimeEntity::id) { member ->
                        AssistChip(onClick = { confirm = false; onRelated(member) }, enabled = !locked, label = { Text(member.title) })
                    }
                } }
            }
            if (state.loading) item { CircularProgressIndicator() }
            if (state.loaded) item {
                Text(state.origin + "\n仅展示本次查询的结果；来源可能未收录全部作品。关系相对于所选作品，加入后可自行标记系列关系。", style = MaterialTheme.typography.bodySmall)
            }
            if (state.items.any { it.cacheStale }) item { Text("来源暂时无法更新，当前展示服务器保留的资料。", color = MaterialTheme.colorScheme.primary) }
            if (state.loaded && state.items.isEmpty()) item { Text("暂未找到结果，可换个名称搜索，或从已有收藏中添加。") }
            items(state.items, key = { it.id }) { item ->
                val existing = library.firstOrNull { it.matchesExternalReference("bangumi", item.id) }
                val inSeries = existing?.seriesId != null && members.any { it.id == existing.id }
                val elsewhere = existing?.seriesId != null && !inSeries
                ListItem(
                    headlineContent = { Text(item.title) },
                    supportingContent = { Text(listOfNotNull(item.typeLabel, item.relation.takeIf(String::isNotBlank),
                        when { inSeries -> "已在本系列"; elsewhere -> "已在其他系列，请从已有收藏中确认移入"; existing != null -> "已收藏"; item.subjectType == null -> "此类型请查看原站"; else -> "未收藏" }).joinToString(" · ")) },
                    leadingContent = { if (item.subjectType != null && !inSeries && !elsewhere) Checkbox(item.id in state.selected,
                        { onToggle(item.id) }, enabled = !locked, modifier = Modifier.semantics { contentDescription = "选择${item.title}" }) },
                    trailingContent = {
                        TextButton(onClick = { if (existing != null) onOpen(existing.id) else uri.openUri(item.url) }, enabled = !state.saving) {
                            Text(if (existing != null) "查看作品" else "原站")
                        }
                    },
                )
            }
            state.message?.let { item { Text(it) } }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, enabled = !state.saving) { Text("关闭") }
                Button(onClick = { confirm = true }, enabled = !locked && selected.isNotEmpty()) { Text(if (state.saving) "正在保存…" else "加入所选 ${selected.size} 部") }
            } }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("加入当前系列？") },
        text = { Text("将归入 $existingCount 部已有收藏，并新增收藏 ${selected.size - existingCount} 部作品。已有进度、评分、感想和标签保留；不会自动标记原作或改编。逐项保存，失败项保留以便重试。") },
        confirmButton = { TextButton(onClick = { confirm = false; onSave() }) { Text("确认加入") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
