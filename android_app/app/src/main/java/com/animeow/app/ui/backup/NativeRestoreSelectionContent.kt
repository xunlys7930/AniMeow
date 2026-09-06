package com.animeow.app.ui.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.data.backup.NativeRestoreSelection

@Composable
internal fun NativeRestoreSelectionContent(
    selection: NativeRestoreSelection,
    onSelectionChange: (NativeRestoreSelection) -> Unit,
) {
    RestoreSelectionRow(
        title = "资料库、角色与封面",
        body = "作品、进度、标签、系列、角色、角色组、提醒及关联封面",
        checked = selection.libraryAndCharacters,
        onCheckedChange = { onSelectionChange(selection.copy(libraryAndCharacters = it)) },
    )
    RestoreSelectionRow(
        title = "AI 分析历史",
        body = "本机保存的看番风格分析记录；模型、提示词等属于自定义配置",
        checked = selection.analysisHistory,
        onCheckedChange = { onSelectionChange(selection.copy(analysisHistory = it)) },
    )
    RestoreSelectionRow(
        title = "自定义配置与品牌资源",
        body = "主题、布局、动效、发现、日历、编辑器、配置档案、启动页与图标资源；不含账号和设备身份",
        checked = selection.appearanceSettings,
        onCheckedChange = { onSelectionChange(selection.copy(appearanceSettings = it)) },
    )
}

@Composable
private fun RestoreSelectionRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
