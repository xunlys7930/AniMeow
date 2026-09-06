package com.animeow.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.importer.LegacyImportSummary
import com.animeow.app.data.importer.LegacyImportUiState
import com.animeow.app.ui.importer.LegacyImportViewModel
import com.animeow.app.ui.components.AniMeowEnterEasing
import com.animeow.app.ui.components.AniMeowExitEasing
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionDurationMillis
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.canSetNavigationDestinationVisible
import com.animeow.app.ui.theme.isNavigationDestinationVisible
import com.animeow.app.ui.navigation.AppDestination
import com.animeow.app.ui.backup.NativeBackupUiState
import com.animeow.app.ui.backup.NativeBackupViewModel
import com.animeow.app.ui.backup.NativeRestoreSelectionContent
import com.animeow.app.data.backup.NativeBackupSummary
import com.animeow.app.data.backup.NativeRestoreSelection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.text.style.TextAlign
import com.animeow.app.ui.cloud.CloudAccountViewModel
import com.animeow.app.ui.statistics.StatisticsViewModel

@Composable
fun ProfileScreen(
    settings: AppearanceSettings,
    onFrontendModeSelected: (FrontendMode) -> Unit,
    onStylePickerRequested: (String) -> Unit,
    onCustomizationRequested: () -> Unit,
    onSettingsRequested: () -> Unit,
    onLibraryManagementRequested: () -> Unit,
    onReminderManagementRequested: () -> Unit,
    onTrashRequested: () -> Unit,
    onDuplicateCleanupRequested: () -> Unit,
    onSeriesShelfRequested: () -> Unit,
    onTagIndexRequested: () -> Unit,
    onStatisticsRequested: () -> Unit,
    onAnimeAnalysisRequested: () -> Unit,
    onTierListRequested: () -> Unit,
    onCharactersRequested: () -> Unit,
    onCommunityRequested: () -> Unit,
    onBangumiImportRequested: () -> Unit,
    onSpreadsheetTransferRequested: () -> Unit,
    onCloudAccountRequested: () -> Unit,
    onPersonalCenterRequested: () -> Unit = {},
    onDiagnosticsRequested: () -> Unit,
    onFeedbackPoolRequested: () -> Unit,
    onHelpRequested: () -> Unit,
    onAboutRequested: () -> Unit,
    onUserManualRequested: () -> Unit = {},
    modifier: Modifier = Modifier,
    importViewModel: LegacyImportViewModel = viewModel(),
    nativeBackupViewModel: NativeBackupViewModel = viewModel(),
    statisticsViewModel: StatisticsViewModel = viewModel(),
    cloudAccountViewModel: CloudAccountViewModel = viewModel(),
) {
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val nativeBackupState by nativeBackupViewModel.state.collectAsStateWithLifecycle()
    val statisticsState by statisticsViewModel.state.collectAsStateWithLifecycle()
    val cloudAccountState by cloudAccountViewModel.state.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copyQqGroup = {
        clipboardManager.setText(AnnotatedString(ANIMEOW_QQ_GROUP))
        Toast.makeText(context, "QQ群号已复制", Toast.LENGTH_SHORT).show()
    }
    val legacyBackupPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(importViewModel::inspect)
    }
    val nativeExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(nativeBackupViewModel::export) }
    val nativeRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(nativeBackupViewModel::inspectRestore) }
    var libraryManagementExpanded by rememberSaveable { mutableStateOf(false) }
    var dataToolsExpanded by rememberSaveable { mutableStateOf(false) }
    var extensionToolsExpanded by rememberSaveable { mutableStateOf(false) }
    var helpMaintenanceExpanded by rememberSaveable { mutableStateOf(false) }
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
            val watchingCount = statisticsState.statusCounts.find { it.name == "在看" }?.count ?: 0
            val completedCount = statisticsState.statusCounts.find { it.name == "看完" }?.count ?: 0
            val syncStatus = if (cloudAccountState.session != null) {
                cloudAccountState.syncSettings.lastSyncAt?.let {
                    "最近同步：${it.replace('T', ' ').take(19)}"
                } ?: "已登录"
            } else {
                "未登录"
            }
            item {
                ProfileHeader(
                    username = cloudAccountState.session?.username,
                    syncStatus = syncStatus,
                    totalAnime = statisticsState.totalAnime,
                    watchingCount = watchingCount,
                    completedCount = completedCount,
                    onClick = onPersonalCenterRequested,
                )
            }
            item {
                QuickActionGrid(
                    onReminders = onReminderManagementRequested,
                    onStatistics = onStatisticsRequested,
                    onCustomization = onCustomizationRequested,
                    onCloudBackup = onCloudAccountRequested,
                )
            }
            item {
                SettingsDisclosureSection(
                    title = "资料库管理",
                    subtitle = "系列、标签、查重、回收站与状态管理",
                    count = 5,
                    expanded = libraryManagementExpanded,
                    onExpandedChange = { libraryManagementExpanded = it },
                ) {
                    PreferenceRow(Icons.Outlined.CollectionsBookmark, "系列书架", "集中浏览多季作品与独立作品", "打开", onSeriesShelfRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.AutoMirrored.Outlined.Label, "标签索引", "按名称、首字母或使用次数浏览", "浏览", onTagIndexRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.FindReplace, "查重与合并", "安全扫描并合并同类型重名作品", "扫描", onDuplicateCleanupRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.RestoreFromTrash, "回收站", "恢复误删作品或永久清理", "查看", onTrashRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.Settings, "状态、标签与系列管理", "新增、配色、排序、迁移和安全删除", "管理", onLibraryManagementRequested)
                }
            }
            item {
                SettingsDisclosureSection(
                    title = "数据与迁移",
                    subtitle = "完整备份、原版数据库、Bangumi 与表格搬家",
                    count = 5,
                    expanded = dataToolsExpanded,
                    onExpandedChange = { dataToolsExpanded = it },
                ) {
                    PreferenceRow(Icons.Outlined.Archive, "导出完整备份", "包含资料、封面、角色和自定义设置", "导出") {
                        nativeExportLauncher.launch("AniMeow_${java.time.LocalDate.now()}.animeow.zip")
                    }
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.Restore, "恢复原生备份", "校验后恢复资料库并重建提醒", "恢复") {
                        nativeRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.Storage, "导入原版数据", "兼容原版 ZIP 与 anime_tracker_v5.db", "选择") {
                        legacyBackupPicker.launch(arrayOf("*/*"))
                    }
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.CloudDownload, "导入 Bangumi 公开收藏", "安全合并进度、评价和标签", "导入", onBangumiImportRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.TableView, "Excel / CSV 搬家", "自定义列映射、冲突策略与导出", "打开", onSpreadsheetTransferRequested)
                }
            }
            item {
                SettingsDisclosureSection(
                    title = "探索与扩展",
                    subtitle = "AI 分析、评级、角色管理与社区",
                    count = 4,
                    expanded = extensionToolsExpanded,
                    onExpandedChange = { extensionToolsExpanded = it },
                ) {
                    PreferenceRow(Icons.Outlined.AutoAwesome, "AI 看番风格", "生成隐私友好的偏好分析报告", onClick = onAnimeAnalysisRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.EmojiEvents, "趣味评级 Tier List", "拖动作品分档并分享排行", onClick = onTierListRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.Groups, "角色管理与关系星图", "角色、作品关联、关系网络与角色组", onClick = onCharactersRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.Groups, "登录社区", "官方群聊、图文交流与个人统计名片", onClick = onCommunityRequested)
                }
            }
            item {
                SettingsDisclosureSection(
                    title = "帮助与维护",
                    subtitle = "反馈池、版本、排障和数据安全",
                    count = 5,
                    expanded = helpMaintenanceExpanded,
                    onExpandedChange = { helpMaintenanceExpanded = it },
                ) {
                    PreferenceRow(Icons.AutoMirrored.Outlined.MenuBook, "用户使用手册", "全部功能索引、设置位置与每日小贴士", "查看", onUserManualRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.BugReport, "开放反馈池", "浏览公开建议，提交问题或功能想法", "打开", onFeedbackPoolRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.AutoMirrored.Outlined.HelpOutline, "帮助与常见问题", "使用指南、排障与数据安全说明", "查看", onHelpRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.Info, "关于与维护", "更新、缓存、更新日志、声明与开源", "打开", onAboutRequested)
                    SettingsDivider()
                    PreferenceRow(Icons.Outlined.BugReport, "诊断与操作轨迹", "本地日志、崩溃恢复与脱敏导出", "查看", onDiagnosticsRequested)
                }
            }
            item {
                SettingsEntryRow(onClick = onSettingsRequested)
            }
            item {
                QqGroupBanner(onCopy = copyQqGroup)
            }
            if (settings.showVersionInProfile) {
                item {
                    Text(
                        "AniMeow v${com.animeow.app.BuildConfig.VERSION_NAME}",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

    LegacyImportDialogs(
        state = importState,
        onConfirm = importViewModel::confirmImport,
        onDismiss = importViewModel::dismiss,
    )
    NativeBackupDialogs(
        state = nativeBackupState,
        onConfirmRestore = nativeBackupViewModel::confirmRestore,
        onDismiss = nativeBackupViewModel::dismiss,
    )
}

@Composable
private fun NativeBackupDialogs(
    state: NativeBackupUiState,
    onConfirmRestore: (NativeRestoreSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        NativeBackupUiState.Idle -> Unit
        NativeBackupUiState.Exporting -> BusyImportDialog("正在导出完整备份", "正在整理数据库、封面和设置。")
        NativeBackupUiState.Inspecting -> BusyImportDialog("正在检查原生备份", "校验格式、路径和数据摘要。")
        NativeBackupUiState.Restoring -> BusyImportDialog("正在恢复资料库", "正在事务写入并重建提醒，请勿关闭应用。")
        is NativeBackupUiState.ExportSuccess -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("完整备份已导出") },
                text = { NativeBackupSummaryText(state.summary) },
                confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("完成") } },
            )
        }
        is NativeBackupUiState.RestoreReady -> {
            var selection by remember(state.prepared) { mutableStateOf(state.selection) }
            AlertDialog(
                onDismissRequest = { if (!state.restoring) onDismiss() },
                title = { Text("恢复这份原生备份？") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NativeBackupSummaryText(state.summary)
                        Text(
                            "仅替换你选择的范围；恢复失败时会自动回滚。云账号和设备身份不会跨设备恢复。",
                            color = MaterialTheme.colorScheme.error,
                        )
                        NativeRestoreSelectionContent(
                            selection = selection,
                            onSelectionChange = { if (!state.restoring) selection = it },
                        )
                        state.message?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    FilledTonalButton(
                        onClick = { onConfirmRestore(selection) },
                        enabled = !selection.isEmpty && !state.restoring,
                    ) { Text(if (state.restoring) "恢复中…" else "确认恢复") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !state.restoring) { Text("取消") }
                },
            )
        }
        is NativeBackupUiState.RestoreSuccess -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("原生备份已恢复") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            buildList {
                                if (state.selection.libraryAndCharacters) add("资料库、角色与封面")
                                if (state.selection.analysisHistory) add("AI 分析历史")
                                if (state.selection.appearanceSettings) add("自定义配置与品牌资源")
                            }.joinToString("、", prefix = "已恢复："),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.selection.libraryAndCharacters) NativeBackupSummaryText(state.summary)
                    }
                },
                confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("完成") } },
            )
        }
        is NativeBackupUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("备份操作失败") },
                text = { Text(state.message) },
                confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("知道了") } },
            )
        }
    }
}

@Composable
private fun NativeBackupSummaryText(summary: NativeBackupSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${summary.animeCount} 部作品 · ${summary.tagCount} 个标签")
        Text("${summary.characterCount} 个角色 · ${summary.groupCount} 个角色组")
        Text("${summary.coverCount} 张本地封面")
        Text("备份时间：${summary.createdAt.replace('T', ' ').take(19)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NavigationCustomizationContent(
    settings: AppearanceSettings,
    onOrderChanged: (List<String>) -> Unit,
    onStartDestinationSelected: (String) -> Unit,
) {
    val ordered = AppDestination.entries.sortedBy { destination ->
        settings.navigationOrder.indexOf(destination.route).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
    val visible = ordered.filter { destination -> settings.isNavigationDestinationVisible(destination.route) }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("导航顺序", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ordered.forEachIndexed { index, destination ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(destination.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(destination.label, modifier = Modifier.weight(1f).padding(start = 10.dp))
                IconButton(
                    onClick = {
                        val routes = ordered.map(AppDestination::route).toMutableList()
                        val item = routes.removeAt(index)
                        routes.add(index - 1, item)
                        onOrderChanged(routes)
                    },
                    enabled = index > 0,
                ) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上移") }
                IconButton(
                    onClick = {
                        val routes = ordered.map(AppDestination::route).toMutableList()
                        val item = routes.removeAt(index)
                        routes.add(index + 1, item)
                        onOrderChanged(routes)
                    },
                    enabled = index < ordered.lastIndex,
                ) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下移") }
            }
        }
        Text("启动落点", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            visible.forEach { destination ->
                FilterChip(
                    selected = settings.startDestination == destination.route,
                    onClick = { onStartDestinationSelected(destination.route) },
                    label = { Text(destination.label) },
                )
            }
        }
    }
}

private const val ANIMEOW_QQ_GROUP = "1073623448"

@Composable
private fun SettingsSectionTitle(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsSurface(
    backgroundAlpha: Float = 0.22f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .motionAnimateContentSize(280),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = backgroundAlpha),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsDisclosureSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    count: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibilityDuration = motionDurationMillis(260)
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motionAnimationSpec(220),
        label = "settings_disclosure_arrow",
    )
    Column(
        modifier = Modifier.motionAnimateContentSize(280),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animatedPressClick(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起$title" else "展开$title",
                    onClick = { onExpandedChange(!expanded) },
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (count != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        "$count 项",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(visibilityDuration, easing = AniMeowEnterEasing)) +
                expandVertically(
                    animationSpec = tween(visibilityDuration, easing = AniMeowEnterEasing),
                    expandFrom = Alignment.Top,
                ),
            exit = fadeOut(tween(visibilityDuration, easing = AniMeowExitEasing)) +
                shrinkVertically(
                    animationSpec = tween(visibilityDuration, easing = AniMeowExitEasing),
                    shrinkTowards = Alignment.Top,
                ),
        ) {
            SettingsSurface(content = content)
        }
    }
}

@Composable
private fun CloudAccountBanner(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = "打开云账号与设备备份",
                onClick = onClick,
            ),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "云账号与设备备份",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "登录、同步、跨设备恢复与编辑个人资料",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun QqGroupBanner(onCopy: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = "复制 AniMeow 用户交流群群号",
                onClick = onCopy,
            ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "AniMeow 用户交流群 · QQ群 $ANIMEOW_QQ_GROUP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
internal fun TogglePreferenceRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                enabled = enabled,
                role = Role.Switch,
                onClickLabel = if (checked) "关闭$title" else "开启$title",
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LegacyImportDialogs(
    state: LegacyImportUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        LegacyImportUiState.Idle -> Unit

        is LegacyImportUiState.Inspecting -> {
            BusyImportDialog(
                title = "正在检查原版备份",
                message = state.sourceName,
            )
        }

        is LegacyImportUiState.Ready -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("确认导入原版数据？") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ImportSummaryText(state.summary)
                        Text(
                            text = "开始后将用这份备份替换当前资料库；外观设置不变。" +
                                "导入前会自动创建完整恢复点，数据库写入失败时也会自动回滚。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.message?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    FilledTonalButton(onClick = onConfirm) {
                        Text(if (state.message == null) "开始导入" else "重试导入")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                },
            )
        }

        is LegacyImportUiState.Importing -> {
            BusyImportDialog(
                title = "正在迁移资料库",
                message = "正在导入 ${state.summary.animeCount} 部作品和本地封面，请不要关闭应用。",
            )
        }

        is LegacyImportUiState.Success -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("原版数据已导入") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ImportSummaryText(state.result.summary, showWarnings = false)
                        Text(
                            text = "已迁移 ${state.result.copiedCoverCount} 张本地封面。" +
                                "返回“追番”即可查看。",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (state.result.restorePointCreated) {
                            Text(
                                text = "导入前的资料与设置已保存在“云同步与恢复”的安全恢复点中。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!state.result.reminderSyncSucceeded) {
                            Text(
                                text = "作品与提醒设置已导入，但系统提醒任务暂未重建；进入提醒管理页后点击“重新同步”即可。",
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    FilledTonalButton(onClick = onDismiss) {
                        Text("完成")
                    }
                },
            )
        }

        is LegacyImportUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("无法导入备份") },
                text = { Text(state.message) },
                confirmButton = {
                    FilledTonalButton(onClick = onDismiss) {
                        Text("知道了")
                    }
                },
            )
        }
    }
}

@Composable
private fun BusyImportDialog(
    title: String,
    message: String,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(message)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ImportSummaryText(
    summary: LegacyImportSummary,
    showWarnings: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = summary.sourceName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text("原版数据库 v${summary.sourceSchemaVersion}")
        Text("${summary.animeCount} 部作品 · ${summary.watchRecordCount} 条观看记录")
        Text("${summary.seriesCount} 个系列 · ${summary.tagCount} 个标签")
        Text("${summary.characterCount} 个角色 · ${summary.characterGroupCount} 个角色组")
        Text("${summary.coverCount} 张本地封面")
        if (showWarnings) {
            summary.warnings.forEach { warning ->
                Text(
                    text = "• $warning",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun PreferenceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = "${actionLabel ?: ""}$title",
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
            tonalElevation = 0.dp,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(9.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
    )
}

@Composable
private fun ProfileHeader(
    username: String?,
    syncStatus: String,
    totalAnime: Int,
    watchingCount: Int,
    completedCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = "打开个人中心",
                onClick = onClick,
            ),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (!username.isNullOrBlank()) {
                            Text(
                                username.first().toString().uppercase(),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        username ?: "点击登录",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        syncStatus,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatItem("追番", totalAnime)
                Box(modifier = Modifier
                    .size(width = 1.dp, height = 24.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)))
                StatItem("在看", watchingCount)
                Box(modifier = Modifier
                    .size(width = 1.dp, height = 24.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)))
                StatItem("看完", completedCount)
            }
        }
    }
}

@Composable
private fun StatItem(label: String, count: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            count.toString(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            " $label",
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun QuickActionGrid(
    onReminders: () -> Unit,
    onStatistics: () -> Unit,
    onCustomization: () -> Unit,
    onCloudBackup: () -> Unit,
) {
    SettingsSurface {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            QuickActionItem(Icons.Outlined.NotificationsActive, "追番提醒", onReminders)
            QuickActionItem(Icons.Outlined.BarChart, "数据统计", onStatistics)
            QuickActionItem(Icons.Outlined.Palette, "外观定制", onCustomization)
            QuickActionItem(Icons.Outlined.CloudDone, "云端备份", onCloudBackup)
        }
    }
}

@Composable
private fun QuickActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            tonalElevation = 0.dp,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(11.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun SettingsEntryRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = "打开设置",
                onClick = onClick,
            ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                tonalElevation = 0.dp,
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(9.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "深色模式、导航、返回手势、界面版本与外观定制",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = -90f },
            )
        }
    }
}
