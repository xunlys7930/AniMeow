package com.animeow.app.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.components.CustomizableColorSelector
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.LocalAccentPaint
import com.animeow.app.ui.theme.RatingBadgeColorStyle

@Composable
internal fun ThemeColorControls(settings: AppearanceSettings, onColor: (Long?) -> Unit) {
    val independent = settings.themeColor != null
    val color = settings.themeColor ?: (LocalAccentPaint.current.colors.first().toArgb().toLong() and 0xFFFFFFFFL)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("主题色", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().toggleable(independent, role = Role.Switch,
                onValueChange = { onColor(if (it) color else null) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("独立设置主题色", style = MaterialTheme.typography.bodyLarge)
                Text(if (independent) "用于页面、卡片和通用控件，与强调色分别调整。" else "当前跟随强调色，开启后可分别搭配。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = independent, onCheckedChange = null)
        }
        if (independent) CustomizableColorSelector(
            color = color,
            presets = listOf(0xFF547EB0L, 0xFF668A79L, 0xFF8A6EA8L, 0xFFB6798BL, 0xFFA57C50L, 0xFF657784L),
            onColorChanged = { onColor(it) },
            modifier = Modifier.fillMaxWidth(),
            dialogTitle = "自定义 AniMeow 主题色",
        )
    }
}

@Composable
internal fun RatingColorExplanation(style: RatingBadgeColorStyle) {
    val text = when (style) {
        RatingBadgeColorStyle.THEME_PRIMARY -> "使用主题主色；主题色未独立设置时，也跟随强调色的渐变与流动。"
        RatingBadgeColorStyle.ACCENT -> "跟随强调色的纯色、渐变与流动；精简动效或系统关闭动画时显示静态配色。"
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
