package com.animeow.app.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.components.HsvColorPickerDialog
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.theme.AccentGradientSettings
import com.animeow.app.ui.theme.AccentMode
import com.animeow.app.ui.theme.AccentPalettePreset
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.LocalAccentPaint
import com.animeow.app.ui.theme.accentBackground
import com.animeow.app.ui.theme.accentGradientColors
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccentGradientControls(
    value: AccentGradientSettings,
    onChange: (AccentGradientSettings) -> Unit,
    onPalette: (List<Long>) -> Unit,
) {
    val primary = LocalAccentPaint.current.colors.first()
    val colors = accentGradientColors(primary, MaterialTheme.colorScheme.tertiary, value)
    val stops = colors.drop(1).map { it.toArgb().toLong() and 0xFFFFFFFFL }
    var editing by remember { mutableStateOf<Int?>(null) }
    var angle by remember(value.angleDegrees) { mutableFloatStateOf(value.angleDegrees) }
    var duration by remember(value.durationMillis) { mutableFloatStateOf(value.durationMillis.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("强调色效果", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentMode.entries.forEach { mode -> FilterChip(value.mode == mode, {
                onChange(value.copy(mode = mode, colors = stops))
            }, label = { Text(mode.label) }) }
        }
        Box(Modifier.fillMaxWidth().height(76.dp).clip(MaterialTheme.shapes.large)
            .accentBackground(LocalAccentPaint.current.withSettings(value.copy(angleDegrees = angle))))
        if (value.mode != AccentMode.SOLID) {
            Text("高级渐变设置", style = MaterialTheme.typography.titleSmall)
            Text("点击色点选择颜色，可使用 2–5 个色点；起始色与上方强调色同步。色点按你选定的颜色保存。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(colors.size) { index ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(colors[index])
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .semantics { contentDescription = "编辑渐变色点 ${index + 1}" }.clickable { editing = index })
                        Text(if (index == 0) "起始色" else "色点 ${index + 1}", style = MaterialTheme.typography.labelSmall)
                        if (index > 0 && colors.size > 2) IconButton(onClick = {
                            onChange(value.copy(colors = stops.filterIndexed { i, _ -> i != index - 1 }))
                        }) { AppIcon(AppIcons.Close, "删除渐变色点 ${index + 1}", Modifier.size(16.dp)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = colors.size < 5, onClick = { onChange(value.copy(colors = stops + 0xFFE5A5B8L)) }) { Text("添加色点") }
                TextButton(onClick = {
                    val reversed = colors.reversed().map { it.toArgb().toLong() and 0xFFFFFFFFL }
                    onPalette(reversed)
                }) { Text("反转配色") }
            }
            Text("渐变方向 ${angle.roundToInt()}°", style = MaterialTheme.typography.bodyMedium)
            Slider(angle, { angle = it }, valueRange = 0f..360f, onValueChangeFinished = { onChange(value.copy(angleDegrees = angle)) },
                modifier = Modifier.semantics { contentDescription = "渐变方向" })
            if (value.mode == AccentMode.FLOW) {
                Text("流动周期 ${(duration / 1000).roundToInt()} 秒", style = MaterialTheme.typography.bodyMedium)
                Slider(duration, { duration = it }, valueRange = 4_000f..24_000f, steps = 19,
                    onValueChangeFinished = { onChange(value.copy(durationMillis = duration.roundToInt())) },
                    modifier = Modifier.semantics { contentDescription = "强调色流动周期" })
                Text("周期越长越舒缓；减少动效或关闭系统动画时保留静态渐变。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("图标与导航强调区域使用这组配色；文字与按钮会适配明暗背景，色点原值保持不变。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    editing?.let { index -> HsvColorPickerDialog(title = "渐变色点 ${index + 1}",
        initialColor = colors.getOrElse(index) { primary }.toArgb().toLong() and 0xFFFFFFFFL,
        onDismiss = { editing = null }, onConfirm = { selected ->
            // Freeze all stops together, including a previously automatic second stop.
            onPalette(colors.mapIndexed { i, old -> if (i == index) selected else old.toArgb().toLong() and 0xFFFFFFFFL })
            editing = null
        }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccentPalettePresets(settings: AppearanceSettings, onSelect: (AccentPalettePreset) -> Unit) {
    Text("配色预设", style = MaterialTheme.typography.labelLarge)
    Text("只调整强调色，保留当前布局与导航。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentPalettePreset.entries.forEach { preset ->
            val colors = preset.colors.map { Color(it) }
            val brush = if (colors.size == 1) SolidColor(colors.first()) else Brush.linearGradient(colors)
            FilterChip(
                selected = !settings.useDynamicColor && settings.accentColor == preset.colors.first() &&
                    settings.accentGradient.mode == preset.mode && settings.accentGradient.colors == preset.colors.drop(1),
                onClick = { onSelect(preset) },
                leadingIcon = { Box(Modifier.size(32.dp, 18.dp).clip(CircleShape).background(brush)) },
                label = { Text("${preset.label} · ${preset.mode.label}") },
            )
        }
    }
}
