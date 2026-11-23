package com.animeow.app.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons

@Composable
internal fun CloudConfigurationCard(
    configured: Boolean,
    username: String?,
    busy: Boolean,
    saving: Boolean,
    onLogin: () -> Unit,
    onSave: () -> Unit,
    onManage: () -> Unit,
) {
    var confirmUpload by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("将当前配置保存到云端", style = MaterialTheme.typography.titleSmall)
            Text("当前外观、配置档案和品牌资源随设备备份保存。恢复时可只选择“自定义配置与品牌资源”。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("使用与其他数据相同的备份额度及保留规则；同时备份资料库，字体文件不上传。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!configured) Text("云端服务暂未开放，可以先保存本地配置档案。", style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(
                onClick = { if (username == null) onLogin() else confirmUpload = true },
                enabled = configured && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else AppIcon(AppIcons.CloudUpload, null, Modifier.size(20.dp))
                Text(if (saving) "正在保存到云端…" else if (username == null) "登录后保存到云端" else "保存到云端",
                    Modifier.padding(start = 8.dp))
            }
            if (username != null) TextButton(onClick = onManage, enabled = !busy) { Text("查看与恢复云端备份") }
        }
    }
    if (confirmUpload && username != null) AlertDialog(
        onDismissRequest = { confirmUpload = false },
        title = { Text("保存当前配置到云端？") },
        text = {
            Text("将作为完整备份保存至账号“$username”（包含当前预览配置、配置档案、品牌资源与收藏资料，不含字体文件；恢复时可单选配置）。")
        },
        confirmButton = { TextButton(onClick = { confirmUpload = false; onSave() }, enabled = !busy) { Text("确认保存") } },
        dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("取消") } },
    )
}
