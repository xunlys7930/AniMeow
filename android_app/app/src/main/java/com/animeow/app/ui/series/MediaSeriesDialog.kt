package com.animeow.app.ui.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import org.json.JSONArray
import org.json.JSONObject

private data class SeriesEditContext(val series: MediaSeriesEntity? = null,
    val members: List<MediaSeriesMember> = emptyList(), val works: List<MediaSeriesWork> = emptyList(), val membersVersion: String = "")

// Keep the original versions across rotation, so restoring a draft never bypasses stale-data checks.
private val seriesEditContextSaver = Saver<SeriesEditContext, String>(save = { context ->
    JSONObject().put("series", context.series?.let { JSONArray(listOf(it.id, it.syncId, it.kind, it.name, it.updatedAt)) })
        .put("membersVersion", context.membersVersion)
        .put("members", JSONArray(context.members.map { JSONArray(listOf(it.id, it.kind, it.workSyncId, it.seriesId, it.sortOrder, it.updatedAt)) }))
        .put("works", JSONArray(context.works.map { JSONArray(listOf(it.syncId, it.title, it.updatedAt)) })).toString()
}, restore = { text ->
    val json = JSONObject(text)
    val series = json.optJSONArray("series")?.let { MediaSeriesEntity(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
    val members = json.getJSONArray("members").let { array -> (0 until array.length()).map { index ->
        val row = array.getJSONArray(index)
        MediaSeriesMember(row.getLong(0), row.getString(1), row.getString(2), if (row.isNull(3)) null else row.getLong(3), row.getInt(4), row.getString(5))
    } }
    val works = json.getJSONArray("works").let { array -> (0 until array.length()).map { index ->
        val row = array.getJSONArray(index); MediaSeriesWork(row.getString(0), row.getString(1), row.getString(2))
    } }
    SeriesEditContext(series, members, works, json.getString("membersVersion"))
})

@Composable
internal fun MediaSeriesDialog(kind: MediaSeriesKind, repository: MediaSeriesRepository,
    works: List<MediaSeriesWork>, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val flow = remember(repository) { repository.snapshot.catch { error = "系列暂时无法读取，请关闭后重试" } }
    val snapshot by flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editContext by rememberSaveable(stateSaver = seriesEditContextSaver) { mutableStateOf(SeriesEditContext()) }
    val original = editContext.series
    val originalMembers = editContext.members
    val originalWorks = editContext.works
    var name by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var sourcePicker by remember { mutableStateOf(false) }
    fun edit(series: MediaSeriesEntity?) {
        val current = snapshot ?: return
        val previousMembers = if (series == null) emptyList() else current.members.filter { it.seriesId == series.id }
        editContext = SeriesEditContext(series, previousMembers, works.filter { work -> previousMembers.any { it.workSyncId == work.syncId } },
            mediaSeriesMembersVersion(current.members))
        name = series?.name.orEmpty()
        order = if (series == null) emptyList() else current.members.filter { it.seriesId == series.id }.map { it.workSyncId }
        query = ""; error = null; editing = true
    }
    fun close() {
        if (busy) return
        val initialOrder = original?.let { series -> originalMembers.filter { it.seriesId == series.id }.map { it.workSyncId } }.orEmpty()
        if (editing && (name != original?.name.orEmpty() || order != initialOrder)) discard = true
        else if (editing) { editing = false; error = null } else onDismiss()
    }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action(); editing = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: IllegalArgumentException) { error = invalid.message ?: "请检查系列资料" }
            catch (changed: IllegalStateException) { error = changed.message ?: "资料已变化，请重新打开系列" }
            catch (_: Exception) { error = "保存未完成，草稿已保留，请稍后重试" }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        BackHandler { close() }
        Surface(Modifier.fillMaxSize()) {
            MediaSeriesContent(kind, snapshot, works, editing, name,
                order, query, busy, error, original != null, ::close, ::edit, { name = it }, { query = it },
                { next ->
                    val added = works.filter { it.syncId in next && originalWorks.none { old -> old.syncId == it.syncId } }
                    editContext = editContext.copy(works = originalWorks + added); order = next
                }, { runAction { repository.save(original, editContext.membersVersion, originalWorks, name, order) } },
                { delete = true }, onSource = { sourcePicker = true })
        }
    }
    if (sourcePicker) SourceSeriesPicker(works, { sourcePicker = false }) { sourceName, ids ->
        edit(null)
        name = sourceName; order = ids
        editContext = editContext.copy(works = works.filter { it.syncId in ids })
        sourcePicker = false
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃系列修改？") },
        text = { Text("尚未保存的名称、归属和顺序将不会生效。") },
        confirmButton = { TextButton(onClick = { discard = false; editing = false; error = null }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if (delete) AlertDialog(onDismissRequest = { if (!busy) delete = false }, title = { Text("删除这个系列？") },
        text = { Text("成员将移出系列，作品收藏和个人记录会保留。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            delete = false; original?.let { series -> runAction { repository.delete(series, editContext.membersVersion) } }
        }) { Text("删除系列") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaSeriesContent(kind: MediaSeriesKind, snapshot: MediaSeriesSnapshot?, works: List<MediaSeriesWork>,
    editing: Boolean, name: String, order: List<String>, query: String, busy: Boolean, error: String?, existing: Boolean,
    onBack: () -> Unit, onEdit: (MediaSeriesEntity?) -> Unit, onName: (String) -> Unit, onQuery: (String) -> Unit,
    onOrder: (List<String>) -> Unit, onSave: () -> Unit, onDelete: () -> Unit, onSource: () -> Unit = {}) {
    val bySync = works.associateBy { it.syncId }
    val visibleOrder = order.filter { it in bySync }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !busy, onClick = onBack) { Text(if (editing) "返回系列列表" else "关闭") }
            if (editing) Button(enabled = !busy && name.trim().isNotEmpty() && name.trim().length <= 100, onClick = onSave) {
                Text(if (busy) "保存中…" else "保存系列")
            } else TextButton(enabled = snapshot != null, onClick = { onEdit(null) }) { Text("新建系列") }
        }
        Text(if (editing) "编辑${kind.label}系列" else "${kind.label}系列", style = MaterialTheme.typography.headlineSmall)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        if (snapshot == null) { if (error == null) CircularProgressIndicator(Modifier.padding(24.dp)); return }
        if (!editing) OutlinedButton(onClick = onSource, enabled = !busy && works.any { it.source != null && it.externalId != null }) { Text("查找来源系列") }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!editing) {
                if (snapshot.series.isEmpty()) item { Text("还没有系列，可新建系列并选择成员。不会根据相似标题自动归组。") }
                items(snapshot.series, key = { "series:${it.id}" }) { series ->
                    val members = snapshot.members.filter { it.seriesId == series.id }.mapNotNull { bySync[it.workSyncId] }
                    ElevatedCard(onClick = { onEdit(series) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(series.name, style = MaterialTheme.typography.titleMedium)
                            Text("${members.size} 部${kind.label}", style = MaterialTheme.typography.labelMedium)
                            if (members.isEmpty()) Text("暂无收藏中的成员") else members.take(3).forEachIndexed { index, work ->
                                Text("${index + 1}. ${work.title}", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            if (members.size > 3) Text("另有 ${members.size - 3} 部，点按查看全部成员")
                        }
                    }
                }
            } else {
                item {
                    OutlinedTextField(name, onName, label = { Text("系列名称") }, enabled = !busy,
                        supportingText = { Text("最多 100 字；仅用于手动整理") }, modifier = Modifier.fillMaxWidth())
                    Text("保存后将把所选作品移入此系列；移出成员会保留作品和个人记录。", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp))
                }
                item { Text("成员顺序 · ${visibleOrder.size} 部", style = MaterialTheme.typography.titleMedium) }
                if (visibleOrder.isEmpty()) item { Text("尚未选择成员，可在下方添加作品。") }
                items(visibleOrder, key = { "member:$it" }) { sync ->
                    val index = visibleOrder.indexOf(sync)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${index + 1}. ${bySync.getValue(sync).title}", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fun move(offset: Int) {
                                    val target = visibleOrder[index + offset]
                                    val next = order.toMutableList()
                                    val sourceIndex = next.indexOf(sync); val targetIndex = next.indexOf(target)
                                    next[sourceIndex] = target; next[targetIndex] = sync; onOrder(next)
                                }
                                TextButton(enabled = !busy && index > 0, onClick = { move(-1) },
                                    modifier = Modifier.semantics { contentDescription = "上移${bySync.getValue(sync).title}" }) { Text("上移") }
                                TextButton(enabled = !busy && index < visibleOrder.lastIndex, onClick = { move(1) },
                                    modifier = Modifier.semantics { contentDescription = "下移${bySync.getValue(sync).title}" }) { Text("下移") }
                                TextButton(enabled = !busy, onClick = { onOrder(order - sync) },
                                    modifier = Modifier.semantics { contentDescription = "将${bySync.getValue(sync).title}移出系列" }) { Text("移出系列") }
                            }
                        }
                    }
                }
                val hidden = order.size - visibleOrder.size
                if (hidden > 0) item { Text("另有 $hidden 项已移出收藏，系列关系保留，恢复收藏后可见。", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedTextField(query, onQuery, enabled = !busy, label = { Text("查找要加入的${kind.label}") }, modifier = Modifier.fillMaxWidth()) }
                val available = works.filter { it.syncId !in order && it.title.contains(query.trim(), true) }
                if (available.isEmpty()) item { Text("没有可加入的作品，可先在${kind.label}库添加收藏。") }
                items(available, key = { "available:${it.syncId}" }) { work ->
                    val currentSeries = snapshot.members.firstOrNull { it.workSyncId == work.syncId }?.seriesId
                        ?.let { id -> snapshot.series.firstOrNull { it.id == id } }
                    OutlinedCard(onClick = { if (!busy) onOrder(order + work.syncId) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(work.title, style = MaterialTheme.typography.titleSmall)
                            Text(currentSeries?.let { "当前：${it.name} · 点按移入此系列" } ?: "未归组 · 点按加入此系列", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (existing) item { TextButton(enabled = !busy, onClick = onDelete) { Text("删除系列，保留成员作品") } }
            }
        }
    }
}
