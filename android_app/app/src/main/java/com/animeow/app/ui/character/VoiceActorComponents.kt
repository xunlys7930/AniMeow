package com.animeow.app.ui.character

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.animeow.app.data.local.CharacterWithRole
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.launch

/** A cast credit belongs to one character in one work, rather than to the global character. */
@Composable
internal fun VoiceActorBindingDialog(
    characterName: String,
    workTitle: String,
    initialValue: String?,
    onSave: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialValue.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initialValue.isNullOrBlank()) "绑定声优" else "编辑声优") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(characterName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("在《$workTitle》中的配音", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null }, singleLine = true,
                    enabled = !saving, label = { Text("声优姓名") },
                    supportingText = { Text("填写一位声优；留空保存可解除绑定。") },
                    modifier = Modifier.fillMaxWidth().testTag("voice-actor-name"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    runCatchingCancellable { onSave(name.trim()) }
                        .onSuccess { onDismiss() }
                        .onFailure { error = "声优保存失败，请重试" }
                    saving = false
                }
            }) { Text(if (saving) "保存中…" else "保存绑定") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkCastDialog(
    workTitle: String,
    characters: List<CharacterWithRole>,
    legacyVoiceActors: String?,
    onSaveVoiceActor: suspend (Long, String) -> Unit,
    onAddCharacter: () -> Unit,
    onCharacterOpen: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<CharacterWithRole?>(null) }
    var showLegacy by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("角色与声优", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(workTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${characters.size} 位角色 · ${characters.count { !it.voiceActor.isNullOrBlank() }} 位已绑定配音",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                LazyColumn(Modifier.weight(1f).testTag("work-cast-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (characters.isEmpty()) item {
                        Text("先添加本作角色，再为每位角色绑定声优。", Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(characters, key = { it.character.id }) { credit ->
                        val name = credit.character.nameCn?.takeIf(String::isNotBlank) ?: credit.character.name
                        OutlinedCard(Modifier.fillMaxWidth().testTag("cast-credit-${credit.character.id}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("角色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(name, fontWeight = FontWeight.SemiBold)
                                        credit.roleName?.takeIf(String::isNotBlank)?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    AppIcon(AppIcons.ChevronRight, null, Modifier.size(18.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("声优", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(credit.voiceActor?.takeIf(String::isNotBlank) ?: "未绑定", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { editing = credit }, modifier = Modifier.testTag("bind-actor-${credit.character.id}")) {
                                        Text(if (credit.voiceActor.isNullOrBlank()) "绑定声优" else "编辑声优")
                                    }
                                    TextButton(onClick = { onDismiss(); onCharacterOpen(credit.character.id) }) { Text("角色资料") }
                                }
                            }
                        }
                    }
                    legacyVoiceActors?.takeIf(String::isNotBlank)?.let { legacy -> item {
                        TextButton(onClick = { showLegacy = !showLegacy }) {
                            Text(if (showLegacy) "收起旧版未对应资料" else "核对旧版未对应的声优资料")
                        }
                        if (showLegacy) {
                            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(legacy)
                                Text("这份原始名单已保留，请在上方逐个绑定对应角色。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddCharacter) { AppIcon(AppIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加／管理角色") }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
    editing?.let { credit ->
        VoiceActorBindingDialog(
            credit.character.nameCn?.takeIf(String::isNotBlank) ?: credit.character.name,
            workTitle, credit.voiceActor,
            onSave = { onSaveVoiceActor(credit.character.id, it) }, onDismiss = { editing = null },
        )
    }
}
