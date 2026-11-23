package com.animeow.app.ui.series

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.animeow.app.data.MediaSeriesWork
import com.animeow.app.data.remote.*
import kotlinx.coroutines.CancellationException

internal fun matchingSourceSeriesWorks(works: List<MediaSeriesWork>, candidates: List<SourceSeriesWork>): List<MediaSeriesWork> =
    works.filter { local -> candidates.any { it.source == local.source && it.id == local.externalId } }

@Composable
internal fun SourceSeriesPicker(works: List<MediaSeriesWork>, onDismiss: () -> Unit,
    lookup: suspend (String, String) -> SourceSeriesPage = { source, id -> WorkSeriesService().lookup(source, id) },
    onUse: (String, List<String>) -> Unit) {
    val uri = LocalUriHandler.current
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var name by rememberSaveable { mutableStateOf("") }
    var page by remember { mutableStateOf<SourceSeriesPage?>(null) }
    var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val work = works.firstOrNull { it.syncId == chosen }
    LaunchedEffect(chosen, retry) {
        if (work == null) return@LaunchedEffect
        loading = true; error = null; page = null; selected = emptyList()
        try {
            page = lookup(requireNotNull(work.source), requireNotNull(work.externalId))
            name = page?.groups?.firstOrNull()?.name?.takeUnless { it == "来源关联作品" } ?: work.title.take(100)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "来源系列暂时无法读取，可重试或返回手动整理。" }
        finally { loading = false }
    }
    val group = page?.groups?.firstOrNull()
    val candidates = group?.works.orEmpty()
    val matched = matchingSourceSeriesWorks(works, candidates)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Text("查找来源系列", style = MaterialTheme.typography.titleLarge)
            Text("只读取来源明确的关系；选择已有收藏，确认后进入系列草稿，不会自动改收藏或进度。")
            TextButton(onClick = onDismiss) { Text("返回手动整理") }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (chosen == null) {
                    item { OutlinedTextField(query, { query = it }, label = { Text("选择要查询的收藏") }, modifier = Modifier.fillMaxWidth()) }
                    val available = works.filter { it.source != null && it.externalId != null && it.title.contains(query, true) }
                    if (available.isEmpty()) item { Text("没有带来源编号的收藏，请先补全作品来源。") }
                    items(available, key = { it.syncId }) { item -> OutlinedCard(onClick = { chosen = item.syncId }, modifier = Modifier.fillMaxWidth()) {
                        Text(item.title, Modifier.padding(16.dp))
                    } }
                } else {
                    item { Text(work?.title ?: "作品已变化，请返回重新选择"); TextButton(onClick = { chosen = null; page = null; error = null }) { Text("更换作品") } }
                    if (loading) item { CircularProgressIndicator() }
                    error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重试") } } }
                    if (page != null && group == null) item { Text("来源没有提供可核实的系列关系，可返回手动整理。") }
                    group?.let { current ->
                        item {
                            Text(current.name, style = MaterialTheme.typography.titleMedium)
                            if (page?.stale == true) Text("显示服务器保留的来源关系，暂时无法更新。")
                            if (page?.partial == true) Text("仅展示来源首批最多 40 项，并非完整系列；更多内容请查看来源。")
                            TextButton(onClick = { uri.openUri(current.url) }) { Text("查看来源关系") }
                            OutlinedTextField(name, { name = it.take(100) }, label = { Text("系列名称") }, modifier = Modifier.fillMaxWidth())
                        }
                        val choices = (listOfNotNull(work) + matched).distinctBy { it.syncId }
                        items(choices, key = { "saved:${it.syncId}" }) { item ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(item.syncId in selected, { selected = if (it) selected + item.syncId else selected - item.syncId },
                                    Modifier.semantics { contentDescription = "选择${item.title}" })
                                Text(item.title, Modifier.weight(1f).padding(top = 12.dp))
                            }
                        }
                        val missing = candidates.filter { candidate -> matched.none { it.source == candidate.source && it.externalId == candidate.id } }
                        if (missing.isNotEmpty()) item { Text("以下来源作品尚未收藏，可先在作品库添加，再回来归组。") }
                        items(missing, key = { "source:${it.source}:${it.id}" }) { item ->
                            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(item.title); Text(item.relation, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { uri.openUri(item.url) }) { Text("查看来源作品") }
                            } }
                        }
                    }
                }
            }
            Button(onClick = { onUse(name.trim(), selected) }, enabled = !loading && group != null && selected.isNotEmpty() && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("使用所选收藏创建系列草稿（${selected.size}）")
            }
        } }
    }
}
