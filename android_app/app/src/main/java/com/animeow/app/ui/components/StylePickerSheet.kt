package com.animeow.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StylePickerSheet(
    currentFrontendMode: FrontendMode,
    currentStyle: AppStyle,
    currentThemeMode: ThemeMode,
    currentAccentColor: Long?,
    onFrontendModeSelected: (FrontendMode) -> Unit,
    onStyleSelected: (AppStyle) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onAccentColorSelected: (Long?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    StableModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "选择界面风格",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "经典版复刻旧版布局与交互，新版保留 2.0 的完整能力；两套界面共用同一资料库，可随时切换。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "界面版本",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FrontendMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentFrontendMode == mode,
                        onClick = { onFrontendModeSelected(mode) },
                        label = { Text(if (mode == FrontendMode.LEGACY) "经典版（旧版）" else "新版 2.0") },
                    )
                }
            }
            Text(
                text = if (currentFrontendMode == FrontendMode.LEGACY) {
                    "当前使用经典版四栏导航与旧版“我的”设置布局。"
                } else {
                    "当前使用新版导航、页面定制与现代设置布局。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "明暗模式",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentThemeMode == mode,
                        onClick = { onThemeModeSelected(mode) },
                        label = { Text(mode.displayName) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "强调色",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilterChip(
                selected = currentAccentColor == null,
                onClick = { onAccentColorSelected(null) },
                label = { Text("跟随套件") },
            )
            CustomizableColorSelector(
                color = currentAccentColor ?: ACCENT_PRESETS.first(),
                presets = ACCENT_PRESETS,
                onColorChanged = onAccentColorSelected,
                modifier = Modifier.fillMaxWidth(),
                dialogTitle = "自定义应用强调色",
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "视觉套件",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))

            AppStyle.entries.forEach { style ->
                ListItem(
                    headlineContent = {
                        Text(style.displayName)
                    },
                    supportingContent = {
                        Text(style.description)
                    },
                    trailingContent = {
                        RadioButton(
                            selected = currentStyle == style,
                            onClick = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.RadioButton,
                            onClick = { onStyleSelected(style) },
                        ),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onDismissRequest) {
                    Text("完成")
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private val ACCENT_PRESETS = listOf(
    0xFF3482FFu.toInt().toLong(),
    0xFF8A4FD0u.toInt().toLong(),
    0xFFE4568Fu.toInt().toLong(),
    0xFF00A2A5u.toInt().toLong(),
    0xFF3E9B55u.toInt().toLong(),
    0xFFF08A24u.toInt().toLong(),
)
