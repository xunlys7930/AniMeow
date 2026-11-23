package com.animeow.app.ui.character

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.CharacterRelationItem
import com.animeow.app.data.preferences.BUILT_IN_RELATION_PRESETS
import com.animeow.app.data.preferences.CharacterRelationPreferences
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RelationEditorDialog(
    sourceCharacterId: Long,
    sourceName: String,
    existingRelations: List<CharacterRelationItem>,
    initialRelation: CharacterRelationItem?,
    candidates: List<CharacterListItem>,
    onConfirm: (Long, String, String, String?, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf(initialRelation?.relatedCharacterId) }
    var choosing by rememberSaveable { mutableStateOf(initialRelation == null) }
    var sourceRole by rememberSaveable { mutableStateOf(initialRelation?.relation?.roleOf(sourceCharacterId).orEmpty()) }
    var targetRole by rememberSaveable { mutableStateOf(initialRelation?.let { it.relation.roleOf(it.relatedCharacterId) }.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(initialRelation?.relation?.note.orEmpty()) }
    var strength by rememberSaveable { mutableFloatStateOf(initialRelation?.relation?.strength?.toFloat() ?: 3f) }
    val target = candidates.firstOrNull { it.character.id == selectedId }
    val targetName = target?.character?.displayName ?: initialRelation?.displayName ?: "目标角色"
    val filtered = remember(candidates, query, sourceCharacterId) {
        candidates.filter { it.character.id != sourceCharacterId &&
            (query.isBlank() || it.character.displayName.contains(query.trim(), true) || it.character.name.contains(query.trim(), true)) }
    }
    val context = LocalContext.current
    val preferences = remember(context) { CharacterRelationPreferences(context) }
    val customPresets by preferences.presets.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var presetSaving by remember { mutableStateOf(false) }
    var managingPresets by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (choosing && selectedId != null) choosing = false else onDismiss() }
        Surface(Modifier.padding(horizontal = 12.dp, vertical = 24.dp).widthIn(max = 640.dp).fillMaxSize(),
            shape = MaterialTheme.shapes.extraLarge) {
            Scaffold(modifier = Modifier.imePadding(), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                TopAppBar(title = {
                    Column {
                        Text(if (initialRelation == null) "添加角色关系" else "编辑角色关系")
                        Text(if (choosing) "1 · 选择目标角色" else "2 · 填写双方关系",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }, navigationIcon = { IconButton(onClick = onDismiss) { AppIcon(AppIcons.Close, "取消") } })
            }, bottomBar = {
                if (!choosing) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(onClick = { selectedId?.let { onConfirm(it, sourceRole.trim(), targetRole.trim(), note, strength.toInt()) } },
                        enabled = selectedId != null && (sourceRole.isNotBlank() || targetRole.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()) { Text("保存关系") }
                }
            }) { padding ->
                if (choosing) Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索目标角色") }, singleLine = true,
                        leadingIcon = { AppIcon(AppIcons.Search, null) }, modifier = Modifier.fillMaxWidth())
                    Text("${filtered.size} 位候选角色 · 上下滑动浏览，点选后填写关系",
                        Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f).testTag("relation-candidates"), contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (filtered.isEmpty()) item { Text(if (query.isBlank()) "还没有其他本地角色，请先在角色中心添加。" else "没有匹配角色，试试其他姓名。") }
                        items(filtered, key = { it.character.id }) { item ->
                            val existing = existingRelations.firstOrNull { it.relatedCharacterId == item.character.id }
                            Card(Modifier.fillMaxWidth().testTag("relation-candidate-${item.character.id}").clickable {
                                if (selectedId != item.character.id) {
                                    selectedId = item.character.id
                                    sourceRole = existing?.relation?.roleOf(sourceCharacterId).orEmpty()
                                    targetRole = existing?.relation?.roleOf(item.character.id).orEmpty()
                                    note = existing?.relation?.note.orEmpty()
                                    strength = existing?.relation?.strength?.toFloat() ?: 3f
                                }
                                choosing = false
                            }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CharacterPortrait(item.character, Modifier.size(48.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.character.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(if (existing == null) "${item.workCount} 部作品" else "已有关联 · 点选修改",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    AppIcon(AppIcons.ChevronRight, "选择角色")
                                }
                            }
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("relation-form"),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(sourceName, style = MaterialTheme.typography.titleMedium)
                                Text("与 $targetName 的关系", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { choosing = true }) { Text("更换目标角色") }
                            }
                        }
                    }
                    item {
                        Text("双方称谓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("填写各自相对于对方的称谓，可只填一端。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(sourceRole, { sourceRole = it.take(120) }, label = { Text("$sourceName 是 $targetName 的") },
                            placeholder = { Text("例如：老师") }, modifier = Modifier.fillMaxWidth().testTag("relation-source-role"))
                        OutlinedTextField(targetRole, { targetRole = it.take(120) }, label = { Text("$targetName 是 $sourceName 的") },
                            placeholder = { Text("例如：学生") }, modifier = Modifier.fillMaxWidth().testTag("relation-target-role"))
                        TextButton(onClick = { val old = sourceRole; sourceRole = targetRole; targetRole = old }) { Text("交换两端称谓") }
                    }
                    item {
                        Text("称谓预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (BUILT_IN_RELATION_PRESETS + customPresets).forEach { preset ->
                                FilterChip(selected = sourceRole == preset.sourceRole && targetRole == preset.targetRole,
                                    onClick = { sourceRole = preset.sourceRole; targetRole = preset.targetRole }, label = { Text(preset.label) })
                            }
                        }
                        TextButton(enabled = !presetSaving && (sourceRole.isNotBlank() || targetRole.isNotBlank()), onClick = {
                            presetSaving = true
                            scope.launch {
                                try {
                                    preferences.add(sourceRole, targetRole)
                                    snackbar.showSnackbar("已保存称谓预设，下次添加关系也可使用")
                                } catch (error: CancellationException) { throw error }
                                catch (error: IllegalArgumentException) { snackbar.showSnackbar(error.message ?: "无法保存预设") }
                                catch (_: Exception) { snackbar.showSnackbar("预设保存失败，请重试") }
                                finally { presetSaving = false }
                            }
                        }) { Text("将当前称谓保存为预设") }
                        if (customPresets.isNotEmpty()) {
                            TextButton(onClick = { managingPresets = !managingPresets }) { Text(if (managingPresets) "收起预设管理" else "管理自定义预设") }
                            if (managingPresets) customPresets.forEach { preset ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(preset.label, Modifier.weight(1f))
                                    IconButton(onClick = { scope.launch {
                                        try { preferences.remove(preset) }
                                        catch (error: CancellationException) { throw error }
                                        catch (_: Exception) { snackbar.showSnackbar("预设移除失败，请重试") }
                                    } }) { AppIcon(AppIcons.Close, "移除预设 ${preset.label}") }
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(note, { note = it }, label = { Text("关系备注（可选）") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
                    item {
                        Text("关系强度：${strength.toInt()} / 5", fontWeight = FontWeight.SemiBold)
                        Slider(strength, { strength = it }, valueRange = 1f..5f, steps = 3)
                        Text("上下滑动可填写备注、调整强度和管理预设。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
