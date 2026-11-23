package com.animeow.app.ui.legacy

import com.animeow.app.ui.icons.AppIcons

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.animeow.app.ui.icons.IconGlyph
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.BuildConfig
import com.animeow.app.R
import com.animeow.app.ui.community.CommunityProfileEditorDialog
import com.animeow.app.ui.community.CommunityViewModel
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ThemeMode

private data class LegacyProfileAction(
    val title: String,
    val subtitle: String,
    val icon: IconGlyph,
    val tint: Color,
    val keywords: String,
    val onClick: () -> Unit,
)

@Composable
fun LegacyProfileScreen(
    modifier: Modifier = Modifier,
    settings: AppearanceSettings,
    onFrontendModeSelected: (FrontendMode) -> Unit,
    onOverallStyleRequested: () -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onPredictiveBackEnabledChanged: (Boolean) -> Unit,
    onCustomizationRequested: () -> Unit,
    onAllToolsRequested: () -> Unit,
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
    onImageSearchRequested: () -> Unit,
    onCloudAccountRequested: () -> Unit,
    onBangumiImportRequested: () -> Unit,
    onBangumiUploadRequested: () -> Unit = {},
    onLegadoSyncRequested: () -> Unit = {},
    onSpreadsheetTransferRequested: () -> Unit,
    onDiagnosticsRequested: () -> Unit,
    onFeedbackPoolRequested: () -> Unit,
    onHelpRequested: () -> Unit,
    onAboutRequested: () -> Unit,
    onUserManualRequested: () -> Unit = {},
    communityViewModel: CommunityViewModel = viewModel(),
) {
    val context = LocalContext.current
    val communityState by communityViewModel.state.collectAsStateWithLifecycle()
    val communityLocalStatistics by communityViewModel.localStatistics.collectAsStateWithLifecycle()
    var editProfileRequested by remember { mutableStateOf(false) }
    LaunchedEffect(communityState.message) {
        communityState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            communityViewModel.clearMessage()
        }
    }
    LaunchedEffect(communityState.profileSaved) {
        if (communityState.profileSaved) {
            editProfileRequested = false
            communityViewModel.consumeProfileSaved()
        }
    }
    LaunchedEffect(editProfileRequested, communityState.session) {
        if (editProfileRequested && communityState.session == null) {
            Toast.makeText(context, "请先登录社区账号", Toast.LENGTH_SHORT).show()
            editProfileRequested = false
        }
    }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val actions = remember(
        settings.frontendMode,
        settings.predictiveBackEnabled,
        onFrontendModeSelected,
        onCustomizationRequested,
        onLibraryManagementRequested,
        onReminderManagementRequested,
        onTrashRequested,
        onDuplicateCleanupRequested,
        onSeriesShelfRequested,
        onTagIndexRequested,
        onStatisticsRequested,
        onAnimeAnalysisRequested,
        onTierListRequested,
        onCharactersRequested,
        onCommunityRequested,
        onImageSearchRequested,
        onCloudAccountRequested,
        onBangumiImportRequested,
        onBangumiUploadRequested,
        onLegadoSyncRequested,
        onSpreadsheetTransferRequested,
        onDiagnosticsRequested,
        onFeedbackPoolRequested,
        onHelpRequested,
        onAboutRequested,
        onUserManualRequested,
        onPredictiveBackEnabledChanged,
        communityViewModel,
    ) {
        listOf(
            LegacyProfileAction(
                "体验新版界面",
                "与经典版共用资料库，可随时切换回来",
                AppIcons.Sparkles,
                LegacyPurple,
                "界面 新版 旧版 经典版 切换",
            ) { onFrontendModeSelected(FrontendMode.MODERN) },
            LegacyProfileAction("数据统计", "双布局、指标显隐与看番分布", AppIcons.Statistics, LegacyPurple, "统计 图表 布局 仪表盘 连续打卡 平均评分", onStatisticsRequested),
            LegacyProfileAction("角色管理", "关系与声优星图", AppIcons.Group, Color(0xFFE64A8B), "角色 人物", onCharactersRequested),
            LegacyProfileAction("趣味评级", "S-D 梯队榜分享", AppIcons.Trophy, LegacyOrange, "tier 评级", onTierListRequested),
            LegacyProfileAction("以图搜番", "上传截图精准识别", AppIcons.ImageSearch, LegacyGreen, "识图 搜番", onImageSearchRequested),
            LegacyProfileAction("账号与云同步", "云端备份、多端恢复与账号管理", AppIcons.CloudDone, LegacyBlue, "云 同步 备份", onCloudAccountRequested),
            LegacyProfileAction("编辑个人资料", "修改昵称、个性签名与社区隐私设置", AppIcons.Edit, LegacyBlue, "编辑 个人资料 昵称 签名 隐私") {
                communityViewModel.clearProfileSaveError()
                communityViewModel.refreshSession()
                editProfileRequested = true
            },
            LegacyProfileAction("追番提醒", "管理已设定的开播与更新提醒", AppIcons.Notification, LegacyOrange, "提醒 通知", onReminderManagementRequested),
            LegacyProfileAction("状态、标签与系列", "整理资料库结构、颜色和排序", AppIcons.Settings, LegacyPurple, "状态 标签 系列", onLibraryManagementRequested),
            LegacyProfileAction("外观与经典界面", "主题、布局、封面和动效", AppIcons.Palette, Color(0xFF8E5AD7), "外观 主题 布局", onCustomizationRequested),
            LegacyProfileAction("回收站", "恢复误删作品或永久清理", AppIcons.RestoreTrash, Color(0xFF8D6E63), "回收站 删除", onTrashRequested),
            LegacyProfileAction("番剧查重", "扫描重复作品并安全合并", AppIcons.Merge, Color(0xFF00A6A6), "查重 合并", onDuplicateCleanupRequested),
            LegacyProfileAction("系列书架", "浏览多季作品与书籍系列", AppIcons.Collection, Color(0xFF5C6BC0), "系列 书架", onSeriesShelfRequested),
            LegacyProfileAction("标签索引", "按名称和使用次数浏览标签", AppIcons.Tag, Color(0xFFAB47BC), "标签 索引", onTagIndexRequested),
            LegacyProfileAction("AI 看番风格", "生成隐私友好的偏好报告", AppIcons.Sparkles, Color(0xFFEC407A), "ai 分析", onAnimeAnalysisRequested),
            LegacyProfileAction("角色群组社区", "浏览、分享与导入角色群组", AppIcons.Group, Color(0xFF26A69A), "社区 分享码", onCommunityRequested),
            LegacyProfileAction("同步收藏到 Bangumi", "对比本机与 Bangumi 差异，批量上传进度与评分", AppIcons.CloudDone, Color(0xFF42A5F5), "bangumi bgm 同步 上传 合并 进度", onBangumiUploadRequested),
            LegacyProfileAction("Bangumi 收藏导入", "迁移公开或私密收藏、进度和评价", AppIcons.Storage, Color(0xFF42A5F5), "bangumi bgm 导入", onBangumiImportRequested),
            LegacyProfileAction("《开源阅读》书架同步", "本地备份、WebDAV 或局域网同步小说进度", AppIcons.Book, Color(0xFF8D6E63), "开源阅读 legado 小说 阅读 电子书 txt epub 同步", onLegadoSyncRequested),
            LegacyProfileAction("Excel / CSV 搬家", "表格导入、导出和列映射", AppIcons.Table, Color(0xFF66BB6A), "excel csv", onSpreadsheetTransferRequested),
            LegacyProfileAction("开放反馈池", "查看公开建议并提交反馈", AppIcons.Bug, Color(0xFFFF7043), "反馈 反馈池 建议 bug", onFeedbackPoolRequested),
            LegacyProfileAction("诊断与操作轨迹", "日志、崩溃恢复与脱敏导出", AppIcons.Bug, Color(0xFFEF5350), "诊断 日志 bug", onDiagnosticsRequested),
            LegacyProfileAction("帮助与常见问题", "使用指南、排障与数据安全", AppIcons.Help, Color(0xFF78909C), "帮助 faq", onHelpRequested),
            LegacyProfileAction("关于与维护", "更新、缓存、声明与开源", AppIcons.Info, Color(0xFF7E57C2), "关于 更新", onAboutRequested),
            LegacyProfileAction("用户使用手册", "全部功能索引与每日小贴士", AppIcons.Book, Color(0xFF5C6BC0), "手册 使用 帮助 指南 功能 位置", onUserManualRequested),
            LegacyProfileAction(
                "预测性返回手势",
                "返回时跟随手势预览上一页",
                AppIcons.Settings,
                LegacyBlue,
                "预测性返回 返回手势 动画",
            ) { onPredictiveBackEnabledChanged(!settings.predictiveBackEnabled) },
        )
    }
    val normalized = query.trim().lowercase(java.util.Locale.ROOT)
    val searchResults = if (normalized.isBlank()) emptyList() else actions.filter { action ->
        listOf(action.title, action.subtitle, action.keywords).any { it.lowercase(java.util.Locale.ROOT).contains(normalized) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LegacyPageHeader(
                title = "我的",
                actions = {
                    IconButton(onClick = { searchExpanded = !searchExpanded }) {
                        Icon(AppIcons.Search, contentDescription = "搜索设置")
                    }
                    IconButton(onClick = onOverallStyleRequested) {
                        Icon(AppIcons.Palette, contentDescription = "界面版本与整体风格")
                    }
                },
            )
        }
        if (searchExpanded) item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                placeholder = { Text("搜索相关设置项") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape).background(Color(0xFFD8F4E7)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = "AniMeow",
                        modifier = Modifier.size(80.dp).clip(CircleShape),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("追番喵", style = MaterialTheme.typography.headlineSmall)
                        Text("AniMeow", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (normalized.isNotBlank()) {
            item {
                Text("搜索结果 · ${searchResults.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            }
            searchResults.forEach { action ->
                item(key = "legacy-search-${action.title}") {
                    LegacyProfileListRow(action, Modifier.padding(horizontal = 20.dp))
                }
            }
        } else {
            item {
                Text("快捷中心", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            }
            actions.drop(1).take(4).chunked(2).forEach { rowActions ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowActions.forEach { action ->
                            LegacyActionCard(
                                icon = action.icon,
                                title = action.title,
                                subtitle = action.subtitle,
                                tint = action.tint,
                                onClick = action.onClick,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            item {
                Text("云同步与网络", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            }
            item {
                LegacyProfileGroup(
                    actions.filter { it.title in setOf("账号与云同步", "编辑个人资料", "追番提醒", "角色群组社区") },
                    Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                Text("个性化外观", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onThemeModeSelected(if (settings.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.DarkMode, contentDescription = null, tint = LegacyPurple)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("黑夜模式", fontWeight = FontWeight.Bold)
                            Text(settings.themeMode.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.themeMode == ThemeMode.DARK,
                            onCheckedChange = { enabled -> onThemeModeSelected(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT) },
                        )
                    }
                    LegacyProfileListRow(
                        LegacyProfileAction(
                            "界面版本与整体风格",
                            "${settings.frontendMode.displayName} · ${settings.appStyle.displayName} · ${settings.themeMode.displayName}",
                            AppIcons.Palette,
                            Color(0xFF8E5AD7),
                            "界面 版本 风格",
                            onOverallStyleRequested,
                        ),
                    )
                    LegacyProfileListRow(
                        LegacyProfileAction(
                            "展示与封面布局",
                            "首页布局、封面角标、信息密度与动画",
                            AppIcons.Sparkles,
                            LegacyBlue,
                            "展示 封面 布局",
                            onCustomizationRequested,
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onPredictiveBackEnabledChanged(!settings.predictiveBackEnabled)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Settings, contentDescription = null, tint = LegacyBlue)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("预测性返回手势", fontWeight = FontWeight.Bold)
                            Text(
                                if (settings.predictiveBackEnabled) "已开启，返回时跟随手势预览" else "已关闭，使用传统返回行为；部分系统（如 MIUI/HyperOS）仍由系统控制",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.predictiveBackEnabled,
                            onCheckedChange = onPredictiveBackEnabledChanged,
                        )
                    }
                }
            }
            item {
                Text("应用设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            }
            item {
                LegacyProfileGroup(
                    listOf(
                        LegacyProfileAction(
                            "通用偏好",
                            "默认观看状态、自动归纳与详情行为",
                            AppIcons.Settings,
                            LegacyPurple,
                            "通用 偏好",
                            onAllToolsRequested,
                        ),
                        LegacyProfileAction(
                            "数据管理与备份",
                            "完整备份、导入恢复与自定义列表",
                            AppIcons.Storage,
                            LegacyBlue,
                            "数据 备份 导入",
                            onAllToolsRequested,
                        ),
                    ),
                    Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                Text("关于与帮助", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            }
            item {
                LegacyProfileGroup(
                    actions.filter { it.title in setOf("开放反馈池", "帮助与常见问题", "关于与维护", "诊断与操作轨迹", "用户使用手册") },
                    Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                Text(
                    "陪你记录每一份热爱",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
    if (editProfileRequested && communityState.session != null) {
        val profile = communityState.currentUser
        if (profile != null) {
            CommunityProfileEditorDialog(
                profile = profile,
                localStatistics = communityLocalStatistics,
                token = communityState.session?.token,
                busy = communityState.busy,
                presetImages = communityState.presetImages,
                presetDisclaimer = communityState.presetDisclaimer,
                saveError = communityState.profileSaveError,
                onDismiss = {
                    if (!communityState.busy) {
                        editProfileRequested = false
                        communityViewModel.clearProfileSaveError()
                    }
                },
                onSave = { nickname, signature, privateProfile, showStatistics, selected, avatar, removeAvatar, presetAvatar ->
                    communityViewModel.updateProfile(
                        nickname = nickname,
                        signature = signature,
                        privateProfile = privateProfile,
                        showStatistics = showStatistics,
                        selectedStatistics = selected,
                        avatarUri = avatar,
                        removeAvatar = removeAvatar,
                        presetAvatar = presetAvatar,
                    )
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { editProfileRequested = false },
                title = { Text("正在加载社区资料") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("正在获取你的社区名片…")
                    }
                },
                confirmButton = {},
            )
        }
    }
}

@Composable
private fun LegacyProfileGroup(
    actions: List<LegacyProfileAction>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)),
    ) {
        actions.forEach { action -> LegacyProfileListRow(action) }
    }
}

@Composable
private fun LegacyProfileListRow(
    action: LegacyProfileAction,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = action.onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).background(action.tint.copy(alpha = 0.12f), MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = action.tint)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(action.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                action.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AppIcons.ChevronRight, contentDescription = null)
    }
}
