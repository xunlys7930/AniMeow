package com.animeow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animeow.app.data.preferences.StatusBadgeBackground
import com.animeow.app.data.preferences.TrackerSettings

@Composable
fun WatchStatusBadge(
    status: String,
    statusColor: Color,
    settings: TrackerSettings,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    style: TextStyle = MaterialTheme.typography.labelSmall,
) {
    val background = when (settings.statusBadgeBackground) {
        StatusBadgeBackground.SURFACE -> MaterialTheme.colorScheme.surface
        StatusBadgeBackground.STATUS -> statusColor
        StatusBadgeBackground.CUSTOM -> Color(settings.statusBadgeCustomColor)
    }
    val foreground = when {
        !filled -> statusColor
        settings.statusBadgeBackground == StatusBadgeBackground.SURFACE -> statusColor
        background.luminance() > 0.179f -> Color(0xFF151917)
        else -> Color.White
    }
    Text(
        status,
        modifier = modifier.then(
            if (filled) Modifier.background(
                background.copy(alpha = settings.infoLightBackgroundOpacity),
                RoundedCornerShape(settings.infoCornerDp.dp),
            ).padding(horizontal = (7 * settings.infoScale).dp, vertical = (3 * settings.infoScale).dp)
            else Modifier,
        ),
        style = style.copy(fontSize = style.fontSize * settings.infoScale),
        color = foreground.copy(alpha = settings.infoTextOpacity),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusBadgeControls(
    settings: TrackerSettings,
    onBackgroundChanged: (StatusBadgeBackground) -> Unit,
    onColorChanged: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("状态气泡底色", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadgeBackground.entries.forEach { option ->
                FilterChip(settings.statusBadgeBackground == option, { onBackgroundChanged(option) }, label = { Text(option.displayName) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WatchStatusBadge("在看", Color(0xFF2196F3), settings)
            WatchStatusBadge("看完", Color(0xFF4CAF50), settings)
            WatchStatusBadge("未看", Color(0xFFFF9800), settings)
        }
        if (settings.statusBadgeBackground == StatusBadgeBackground.CUSTOM) {
            CustomizableColorSelector(
                color = settings.statusBadgeCustomColor,
                presets = listOf(0xFF466B59, 0xFF506C9B, 0xFF79619A, 0xFFAA6565, 0xFFE7D8B1, 0xFF202824, 0xFFF3F1EA),
                onColorChanged = onColorChanged,
                dialogTitle = "状态气泡底色",
            )
        }
        if (settings.statusBadgeBackground == StatusBadgeBackground.STATUS) {
            Text("各状态的颜色沿用「状态管理」中的设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
