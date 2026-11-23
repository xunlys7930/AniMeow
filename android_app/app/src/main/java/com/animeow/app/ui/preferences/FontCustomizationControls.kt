package com.animeow.app.ui.preferences

import com.animeow.app.ui.icons.AppIcons

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animeow.app.data.fonts.ImportedAppFont
import com.animeow.app.ui.theme.AppFontChoice
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.bundledFontFamily

@Composable
internal fun FontCustomizationControls(settings: AppearanceSettings, state: CustomizationUiState, viewModel: CustomizationViewModel) {
    val context = LocalContext.current
    var pendingRemoval by remember { mutableStateOf<ImportedAppFont?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::importFont) }
    AppFontChoice.entries.filter { it != AppFontChoice.CUSTOM }.forEach { font ->
        Surface(
            onClick = { viewModel.setFontChoice(font) }, enabled = !state.busy,
            color = if (settings.appFontChoice == font) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(font.displayName, style = MaterialTheme.typography.titleMedium, fontFamily = bundledFontFamily(font) ?: androidx.compose.ui.text.font.FontFamily.Default)
                    Text("追番喵 · 记录每份喜欢 0123", style = MaterialTheme.typography.bodyMedium, fontFamily = bundledFontFamily(font) ?: androidx.compose.ui.text.font.FontFamily.Default)
                }
                RadioButton(selected = settings.appFontChoice == font, onClick = null)
            }
        }
    }
    FilledTonalButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导入 TTF / OTF 字体") }
    Text("单个文件最大 24 MiB。本地完整备份可携带字体；云备份与外观分享不上传字体文件。换机后需重新导入自选字体，缺少的字符由系统补足。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (settings.appFontChoice == AppFontChoice.CUSTOM && state.fonts.none { it.id == settings.customFontId }) {
        Text("本机找不到所选字体，已使用系统字体，请重新导入。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    state.fonts.forEach { font ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(font.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${"%.1f".format(font.byteSize / 1024f / 1024f)} MiB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { viewModel.selectFont(font) }, enabled = !state.busy) { Text(if (settings.appFontChoice == AppFontChoice.CUSTOM && settings.customFontId == font.id) "使用中" else "使用") }
            IconButton(onClick = { pendingRemoval = font }, enabled = !state.busy) { Icon(AppIcons.Delete, contentDescription = "移除字体 ${font.name}") }
        }
    }
    TextButton(onClick = { showLicenses = true }) { Text("内置字体的来源与开源授权") }
    pendingRemoval?.let { font ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null }, title = { Text("移除 ${font.name}？") },
            text = { Text("将删除本机的字体文件。引用此字体的配置会使用系统字体，需要时可重新导入。") },
            confirmButton = { TextButton(onClick = { viewModel.removeFont(font); pendingRemoval = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("保留") } },
        )
    }
    if (showLicenses) {
        val licenses = remember {
            listOf("NOTICE.txt", "NotoSansSC-OFL.txt", "NotoSerifSC-OFL.txt", "LXGWWenKaiLite-OFL.txt")
                .joinToString("\n\n") { name -> runCatching { context.assets.open("font-licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault(name) }
        }
        AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("字体来源与授权") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(licenses, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("知道了") } })
    }
}
