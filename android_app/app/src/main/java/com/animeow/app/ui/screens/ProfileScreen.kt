package com.animeow.app.ui.screens

import com.animeow.app.ui.icons.AppIcons

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import com.animeow.app.ui.icons.AppIcon as Icon
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
import com.animeow.app.ui.icons.IconGlyph
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.text.style.TextAlign

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    settings: AppearanceSettings,
    onCustomizationRequested: () -> Unit,
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
    onBangumiUploadRequested: () -> Unit = {},
    onLegadoSyncRequested: () -> Unit = {},
    onOpenAnimeDetail: (Long) -> Unit = {},
    onSpreadsheetTransferRequested: () -> Unit,
    onCloudAccountRequested: () -> Unit,
    onDiagnosticsRequested: () -> Unit,
    onFeedbackPoolRequested: () -> Unit,
    onHelpRequested: () -> Unit,
    onAboutRequested: () -> Unit,
    onUserManualRequested: () -> Unit = {},
    importViewModel: LegacyImportViewModel = viewModel(),
    nativeBackupViewModel: NativeBackupViewModel = viewModel(),
    initialSection: String? = null,
) {
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val nativeBackupState by nativeBackupViewModel.state.collectAsStateWithLifecycle()
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
    var expandedSection by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    var showCategories by remember { mutableStateOf(false) }
    var showRecentBooks by rememberSaveable { mutableStateOf(false) }
    if (showRecentBooks) {
        com.animeow.app.ui.reader.RecentBookReadingSheet(
            onDismiss = { showRecentBooks = false },
            onOpenAnimeDetail = onOpenAnimeDetail,
        )
    }
    val visibleCategories by com.animeow.app.data.preferences.LibraryCategories.visible.collectAsStateWithLifecycle()
    LazyColumn(
        state = androidx.compose.foundation.lazy.rememberLazyListState(
            initialFirstVisibleItemIndex = when (initialSection) { "library" -> 3; "data" -> 4; "extensions" -> 5; "help" -> 6; else -> 0 },
        ),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            item(key = "watching") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "动画观看",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsSurface {
                        PreferenceRow(AppIcons.History, "播放历史", "查看本机播放位置并继续观看", "打开") {
                            context.startActivity(com.animeow.app.ui.playback.PlaybackActivity.history(context))
                        }
                        SettingsDivider()
                        PreferenceRow(AppIcons.Download, "下载管理", "下载任务、离线播放与空间管理", "打开") {
                            context.startActivity(com.animeow.app.ui.playback.PlaybackActivity.downloads(context))
                        }
                        SettingsDivider()
                        PreferenceRow(AppIcons.Book, "最近阅读", "续读本地 TXT / EPUB 电子书与《开源阅读》记录", "查看") {
                            showRecentBooks = true
                        }
                    }
                }
            }
            item(key = "categories") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "收藏与发现",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsSurface {
                        PreferenceRow(AppIcons.Tune, "分类显示", "收藏页与发现页只显示勾选的类型；音声默认关闭", "设置") { showCategories = true }
                    }
                }
            }
            item(key = "quick-actions") {
                QuickActionGrid(
                    onReminders = onReminderManagementRequested,
                    onStatistics = onStatisticsRequested,
                    onCustomization = onCustomizationRequested,
                    onCloudBackup = onCloudAccountRequested,
                )
            }
            item(key = "library") {
                SettingsDisclosureSection(
                    title = "资料库管理",
                    subtitle = "系列、标签、查重与回收站",
                    expanded = expandedSection == "library",
                    onExpandedChange = { expandedSection = if (it) "library" else null },
                ) {
                    PreferenceRow(AppIcons.Collection, "系列书架", "集中浏览多季作品与独立作品", "打开", onSeriesShelfRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Tag, "标签索引", "按名称、首字母或使用次数浏览", "浏览", onTagIndexRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Merge, "查重与合并", "扫描重复项，也可手动合并不同语言的同一作品", "合并", onDuplicateCleanupRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.RestoreTrash, "回收站", "恢复误删作品或永久清理", "查看", onTrashRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Settings, "状态、标签与系列管理", "新增、配色、排序、迁移和安全删除", "管理", onLibraryManagementRequested)
                }
            }
            item(key = "data") {
                SettingsDisclosureSection(
                    title = "数据与迁移",
                    subtitle = "本地备份、恢复与导入导出",
                    expanded = expandedSection == "data",
                    onExpandedChange = { expandedSection = if (it) "data" else null },
                ) {
                    PreferenceRow(AppIcons.Archive, "导出完整备份", "包含资料、封面、角色和自定义设置", "导出") {
                        nativeExportLauncher.launch("AniMeow_${java.time.LocalDate.now()}.animeow.zip")
                    }
                    SettingsDivider()
                    PreferenceRow(AppIcons.History, "从备份恢复", "校验备份文件后恢复收藏资料与提醒", "恢复") {
                        nativeRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                    SettingsDivider()
                    PreferenceRow(AppIcons.Storage, "导入旧版数据", "支持 1.x 旧版备份压缩包与数据文件", "选择") {
                        legacyBackupPicker.launch(arrayOf("*/*"))
                    }
                    SettingsDivider()
                    PreferenceRow(AppIcons.CloudDone, "同步本机收藏到 Bangumi", "对比本机与 Bangumi 差异，批量上传进度、状态与评分", "同步", onBangumiUploadRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.CloudDownload, "导入 Bangumi 收藏", "拉回 Bangumi 公开或私密收藏并安全合并", "导入", onBangumiImportRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Book, "《开源阅读》书架同步", "支持本地备份、WebDAV 与局域网服务同步小说进度", "同步", onLegadoSyncRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Table, "Excel / CSV 搬家", "自定义列映射、冲突策略与导出", "打开", onSpreadsheetTransferRequested)
                }
            }
            item(key = "extensions") {
                SettingsDisclosureSection(
                    title = "探索与扩展",
                    subtitle = "AI 分析、评级、角色管理与社区",
                    expanded = expandedSection == "extensions",
                    onExpandedChange = { expandedSection = if (it) "extensions" else null },
                ) {
                    PreferenceRow(AppIcons.Sparkles, "AI 看番风格", "生成隐私友好的偏好分析报告", onClick = onAnimeAnalysisRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Trophy, "趣味评级 Tier List", "拖动作品分档并分享排行", onClick = onTierListRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Group, "角色管理与关系星图", "角色、作品关联、关系网络与角色组", onClick = onCharactersRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Group, "社区交流", "茶话会、图文帖子与个人名片", onClick = onCommunityRequested)
                }
            }
            item(key = "help") {
                SettingsDisclosureSection(
                    title = "帮助与维护",
                    subtitle = "使用指南、反馈与版本维护",
                    expanded = expandedSection == "help",
                    onExpandedChange = { expandedSection = if (it) "help" else null },
                ) {
                    PreferenceRow(AppIcons.Book, "用户使用手册", "全部功能索引、设置位置与每日小贴士", "查看", onUserManualRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Bug, "开放反馈池", "浏览公开建议，提交问题或功能想法", "打开", onFeedbackPoolRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Help, "帮助与常见问题", "使用指南、排障与数据安全说明", "查看", onHelpRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Info, "关于与维护", "更新、缓存、更新日志、声明与开源", "打开", onAboutRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Bug, "诊断与操作轨迹", "本地日志、崩溃恢复与脱敏导出", "查看", onDiagnosticsRequested)
                    SettingsDivider()
                    PreferenceRow(AppIcons.Group, "用户交流群", "QQ群 $ANIMEOW_QQ_GROUP · 点击复制", "复制", copyQqGroup)
                }
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

    if (showCategories) AlertDialog(
        onDismissRequest = { showCategories = false },
        title = { Text("收藏与发现分类") },
        text = {
            Column {
                Text("取消勾选的类型不会出现在收藏页与发现页；已收藏的数据不会删除。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("anime" to "动画", "book" to "书籍", "game" to "游戏", "audio" to "音声").forEach { (key, label) ->
                    TogglePreferenceRow(
                        title = label,
                        subtitle = if (key == "audio") "默认关闭" else "默认开启",
                        checked = key in visibleCategories,
                        onCheckedChange = { on ->
                            com.animeow.app.data.preferences.LibraryCategories.setVisible(
                                if (on) visibleCategories + key else visibleCategories - key,
                            )
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { showCategories = false }) { Text("完成") } },
    )

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
        NativeBackupUiState.Exporting -> BusyImportDialog("正在导出完整备份", "正在打包收藏资料、封面和设置…")
        NativeBackupUiState.Inspecting -> BusyImportDialog("正在读取备份文件", "正在校验文件完整性与备份内容…")
        NativeBackupUiState.Restoring -> BusyImportDialog("正在恢复资料", "正在恢复数据并更新提醒，请勿关闭应用。")
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
                title = { Text("恢复这份备份？") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NativeBackupSummaryText(state.summary)
                        Text(
                            "仅替换你勾选的内容；若恢复中断，本地原有数据不会受影响。云账号登录状态需在新设备重新登录。",
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
                title = { Text("备份已恢复") },
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
        Text("${summary.animeCount} 部动画/书籍 · ${summary.gameCount} 部游戏、${summary.audioCount} 部音声 · ${summary.tagCount} 个标签")
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
                ) { Icon(AppIcons.Collapse, contentDescription = "上移") }
                IconButton(
                    onClick = {
                        val routes = ordered.map(AppDestination::route).toMutableList()
                        val item = routes.removeAt(index)
                        routes.add(index + 1, item)
                        onOrderChanged(routes)
                    },
                    enabled = index < ordered.lastIndex,
                ) { Icon(AppIcons.Expand, contentDescription = "下移") }
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibilityDuration = motionDurationMillis(260)
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motionAnimationSpec(220),
        label = "settings_disclosure_arrow",
    )
    SettingsSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animatedPressClick(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起$title" else "展开$title",
                    onClick = { onExpandedChange(!expanded) },
                )
                .heightIn(min = 64.dp)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                icon = AppIcons.Expand,
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
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                content()
            }
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
                            text = "开始后将用这份备份替换当前收藏资料，界面外观设置保持不变。" +
                                "导入前会自动保存恢复点，若中途出错也不会损坏原有数据。",
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
                title = { Text("旧版数据已导入") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ImportSummaryText(state.result.summary, showWarnings = false)
                        Text(
                            text = "已迁移 ${state.result.copiedCoverCount} 张本地封面，" +
                                "前往底部「收藏」页即可查看。",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (state.result.restorePointCreated) {
                            Text(
                                text = "导入前的资料已自动保存在「账号与同步」的安全恢复点中。",
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
        Text("旧版数据格式 v${summary.sourceSchemaVersion}")
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
    icon: IconGlyph,
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
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(9.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            AppIcons.Expand,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = -90f },
        )
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
private fun QuickActionGrid(
    onReminders: () -> Unit,
    onStatistics: () -> Unit,
    onCustomization: () -> Unit,
    onCloudBackup: () -> Unit,
) {
    SettingsSurface {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f) 2 else 4
            val actions = listOf(
                Triple(AppIcons.Notification, "追番提醒", onReminders),
                Triple(AppIcons.Statistics, "数据统计", onStatistics),
                Triple(AppIcons.Palette, "外观与阅读", onCustomization),
                Triple(AppIcons.CloudDone, "云端备份", onCloudBackup),
            )
            Column(Modifier.padding(vertical = 8.dp)) {
                actions.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (icon, label, onClick) ->
                            QuickActionItem(Modifier.weight(1f), icon, label, onClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionItem(
    modifier: Modifier = Modifier,
    icon: IconGlyph,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
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
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(11.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}
