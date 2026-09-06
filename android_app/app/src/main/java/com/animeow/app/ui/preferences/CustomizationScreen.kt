package com.animeow.app.ui.preferences

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BrandingWatermark
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.preferences.ConfigurationProfile
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.components.CustomizableColorSelector
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.pageSwitchEnterTransition
import com.animeow.app.ui.components.pageSwitchExitTransition
import com.animeow.app.ui.components.scaledMotionDurationMillis
import com.animeow.app.ui.theme.APP_FONT_SCALE_MAX
import com.animeow.app.ui.theme.APP_FONT_SCALE_MIN
import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.CalendarLayoutPreset
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.NavigationLabelMode
import com.animeow.app.ui.theme.isNavigationDestinationVisible
import com.animeow.app.ui.navigation.AppDestination
import com.animeow.app.ui.navigation.FloatingNavigationBar
import com.animeow.app.ui.components.StatusBadgeControls
import com.animeow.app.ui.components.WatchStatusBadge
import com.animeow.app.ui.theme.PageTransitionStyle
import com.animeow.app.ui.theme.ProfileSearchStyle
import com.animeow.app.ui.theme.RatingIconStyle
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.canSetNavigationDestinationVisible
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private object CustomizationScrollPosition {
    var firstVisibleItemIndex: Int = 0
    var firstVisibleItemScrollOffset: Int = 0
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomizationScreen(
    onBack: () -> Unit,
    onBrandingRequested: () -> Unit,
    initialQuery: String = "",
    modifier: Modifier = Modifier,
    viewModel: CustomizationViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trackerSettings by viewModel.trackerSettings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var profileName by rememberSaveable { mutableStateOf("") }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var fontDraft by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale) }
    var cornerDraft by remember(settings.cornerScale) { mutableFloatStateOf(settings.cornerScale) }
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = CustomizationScrollPosition.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = CustomizationScrollPosition.firstVisibleItemScrollOffset,
        )
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { (index, offset) ->
            CustomizationScrollPosition.firstVisibleItemIndex = index
            CustomizationScrollPosition.firstVisibleItemScrollOffset = offset
        }
    }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importProfile)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportCurrent(it, profileName.ifBlank { "我的配置" }) }
    }

    val sections = remember {
        listOf(
            "preview" to "首页预览",
            "visual" to "视觉",
            "layout" to "布局",
            "size" to "尺寸",
            "card" to "卡片",
            "cover" to "封面",
            "motion" to "动效",
            "nav" to "导航",
            "brand" to "品牌",
            "profile" to "配置",
        )
    }

    LaunchedEffect(Unit) { viewModel.beginDraft() }
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    fun requestExit() {
        if (uiState.busy) return
        if (uiState.hasChanges) showDiscardDialog = true else onBack()
    }

    BackHandler(onBack = ::requestExit)

    fun matches(vararg terms: String): Boolean {
        val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return tokens.isEmpty() || tokens.any { token ->
            terms.any { term ->
                term.contains(token, ignoreCase = true) || token.contains(term, ignoreCase = true)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("打造我的 AniMeow") },
                navigationIcon = {
                    IconButton(onClick = ::requestExit, enabled = !uiState.busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "取消并返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = uiState.canUndo && !uiState.busy) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "撤销")
                    }
                    TextButton(onClick = { viewModel.commit(onBack) }, enabled = !uiState.busy) { Text("应用") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    label = { Text("搜索颜色、布局、手势、日历或配置") },
                    singleLine = true,
                )
            }

            item(key = "section-chips") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sections) { (key, label) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val index = sections.indexOfFirst { it.first == key }
                                if (index >= 0) {
                                    // +2 to account for search and chips items
                                    scope.launch { listState.animateScrollToItem(index + 2) }
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            item(key = "preview") {
                HomePagePreview(settings, trackerSettings)
            }

            if (matches("风格", "主题", "颜色", "强调色", "动态色", "随机", "MIUIX", "浅色", "深色", "暗色", "系统动态色", "壁纸", "随机强调色", "自定义", "色板", "换色", "套件", "预览", "二次元", "赛博", "复古", "像素", "护眼")) item(key = "visual") {
                SettingSection("视觉套件", "默认 MIUIX；视觉变化不会更换功能语义") {
                    ChoiceFlow(AppStyle.entries, settings.appStyle, { it.displayName }, viewModel::setAppStyle)
                    ChoiceFlow(ThemeMode.entries, settings.themeMode, { it.displayName }, viewModel::setThemeMode)
                    ToggleRow("使用系统动态色", "Android 12+ 从壁纸取色，关闭时使用当前风格配色", settings.useDynamicColor, viewModel::setDynamicColor)
                    ToggleRow(
                        "每次启动随机强调色",
                        "从经过可读性筛选的色板中换色，不改变布局与功能配置",
                        settings.randomAccentOnLaunch,
                        viewModel::setRandomAccentOnLaunch,
                    )
                    Text("强调色", style = MaterialTheme.typography.labelLarge)
                    CustomizableColorSelector(
                        color = settings.accentColor ?: DEFAULT_CUSTOM_ACCENT,
                        presets = CUSTOM_ACCENT_PRESETS,
                        onColorChanged = viewModel::setAccentColor,
                        modifier = Modifier.fillMaxWidth(),
                        dialogTitle = "自定义 AniMeow 强调色",
                    )
                    FilledTonalButton(
                        onClick = viewModel::randomizeAccent,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("立即换一个强调色") }
                    VisualSuitePreview(settings)
                }
            }

            if (matches("首页", "详情", "布局", "密度", "列数", "画幅", "材质", "玻璃", "描边", "顶部", "间距", "时间线", "分组", "海报", "方形", "宽幅", "经典", "卡片", "tonal", "outlined", "glass", "舒适", "紧凑", "网格", "缩放", "栏目", "宽松", "信息密度")) item(key = "layout") {
                SettingSection("布局与密度", "页面布局与视觉套件相互独立") {
                    Text("首页布局", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(HomeLayout.entries, settings.homeLayout, { it.displayName }, viewModel::setHomeLayout)
                    if (settings.homeLayout == HomeLayout.TIME_LINE) {
                        Text("时间线分组依据", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(
                            values = com.animeow.app.ui.theme.TimelineGroupField.entries,
                            selected = trackerSettings.timelineGroupField,
                            label = { it.displayName },
                            onSelected = viewModel::setTimelineGroupField,
                        )
                        Text(
                            trackerSettings.timelineGroupField.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("封面画幅", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(CoverAspectRatio.entries, settings.coverAspectRatio, { it.displayName }, viewModel::setCoverAspectRatio)
                    Text("详情布局", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(DetailLayout.entries, settings.detailLayout, { it.displayName }, viewModel::setDetailLayout)
                    Text("详情卡片材质", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(DetailCardStyle.entries, settings.detailCardStyle, { it.displayName }, viewModel::setDetailCardStyle)
                    Text(
                        settings.detailCardStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("信息密度", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(
                        values = ContentDensity.entries,
                        selected = settings.contentDensity,
                        label = { it.displayName },
                        onSelected = viewModel::setDensity,
                    )
                    Text(
                        settings.contentDensity.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("栏目间距", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(
                        values = com.animeow.app.ui.theme.SectionSpacing.entries,
                        selected = settings.sectionSpacing,
                        label = { it.displayName },
                        onSelected = viewModel::setSectionSpacing,
                    )
                    Text(
                        settings.sectionSpacing.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("顶部间距", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(
                        values = com.animeow.app.ui.theme.TopBarSpacing.entries,
                        selected = settings.topBarSpacing,
                        label = { it.displayName },
                        onSelected = viewModel::setTopBarSpacing,
                    )
                    Text(
                        settings.topBarSpacing.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("网格列数：${settings.gridColumns}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = settings.gridColumns.toFloat(),
                        onValueChange = { viewModel.setGridColumns(it.roundToInt()) },
                        valueRange = 2f..6f,
                        steps = 3,
                    )
                    Text(
                        "海报墙支持双指缩放快速调整列数，范围同样限制为 2–6 列。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LayoutDensityPreview(settings)
                    DetailLayoutAndCardPreview(settings)
                }
            }

            if (matches("字体", "圆角", "大小", "缩放", "滑块", "百分比")) item(key = "size") {
                SettingSection("尺寸", "所有值都有可读性和触控安全边界") {
                    Text("字体缩放 ${(fontDraft * 100).roundToInt()}%")
                    Slider(
                        value = fontDraft,
                        onValueChange = { fontDraft = it },
                        onValueChangeFinished = { viewModel.setFontScale(fontDraft) },
                        valueRange = APP_FONT_SCALE_MIN..APP_FONT_SCALE_MAX,
                    )
                    Text("圆角强度 ${(cornerDraft * 100).roundToInt()}%")
                    Slider(
                        value = cornerDraft,
                        onValueChange = { cornerDraft = it },
                        onValueChangeFinished = { viewModel.setCornerScale(cornerDraft) },
                        valueRange = 0.5f..1.5f,
                    )
                    SizePreview(fontDraft = fontDraft, cornerDraft = cornerDraft)
                }
            }

            if (matches("卡片", "角标", "评分", "标题", "进度", "状态", "填充", "封面", "位置", "数量", "显示标题", "显示状态", "状态填充色", "状态标签", "显示评分", "显示进度", "评分图标", "评分底色", "橙色", "黑框白字", "主题色", "半透明", "深色", "自定义颜色", "ARGB", "封面标题", "爪印", "星标", "数字")) item(key = "card") {
                SettingSection("卡片信息", "同一设置会在支持的首页布局中保持一致") {
                    ToggleRow("显示标题", "隐藏后仍可通过封面与详情识别作品", settings.showTitle, viewModel::setShowTitle)
                    ToggleRow("显示状态", "在卡片上展示当前观看状态", settings.showStatus, viewModel::setShowStatus)
                    ToggleRow(
                        "状态填充色",
                        "开启后状态文字带半透明背景色，更醒目；关闭则仅显示彩色文字",
                        settings.statusChipFilled,
                        viewModel::setStatusChipFilled,
                    )
                    ToggleRow("显示评分", "展示本地或在线评分", settings.showRating, viewModel::setShowRating)
                    StatusBadgeControls(trackerSettings, viewModel::setStatusBadgeBackground, viewModel::setStatusBadgeCustomColor)
                    ToggleRow("显示进度", "展示已看/总集数", settings.showProgress, viewModel::setShowProgress)
                    ToggleRow(
                        "状态标签显示数量",
                        "在全部、看完、在看等状态标签旁显示对应番剧数量",
                        trackerSettings.showStatusCount,
                        viewModel::setShowStatusCount,
                    )
                    Text("角标样式", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(CoverBadgeStyle.entries, settings.coverBadgeStyle, { it.displayName }, viewModel::setBadgeStyle)
                    Text("评分图标", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(RatingIconStyle.entries, settings.ratingIconStyle, { "${it.symbol} ${it.displayName}".trim() }, viewModel::setRatingIcon)
                    Text("评分底色", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(RatingBadgeColorStyle.entries, settings.ratingBadgeColorStyle, { it.displayName }, viewModel::setRatingBadgeColor)
                    if (settings.ratingBadgeColorStyle == RatingBadgeColorStyle.CUSTOM) {
                        var showColorPicker by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(settings.ratingBadgeCustomColor)),
                            )
                            Column(Modifier.weight(1f)) {
                                Text("自定义颜色", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    "ARGB: %08X".format(settings.ratingBadgeCustomColor.toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showColorPicker = true }) { Text("调整") }
                        }
                        if (showColorPicker) {
                            ColorPickerDialog(
                                initialColor = settings.ratingBadgeCustomColor,
                                onDismiss = { showColorPicker = false },
                                onColorSelected = { color ->
                                    viewModel.setRatingBadgeCustomColor(color)
                                    showColorPicker = false
                                },
                            )
                        }
                    }
                    Text("封面标题位置", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(CoverTitlePosition.entries, settings.coverTitlePosition, { it.displayName }, viewModel::setTitlePosition)
                    CardInfoPreview(settings, trackerSettings)
                }
            }

            if (matches("封面", "透明度", "文字", "黑框", "白框", "信息框", "饱和度", "图片", "黑色信息框", "白色信息框", "封面图像", "预览", "信息层")) item(key = "cover") {
                SettingSection("封面与信息层", "文字、深浅信息框和封面图像分别调整，修改会在下方立即显示") {
                    OpacitySlider("文字透明度", trackerSettings.infoTextOpacity, viewModel::setTrackerInfoTextOpacity)
                    OpacitySlider("黑色信息框透明度", trackerSettings.infoDarkBackgroundOpacity, viewModel::setTrackerDarkBackgroundOpacity, 0f)
                    OpacitySlider("白色信息框透明度", trackerSettings.infoLightBackgroundOpacity, viewModel::setTrackerLightBackgroundOpacity, 0f)
                    OpacitySlider("封面图像透明度", trackerSettings.coverImageOpacity, viewModel::setTrackerCoverImageOpacity)
                    Text("封面饱和度 ${(trackerSettings.coverSaturation * 100).roundToInt()}%")
                    Slider(
                        value = trackerSettings.coverSaturation,
                        onValueChange = viewModel::setTrackerCoverSaturation,
                        valueRange = 0f..1.5f,
                        steps = 14,
                    )
                    CardInfoPreview(settings, trackerSettings)
                }
            }

            if (matches("动效", "动画", "过渡", "切换", "视差", "淡化", "触觉", "声音", "手势", "滑动", "动效强度", "转场", "refined", "parallax", "card", "soft", "fade", "触觉反馈", "操作音效", "卡片滑动", "快捷操作", "右滑", "左滑", "返回退出", "双击", "弹窗", "直接退出", "编辑", "标记", "收藏", "删除", "在看")) item(key = "motion") {
                SettingSection("动效与交互", "动画风格与强度可分别调整；关闭手势后所有功能仍有按钮入口") {
                    Text("动效强度", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(MotionLevel.entries, settings.motionLevel, { it.displayName }, viewModel::setMotion)
                    PageTransitionChooser(
                        selected = settings.pageTransitionStyle,
                        motionLevel = settings.motionLevel,
                        onSelected = viewModel::setPageTransitionStyle,
                    )
                    ToggleRow("触觉反馈", "打卡、拖动与滑动操作提供轻触觉", settings.hapticFeedback, viewModel::setHaptic)
                    ToggleRow("操作音效", "使用系统轻量点击音，不播放连续音效", settings.soundFeedback, viewModel::setSound)
                    ToggleRow(
                        "启用卡片滑动快捷操作",
                        "默认关闭，避免切换主导航时误加集数或改变状态",
                        settings.cardSwipeActionsEnabled,
                        viewModel::setCardSwipeActionsEnabled,
                    )
                    Text("右滑动作", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(
                        SwipeAction.entries,
                        settings.swipeStartAction,
                        { it.displayName },
                        viewModel::setSwipeStart,
                        enabled = settings.cardSwipeActionsEnabled,
                    )
                    Text("左滑动作", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(
                        SwipeAction.entries,
                        settings.swipeEndAction,
                        { it.displayName },
                        viewModel::setSwipeEnd,
                        enabled = settings.cardSwipeActionsEnabled,
                    )
                    Text("返回退出方式", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(
                        values = com.animeow.app.ui.theme.ExitBehavior.entries,
                        selected = settings.exitBehavior,
                        label = { it.displayName },
                        onSelected = viewModel::setExitBehavior,
                    )
                    Text(
                        settings.exitBehavior.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (matches("导航", "发现", "日历", "社区", "统计", "搜索入口", "我的", "月视图", "周视图", "议程", "剪贴板", "显示发现", "显示日历", "显示社区", "显示统计", "剪贴板分享码", "底部导航", "悬浮", "胶囊", "边距", "留白", "圆角", "阴影", "浮动", "经典底栏", "搜索")) item(key = "nav") {
                SettingSection("导航与日历", "主导航 2–5 个入口，追番与我的固定保留") {
                    ToggleRow(
                        "显示发现",
                        "从主导航显示或隐藏找番页",
                        settings.showDiscovery,
                        viewModel::setShowDiscovery,
                        enabled = settings.canSetNavigationDestinationVisible("discovery", !settings.showDiscovery),
                    )
                    ToggleRow(
                        "显示日历",
                        "隐藏入口不会删除提醒",
                        settings.showCalendar,
                        viewModel::setShowCalendar,
                        enabled = settings.canSetNavigationDestinationVisible("calendar", !settings.showCalendar),
                    )
                    ToggleRow(
                        "显示社区",
                        "将登录社区提升为可排序主导航",
                        settings.showCommunity,
                        viewModel::setShowCommunity,
                        enabled = settings.canSetNavigationDestinationVisible("community", !settings.showCommunity),
                    )
                    ToggleRow(
                        "显示统计",
                        "将统计页加入可排序主导航",
                        settings.showStatistics,
                        viewModel::setShowStatistics,
                        enabled = settings.canSetNavigationDestinationVisible("statistics", !settings.showStatistics),
                    )
                    ToggleRow(
                        "剪贴板分享码识别",
                        "自动检测剪贴板中的角色群组分享码并提示跳转，默认关闭",
                        settings.clipboardShareDetection,
                        viewModel::setClipboardShareDetection,
                    )
                    Text("“我的”页搜索入口", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(ProfileSearchStyle.entries, settings.profileSearchStyle, { it.displayName }, viewModel::setProfileSearchStyle)
                    Text(
                        settings.profileSearchStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("底部导航样式", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(NavigationBarStyle.entries, settings.navigationBarStyle, { it.displayName }, viewModel::setNavigationBarStyle)
                    Text(
                        settings.navigationBarStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (settings.navigationBarStyle == NavigationBarStyle.FLOATING) {
                        Text("导航文字", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(NavigationLabelMode.entries, settings.navigationLabelMode, { it.displayName }, viewModel::setNavigationLabelMode)
                        Text("胶囊高度 ${settings.floatingBarHeight}dp", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = settings.floatingBarHeight.toFloat(),
                            onValueChange = { viewModel.setFloatingBarHeight(it.roundToInt()) },
                            valueRange = 48f..80f,
                            steps = 7,
                        )
                        Text("左右边距 ${settings.floatingBarHorizontalMargin}dp", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = settings.floatingBarHorizontalMargin.toFloat(),
                            onValueChange = { viewModel.setFloatingBarHorizontalMargin(it.roundToInt()) },
                            valueRange = 0f..60f,
                        )
                        Text("距系统导航区 ${settings.floatingBarBottomMargin}dp", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = settings.floatingBarBottomMargin.toFloat(),
                            onValueChange = { viewModel.setFloatingBarBottomMargin(it.roundToInt()) },
                            valueRange = 0f..40f,
                        )
                        Text("圆角弧度 ${settings.floatingBarCornerRadius}dp", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = settings.floatingBarCornerRadius.toFloat(),
                            onValueChange = { viewModel.setFloatingBarCornerRadius(it.roundToInt()) },
                            valueRange = 0f..48f,
                        )
                        Text("阴影深度 ${settings.floatingBarShadowElevation}dp", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = settings.floatingBarShadowElevation.toFloat(),
                            onValueChange = { viewModel.setFloatingBarShadowElevation(it.roundToInt()) },
                            valueRange = 0f..24f,
                        )
                    }
                    NavigationPreview(settings)
                    Text("日历默认结构", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(CalendarLayoutPreset.entries, settings.calendarLayoutPreset, { it.displayName }, viewModel::setCalendarPreset)
                }
            }

            if (matches("品牌", "启动", "封面", "图标", "桌面")) item(key = "brand") {
                SettingSection("品牌与启动体验", "品牌页独立即时保存；返回后不会被本页的“放弃调整”撤销") {
                    FilledTonalButton(
                        onClick = { viewModel.openBranding(onBrandingRequested) },
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.BrandingWatermark, contentDescription = null)
                        Text("打开品牌定制", modifier = Modifier.padding(start = 7.dp))
                    }
                }
            }

            if (matches("配置", "档案", "导入", "导出", "恢复默认")) item(key = "profile") {
                SettingSection("配置档案", "保存全部可迁移外观与页面行为；不包含账号、密钥和本地图片路径") {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("配置名称") },
                        singleLine = true,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { viewModel.saveProfile(profileName) }, enabled = !uiState.busy) {
                            Icon(Icons.Outlined.Save, contentDescription = null)
                            Text("保存", modifier = Modifier.padding(start = 5.dp))
                        }
                        FilledTonalButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream")) }, enabled = !uiState.busy) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Text("导入", modifier = Modifier.padding(start = 5.dp))
                        }
                        FilledTonalButton(onClick = { exportLauncher.launch("AniMeow_${profileName.ifBlank { "配置" }}.animeow-config.json") }, enabled = !uiState.busy) {
                            Text("导出当前")
                        }
                        FilledTonalButton(onClick = viewModel::resetToDefaults, enabled = !uiState.busy) {
                            Icon(Icons.Outlined.Restore, contentDescription = null)
                            Text("恢复默认", modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                    uiState.profiles.forEach { profile ->
                        ProfileRow(
                            profile = profile,
                            onApply = { viewModel.applyProfile(profile) },
                            onDuplicate = { viewModel.duplicateProfile(profile) },
                            onDelete = { viewModel.deleteProfile(profile) },
                        )
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未应用的调整？") },
            text = { Text("当前页面的外观与页面行为预览会恢复为进入前的配置；品牌页中已即时保存的本地图片不会删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.discard(onBack)
                    },
                ) { Text("放弃调整") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun HomePagePreview(
    settings: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
) {
    val scale = settings.contentDensity.scale
    val spacing = settings.sectionSpacing.dpValue.dp * scale
    PreviewSurface("首页实时预览 · ${settings.homeLayout.displayName} · ${settings.contentDensity.displayName}") {
        // Mini app bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AniMeow", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
            ).forEach { color ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            }
        }

        Spacer(Modifier.height(spacing))

        // Section label
        Text("在看", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

        Spacer(Modifier.height(spacing))

        // Preview cards based on home layout
        val sampleTitles = listOf("示例番组 A", "示例番组 B", "示例番组 C", "示例番组 D")
        when (settings.homeLayout) {
            HomeLayout.POSTER_WALL, HomeLayout.RECOMMEND_GRID -> {
                val cols = settings.gridColumns.coerceIn(2, 6)
                sampleTitles.take(cols).chunked(cols).forEach { rowTitles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy((5 * scale).dp),
                    ) {
                        rowTitles.forEach { title ->
                            MiniPosterCard(
                                title = title,
                                settings = settings,
                                trackerSettings = trackerSettings,
                                scale = scale,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(cols - rowTitles.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height((5 * scale).dp))
                }
            }
            HomeLayout.CARD_FEED -> {
                sampleTitles.take(2).forEach { title ->
                    MiniCardFeedRow(title, settings, trackerSettings, scale)
                    Spacer(Modifier.height((7 * scale).dp))
                }
            }
            HomeLayout.BENTO -> {
                Row(
                    modifier = Modifier.fillMaxWidth().height((82 * scale).dp),
                    horizontalArrangement = Arrangement.spacedBy((7 * scale).dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1.35f).fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy((7 * scale).dp),
                    ) {
                        MiniPosterCard(sampleTitles[0], settings, trackerSettings, scale, Modifier.fillMaxWidth().weight(1f))
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy((7 * scale).dp),
                    ) {
                        MiniPosterCard(sampleTitles[1], settings, trackerSettings, scale, Modifier.fillMaxWidth().weight(1f))
                        MiniPosterCard(sampleTitles[2], settings, trackerSettings, scale, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
            HomeLayout.COMPACT_INDEX -> {
                sampleTitles.take(3).forEach { title ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = (2 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size((32 * scale).dp).clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        )
                        Column(Modifier.weight(1f)) {
                            if (settings.showTitle) Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                            if (settings.showStatus) {
                                val sc = Color(0xFF2196F3)
                                Text("在看", color = sc, style = MaterialTheme.typography.labelSmall,
                                    modifier = if (settings.statusChipFilled) Modifier.clip(CircleShape).background(sc.copy(alpha = 0.25f)).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier)
                            }
                        }
                        if (settings.showRating) Text("${settings.ratingIconStyle.symbol} 8.8", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HomeLayout.TIME_LINE -> {
                Column(verticalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "2024年1月",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${sampleTitles.size} 部",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val cols = settings.gridColumns.coerceIn(2, 6)
                    sampleTitles.take(cols).chunked(cols).forEach { rowTitles ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy((5 * scale).dp),
                        ) {
                            rowTitles.forEach { title ->
                                MiniPosterCard(
                                    title = title,
                                    settings = settings,
                                    trackerSettings = trackerSettings,
                                    scale = scale,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(cols - rowTitles.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPosterCard(
    title: String,
    settings: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val statusColor = Color(0xFF2196F3)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(settings.coverAspectRatio.ratio)
                .clip(RoundedCornerShape((8 * settings.cornerScale).dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * trackerSettings.coverImageOpacity)),
            )
            // Badge
            if (settings.coverBadgeStyle != CoverBadgeStyle.BOTTOM_BAR) {
                Text(
                    "在看",
                    modifier = Modifier.align(Alignment.TopStart).padding((3 * scale).dp)
                        .background(Color.White.copy(alpha = trackerSettings.infoLightBackgroundOpacity), RoundedCornerShape(5.dp))
                        .padding(horizontal = (4 * scale).dp, vertical = 1.dp),
                    color = statusColor.copy(alpha = trackerSettings.infoTextOpacity),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // Overlay title
            if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.OVERLAY) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity)),
                ) {
                    Text(
                        title,
                        modifier = Modifier.padding((4 * scale).dp),
                        color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
        // Below-cover info
        if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW) {
            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (settings.showStatus) {
                Text(
                    "在看",
                    color = statusColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = if (settings.statusChipFilled) Modifier.clip(CircleShape).background(statusColor.copy(alpha = 0.25f)).padding(horizontal = 4.dp, vertical = 0.dp) else Modifier,
                )
            }
            if (settings.showRating) Text("${settings.ratingIconStyle.symbol} 8.8", style = MaterialTheme.typography.labelSmall)
        }
        if (settings.showProgress) Text("8/12", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MiniCardFeedRow(
    title: String,
    settings: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
    scale: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((7 * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width((60 * scale).dp)
                .aspectRatio(settings.coverAspectRatio.ratio)
                .clip(RoundedCornerShape((8 * settings.cornerScale).dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * trackerSettings.coverImageOpacity)))
            if (settings.coverBadgeStyle != CoverBadgeStyle.BOTTOM_BAR) {
                Text("在看", modifier = Modifier.align(Alignment.TopStart).padding(3.dp)
                    .background(Color.White.copy(alpha = trackerSettings.infoLightBackgroundOpacity), RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                    color = Color(0xFF2196F3).copy(alpha = trackerSettings.infoTextOpacity),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (settings.showTitle) Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            if (settings.showStatus) {
                val sc = Color(0xFF2196F3)
                Text("在看", color = sc, style = MaterialTheme.typography.labelSmall,
                    modifier = if (settings.statusChipFilled) Modifier.clip(CircleShape).background(sc.copy(alpha = 0.25f)).padding(horizontal = 4.dp, vertical = 0.dp) else Modifier)
            }
            if (settings.showRating) Text("${settings.ratingIconStyle.symbol} 8.8", style = MaterialTheme.typography.labelSmall)
            if (settings.showProgress) Text("已看 8/12 集", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VisualSuitePreview(settings: com.animeow.app.ui.theme.AppearanceSettings) {
    PreviewSurface("实时预览 · ${settings.appStyle.displayName}") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("AniMeow 主题组件", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.tertiary,
                    ).forEach { color ->
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(color))
                    }
                }
                Text(
                    if (settings.useDynamicColor) "当前由系统壁纸控制颜色" else "当前使用自定义强调色",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(onClick = {}) { Text("按钮") }
        }
    }
}

@Composable
private fun LayoutDensityPreview(settings: com.animeow.app.ui.theme.AppearanceSettings) {
    val scale = settings.contentDensity.scale
    PreviewSurface("布局预览 · ${settings.homeLayout.displayName} · ${settings.contentDensity.displayName}") {
        when (settings.homeLayout) {
            HomeLayout.BENTO -> Row(
                modifier = Modifier.fillMaxWidth().height((82 * scale).dp),
                horizontalArrangement = Arrangement.spacedBy((7 * scale).dp),
            ) {
                PreviewBlock(Modifier.weight(1.35f).fillMaxSize(), MaterialTheme.colorScheme.primaryContainer)
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy((7 * scale).dp),
                ) {
                    PreviewBlock(Modifier.weight(1f).fillMaxWidth(), MaterialTheme.colorScheme.secondaryContainer)
                    PreviewBlock(Modifier.weight(1f).fillMaxWidth(), MaterialTheme.colorScheme.tertiaryContainer)
                }
            }
            HomeLayout.CARD_FEED -> Column(verticalArrangement = Arrangement.spacedBy((7 * scale).dp)) {
                repeat(2) { PreviewBlock(Modifier.fillMaxWidth().height((34 * scale).dp)) }
            }
            HomeLayout.COMPACT_INDEX -> Column(verticalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
                repeat(3) { PreviewBlock(Modifier.fillMaxWidth().height((20 * scale).dp), MaterialTheme.colorScheme.surfaceContainerHigh) }
            }
            HomeLayout.POSTER_WALL,
            HomeLayout.RECOMMEND_GRID,
            -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy((5 * scale).dp),
            ) {
                repeat(settings.gridColumns.coerceIn(2, 6)) { index ->
                    PreviewBlock(
                        modifier = Modifier.weight(1f).aspectRatio(settings.coverAspectRatio.ratio),
                        color = if (index % 2 == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    )
                }
            }
            HomeLayout.TIME_LINE -> Column(verticalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
                PreviewBlock(
                    modifier = Modifier.fillMaxWidth().height((16 * scale).dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy((5 * scale).dp),
                ) {
                    repeat(settings.gridColumns.coerceIn(2, 6)) { index ->
                        PreviewBlock(
                            modifier = Modifier.weight(1f).aspectRatio(settings.coverAspectRatio.ratio),
                            color = if (index % 2 == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SizePreview(fontDraft: Float, cornerDraft: Float) {
    PreviewSurface("尺寸实时预览") {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape((18 * cornerDraft).dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "字体与圆角会同步变化",
                    fontSize = (16 * fontDraft).sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "当前字体 ${(fontDraft * 100).roundToInt()}% · 圆角 ${(cornerDraft * 100).roundToInt()}%",
                    fontSize = (12 * fontDraft).sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun CardInfoPreview(
    settings: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
) {
    PreviewSurface("卡片实时预览") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(84.dp)
                    .aspectRatio(settings.coverAspectRatio.ratio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * trackerSettings.coverImageOpacity)),
                )
                if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.OVERLAY) {
                    Text(
                        "示例作品",
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity))
                            .padding(6.dp),
                        color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                if (settings.coverBadgeStyle != CoverBadgeStyle.BOTTOM_BAR) {
                    WatchStatusBadge("在看", Color(0xFF2196F3), trackerSettings, Modifier.align(Alignment.TopStart).padding(5.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW) {
                    Text("示例作品标题", fontWeight = FontWeight.SemiBold)
                }
                if (settings.showStatus) {
                    val previewStatusColor = Color(0xFF2196F3)
                    WatchStatusBadge("在看", previewStatusColor, trackerSettings, filled = settings.statusChipFilled, style = MaterialTheme.typography.labelMedium)
                }
                if (settings.showRating) Text("${settings.ratingIconStyle.symbol} 8.8", style = MaterialTheme.typography.labelMedium)
                if (settings.showProgress) Text("已看 8 / 12 集", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "黑框",
                        modifier = Modifier.background(Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity), RoundedCornerShape(6.dp)).padding(5.dp),
                        color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "白框",
                        modifier = Modifier.background(Color.White.copy(alpha = trackerSettings.infoLightBackgroundOpacity), RoundedCornerShape(6.dp)).padding(5.dp),
                        color = Color.Black.copy(alpha = trackerSettings.infoTextOpacity),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLayoutAndCardPreview(settings: com.animeow.app.ui.theme.AppearanceSettings) {
    val cardShape = when (settings.detailCardStyle) {
        DetailCardStyle.TONAL -> MaterialTheme.shapes.medium
        DetailCardStyle.OUTLINED -> RoundedCornerShape(16.dp)
        DetailCardStyle.GLASS -> RoundedCornerShape(20.dp)
    }
    val cardBackground = when (settings.detailCardStyle) {
        DetailCardStyle.TONAL -> MaterialTheme.colorScheme.surfaceVariant
        DetailCardStyle.OUTLINED -> MaterialTheme.colorScheme.surface
        DetailCardStyle.GLASS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    }

    PreviewSurface("详情排版与材质实时预览 · ${settings.detailLayout.displayName} · ${settings.detailCardStyle.displayName}") {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = cardBackground,
            border = if (settings.detailCardStyle == DetailCardStyle.OUTLINED) {
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            } else if (settings.detailCardStyle == DetailCardStyle.GLASS) {
                androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
            } else null,
            shadowElevation = if (settings.detailCardStyle == DetailCardStyle.TONAL) 4.dp else 0.dp,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (settings.detailLayout) {
                    DetailLayout.CLASSIC -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.width(64.dp).aspectRatio(settings.coverAspectRatio.ratio)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center,
                        ) { Text("画幅", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("经典卡片排版", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("比例 ${settings.coverAspectRatio.displayName} · 材质 ${settings.detailCardStyle.displayName}", style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(progress = { 0.7f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.small))
                        }
                    }
                    DetailLayout.MAGAZINE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(60.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                            contentAlignment = Alignment.Center,
                        ) { Text("杂志叙事视角", color = Color.White, fontWeight = FontWeight.Bold) }
                        Text("画幅比例 ${settings.coverAspectRatio.displayName} · ${settings.detailCardStyle.displayName}", style = MaterialTheme.typography.labelSmall)
                    }
                    DetailLayout.DASHBOARD -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).aspectRatio(settings.coverAspectRatio.ratio)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center,
                        ) { Text("海报", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("双列数据看板", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("追番进度与状态大卡片", style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(progress = { 0.85f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.small))
                        }
                    }
                    DetailLayout.MINIMAL -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Text("极简", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        Text("极简模式 · 高密度基础视图", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationPreview(settings: com.animeow.app.ui.theme.AppearanceSettings) {
    val destinations = settings.navigationOrder.mapNotNull { route ->
        AppDestination.entries.firstOrNull { it.route == route && settings.isNavigationDestinationVisible(route) }
    }
    var current by remember { mutableStateOf(AppDestination.TRACKER) }
    PreviewSurface("导航实时预览") {
        if (settings.navigationBarStyle == NavigationBarStyle.FLOATING) {
            FloatingNavigationBar(settings, destinations, current, { current = it }, applySystemInsets = false)
        } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            destinations.forEachIndexed { index, destination ->
                Surface(
                    color = if (index == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Text(destination.label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        }
    }
}

@Composable
private fun OpacitySlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    minimum: Float = 0.35f,
) {
    Text("$label ${(value * 100).roundToInt()}%")
    Slider(value = value, onValueChange = onValueChange, valueRange = minimum..1f, steps = 12)
}

@Composable
private fun PreviewSurface(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun PreviewBlock(
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Surface(modifier = modifier, color = color, shape = MaterialTheme.shapes.medium) {}
}

@Composable
private fun PageTransitionChooser(
    selected: PageTransitionStyle,
    motionLevel: MotionLevel,
    onSelected: (PageTransitionStyle) -> Unit,
) {
    var previewStyle by remember(selected) { mutableStateOf(selected) }
    var previewStep by rememberSaveable { mutableIntStateOf(0) }
    var previewDirection by rememberSaveable { mutableIntStateOf(1) }
    val reducedMotion = motionLevel == MotionLevel.REDUCED
    val previewDuration = scaledMotionDurationMillis(460, motionLevel)

    Text("页面切换风格", style = MaterialTheme.typography.labelLarge)
    ChoiceFlow(
        values = PageTransitionStyle.entries,
        selected = previewStyle,
        label = { it.displayName },
        onSelected = { style ->
            previewStyle = style
            onSelected(style)
            previewDirection = 1
            previewStep += 1
        },
    )
    Text(
        previewStyle.description,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        AnimatedContent(
            targetState = previewStep,
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp),
            transitionSpec = {
                if (motionLevel == MotionLevel.NONE) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    pageSwitchEnterTransition(
                        style = previewStyle,
                        durationMillis = previewDuration,
                        direction = previewDirection,
                        reduced = reducedMotion,
                    ) togetherWith pageSwitchExitTransition(
                        style = previewStyle,
                        durationMillis = previewDuration,
                        direction = previewDirection,
                        reduced = reducedMotion,
                    )
                }
            },
            label = "page_transition_preview",
        ) { step ->
            MotionPreviewPage(showLibrary = (step and 1) == 0)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = {
                previewDirection = -1
                previewStep -= 1
            },
        ) { Text("向右返回") }
        TextButton(
            onClick = {
                previewDirection = 1
                previewStep += 1
            },
        ) { Text("向左前进") }
    }
    if (motionLevel == MotionLevel.NONE) {
        Text(
            "当前已关闭装饰动效，预览和实际页面都会立即切换。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MotionPreviewPage(showLibrary: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (showLibrary) "我的追番" else "发现新作",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (showLibrary) "最近更新 · 12 部正在追" else "本周热门 · 为你推荐",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = MaterialTheme.shapes.medium,
            color = if (showLibrary) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (showLibrary) "继续观看  第 08 集" else "今日精选  9.1 分",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = if (showLibrary) "上次修改于今天" else "来自发现页的在线条目",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .motionAnimateContentSize(280),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceFlow(
    values: Iterable<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                enabled = enabled,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun ToggleRow(
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
                role = androidx.compose.ui.semantics.Role.Switch,
                onClickLabel = if (checked) "关闭$title" else "开启$title",
                onClick = { onCheckedChange(!checked) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ProfileRow(
    profile: ConfigurationProfile,
    onApply: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(profile.name, fontWeight = FontWeight.SemiBold)
            Text(
                "${profile.settings.appStyle.displayName} · ${profile.settings.homeLayout.displayName} · " +
                    "${profile.settings.contentDensity.displayName} · ${profile.configuration.sectionCount} 类设置",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = onApply) { Text("预览") }
                TextButton(onClick = onDuplicate) { Text("复制") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private const val DEFAULT_CUSTOM_ACCENT = 0xFF6750A4L

private val CUSTOM_ACCENT_PRESETS = listOf(
    0xFF6750A4L,
    0xFFEC407AL,
    0xFF00A6A6L,
    0xFF3F8CFFL,
    0xFF66BB6AL,
    0xFFE6A700L,
    0xFFFF7043L,
    0xFF8E5CFFL,
)

@Composable
internal fun ColorPickerDialog(
    initialColor: Long,
    onDismiss: () -> Unit,
    onColorSelected: (Long) -> Unit,
) {
    var alpha by remember { mutableStateOf(((initialColor shr 24) and 0xFF).toInt()) }
    var red by remember { mutableStateOf(((initialColor shr 16) and 0xFF).toInt()) }
    var green by remember { mutableStateOf(((initialColor shr 8) and 0xFF).toInt()) }
    var blue by remember { mutableStateOf((initialColor and 0xFF).toInt()) }
    val currentColor = remember(alpha, red, green, blue) {
        val a = (alpha and 0xFF).toLong()
        val r = (red and 0xFF).toLong()
        val g = (green and 0xFF).toLong()
        val b = (blue and 0xFF).toLong()
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义评分底色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 预览
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(currentColor)),
                    )
                    Column {
                        Text("预览", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "ARGB: %08X".format(currentColor.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 透明度滑块
                ColorSlider("透明度", alpha, 0xFF888888, alpha.toFloat()) { alpha = it.toInt() }
                // 红色滑块
                ColorSlider("红色", red, 0xFFFF5252, red.toFloat()) { red = it.toInt() }
                // 绿色滑块
                ColorSlider("绿色", green, 0xFF69F0AE, green.toFloat()) { green = it.toInt() }
                // 蓝色滑块
                ColorSlider("蓝色", blue, 0xFF448AFF, blue.toFloat()) { blue = it.toInt() }
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(currentColor) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun ColorSlider(
    label: String,
    value: Int,
    thumbColor: Long,
    currentValue: Float,
    onValueChange: (Float) -> Unit,
) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(
        value = currentValue,
        onValueChange = onValueChange,
        valueRange = 0f..255f,
        steps = 254,
        colors = SliderDefaults.colors(
            thumbColor = Color(thumbColor),
            activeTrackColor = Color(thumbColor),
        ),
    )
}
