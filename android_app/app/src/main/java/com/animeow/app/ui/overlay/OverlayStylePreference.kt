package com.animeow.app.ui.overlay

import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.animeow.app.data.overlay.OverlayBallDrawable
import com.animeow.app.ui.theme.OverlayStyle

@Composable
internal fun OverlayStylePreference(selected: OverlayStyle, onSelected: (OverlayStyle) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("悬浮球外观") },
        supportingContent = { Text("${selected.displayName} · 点击切换") },
        leadingContent = { OverlayStylePreview(selected) },
        modifier = Modifier.clickable(role = Role.Button, onClick = { open = true }),
    )
    if (open) OverlayStylePicker(selected, onSelected, onDismiss = { open = false })
}

@Composable
internal fun OverlayStylePicker(selected: OverlayStyle, onSelected: (OverlayStyle) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("悬浮球外观") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                OverlayStyle.entries.forEach { style ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = style == selected, role = Role.RadioButton,
                            onClick = { onSelected(style); onDismiss() },
                        ).padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OverlayStylePreview(style)
                        Column(Modifier.weight(1f)) {
                            Text(style.displayName + if (style == OverlayStyle.QUIET_PAW) "（默认）" else "")
                            Text(style.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(selected = style == selected, onClick = null)
                    }
                }
                Text("选择后立即保存，已开启的悬浮窗也会更新。拖到屏幕边缘可半隐藏，点击展开操作。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun OverlayStylePreview(style: OverlayStyle) {
    AndroidView(
        factory = { context -> ImageView(context).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
        update = { view ->
            if ((view.drawable as? OverlayBallDrawable)?.style != style) {
                view.setImageDrawable(OverlayBallDrawable(view.context, style).apply { progress = 0.25f })
            }
        },
        modifier = Modifier.size(48.dp),
    )
}
