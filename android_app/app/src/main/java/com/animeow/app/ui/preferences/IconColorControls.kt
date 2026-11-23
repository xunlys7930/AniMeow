package com.animeow.app.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.components.CustomizableColorSelector
import com.animeow.app.ui.icons.IconColorMode
import com.animeow.app.ui.icons.IconColorSettings

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconColorControls(value: IconColorSettings?, allowInherit: Boolean = false, onChange: (IconColorSettings?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("图标配色", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowInherit) FilterChip(selected = value == null, onClick = { onChange(null) }, label = { Text("跟随整套配色") })
            IconColorMode.entries.forEach { mode ->
                FilterChip(selected = value?.mode == mode, onClick = { onChange((value ?: IconColorSettings()).copy(mode = mode)) },
                    label = { Text(mode.label) })
            }
        }
        if (value?.mode == IconColorMode.CUSTOM) CustomizableColorSelector(
            value.customColor, listOf(0xFF527D72L, 0xFF6175B5L, 0xFFB56B88L, 0xFFAC6D42L, 0xFF715A9FL),
            onColorChanged = { onChange(value.copy(customColor = it)) }, dialogTitle = "自定义图标颜色")
        if (value?.mode == IconColorMode.ACCENT) Text("同时跟随强调色的渐变与流动效果。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
