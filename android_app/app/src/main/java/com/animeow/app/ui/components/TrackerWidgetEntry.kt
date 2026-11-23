package com.animeow.app.ui.components

import com.animeow.app.ui.icons.AppIcon as Icon

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.data.widget.TrackerWidget

/**
 * 桌面小组件入口卡片（用于非设置页面的独立展示）。
 *
 * 设置页请直接使用 PreferenceRow 以保持样式统一，
 * 点击后调用 [TrackerWidgetSetupDialog] 即可。
 */
@Composable
internal fun TrackerWidgetEntry(modifier: Modifier = Modifier) {
    var showSetup by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.clickable(role = Role.Button, onClickLabel = "添加桌面小组件", onClick = { showSetup = true }).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AppIcons.Widgets, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("桌面小组件", style = MaterialTheme.typography.titleSmall)
                Text("继续记录 · 在桌面记录 +1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.ChevronRight, "查看添加方法")
        }
    }
    if (showSetup) TrackerWidgetSetupDialog { showSetup = false }
}

/**
 * 桌面小组件添加引导对话框。
 *
 * 提供两种添加方式：
 * 1. 快捷添加（调用系统 requestPinAppWidget）—— 受桌面启动器支持情况影响
 * 2. 手动添加（长按桌面空白处 → 小组件 → AniMeow）—— 所有支持小组件的桌面均可用
 */
@Composable
internal fun TrackerWidgetSetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pinResult by remember { mutableStateOf<PinResult?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Widgets, null) },
        title = { Text("把继续记录放到桌面") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 预览卡片
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("AniMeow · 继续记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("最近在看的作品", style = MaterialTheme.typography.bodyMedium)
                                Text("第 6 / 12 集", style = MaterialTheme.typography.labelMedium)
                            }
                            Icon(AppIcons.Add, "记录一集")
                        }
                        LinearProgressIndicator(progress = { 0.5f }, modifier = Modifier.fillMaxWidth())
                    }
                }

                // 功能说明
                Text("显示最近两部未看完的在看作品。点击作品打开详情，点 +1 记录进度，点标题回到资料库。")

                // 添加方法说明
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("添加方式：", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("•  长按手机桌面空白处 → 小组件（或添加工具）→ AniMeow → 继续记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("•  大小和外观由桌面启动器决定，添加后可长按拖动调整", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 快捷添加结果提示
                pinResult?.let { result ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (result.success) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (result.success) AppIcons.CheckCircle else AppIcons.Widgets,
                                null,
                                tint = if (result.success) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                result.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.success) MaterialTheme.colorScheme.onTertiaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val success = runCatching { TrackerWidget.requestPin(context) }.getOrDefault(false)
                pinResult = if (success) {
                    PinResult(true, "已向桌面发起添加请求，请在桌面弹窗中选择「添加」。\n如果没有弹窗，请按上面的方法手动添加。")
                } else {
                    PinResult(false, "当前桌面不支持快捷添加，请按上面的方法手动添加到桌面。")
                }
            }) {
                Text("快捷添加到桌面")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

private data class PinResult(
    val success: Boolean,
    val message: String,
)
