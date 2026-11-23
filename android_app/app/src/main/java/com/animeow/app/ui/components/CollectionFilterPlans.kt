package com.animeow.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import com.animeow.app.data.preferences.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

private val activeCollectionPlans = MutableStateFlow<Map<String, String>>(emptyMap())

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CollectionFilterPlans(
    module: String,
    current: CollectionFilterConditions,
    availableTags: List<String>,
    statuses: List<String>,
    enabled: Boolean = true,
    requiredSubjectType: String? = null,
    onApply: (CollectionFilterConditions) -> String?,
) {
    val context = LocalContext.current
    val preferences = remember(context) { CollectionFilterPreferences(context) }
    var plans by remember { mutableStateOf<List<CollectionFilterPlan>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf(false) }
    val activeIds by activeCollectionPlans.collectAsState()
    val sessionKey = module + ":" + requiredSubjectType.orEmpty()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(preferences, refresh) {
        try { preferences.plans.collect { plans = it; error = null } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "筛选方案暂时无法读取，请重试" }
    }
    val selected = plans?.firstOrNull { it.id == activeIds[sessionKey] }
    fun save(plan: CollectionFilterPlan) { scope.launch {
        busy = true
        try { preferences.save(plan); activeCollectionPlans.update { it + (sessionKey to plan.id) }; naming = false; error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IllegalArgumentException) { error = failure.message ?: "请检查方案名称和条件" }
        catch (_: Exception) { error = "方案未能保存，请重试；当前条件已保留" }
        finally { busy = false }
    } }
    Column {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text(selected?.let { "筛选方案：${it.name}${if (it.conditions != current.normalized()) "（已修改）" else ""}" } ?: "收藏筛选方案")
        }
        if (!enabled) Text("当前操作结束后可切换方案", style = MaterialTheme.typography.bodySmall)
    }
    if (open && !naming && deleting == null) AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("收藏筛选方案") },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("保存当前查找和筛选条件，之后一次应用。", style = MaterialTheme.typography.bodySmall) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text("重新读取") } } }
            if (plans == null && error == null) item { CircularProgressIndicator() }
            if (plans?.none { it.module == module && (requiredSubjectType == null || it.conditions.subjectType == requiredSubjectType) } == true) item { Text("还没有保存的方案") }
            items(plans.orEmpty().filter { it.module == module && (requiredSubjectType == null || it.conditions.subjectType == requiredSubjectType) }, key = { it.id }) { plan ->
                val missing = plan.conditions.missingConditions(availableTags, statuses)
                var menuOpen by remember(plan.id) { mutableStateOf(false) }
                var expanded by rememberSaveable(plan.id) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plan.name, style = MaterialTheme.typography.titleSmall)
                    if (missing.isEmpty()) Text(
                        collectionFilterSummary(plan.conditions, module).let { if (expanded) it else it.replace("\n", " · ") },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    ) else Text("缺少${missing.joinToString("、")}，请调整当前条件后更新此方案",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "应用方案${plan.name}" }, enabled = enabled && !busy && missing.isEmpty(), onClick = {
                            val failure = onApply(plan.conditions)
                            if (failure == null) {
                                activeCollectionPlans.update { it + (sessionKey to plan.id) }; open = false
                            } else error = failure
                        }) { Text("应用") }
                        Box {
                            TextButton(modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "管理方案${plan.name}" },
                                enabled = !busy, onClick = { menuOpen = true }) { Text("更多") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (missing.isEmpty()) DropdownMenuItem(
                                    text = { Text(if (expanded) "收起完整条件" else "查看完整条件") },
                                    onClick = { menuOpen = false; expanded = !expanded },
                                )
                                DropdownMenuItem(text = { Text("改名") },
                                    modifier = Modifier.semantics { contentDescription = "修改方案${plan.name}的名称" }, enabled = !busy,
                                    onClick = { menuOpen = false; editingId = plan.id; name = plan.name; naming = true })
                                DropdownMenuItem(text = { Text("用当前条件更新") },
                                    modifier = Modifier.semantics { contentDescription = "用当前条件更新方案${plan.name}" }, enabled = enabled && !busy,
                                    onClick = { menuOpen = false; save(plan.copy(conditions = current.normalized())) })
                                DropdownMenuItem(text = { Text("删除") },
                                    modifier = Modifier.semantics { contentDescription = "删除方案${plan.name}" }, enabled = !busy,
                                    onClick = { menuOpen = false; deleting = plan.id })
                            }
                        }
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = plans != null && enabled && !busy, onClick = { editingId = null; name = ""; naming = true }) { Text("保存当前条件") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("关闭") } })
    if (naming) AlertDialog(onDismissRequest = { if (!busy) naming = false }, title = { Text(if (editingId == null) "保存当前条件" else "修改方案名称") },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        text = { Column {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("方案名称") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = {
            val previous = plans?.firstOrNull { it.id == editingId }
            save(CollectionFilterPlan(previous?.id ?: UUID.randomUUID().toString(), name.trim(), module, previous?.conditions ?: current.normalized()))
        }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = { naming = false }) { Text("取消") } })
    if (deleting != null) AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除筛选方案？") },
        text = { Text("作品和个人记录会保留。") }, confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { preferences.delete(deleting!!); deleting = null; error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "方案未能删除，请重试" }
            finally { busy = false }
        } }) { Text("删除") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("保留") } })
}

internal fun collectionFilterSummary(value: CollectionFilterConditions, module: String): String = buildList {
    if (module == "library") add("类型：" + when (value.subjectType) { "anime" -> "动画"; "book" -> "书籍"; else -> "全部作品" })
    add("状态：${value.status} · ${value.query.ifBlank { "不限关键词" }}")
    if (value.tags.isNotEmpty()) add("标签：${value.tags.joinToString("、")}" + if (module == "library") (if (value.matchAllTags) "（同时包含）" else "（包含任一）") else "")
    if (value.years.isNotEmpty()) add("年份：${value.years.joinToString("、")}")
    if (module == "library") add("排序：${com.animeow.app.ui.tracker.LibrarySort.fromStorage(value.sort).displayName} · ${if (value.ascending) "升序" else "降序"}")
}.joinToString("\n")
