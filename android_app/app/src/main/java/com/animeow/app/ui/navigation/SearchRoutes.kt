package com.animeow.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.data.preferences.personalizationFields
import com.animeow.app.ui.audio.AudioWorkScreen
import com.animeow.app.ui.cloud.BangumiSyncStatusDialog
import com.animeow.app.ui.games.GameDiscoveryScreen
import com.animeow.app.ui.games.GameScreen
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.IconGlyph
import com.animeow.app.ui.playback.PlaybackActivity
import com.animeow.app.ui.preferences.customizationSection
import com.animeow.app.ui.preferences.customizationSections
import com.animeow.app.ui.screens.DiscoveryDisplaySheet
import com.animeow.app.ui.tracker.rememberEbookImport
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.search.GlobalSearchAction
import com.animeow.app.ui.search.GlobalSearchScreen
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.animeow.app.ui.navigation.appComposable as composable
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.searchRoutes(navController: NavHostController, settings: AppearanceSettings) {
    searchMediaRoutes(navController, settings)
    composable(
        "$GLOBAL_SEARCH_ROUTE?scope={scope}",
        arguments = listOf(navArgument("scope") { type = NavType.StringType; defaultValue = "all" }),
    ) {
        val context = LocalContext.current
        val importEbook = rememberEbookImport { navController.navigate(it) }
        var showBangumiSync by rememberSaveable { mutableStateOf(false) }
        fun page(title: String, subtitle: String, keywords: String, icon: IconGlyph, route: String) =
            GlobalSearchAction(title, subtitle, keywords, icon) { navController.navigate(route) }
        fun openPageAction(action: PageAction) {
            navController.navigate(action.destination.pageRoute)
            navController.requestPageAction(action)
        }
        GlobalSearchScreen(
            onBack = navController::navigateUp,
            onAnimeClick = { navController.navigate("anime/$it") },
            onSeriesClick = { navController.navigate("series/$it") },
            onTagClick = { navController.navigate("tags/$it") },
            onCharacterClick = { navController.navigate("$CHARACTERS_ROUTE?characterId=$it") },
            onGroupClick = { navController.navigate("$CHARACTER_GROUPS_ROUTE?groupId=$it") },
            onStatusClick = { navController.navigate("statistics/status/${Uri.encode(it)}") },
            onGameClick = { navController.navigate("$GAME_COLLECTION_ROUTE?id=$it") },
            onAudioClick = { navController.navigate("$AUDIO_COLLECTION_ROUTE?id=$it") },
            onEpisodeClick = { id, episode -> navController.navigate("anime/$id/episodes?episode=$episode") },
            onManualTopicClick = { navController.navigate("$USER_MANUAL_ROUTE?query=${Uri.encode(it)}") },
            onGameDiscoveryClick = { navController.navigate("$GAME_DISCOVERY_ROUTE?query=${Uri.encode(it.trim())}") },
            actions = listOf(
                GlobalSearchAction(
                    "文字大小", "外观与阅读 → 字体与阅读 → 文字大小",
                    "字体 字号 字太小 字太大 字体大小 字体缩放 文字大小", AppIcons.EditNote,
                ) { _ -> navController.navigate(customizationRoute(query = "文字大小", section = "size")) },
                GlobalSearchAction(
                    "操作与反馈", "进度操作、滑动手势、返回退出与触觉音效",
                    "操作 进度 手势 滑动 触觉 声音 音效 +1 合并 长按", AppIcons.Settings,
                ) { query -> navController.navigate(customizationRoute(query = query, section = "behavior")) },
                GlobalSearchAction(
                    "底栏背景不透明度", "外观与阅读 → 页面与卡片 → 底栏外形",
                    "底栏透明度 底栏不透明度 导航透明度 背景不透明度", AppIcons.Visibility,
                ) { _ -> navController.navigate(customizationRoute(query = "底栏背景不透明度", section = "nav")) },
                GlobalSearchAction(
                    "详情进度操作", "设置 → 操作与反馈 → 详情进度操作",
                    "进度操作 合并操作 独立按钮 长按加一", AppIcons.Settings,
                ) { _ -> navController.navigate(customizationRoute(query = "详情进度操作", section = "behavior")) },
                GlobalSearchAction(
                    "外观与阅读",
                    "主题、字体、页面、卡片、视觉动效与个性化配置",
                    "外观 布局 主题 深色 miuix 字体 圆角 动画 手势 配置 启动 图标",
                    AppIcons.Palette,
                ) { query -> navController.navigate(customizationRoute(query)) },
                GlobalSearchAction(
                    "外观细调",
                    "先调常用项，再按页面、卡片、底栏等对象细调",
                    "简洁 全部 高级 进阶 硬核 设置太多 精简 显示全部 模式 深度 自定义",
                    AppIcons.SettingsDepth,
                ) { _ -> navController.navigate(customizationRoute("")) },
                GlobalSearchAction(
                    "预测性返回手势",
                    if (settings.predictiveBackEnabled) "当前已开启；前往“设置”可随时关闭" else "当前已关闭；前往“设置”可选择开启",
                    "预测性返回 返回手势 动画 安卓 手势 back",
                    AppIcons.Settings,
                ) { _ -> navController.navigate(customizationRoute(query = "预测性返回", section = "behavior")) },
                GlobalSearchAction(
                    "状态、标签与系列管理",
                    "新增、配色、排序、迁移和安全删除",
                    "状态 标签 系列 管理 颜色 排序",
                    AppIcons.Settings,
                ) { _ -> navController.navigate(LIBRARY_MANAGEMENT_ROUTE) },
                GlobalSearchAction(
                    "追番提醒管理",
                    "权限检查、任务同步、测试通知与集中编辑",
                    "提醒 通知 权限 闹钟 时间",
                    AppIcons.Notification,
                ) { _ -> navController.navigate(REMINDER_MANAGEMENT_ROUTE) },
                GlobalSearchAction(
                    "桌面小组件",
                    "预览继续记录组件，并添加到手机桌面",
                    "桌面 小组件 组件 widget 继续记录 快捷 +1",
                    AppIcons.Widgets,
                ) { _ -> navController.navigate("$SETTINGS_ROUTE?section=widget") },
                GlobalSearchAction(
                    "悬浮球外观",
                    "奶油猫团、月光琉璃、静音猫爪；默认静音猫爪",
                    "悬浮球 悬浮窗 悬浮记录 贴边 奶油猫团 月光琉璃 静音猫爪",
                    AppIcons.Widgets,
                ) { _ -> navController.navigate(customizationRoute(section = "visual")) },
                GlobalSearchAction(
                    "数据统计与 AI 分析",
                    "双布局、指标显隐、状态、评分、标签、活动图表和看番风格",
                    "统计 图表 ai 分析 评分 标签 时长 布局 仪表盘 连续打卡 平均评分",
                    AppIcons.Statistics,
                ) { _ -> navController.navigate(AppDestination.STATISTICS.pageRoute) },
                GlobalSearchAction(
                    "云账号与设备备份",
                    "登录、修改密码、退出账号、同步与跨设备恢复",
                    "云端 账号 登录 注册 密码 退出账号 注销 同步 备份 恢复",
                    AppIcons.CloudDone,
                ) { _ -> navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                GlobalSearchAction(
                    "导入原版与完整备份",
                    "兼容原版 ZIP / SQLite，并支持原生完整备份",
                    "原版 旧版 数据库 sqlite zip 迁移 导出完整备份 恢复原生备份",
                    AppIcons.Storage,
                ) { _ -> navController.navigate("$PROFILE_TOOLS_ROUTE?section=data") },
                GlobalSearchAction(
                    "Excel / CSV 搬家",
                    "自定义列映射、预检、批量导入与导出",
                    "excel csv xlsx 表格 导入 导出",
                    AppIcons.Table,
                ) { _ -> navController.navigate(SPREADSHEET_TRANSFER_ROUTE) },
                GlobalSearchAction(
                    "同步本机收藏到 Bangumi",
                    "对比本机与 Bangumi 收藏差异，批量上传进度、状态与评分",
                    "bangumi bgm 同步 上传 导出 合并 进度 评分 收藏",
                    AppIcons.CloudDone,
                ) { _ -> navController.navigate(BANGUMI_UPLOAD_ROUTE) },
                GlobalSearchAction(
                    "导入 Bangumi 收藏",
                    "拉回 Bangumi 公开或私密收藏并安全合并",
                    "bangumi bgm 收藏 导入 用户名 uid",
                    AppIcons.CloudDownload,
                ) { _ -> navController.navigate(BANGUMI_IMPORT_ROUTE) },
                GlobalSearchAction(
                    "《开源阅读》书架同步",
                    "支持本地备份、WebDAV 与局域网服务同步小说进度",
                    "开源阅读 legado 小说 阅读 电子书 txt epub 书架 webdav 同步 导入",
                    AppIcons.Book,
                ) { _ -> navController.navigate(LEGADO_SYNC_ROUTE) },
                GlobalSearchAction(
                    "社区",
                    "群聊、图文交流与个人统计名片",
                    "登录社区 群聊 帖子 图片 名片 举报 群码",
                    AppIcons.Group,
                ) { _ -> navController.navigate(AppDestination.COMMUNITY.pageRoute) },
                GlobalSearchAction(
                    "互动消息",
                    "查看谁点赞、评论、回复或收藏了你的内容",
                    "社区 消息 通知 点赞 评论 回复 收藏 未读",
                    AppIcons.Notification,
                ) { _ -> navController.navigate(COMMUNITY_INBOX_ROUTE) },
                GlobalSearchAction(
                    "角色群组分享工具",
                    "浏览公开角色组、分享码预览、导入与发布管理",
                    "角色 群组 分享码 导入 发布 公开",
                    AppIcons.Group,
                ) { _ -> navController.navigate(CHARACTER_COMMUNITY_ROUTE) },
                GlobalSearchAction(
                    "诊断与操作轨迹",
                    "崩溃恢复、错误堆栈、脱敏复制与导出",
                    "诊断 日志 崩溃 bug 错误 隐私",
                    AppIcons.Bug,
                ) { _ -> navController.navigate(DIAGNOSTICS_ROUTE) },
                GlobalSearchAction(
                    "开放反馈池",
                    "公开浏览建议、问题与功能想法；登录后可提交反馈",
                    "反馈 反馈池 建议 bug 意见 功能 公开",
                    AppIcons.Bug,
                ) { _ -> navController.navigate(FEEDBACK_POOL_ROUTE) },
                GlobalSearchAction(
                    "帮助与常见问题",
                    "使用指南、排障和数据安全说明",
                    "帮助 faq 常见问题 闪退 封面 提醒 备份",
                    AppIcons.Help,
                ) { _ -> navController.navigate(HELP_ROUTE) },
                GlobalSearchAction(
                    "关于与维护",
                    "更新、缓存、更新日志、声明、鸣谢与开源渠道",
                    "关于 更新 缓存 日志 声明 鸣谢 开源 版本",
                    AppIcons.Info,
                ) { _ -> navController.navigate(ABOUT_ROUTE) },
                GlobalSearchAction(
                    "用户使用手册",
                    "全部功能索引、设置位置与每日小贴士",
                    "手册 使用 帮助 指南 教程 说明 功能 位置 每日 提示 找不到",
                    AppIcons.Book,
                ) { _ -> navController.navigate(USER_MANUAL_ROUTE) },
                GlobalSearchAction(
                    "深色模式",
                    if (settings.themeMode == ThemeMode.DARK) "已固定深色护眼主题" else "当前明亮或跟随系统",
                    "黑夜 深色 暗色 主题 模式 护眼 夜间",
                    AppIcons.DarkMode,
                ) { _ -> navController.navigate(customizationRoute(section = "visual")) },
                GlobalSearchAction(
                    "显示发现/日历/社区/统计",
                    "控制底部导航入口的可见性",
                    "显示 发现 日历 社区 统计 入口 导航 隐藏 底部",
                    AppIcons.Discover,
                ) { _ -> navController.navigate(customizationRoute(section = "navigation")) },
                GlobalSearchAction(
                    "导航顺序与启动落点",
                    "调整底部导航排列顺序和启动首页",
                    "导航 顺序 启动 落点 首页 排列 底部",
                    AppIcons.SwapVertical,
                ) { _ -> navController.navigate(customizationRoute(section = "navigation")) },
                GlobalSearchAction(
                    "导航栏样式",
                    "底栏外观、标签、尺寸与悬浮间距",
                    "导航 底栏 样式 胶囊 浮岛 极简 标签 图标 高度",
                    AppIcons.Tune,
                ) { _ -> navController.navigate(customizationRoute(section = "nav")) },
                GlobalSearchAction(
                    "经典模式",
                    "切换经典或现代界面，保留资料和账号",
                    "经典 原版 旧版 现代 模式 界面 切换",
                    AppIcons.SwapVertical,
                ) { _ -> navController.navigate("$SETTINGS_ROUTE?section=help") },
                GlobalSearchAction(
                    "社区样式",
                    "在信息流与图片瀑布流之间切换社区浏览方式",
                    "社区 样式 浏览 信息流 瀑布流 切换 帖子 列表",
                    AppIcons.Grid,
                ) { _ -> navController.navigate("$SETTINGS_ROUTE?section=community") },
                GlobalSearchAction(
                    "在我的页面显示社区动态",
                    "显示或隐藏帖子、点赞和收藏记录",
                    "我的 个人中心 社区动态 帖子 点赞 收藏 隐藏 显示",
                    AppIcons.Profile,
                ) { _ -> navController.navigate("$SETTINGS_ROUTE?section=community") },
                GlobalSearchAction(
                    "返回退出方式",
                    "双击退出、弹窗确认或直接退出",
                    "返回 退出 双击 弹窗 直接 退出方式",
                    AppIcons.Settings,
                ) { _ -> navController.navigate(customizationRoute(query = "返回退出方式", section = "behavior")) },
                GlobalSearchAction(
                    "显示版本号",
                    "在「我的」页面底部显示当前应用版本",
                    "版本 版本号 显示 底部",
                    AppIcons.Hash,
                ) { _ -> navController.navigate("$SETTINGS_ROUTE?section=help") },
                page("回收站", "查看与恢复已删除作品", "找回 误删 删除 恢复", AppIcons.RestoreTrash, TRASH_ROUTE),
                page("查重与合并", "整理重复作品与资料", "去重 同名 重复 数据 合并", AppIcons.Sync, DUPLICATES_ROUTE),
                page("系列书架", "查看动画、书籍、游戏和音声的系列", "同系列 归组 分组 关联", AppIcons.Book, SERIES_SHELF_ROUTE),
                page("标签索引", "多选查找或批量管理标签", "分类 标签联合查找 同时包含 屏蔽", AppIcons.Tag, TAG_INDEX_ROUTE),
                page("角色管理与关系星图", "查找角色、配音、作品及角色关系", "人物 声优 关系图 星图", AppIcons.Group, CHARACTERS_ROUTE),
                page("角色组管理", "整理角色组与组内作品", "群组 角色小组 分组", AppIcons.Group, CHARACTER_GROUPS_ROUTE),
                page("以图搜番", "用截图识别动画及集数", "识图 图片 截图 画面 反向搜索", AppIcons.ImageSearch, IMAGE_SEARCH_ROUTE),
                page("AI 看番风格", "查看追番分析和历史报告", "人工智能 AI 追番统计 报告 偏好", AppIcons.Statistics, ANIME_ANALYSIS_ROUTE),
                page("趣味评级 Tier List", "对作品分档并分享排行", "等级 排行 梯度 Tier 评级", AppIcons.Statistics, TIER_LIST_ROUTE),
                page("追番日历", "按日、周、月查看排期与阅读足迹", "放送 更新 播出 时间表 议程", AppIcons.Calendar, AppDestination.CALENDAR.pageRoute),
                page("发现", "浏览在线动画、书籍和游戏", "今日放送 新番总览 评分榜 排行榜", AppIcons.Discover, AppDestination.DISCOVERY.pageRoute),
                page("游戏收藏", "查看与管理游戏记录", "游戏库 在玩 游玩 Galgame VNDB", AppIcons.Tracker, GAME_COLLECTION_ROUTE),
                page("音声收藏", "查看与管理收听记录", "音声库 ASMR 广播剧 DLsite RJ 在听", AppIcons.Book, AUDIO_COLLECTION_ROUTE),
                page("在线游戏", "搜索公共游戏资料", "游戏发现 游戏热榜 Galgame VNDB", AppIcons.Discover, GAME_DISCOVERY_ROUTE),
                page("手动创建动画", "填写作品资料和观看进度", "手动添加 新建 动画 番剧", AppIcons.Add, EDITOR_ROUTE),
                page("创建书籍", "手动添加小说、轻小说或漫画", "新建 手动添加 书籍 小说 阅读", AppIcons.Book, BOOK_EDITOR_ROUTE),
                page("缓存管理", "查看存储占用与清理缓存", "清理 空间 电子书插图 图片 缓冲", AppIcons.Storage, CACHE_ROUTE),
                page("更新日志", "查看版本改动", "版本 新功能 更新记录 changelog", AppIcons.History, CHANGELOG_ROUTE),
                page("免责声明", "阅读数据与第三方内容说明", "版权 法律声明 使用条款", AppIcons.Info, DISCLAIMER_ROUTE),
                page("鸣谢与支持", "查看赞助鸣谢及支持方式", "赞助 爱发电 捐助 支持作者", AppIcons.Info, CREDITS_ROUTE),
                page("屏蔽管理", "查看或取消已屏蔽的用户", "黑名单 社区 拉黑 取消屏蔽", AppIcons.Settings, COMMUNITY_BLOCKLIST_ROUTE),
                page("消息与公告", "查看服务通知与公告", "系统消息 官方公告 通知", AppIcons.Notification, ANNOUNCEMENTS_ROUTE),
                page("个人中心", "查看个人资料和活动", "个人主页 头像 签名 我的帖子", AppIcons.Profile, PERSONAL_CENTER_ROUTE),
                page("Bangumi 同步设置", "连接账号、选择自动同步和访问方式", "bgm 绑定 授权 收藏 双向合并 直连 中继", AppIcons.Sync, CLOUD_ACCOUNT_ROUTE),
                page("桌面图标与启动封面", "选择桌面图标和启动画面", "启动图标 启动画面 品牌 app icon", AppIcons.Palette, BRANDING_ROUTE),
                GlobalSearchAction("导入本地电子书", "从手机选择 TXT 或 EPUB", "本地小说 轻小说 电子书 TXT EPUB 阅读", AppIcons.FileOpen) { importEbook() },
                GlobalSearchAction("播放历史", "查看最近观看、继续播放或重播", "观看记录 断点 续播 历史记录", AppIcons.History) { context.startActivity(PlaybackActivity.history(context)) },
                GlobalSearchAction("动画离线缓存", "查看下载任务与已缓存集数", "离线 下载 缓存集数 视频下载", AppIcons.Download) { context.startActivity(PlaybackActivity.downloads(context)) },
                GlobalSearchAction("Bangumi 同步状态", "查看同步队列和失败原因，或立即重试", "bgm 自动同步 进度 评分 队列 在首页显示同步状态 显示Bangumi同步状态", AppIcons.Sync) { showBangumiSync = true },
                GlobalSearchAction("自定义发现页", "设置列表、网格与结果密度", "紧凑信息列表 紧凑封面网格 发现显示 搜索结果 预设", AppIcons.Tune) { openPageAction(PageAction.CUSTOMIZE_DISCOVERY) },
                GlobalSearchAction("定制追番日历页", "设置日历视图与显示样式", "日历设置 周视图 月视图 议程 封面", AppIcons.Tune) { openPageAction(PageAction.CUSTOMIZE_CALENDAR) },
            ),
            indexedActions = customizationSearchActions { navController.navigate(it) },
        )
        if (showBangumiSync) BangumiSyncStatusDialog(onDismiss = { showBangumiSync = false })
    }
}

private fun customizationSearchActions(navigate: (String) -> Unit): List<GlobalSearchAction> {
    val sections = customizationSections.map { section ->
        GlobalSearchAction(section.title, section.summary, section.keywords.joinToString(" "), AppIcons.Settings) {
            navigate(customizationRoute(section = section.key))
        }
    }
    val fields = personalizationFields.values.flatten().mapNotNull { field ->
        val section = customizationSection(field.searchSection) ?: return@mapNotNull null
        val control = field.control
        val title = control?.title ?: field.label
        val path = if (section.key in setOf("behavior", "navigation")) "设置" else "外观与阅读"
        GlobalSearchAction(title, "$path → ${section.title}", control?.aliases.orEmpty().joinToString(" "), AppIcons.Settings) {
            navigate(customizationRoute(query = control?.aliases?.firstOrNull() ?: field.label, section = section.key))
        }
    }
    return sections + fields
}

private fun NavGraphBuilder.searchMediaRoutes(navController: NavHostController, settings: AppearanceSettings) {
    val mediaId = navArgument("id") { type = NavType.LongType; defaultValue = 0L }
    val openSeries: (Long) -> Unit = { navController.navigate("series/$it") }
    composable("$GAME_COLLECTION_ROUTE?id={id}", arguments = listOf(mediaId)) { entry ->
        SearchMediaPage("游戏收藏", navController::navigateUp) { modifier ->
            GameScreen(settings, modifier, onSeriesOpen = openSeries, initialGameId = entry.arguments?.getLong("id") ?: 0L)
        }
    }
    composable("$AUDIO_COLLECTION_ROUTE?id={id}", arguments = listOf(mediaId)) { entry ->
        SearchMediaPage("音声收藏", navController::navigateUp) { modifier ->
            AudioWorkScreen(modifier, onSeriesOpen = openSeries, initialWorkId = entry.arguments?.getLong("id") ?: 0L)
        }
    }
    composable("$GAME_DISCOVERY_ROUTE?query={query}", arguments = listOf(navArgument("query") { type = NavType.StringType; defaultValue = "" })) { entry ->
        val context = LocalContext.current
        val preferences = remember(context) { DiscoveryPreferences(context) }
        val settings by preferences.settings.collectAsStateWithLifecycle(DiscoveryNetworkSettings())
        val scope = rememberCoroutineScope()
        var showDisplay by rememberSaveable { mutableStateOf(false) }
        SearchMediaPage("在线游戏", navController::navigateUp) { modifier ->
            GameDiscoveryScreen(settings.display, onDisplaySettings = { showDisplay = true }, modifier = modifier,
                onGlobalSearchRequested = navController::navigateUp, initialQuery = entry.arguments?.getString("query").orEmpty())
        }
        if (showDisplay) DiscoveryDisplaySheet(settings.display, onSave = { display ->
            scope.launch {
                runCatchingCancellable { preferences.saveDisplay(display) }.onFailure {
                    android.widget.Toast.makeText(context, "保存显示设置失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }, onDismiss = { showDisplay = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchMediaPage(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) { AppIcon(AppIcons.Back, contentDescription = "返回") }
        })
    }) { padding -> content(Modifier.padding(padding)) }
}
