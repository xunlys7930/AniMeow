package com.animeow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ExitBehavior
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.canSetNavigationDestinationVisible

@Composable
fun SettingsScreen(
    settings: AppearanceSettings,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onOverallStyleRequested: () -> Unit,
    onFrontendModeSelected: (FrontendMode) -> Unit,
    onPredictiveBackEnabledChanged: (Boolean) -> Unit,
    onExitBehaviorChanged: (ExitBehavior) -> Unit,
    onShowDiscoveryChanged: (Boolean) -> Unit,
    onShowCalendarChanged: (Boolean) -> Unit,
    onShowCommunityChanged: (Boolean) -> Unit,
    onShowStatisticsChanged: (Boolean) -> Unit,
    onShowVersionInProfileChanged: (Boolean) -> Unit,
    onNavigationOrderChanged: (List<String>) -> Unit,
    onStartDestinationSelected: (String) -> Unit,
    onCustomizationRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var commonSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var navigationExpanded by rememberSaveable { mutableStateOf(false) }
    var otherSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var versionDisplayExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsDisclosureSection(
                title = "常用设置",
                subtitle = "深色模式、界面版本、返回手势与外观定制",
                expanded = commonSettingsExpanded,
                onExpandedChange = { commonSettingsExpanded = it },
            ) {
                TogglePreferenceRow(
                    title = "黑夜模式",
                    subtitle = when (settings.themeMode) {
                        ThemeMode.DARK -> "已固定使用深色护眼主题"
                        ThemeMode.LIGHT -> "当前使用明亮浅色主题"
                        ThemeMode.SYSTEM -> "当前跟随系统；开启后固定为深色主题"
                    },
                    checked = settings.themeMode == ThemeMode.DARK,
                    onCheckedChange = { enabled ->
                        onThemeModeSelected(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT)
                    },
                )
                SettingsDivider()
                PreferenceRow(
                    icon = Icons.Outlined.Palette,
                    title = "整体风格与界面版本",
                    subtitle = "${settings.frontendMode.displayName} · ${settings.appStyle.displayName} · ${settings.themeMode.displayName}",
                    actionLabel = "定制",
                    onClick = onOverallStyleRequested,
                )
                SettingsDivider()
                PreferenceRow(
                    icon = Icons.Outlined.Tune,
                    title = "外观定制",
                    subtitle = "主题、字体、圆角、封面、卡片、动效与配置档案",
                    actionLabel = "打开",
                    onClick = onCustomizationRequested,
                )
                SettingsDivider()
                TogglePreferenceRow(
                    title = "预测性返回手势",
                    subtitle = if (settings.predictiveBackEnabled) {
                        "已开启，返回时会跟随手势预览上一页"
                    } else {
                        "已关闭，使用传统返回行为；部分系统（如 MIUI/HyperOS）的预览动画可能仍由系统控制"
                    },
                    checked = settings.predictiveBackEnabled,
                    onCheckedChange = onPredictiveBackEnabledChanged,
                )
                SettingsDivider()
                PreferenceRow(
                    icon = Icons.Outlined.SwapVert,
                    title = if (settings.frontendMode == FrontendMode.LEGACY) "体验新版界面" else "返回经典版界面",
                    subtitle = "新版与 v1.3.9 经典前端可随时自由切换，资料完全共用",
                    actionLabel = "切换",
                    onClick = {
                        onFrontendModeSelected(
                            if (settings.frontendMode == FrontendMode.LEGACY) FrontendMode.MODERN else FrontendMode.LEGACY,
                        )
                    },
                )
            }
        }

        item {
            SettingsDisclosureSection(
                title = "导航与启动",
                subtitle = "选择主导航入口、顺序和启动落点",
                expanded = navigationExpanded,
                onExpandedChange = { navigationExpanded = it },
            ) {
                TogglePreferenceRow(
                    "显示发现",
                    "关闭后仅隐藏入口，不删除功能数据",
                    settings.showDiscovery,
                    onShowDiscoveryChanged,
                    settings.canSetNavigationDestinationVisible("discovery", !settings.showDiscovery),
                )
                SettingsDivider()
                TogglePreferenceRow(
                    "显示日历",
                    "关闭后提醒仍会保留",
                    settings.showCalendar,
                    onShowCalendarChanged,
                    settings.canSetNavigationDestinationVisible("calendar", !settings.showCalendar),
                )
                SettingsDivider()
                TogglePreferenceRow(
                    "显示社区",
                    "关闭后仍可从发现与工具入口打开",
                    settings.showCommunity,
                    onShowCommunityChanged,
                    settings.canSetNavigationDestinationVisible("community", !settings.showCommunity),
                )
                SettingsDivider()
                TogglePreferenceRow(
                    "显示统计",
                    "关闭后仍可从资料库工具打开",
                    settings.showStatistics,
                    onShowStatisticsChanged,
                    settings.canSetNavigationDestinationVisible("statistics", !settings.showStatistics),
                )
                SettingsDivider()
                NavigationCustomizationContent(
                    settings = settings,
                    onOrderChanged = onNavigationOrderChanged,
                    onStartDestinationSelected = onStartDestinationSelected,
                )
            }
        }

        item {
            SettingsDisclosureSection(
                title = "其他",
                subtitle = "返回退出方式等应用行为",
                expanded = otherSettingsExpanded,
                onExpandedChange = { otherSettingsExpanded = it },
            ) {
                ExitBehaviorPicker(settings, onExitBehaviorChanged)
            }
        }

        item {
            SettingsDisclosureSection(
                title = "界面选项",
                subtitle = "版本号显示等界面偏好",
                count = 1,
                expanded = versionDisplayExpanded,
                onExpandedChange = { versionDisplayExpanded = it },
            ) {
                TogglePreferenceRow(
                    title = "显示版本号",
                    subtitle = "在「我的」页面底部显示当前应用版本",
                    checked = settings.showVersionInProfile,
                    onCheckedChange = onShowVersionInProfileChanged,
                )
            }
        }
    }
}

@Composable
private fun ExitBehaviorPicker(
    settings: AppearanceSettings,
    onExitBehaviorChanged: (ExitBehavior) -> Unit,
) {
    val exitOptions = ExitBehavior.entries
    val currentExitIndex = exitOptions.indexOf(settings.exitBehavior).coerceAtLeast(0)
    var showExitPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = "选择返回退出方式",
                onClick = { showExitPicker = true },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("返回退出方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "当前：${settings.exitBehavior.displayName}（${settings.exitBehavior.description}）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            settings.exitBehavior.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (showExitPicker) {
        AlertDialog(
            onDismissRequest = { showExitPicker = false },
            title = { Text("返回退出方式") },
            text = {
                Column {
                    exitOptions.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animatedPressClick(
                                    role = Role.RadioButton,
                                    onClickLabel = option.displayName,
                                    onClick = {
                                        onExitBehaviorChanged(option)
                                        showExitPicker = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(
                                selected = index == currentExitIndex,
                                onClick = null,
                            )
                            Column {
                                Text(option.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExitPicker = false }) { Text("关闭") }
            },
        )
    }
}
