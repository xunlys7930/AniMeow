package com.animeow.app.ui.screens

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import com.animeow.app.ui.icons.AppIcon as Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionSlideContentSpec
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.components.ConfirmationDialog
import com.animeow.app.ui.community.CommunityStylePicker
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ExitBehavior
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.canSetNavigationDestinationVisible

@Composable
fun SettingsScreen(
    settings: AppearanceSettings,
    onThemeModeSelected: (ThemeMode) -> Unit,
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
    onCloudAccountRequested: () -> Unit = {},
    onToolsRequested: () -> Unit = {},
    onAboutRequested: () -> Unit = {},
    onSearchRequested: () -> Unit = {},
    onBlockManagementRequested: () -> Unit = {},
    initialSection: String? = null,
    appearanceViewModel: AppearanceViewModel = viewModel(),
    onSectionRequested: (String) -> Unit = {},
    onCustomizationSectionRequested: (String) -> Unit = {},
    onDataToolsRequested: () -> Unit = {},
    onBangumiUploadRequested: () -> Unit = {},
    onBangumiImportRequested: () -> Unit = {},
    onLegadoSyncRequested: () -> Unit = {},
    onUserManualRequested: () -> Unit = {},
    onHelpRequested: () -> Unit = {},
    onDiagnosticsRequested: () -> Unit = {},
    onFeedbackPoolRequested: () -> Unit = {},
) {
    // Categories use the existing settings route; old section links remain reachable.
    var pendingFrontend by rememberSaveable { mutableStateOf<FrontendMode?>(null) }
    var showWidgetSetup by rememberSaveable { mutableStateOf(false) }
    var communityExpanded by rememberSaveable(initialSection) { mutableStateOf(initialSection == "community") }
    val section = initialSection?.takeIf { it in setOf("appearance", "navigation", "behavior", "data", "account", "tools", "community", "widget", "help") }
    val motionLevel = LocalMotionLevel.current
    AnimatedContent(
        targetState = section,
        transitionSpec = { motionSlideContentSpec(motionLevel, forward = targetState != null) },
        modifier = modifier.fillMaxSize(),
        label = "settings_section_content",
    ) { activeSection ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeSection == null) {
                item(key = "search") {
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .animatedPressClick(onClickLabel = "搜索设置", onClick = onSearchRequested),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                AppIcons.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "搜索设置",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "试试字号、底栏、备份或退出方式",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "categories") {
                    SettingsSurface {
                        PreferenceRow(AppIcons.Palette, "外观与阅读", "${settings.themeMode.displayName} · 文字 ${(settings.fontScale * 100).toInt()}%", onClick = onCustomizationRequested)
                        SettingsDivider()
                        PreferenceRow(AppIcons.SwapVertical, "导航与启动", "底栏入口、排序与启动页", onClick = { onCustomizationSectionRequested("navigation") })
                        SettingsDivider()
                        PreferenceRow(AppIcons.Settings, "操作与反馈", "进度操作、手势、返回与触感", onClick = { onCustomizationSectionRequested("behavior") })
                        SettingsDivider()
                        PreferenceRow(AppIcons.CloudDone, "账号与数据", "登录、同步、完整备份与迁入", onClick = { onSectionRequested("data") })
                        SettingsDivider()
                        PreferenceRow(AppIcons.Grid, "工具与内容", "资料库管理、社区与扩展", onClick = { onSectionRequested("tools") })
                        SettingsDivider()
                        PreferenceRow(AppIcons.Help, "帮助与维护", "手册、更新、缓存与诊断", onClick = { onSectionRequested("help") })
                    }
                }
            } else when (activeSection) {
            "appearance" -> item {
                SettingsSurface {
                    ThemeModePreference(settings.themeMode, onThemeModeSelected)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Palette, "外观与阅读", "主题、字体、页面、卡片与底栏外形", onClick = onCustomizationRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.SwapVertical, "界面版本与版本号", "已归入帮助与维护，原有选择保持不变", onClick = { onSectionRequested("help") })
                }
            }
            "navigation" -> {
                item(key = "nav-toggles") {
                    SettingsSurface {
                        TogglePreferenceRow(
                            "显示发现",
                            "从主导航显示或隐藏找番页",
                            settings.showDiscovery,
                            onShowDiscoveryChanged,
                            enabled = settings.canSetNavigationDestinationVisible("discovery", !settings.showDiscovery),
                        )
                        SettingsDivider()
                        TogglePreferenceRow(
                            "显示日历",
                            "隐藏入口不会删除提醒",
                            settings.showCalendar,
                            onShowCalendarChanged,
                            enabled = settings.canSetNavigationDestinationVisible("calendar", !settings.showCalendar),
                        )
                        SettingsDivider()
                        TogglePreferenceRow(
                            "显示社区",
                            "将登录社区提升为可排序主导航",
                            settings.showCommunity,
                            onShowCommunityChanged,
                            enabled = settings.canSetNavigationDestinationVisible("community", !settings.showCommunity),
                        )
                        SettingsDivider()
                        TogglePreferenceRow(
                            "显示统计",
                            "将统计页加入可排序主导航",
                            settings.showStatistics,
                            onShowStatisticsChanged,
                            enabled = settings.canSetNavigationDestinationVisible("statistics", !settings.showStatistics),
                        )
                    }
                }
                item(key = "nav-order") {
                    SettingsSurface {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            NavigationCustomizationContent(settings, onNavigationOrderChanged, onStartDestinationSelected)
                        }
                        SettingsDivider()
                        PreferenceRow(AppIcons.Settings, "底栏外形与更多启动项", "在统一设置页预览、对比变化或撤销", onClick = { onCustomizationSectionRequested("navigation") })
                    }
                }
            }
            "behavior" -> item {
                SettingsSurface {
                    ExitBehaviorPicker(settings, onExitBehaviorChanged)
                    SettingsDivider()
                    TogglePreferenceRow(
                        "预测性返回手势",
                        "启用系统级返回手势预览（Android 13+）",
                        settings.predictiveBackEnabled,
                        onPredictiveBackEnabledChanged,
                    )
                    SettingsDivider()
                    PreferenceRow(AppIcons.Settings, "手势动作、触感与更多反馈", "在统一设置页调整左右滑动作与触感强弱", onClick = { onCustomizationSectionRequested("behavior") })
                }
            }
            "data", "account" -> item {
                SettingsSurface {
                    PreferenceRow(AppIcons.CloudDone, "账号与同步", "登录、连接 Bangumi、自动同步与云端设备备份", onClick = onCloudAccountRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.CloudDone, "同步本机收藏到 Bangumi", "对比本机与 Bangumi 收藏差异，批量上传进度、状态与评分", onClick = onBangumiUploadRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.CloudDownload, "导入 Bangumi 收藏", "拉回 Bangumi 收藏并安全合并到本机", onClick = onBangumiImportRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Book, "《开源阅读》书架同步", "从本地备份、WebDAV 或局域网同步小说与阅读进度", onClick = onLegadoSyncRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Archive, "本地备份与数据迁入", "完整导出、原生恢复、原版 / Excel / CSV", onClick = onDataToolsRequested)
                }
            }
            "tools", "community", "widget" -> {
                item(key = "tools") {
                    SettingsSurface {
                        PreferenceRow(AppIcons.Grid, "资料库与全部工具", "标签、系列、查重、回收站、播放与扩展", onClick = onToolsRequested)
                        SettingsDivider()
                        PreferenceRow(AppIcons.Widgets, "桌面小组件", "继续记录 · 在桌面记录 +1", onClick = { showWidgetSetup = true })
                    }
                }
                item(key = "community") {
                    SettingsDisclosureSection(
                        title = "社区与屏蔽", subtitle = settings.communityStyle.displayName,
                        expanded = communityExpanded, onExpandedChange = { communityExpanded = it },
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            CommunityStylePicker(selected = settings.communityStyle, onSelect = appearanceViewModel::setCommunityStyle)
                        }
                        SettingsDivider()
                        PreferenceRow(AppIcons.Block, "屏蔽管理", "查看和取消已屏蔽的用户", onClick = onBlockManagementRequested)
                        SettingsDivider()
                        TogglePreferenceRow(
                            "在我的页面显示社区动态",
                            "显示帖子、点赞和收藏记录",
                            settings.showCommunityActivityInProfile,
                            appearanceViewModel::setShowCommunityActivityInProfile,
                        )
                    }
                }
            }
            else -> {
                item(key = "help") {
                    SettingsSurface {
                        PreferenceRow(AppIcons.Book, "用户使用手册", "功能索引、设置位置与每日小贴士", onClick = onUserManualRequested)
                        SettingsDivider()
                        PreferenceRow(AppIcons.Help, "帮助与常见问题", "使用指南、排障与数据安全", onClick = onHelpRequested)
                        SettingsDivider()
                        PreferenceRow(AppIcons.Info, "关于与更新", "版本、更新日志与缓存管理", onClick = onAboutRequested)
                        SettingsDivider()
                        PreferenceRow(AppIcons.Bug, "诊断与操作轨迹", "本地日志、崩溃恢复与脱敏导出", onClick = onDiagnosticsRequested)
                        SettingsDivider()
                        PreferenceRow(AppIcons.Bug, "开放反馈池", "浏览建议，提交问题或功能想法", onClick = onFeedbackPoolRequested)
                    }
                }
                item(key = "interface") {
                    SettingsSurface {
                        PreferenceRow(AppIcons.SwapVertical, "界面版本", "当前为${settings.frontendMode.displayName}，点击切换", "切换") {
                            pendingFrontend = if (settings.frontendMode == FrontendMode.LEGACY) FrontendMode.MODERN else FrontendMode.LEGACY
                        }
                        SettingsDivider()
                        TogglePreferenceRow("显示版本号", "显示在「我的」页面底部", settings.showVersionInProfile, onShowVersionInProfileChanged)
                    }
                }
            }
        }
        }
    }
    if (showWidgetSetup) {
        com.animeow.app.ui.components.TrackerWidgetSetupDialog { showWidgetSetup = false }
    }
    pendingFrontend?.let { target ->
        ConfirmationDialog(
            title = "切换到${target.displayName}？",
            message = "切换后，首页与部分页面的布局会改变，资料库和账号将保留。",
            confirmLabel = "切换界面",
            onConfirm = { pendingFrontend = null; onFrontendModeSelected(target) },
            onDismiss = { pendingFrontend = null },
        )
    }
}

@Composable
internal fun ThemeModePreference(selected: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    PreferenceRow(
        icon = AppIcons.DarkMode,
        title = "深色模式",
        subtitle = selected.displayName,
        onClick = { showPicker = true },
    )
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("深色模式") },
            text = {
                Column {
                    ThemeMode.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .animatedPressClick(role = Role.RadioButton, onClick = {
                                    onSelected(option)
                                    showPicker = false
                                })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Text(option.displayName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
        )
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
                settings.exitBehavior.description,
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
