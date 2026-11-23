package com.animeow.app.ui.preferences

import com.animeow.app.ui.icons.AppIcons

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.preferences.ConfigurationProfile
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.components.glassBackdropSource
import com.animeow.app.ui.components.CustomizableColorSelector
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.CardInfoSortSheet
import com.animeow.app.ui.components.ConfirmationDialog
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
import com.animeow.app.ui.theme.DetailProgressMode
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
import com.animeow.app.ui.icons.glyph
import com.animeow.app.ui.theme.canSetNavigationDestinationVisible
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CustomizationScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onBrandingRequested: () -> Unit,
    onCloudAccountRequested: () -> Unit = {},
    initialQuery: String = "",
    initialSection: String? = null,
    viewModel: CustomizationViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trackerSettings by viewModel.trackerSettings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    // 二级页（总览）只放风格套件与常用项；三级页 openSection 展示单个分区的全部控件。
    // 三级页与总览共用同一份草稿，返回总览不会丢失未应用的调整。
    var openSection by rememberSaveable(initialSection) {
        mutableStateOf(customizationSection(initialSection)?.key)
    }
    val searching = query.isNotBlank()
    var profileName by rememberSaveable { mutableStateOf("") }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var showChangeReview by rememberSaveable { mutableStateOf(false) }
    var suitesExpanded by rememberSaveable { mutableStateOf(false) }
    var sectionHistory by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var focusedControl by rememberSaveable { mutableStateOf(customizationControlTarget(initialQuery)?.key.orEmpty()) }
    fun openPage(key: String) {
        sectionHistory = sectionHistory + openSection.orEmpty()
        openSection = key
    }
    var profilePendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var fontDraft by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale) }
    var cornerDraft by remember(settings.cornerScale) { mutableFloatStateOf(settings.cornerScale) }
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    val feedback = com.animeow.app.ui.components.LocalActionFeedback.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importProfile)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportCurrent(it, profileName.ifBlank { "我的配置" }) }
    }

    // 仅在搜索结果中用于按分区筛选
    var selectedSection by rememberSaveable { mutableStateOf<String?>(null) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    val searchTargets = remember(query) { searchCustomizationTargets(query) }
    val matchingSections = remember(searchTargets) {
        searchTargets.mapNotNull { customizationSection(it.section) }.distinctBy { it.key }
    }
    fun visibleKeysFor(pageSection: String?): Set<String> = visibleCustomizationKeys(pageSection)
    fun previewSectionFor(pageSection: String?): String? = pageSection

    LaunchedEffect(Unit) { viewModel.beginDraft() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCloudAccount()
    }
    LaunchedEffect(uiState.message, uiState.classicUndoDeadline) {
        uiState.message?.takeIf { uiState.classicUndoDeadline == null }?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(uiState.classicUndoDeadline) {
        val deadline = uiState.classicUndoDeadline ?: return@LaunchedEffect
        snackbarHost.currentSnackbarData?.dismiss()
        val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1)
        val result = kotlinx.coroutines.withTimeoutOrNull(remaining) {
            snackbarHost.showSnackbar("已切换经典界面。返回新版：我的 → 设置 → 帮助与维护 → 界面版本。",
                actionLabel = "撤销", duration = androidx.compose.material3.SnackbarDuration.Indefinite)
        }
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.undoClassicSuite().join()
        else viewModel.finishClassicUndo()
    }
    if (uiState.confirmClassicSuite) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClassicSuite,
            title = { Text("切换到经典界面？") },
            text = { Text("“经典书架”预设会切换为经典界面，并调整书架密度、导航和装饰。\n\n切换后 5 秒内可点“撤销”恢复当前设置。之后也可在 我的 → 设置 → 帮助与维护 → 界面版本 中选择新版。") },
            confirmButton = { TextButton(onClick = { viewModel.confirmClassicSuite() }) { Text("确认切换") } },
            dismissButton = { TextButton(onClick = viewModel::dismissClassicSuite) { Text("保留当前界面") } },
        )
    }
    fun requestExit() {
        if (uiState.busy) return
        if (uiState.hasChanges) showDiscardDialog = true else onBack()
    }
    // Internal leaves return to their object hub/search results. External deep links return to source.
    val enteredAtSection = customizationSection(initialSection) != null
    fun navigateBack() {
        if (uiState.busy) return
        if (sectionHistory.isNotEmpty()) {
            openSection = sectionHistory.last().takeIf(String::isNotEmpty)
            sectionHistory = sectionHistory.dropLast(1)
        } else if (openSection != null && !enteredAtSection) {
            openSection = null
        } else requestExit()
    }

    BackHandler(onBack = ::navigateBack)

    val openSectionInfo = customizationSection(openSection)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (uiState.hasChanges) Surface(shadowElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val count = uiState.draftChanges.size
                    Text(if (count > 0) "$count 项未应用" else "有未应用的个性化调整", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        TextButton(onClick = viewModel::undo, enabled = uiState.canUndo && !uiState.busy) { Text("撤销上一步") }
                        OutlinedButton(onClick = { showChangeReview = true }, enabled = !uiState.busy) { Text("查看变化") }
                        Button(
                            onClick = {
                                viewModel.commit {
                                    scope.launch { snackbarHost.showSnackbar("已应用个性化调整") }
                                }
                            },
                            enabled = !uiState.busy,
                        ) { Text("直接应用") }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(openSectionInfo?.title ?: "外观与阅读", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = ::navigateBack, enabled = !uiState.busy) {
                        Icon(
                            AppIcons.Back,
                            contentDescription = if (sectionHistory.isNotEmpty() || (openSectionInfo != null && !enteredAtSection)) "返回上一级设置" else "取消并返回",
                        )
                    }
                },
                actions = {
                    if (previewSectionFor(openSection) in PREVIEW_SECTIONS || (openSection == null && !searching)) {
                        IconButton(onClick = { showPreview = !showPreview }) {
                            Icon(AppIcons.Visibility, contentDescription = if (showPreview) "收起实时预览" else "展开实时预览")
                        }
                    }
                },
            )
        },
    ) { padding ->
      AnimatedContent(
        targetState = openSection to sectionHistory.size,
        modifier = Modifier.fillMaxSize().padding(padding),
        transitionSpec = {
            val forward = targetState.second > initialState.second
            val duration = scaledMotionDurationMillis(320, settings.motionLevel)
            val reduced = settings.motionLevel == MotionLevel.REDUCED
            if (settings.motionLevel == MotionLevel.NONE) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                pageSwitchEnterTransition(settings.pageTransitionStyle, duration, if (forward) 1 else -1, reduced) togetherWith
                    pageSwitchExitTransition(settings.pageTransitionStyle, duration, if (forward) 1 else -1, reduced)
            }
        },
        label = "customization-depth",
      ) { (pageSection, _) ->
        val pageListState = if (pageSection == null) listState else rememberLazyListState()
        val visibleSectionKeys = visibleKeysFor(pageSection)
        val previewSection = previewSectionFor(pageSection)
        val overview = pageSection == null && !searching
        androidx.compose.runtime.CompositionLocalProvider(
            LocalActiveCustomizationTitle provides customizationSection(pageSection)?.title,
        ) {
        Column(Modifier.fillMaxSize()) {
            if (pageSection == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selectedSection = null
                        scope.launch { listState.scrollToItem(0) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    placeholder = { Text("搜索设置，如字太小、封面、手势", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = {
                            query = ""
                            selectedSection = null
                            scope.launch { listState.scrollToItem(0) }
                        }) { Icon(AppIcons.Close, contentDescription = "清除搜索") }
                    },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
            if (pageSection == null && searching) LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") {
                        FilterChip(
                            selected = selectedSection == null,
                            onClick = {
                                selectedSection = null
                                scope.launch { listState.scrollToItem(0) }
                            },
                            label = { Text("全部") },
                        )
                    }
                    items(matchingSections, key = { it.key }) { section ->
                        FilterChip(
                            selected = selectedSection == section.key,
                            onClick = {
                                selectedSection = section.key
                                scope.launch { listState.scrollToItem(0) }
                            },
                            label = { Text(section.label) },
                        )
                    }
                }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("customization-list"),
            state = pageListState,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showPreview && previewSection in PREVIEW_SECTIONS) item(key = "preview-$previewSection") {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        when (previewSection) {
                            "visual" -> VisualSuitePreview(settings)
                            "layout" -> LayoutLivePreview(settings, trackerSettings)
                            "size" -> SizePreview(fontDraft, cornerDraft, settings.fontScale)
                            "card", "cover" -> CardInfoPreview(settings, trackerSettings)
                            "nav" -> NavigationPreview(settings)
                        }
                    }
                }
            }
            if (overview) {
                if (showPreview) item(key = "overview-preview") { VisualSuitePreview(settings) }
                item(key = "overview-suite") {
                    com.animeow.app.ui.screens.SettingsDisclosureSection(
                        title = "整套外观预设", subtitle = "搭配颜色与布局；保留操作习惯",
                        expanded = suitesExpanded, onExpandedChange = { suitesExpanded = it },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("外观预设不会修改进度操作、手势或返回方式。", style = MaterialTheme.typography.bodySmall)
                            AppearanceSuitePicker(settings, { viewModel.applySuite(it) }, enabled = !uiState.busy)
                        }
                    }
                }
                item(key = "overview-quick") { CustomizationQuickSettings(settings, viewModel) }
                item(key = "overview-advanced") {
                    AdvancedCustomizationEntries(uiState.changesFromDefault, onOpen = ::openPage)
                }
            }
            if (pageSection == "pages") item(key = "page-hub") {
                AdvancedCustomizationEntries(uiState.changesFromDefault, sections = pageCustomizationSections, onOpen = ::openPage)
            }
            if (pageSection == null && searching) {
                items(searchTargets.filter { selectedSection == null || it.section == selectedSection }, key = { "result-${it.key}" }) { target ->
                    com.animeow.app.ui.screens.PreferenceRow(
                        AppIcons.Search, target.title, target.path, "打开",
                    ) { focusedControl = target.key; showPreview = false; openPage(target.section) }
                }
            }
            if (pageSection == null && searching && searchTargets.isEmpty()) item(key = "empty-search") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("没有找到相关设置", style = MaterialTheme.typography.titleMedium)
                    Text("试试「字体」「配色」或「导航」", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { query = ""; selectedSection = null }) { Text("回到外观与阅读") }
                }
            }

            if ("visual" in visibleSectionKeys) item(key = "visual") {
                SettingSection("主题与配色", "主题色、强调色与渐变、背景；风格套件在上一页") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "visual", viewModel::resetCategory)
                    ToggleRow("增强可读性", "提高文字对比度，透明浮层使用更稳定的底色", settings.highContrast, viewModel::setHighContrast)
                    Text("界面风格", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(AppStyle.entries, settings.appStyle, { it.displayName }, viewModel::setAppStyle)
                    Text("外观模式", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(ThemeMode.entries, settings.themeMode, { it.displayName }, viewModel::setThemeMode)
                    com.animeow.app.ui.overlay.OverlayStylePreference(settings.overlayStyle, viewModel::setOverlayStyle)
                    ToggleRow("使用系统动态色", "Android 12+ 从壁纸获取默认配色，独立设置的主题色仍会保留", settings.useDynamicColor, viewModel::setDynamicColor)
                    ThemeColorControls(settings, viewModel::setThemeColor)
                    BackgroundColorControls(
                        settings = settings,
                        wallpaperResolver = viewModel::resolveWallpaper,
                        onBackgroundColor = viewModel::setBackgroundColor,
                        onImportWallpaper = viewModel::importWallpaper,
                        onRemoveWallpaper = viewModel::removeWallpaper,
                        onScaleMode = viewModel::setBackgroundScaleMode,
                        onOpacity = viewModel::setBackgroundImageOpacity,
                        onBlur = viewModel::setBackgroundImageBlur,
                        onDimOverlay = viewModel::setBackgroundDimOverlay,
                    )
                    ToggleRow(
                        "每次启动随机强调色",
                        "从经过可读性筛选的色板中换色，不改变布局与功能配置",
                        settings.randomAccentOnLaunch,
                        viewModel::setRandomAccentOnLaunch,
                    )
                    AccentPalettePresets(settings, viewModel::applyAccentPreset)
                    Text("强调色与渐变起始色", style = MaterialTheme.typography.labelLarge)
                    Text("用于导航高亮、跟随强调色的图标与评分；可与主题色分开搭配。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val sourcePalette = com.animeow.app.ui.theme.LocalAccentPaint.current.colors
                    CustomizableColorSelector(
                        color = sourcePalette.first().toArgb().toLong() and 0xFFFFFFFFL,
                        presets = CUSTOM_ACCENT_PRESETS,
                        onColorChanged = { selected ->
                            if (settings.accentGradient.mode == com.animeow.app.ui.theme.AccentMode.SOLID) viewModel.setAccentColor(selected)
                            else viewModel.setAccentPalette(sourcePalette.mapIndexed { index, color ->
                                if (index == 0) selected else color.toArgb().toLong() and 0xFFFFFFFFL
                            })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        dialogTitle = "自定义 AniMeow 强调色",
                    )
                    FilledTonalButton(
                        onClick = viewModel::randomizeAccent,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("立即换一个强调色") }
                    AccentGradientControls(settings.accentGradient, viewModel::setAccentGradient, viewModel::setAccentPalette)
                    if (showPreview && previewSection == null) VisualSuitePreview(settings)
                }
            }

            if ("icons" in visibleSectionKeys) item(key = "icons") {
                SettingSection("功能图标", "选择整套风格，也能单独调整某个图标") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "icons", viewModel::resetCategory)
                    IconCustomizationControls(
                        settings = settings.icons,
                        onStyle = viewModel::setIconStyle,
                        onVariant = viewModel::setIconVariant,
                        onAnimated = viewModel::setIconAnimated,
                        onOverride = viewModel::setIconOverride,
                        onResetOverrides = viewModel::resetIconOverrides,
                        onColor = viewModel::setIconColor,
                    )
                }
            }

            if ("layout" in visibleSectionKeys) item(key = "layout") {
                SettingSection("首页与详情", "按当前页面调整模块、布局、圆角与密度") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "layout", viewModel::resetCategory)
                    TextButton(onClick = { openPage("behavior") }) { Text("调整详情进度操作 → 操作与反馈") }
                    Text("圆角强度 ${(cornerDraft * 100).roundToInt()}%")
                    Slider(
                        value = cornerDraft,
                        onValueChange = { cornerDraft = it },
                        onValueChangeFinished = { viewModel.setCornerScale(cornerDraft) },
                        valueRange = 0.5f..1.5f,
                    )

                    CustomizationGroup("首页与模块", "布局、首页模块与显示顺序") {
                        HomeModuleControls(trackerSettings, viewModel::setHomeModuleVisible, viewModel::moveHomeModule)
                        Text("首页布局", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(HomeLayout.selectable, settings.homeLayout, { it.displayName }, viewModel::setHomeLayout)
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
                    }
                    CustomizationGroup("详情与材质", "详情布局与资料卡片") {
                        Text("详情布局", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(DetailLayout.entries, settings.detailLayout, { it.displayName }, viewModel::setDetailLayout)
                        Text(settings.detailLayout.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Text("详情卡片材质", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(DetailCardStyle.entries, settings.detailCardStyle, { it.displayName }, viewModel::setDetailCardStyle)
                        Text(
                            if (settings.detailLayout == DetailLayout.MINIMAL) "极简阅读使用留白与分隔线；卡片材质会保留，切换到其他布局后生效。"
                            else settings.detailCardStyle.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    CustomizationGroup("密度与间距", "页面密度、留白与网格列数", initiallyExpanded = false, forceExpanded = searching) {
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
                        PageDensityControls(settings, viewModel)
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
                        DraftIntSlider(
                            label = "网格列数：",
                            suffix = "",
                            value = settings.gridColumns,
                            onValueCommitted = viewModel::setGridColumns,
                            valueRange = 2f..6f,
                            steps = 3,
                            labelStyle = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            "海报墙支持双指缩放快速调整列数，范围同样限制为 2–6 列。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (showPreview && previewSection == null) {
                        LayoutLivePreview(settings, trackerSettings)
                        DetailLayoutAndCardPreview(settings)
                    }
                }
            }

            if ("size" in visibleSectionKeys) item(key = "size") {
                SettingSection("字体与阅读", "选择内置开源字体或导入字体，所有页面即时预览") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "size", viewModel::resetCategory)
                    SearchableSetting("font-scale", "文字大小", focusedControl, { focusedControl = "" }) {
                        Text("文字大小 ${(fontDraft * 100).roundToInt()}%")
                        Slider(
                            value = fontDraft,
                            onValueChange = { fontDraft = it },
                            onValueChangeFinished = { viewModel.setFontScale(fontDraft) },
                            valueRange = APP_FONT_SCALE_MIN..APP_FONT_SCALE_MAX,
                            modifier = Modifier.semantics { contentDescription = "文字大小" },
                        )
                    }
                    FontCustomizationControls(settings, uiState, viewModel)
                    if (previewSection == null) SizePreview(fontDraft = fontDraft, cornerDraft = cornerDraft, appliedFontScale = settings.fontScale)
                }
            }

            if ("card" in visibleSectionKeys) item(key = "card") {
                SettingSection("卡片信息", "同一设置会在支持的首页布局中保持一致") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "card", viewModel::resetCategory)
                    CustomizationGroup("显示内容与顺序", "标题、状态、进度与编辑入口") {
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
                            var showCardInfoSort by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animatedPressClick(onClickLabel = "打开卡片信息排序", onClick = { showCardInfoSort = true }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("卡片信息排序", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "拖动调整标题、类型与放送、状态、进度、评分的先后",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(AppIcons.ChevronRight, contentDescription = null)
                            }
                            if (showCardInfoSort) {
                                CardInfoSortSheet(
                                    settings = settings,
                                    onOrderChanged = viewModel::setCardInfoOrder,
                                    onDismissRequest = { showCardInfoSort = false },
                                )
                            }
                        ToggleRow("系列编辑按钮", "显示首页系列卡片上的铅笔；隐藏后仍可进入详情编辑", settings.showSeriesEditButton, viewModel::setShowSeriesEditButton)
                        ToggleRow("置顶图标", "显示置顶区的图标和封面角标；隐藏后仍可长按置顶封面取消置顶", settings.showPinBadges, viewModel::setShowPinBadges)
                        ToggleRow(
                            "状态标签显示数量",
                            "在全部、看完、在看等状态标签旁显示对应作品数量",
                            trackerSettings.showStatusCount,
                            viewModel::setShowStatusCount,
                        )
                    }
                    CustomizationGroup("评分与角标细节", "图标、颜色与封面标题位置", initiallyExpanded = false, forceExpanded = searching) {
                        Text("角标样式", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(CoverBadgeStyle.entries, settings.coverBadgeStyle, { it.displayName }, viewModel::setBadgeStyle)
                        Text("评分图标", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RatingIconStyle.entries.forEach { ratingStyle ->
                                FilterChip(
                                    selected = settings.ratingIconStyle == ratingStyle,
                                    onClick = { viewModel.setRatingIcon(ratingStyle) },
                                    label = { Text(ratingStyle.displayName) },
                                    leadingIcon = ratingStyle.glyph?.let { glyph ->
                                        { Icon(glyph, null, Modifier.size(18.dp)) }
                                    },
                                )
                            }
                        }
                        Text("评分底色", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(RatingBadgeColorStyle.entries, settings.ratingBadgeColorStyle, { it.displayName }, viewModel::setRatingBadgeColor)
                        RatingColorExplanation(settings.ratingBadgeColorStyle)
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
                    }
                    if (showPreview && previewSection == null) CardInfoPreview(settings, trackerSettings)
                }
            }

            if ("cover" in visibleSectionKeys) item(key = "cover") {
                SettingSection("封面与信息层", "调整封面图像与文字的清晰度，实时查看变化") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "cover", viewModel::resetCategory)
                        Text("封面画幅", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(CoverAspectRatio.entries, settings.coverAspectRatio, { it.displayName }, viewModel::setCoverAspectRatio)

                    CustomizationGroup("封面图像与文字细节", "透明度、信息底框与饱和度", initiallyExpanded = false, forceExpanded = searching || pageSection == "cover") {
                        OpacitySlider("文字透明度", trackerSettings.infoTextOpacity, viewModel::setTrackerInfoTextOpacity)
                        OpacitySlider("黑色信息框透明度", trackerSettings.infoDarkBackgroundOpacity, viewModel::setTrackerDarkBackgroundOpacity, 0f)
                        OpacitySlider("白色信息框透明度", trackerSettings.infoLightBackgroundOpacity, viewModel::setTrackerLightBackgroundOpacity, 0f)
                        OpacitySlider("封面图像透明度", trackerSettings.coverImageOpacity, viewModel::setTrackerCoverImageOpacity)
                        var saturationDraft by remember(trackerSettings.coverSaturation) {
                            mutableFloatStateOf(trackerSettings.coverSaturation)
                        }
                        Text("封面饱和度 ${(saturationDraft * 100).roundToInt()}%")
                        Slider(
                            value = saturationDraft,
                            onValueChange = { saturationDraft = it },
                            onValueChangeFinished = { viewModel.setTrackerCoverSaturation(saturationDraft) },
                            valueRange = 0f..1.5f,
                            steps = 14,
                        )
                    }
                    if (showPreview && previewSection == null) CardInfoPreview(settings, trackerSettings)
                }
            }

            if ("motion" in visibleSectionKeys) item(key = "motion") {
                SettingSection("视觉动效", "动画风格与强度可分别调整；不会改变操作习惯") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "motion", viewModel::resetCategory)
                    TextButton(onClick = { openPage("behavior") }) { Text("手势、返回与触感已移到操作与反馈") }
                    Text("玻璃性能档位", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(com.animeow.app.ui.theme.GlassQuality.entries, settings.glassQuality, { it.label }, viewModel::setGlassQuality)
                    Text("自动模式在低内存设备或省电模式下减少特效；轻量模式关闭模糊与折射。", style = MaterialTheme.typography.bodySmall)
                    Text("动效强度", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(MotionLevel.entries, settings.motionLevel, { it.displayName }, viewModel::setMotion)
                    PageTransitionChooser(
                        selected = settings.pageTransitionStyle,
                        motionLevel = settings.motionLevel,
                        onSelected = viewModel::setPageTransitionStyle,
                    )

                }
            }

            if ("behavior" in visibleSectionKeys) item(key = "behavior") {
                SettingSection("操作与反馈", "这些操作习惯不随外观预设改变；关闭手势后仍保留按钮入口") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "behavior", viewModel::resetCategory)
                    SearchableSetting("detail-progress", "详情进度操作", focusedControl, { focusedControl = "" }) {
                        Text("详情进度操作", style = MaterialTheme.typography.labelLarge)
                        ChoiceFlow(DetailProgressMode.entries, settings.detailProgressMode, { it.displayName }, viewModel::setDetailProgressMode)
                        Text(settings.detailProgressMode.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    CustomizationGroup("手势与触感", "预测性返回、滑动、触觉与音效", initiallyExpanded = false, forceExpanded = searching && customizationControlTarget(query) == null) {
                        ToggleRow("预测性返回手势", "只控制系统返回预览，不关闭普通页面转场", settings.predictiveBackEnabled, viewModel::setPredictiveBackEnabled)
                        ToggleRow("触觉反馈", "导航、选择、长按和打卡提供不同的触感", settings.hapticFeedback, viewModel::setHaptic)
                        ToggleRow("操作音效", "短点击与完成提示音，遵循系统音量、静音和勿扰设置", settings.soundFeedback, viewModel::setSound)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scope.launch { snackbarHost.showSnackbar(feedback.previewHaptic()) } }, enabled = settings.hapticFeedback) { Text("测试触觉") }
                            OutlinedButton(onClick = { scope.launch { snackbarHost.showSnackbar(feedback.previewSound()) } }, enabled = settings.soundFeedback) { Text("试听音效") }
                        }
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
                    }
                    SearchableSetting("exit-behavior", "返回退出方式", focusedControl, { focusedControl = "" }) {
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
            }

            if ("nav" in visibleSectionKeys) item(key = "nav") {
                SettingSection("底栏外形", "这里只调外形；入口、顺序与启动页在设置 → 导航与启动") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "nav", viewModel::resetCategory)
                    TextButton(onClick = { openPage("navigation") }) { Text("调整入口、顺序与启动页") }
                    Text("底部导航样式", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(NavigationBarStyle.entries, settings.activeNavigationBarStyle, { it.displayName }, viewModel::setNavigationBarStyle)
                    Text(
                        settings.activeNavigationBarStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("导航文字", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(NavigationLabelMode.entries, settings.navigationLabelMode, { it.displayName }, viewModel::setNavigationLabelMode)
                    SearchableSetting("nav-opacity", "底栏背景不透明度", focusedControl, { focusedControl = "" }) {
                        if (settings.activeNavigationBarStyle.isFloating) {
                            OpacitySlider("底栏背景不透明度", settings.floatingBarOpacity, viewModel::setFloatingBarOpacity, minimum = 0f)
                            Text("0% 时保留导航图标和选中标记，背景完全透明。", style = MaterialTheme.typography.bodySmall)
                        } else Text("当前底栏样式没有独立背景透明度。可先在上方选择悬浮样式，现有选择不会自动改变。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (settings.activeNavigationBarStyle.isFloating) {
                        CustomizationGroup("底栏尺寸与特效", "高度、间距、圆角、阴影与模糊", initiallyExpanded = false, forceExpanded = searching) {
                            if (settings.activeNavigationBarStyle == NavigationBarStyle.LIQUID_GLASS) {
                                DraftIntSlider("高斯模糊 ", "dp", settings.floatingBarBlur, viewModel::setFloatingBarBlur, 0f..40f, steps = 7)
                                Text("支持的系统会显示实时模糊与玻璃折射，其他设备保留透明面板。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DraftIntSlider("胶囊高度 ", "dp", settings.floatingBarHeight, viewModel::setFloatingBarHeight, 48f..80f, steps = 7)
                            DraftIntSlider("左右边距 ", "dp", settings.floatingBarHorizontalMargin, viewModel::setFloatingBarHorizontalMargin, 0f..60f)
                            DraftIntSlider("距系统导航区 ", "dp", settings.floatingBarBottomMargin, viewModel::setFloatingBarBottomMargin, 0f..40f)
                            DraftIntSlider("圆角弧度 ", "dp", settings.floatingBarCornerRadius, viewModel::setFloatingBarCornerRadius, 0f..48f)
                            DraftIntSlider("阴影深度 ", "dp", settings.floatingBarShadowElevation, viewModel::setFloatingBarShadowElevation, 0f..24f)
                        }
                    }
                    if (showPreview && previewSection == null) NavigationPreview(settings)
                }
            }

            if ("navigation" in visibleSectionKeys) item(key = "navigation") {
                SettingSection("导航与启动", "隐藏入口不删除数据；启动页只影响下次启动") {
                    ChangedSettingsPanel(uiState.changesFromDefault, "navigation", viewModel::resetCategory)
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
                    ChoiceFlow(ProfileSearchStyle.selectable, settings.profileSearchStyle, { it.displayName }, viewModel::setProfileSearchStyle)
                    Text(
                        settings.profileSearchStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    com.animeow.app.ui.screens.NavigationCustomizationContent(
                        settings, viewModel::setNavigationOrder, viewModel::setStartDestination,
                    )
                    Text("日历默认结构", style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(CalendarLayoutPreset.entries, settings.activeCalendarLayoutPreset, { it.displayName }, viewModel::setCalendarPreset)
                    TextButton(onClick = { openPage("nav") }) { Text("调整底栏外形") }
                }
            }

            if ("brand" in visibleSectionKeys) item(key = "brand") {
                SettingSection("品牌与启动体验", "默认使用原版图标，也可选用新图标或设置启动封面；此处调整会立即保存") {
                    val widgetContext = LocalContext.current
                    TextButton(onClick = {
                        if (!com.animeow.app.data.widget.TrackerWidget.requestPin(widgetContext)) scope.launch {
                            snackbarHost.showSnackbar("请长按桌面 → 小组件 → AniMeow，添加“继续记录”")
                        }
                    }) { Text("添加桌面“继续记录”小组件") }
                    Text("直接查看在看的作品并记录 +1 集；添加与否由你选择。", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(
                        onClick = { viewModel.openBranding(onBrandingRequested) },
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(AppIcons.Branding, contentDescription = null)
                        Text("更换图标与启动封面", modifier = Modifier.padding(start = 7.dp))
                    }
                }
            }

            if ("profile" in visibleSectionKeys) item(key = "profile") {
                SettingSection("个性化配置", "保存外观、操作习惯、导航与页面偏好；应用前核对范围") {
                    TextButton(onClick = onCloudAccountRequested) { Text("云端设备备份（含资料库，不含字体）") }
                    TextButton(onClick = { viewModel.shareCurrent(profileName) }, enabled = !uiState.busy) { Text("分享个性化配置") }
                    Text("分享不含字体文件、背景图片与数据源地址。导入前可对照，导入后可撤销。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("配置名称") },
                        singleLine = true,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { viewModel.saveProfile(profileName) }, enabled = !uiState.busy) {
                            Icon(AppIcons.Save, contentDescription = null)
                            Text("保存", modifier = Modifier.padding(start = 5.dp))
                        }
                        FilledTonalButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream")) }, enabled = !uiState.busy) {
                            Icon(AppIcons.Download, contentDescription = null)
                            Text("导入", modifier = Modifier.padding(start = 5.dp))
                        }
                        FilledTonalButton(onClick = { exportLauncher.launch("AniMeow_${profileName.ifBlank { "配置" }}.animeow-config.json") }, enabled = !uiState.busy) {
                            Text("导出当前")
                        }
                        FilledTonalButton(onClick = { showResetConfirmation = true }, enabled = !uiState.busy) {
                            Icon(AppIcons.History, contentDescription = null)
                            Text("恢复默认", modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                    uiState.profiles.forEach { profile ->
                        ProfileRow(
                            profile = profile,
                            enabled = !uiState.busy,
                            onApply = { viewModel.applyProfile(profile) },
                            onDuplicate = { viewModel.duplicateProfile(profile) },
                            onDelete = { profilePendingDeletionId = profile.id },
                        )
                    }
                }
            }
        }
        }
        }
      }
    }
    if (showChangeReview) {
        AlertDialog(
            onDismissRequest = { if (!uiState.busy) showChangeReview = false },
            title = { Text("确认本次调整") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("以下为本次改动摘要；确认应用后立即生效（品牌页中单独保存的图片不受整页撤销影响）。", style = MaterialTheme.typography.bodySmall)
                    }
                    uiState.draftChanges.groupBy { it.category }.forEach { (category, changes) ->
                        item(key = "heading-$category") {
                            Text(com.animeow.app.data.preferences.personalizationCategoryTitle(category), fontWeight = FontWeight.SemiBold)
                        }
                        items(changes) { change ->
                            Column {
                                Text(change.label, style = MaterialTheme.typography.titleSmall)
                                Text("原来：${change.before}\n现在：${change.after}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (uiState.draftChanges.isEmpty()) item { Text("有其他个性化配置调整；请确认当前预览后再应用。") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChangeReview = false; viewModel.commit(onBack) }, enabled = uiState.hasChanges && !uiState.busy) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { showChangeReview = false }, enabled = !uiState.busy) { Text("继续调整") } },
        )
    }
    if (showResetConfirmation) {
        ConfirmationDialog(
            title = "恢复默认设置？",
            message = "外观、布局和页面行为将预览为默认值。查看变化并点击“应用”才会保留，返回时仍可放弃调整。已保存的配置档案不会删除。",
            confirmLabel = "恢复默认",
            onConfirm = { showResetConfirmation = false; viewModel.resetToDefaults() },
            onDismiss = { showResetConfirmation = false },
            busy = uiState.busy,
        )
    }
    uiState.profiles.firstOrNull { it.id == profilePendingDeletionId }?.let { profile ->
        ConfirmationDialog(
            title = "删除配置档案？",
            message = "将永久删除“${profile.name}”，无法通过撤销恢复。当前使用中的外观不会改变。",
            confirmLabel = "删除档案",
            onConfirm = { profilePendingDeletionId = null; viewModel.deleteProfile(profile) },
            onDismiss = { profilePendingDeletionId = null },
            destructive = true,
            busy = uiState.busy,
        )
    }
    ImportAppearanceComparison(settings, trackerSettings, uiState, viewModel::confirmImport, viewModel::dismissImport)
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
internal fun HomePagePreview(
    settings: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
) {
    val previewDensity = settings.pageDensities["tracker"] ?: settings.contentDensity
    val scale = previewDensity.scale
    val spacing = settings.sectionSpacing.dpValue.dp * scale
    PreviewSurface("首页实时预览 · ${settings.homeLayout.displayName} · ${previewDensity.displayName}") {
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
                    modifier = Modifier.fillMaxWidth().height((190 * scale).dp),
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
                        MiniCardFeedRow(sampleTitles[1], settings, trackerSettings, scale, compact = true)
                        MiniCardFeedRow(sampleTitles[2], settings, trackerSettings, scale, compact = true)
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
                        if (settings.showRating) com.animeow.app.ui.icons.RatingText("8.8", settings.ratingIconStyle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            "2026年9月",
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
    val sample = com.animeow.app.data.local.AnimeEntity(id = 1, title = title, status = "在看", rating = 88, watchedEpisodes = 8, totalEpisodes = 12,
        coverUrl = "android.resource://${LocalContext.current.packageName}/drawable/preview_cover")
    Box(modifier) {
        com.animeow.app.ui.screens.PosterAnimeCard(sample, settings, trackerSettings,
            selected = false, onClick = {}, onLongClick = {}, swipeEnabled = false, onSwipe = { _, _ -> })
    }
}

@Composable
private fun MiniCardFeedRow(
    title: String,
    settings: com.animeow.app.ui.theme.AppearanceSettings,
    trackerSettings: TrackerSettings,
    scale: Float,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((7 * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(((if (compact) 38 else 60) * scale).dp)
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
            if (settings.showTitle) Text(title, fontWeight = FontWeight.SemiBold,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (settings.showStatus) {
                val sc = Color(0xFF2196F3)
                Text("在看", color = sc, style = MaterialTheme.typography.labelSmall,
                    modifier = if (settings.statusChipFilled) Modifier.clip(CircleShape).background(sc.copy(alpha = 0.25f)).padding(horizontal = 4.dp, vertical = 0.dp) else Modifier)
            }
            if (settings.showRating) com.animeow.app.ui.icons.RatingText("8.8", settings.ratingIconStyle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (settings.showProgress) Text(if (compact) "8/12 集" else "已看 8/12 集", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    if (settings.themeColor != null) "独立主题色 · 强调色分别搭配" else if (settings.useDynamicColor) "使用系统壁纸配色" else if (settings.accentColor != null) "主题色跟随强调色" else "使用所选风格的配色",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(onClick = {}) { Text("按钮") }
        }
    }
}

@Composable
private fun SizePreview(fontDraft: Float, cornerDraft: Float, appliedFontScale: Float) {
    PreviewSurface("尺寸实时预览") {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape((18 * cornerDraft).dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "字体与圆角会同步变化",
                    fontSize = (16 * fontDraft / appliedFontScale).sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "当前字体 ${(fontDraft * 100).roundToInt()}% · 圆角 ${(cornerDraft * 100).roundToInt()}%",
                    fontSize = (12 * fontDraft / appliedFontScale).sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun CardInfoPreview(settings: com.animeow.app.ui.theme.AppearanceSettings, trackerSettings: TrackerSettings) {
    val packageName = LocalContext.current.packageName
    val sample = com.animeow.app.data.local.AnimeEntity(id = 1, title = "山间来信", status = "在看", rating = 88, watchedEpisodes = 8, totalEpisodes = 12,
        coverUrl = "android.resource://$packageName/drawable/preview_cover")
    PreviewSurface("卡片与封面实时预览") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp)) {
                com.animeow.app.ui.screens.PosterAnimeCard(sample, settings, trackerSettings,
                    selected = false, onClick = {}, onLongClick = {}, swipeEnabled = false, onSwipe = { _, _ -> })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${settings.coverAspectRatio.displayName} · ${settings.coverTitlePosition.displayName}", style = MaterialTheme.typography.bodySmall)
                Text("饱和度 ${(trackerSettings.coverSaturation * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Text("图像 ${(trackerSettings.coverImageOpacity * 100).roundToInt()}% · 文字 ${(trackerSettings.infoTextOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("示例使用与首页相同的卡片，修改会立即反映在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LayoutLivePreview(settings: com.animeow.app.ui.theme.AppearanceSettings, trackerSettings: TrackerSettings) {
    var details by remember { mutableStateOf(false) }
    var previousDetail by remember { mutableStateOf(settings.detailLayout to settings.detailCardStyle) }
    LaunchedEffect(settings.homeLayout, settings.contentDensity, settings.gridColumns, settings.sectionSpacing) { details = false }
    LaunchedEffect(settings.detailLayout, settings.detailCardStyle) {
        val currentDetail = settings.detailLayout to settings.detailCardStyle
        if (currentDetail != previousDetail) details = true
        previousDetail = currentDetail
    }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!details, { details = false }, label = { Text("首页预览") })
            FilterChip(details, { details = true }, label = { Text("详情预览") })
        }
        if (details) DetailLayoutAndCardPreview(settings) else HomePagePreview(settings, trackerSettings)
    }
}

@Composable
private fun DetailLayoutAndCardPreview(settings: com.animeow.app.ui.theme.AppearanceSettings) {
    PreviewSurface("详情页预览 · ${settings.detailLayout.displayName}") {
        com.animeow.app.ui.anime.DetailLayoutPreview(settings)
        Text("示例内容 · 可上下滑动查看排版", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavigationPreview(settings: com.animeow.app.ui.theme.AppearanceSettings) {
    val destinations = settings.navigationOrder.mapNotNull { route ->
        AppDestination.entries.firstOrNull { it.route == route && settings.isNavigationDestinationVisible(route) }
    }
    var current by remember { mutableStateOf(AppDestination.TRACKER) }
    val backdrop = com.animeow.app.ui.components.rememberGlassBackdrop()
    LaunchedEffect(destinations) { if (current !in destinations) current = destinations.first() }
    PreviewSurface("导航实时预览 · 点击切换") {
        Box(Modifier.fillMaxWidth().height(126.dp).clip(MaterialTheme.shapes.large)) {
            Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.animeow.app.R.drawable.preview_cover), null,
                    Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            if (settings.activeNavigationBarStyle.isFloating) {
                FloatingNavigationBar(settings, destinations, current, { current = it }, Modifier.align(Alignment.BottomCenter), applySystemInsets = false, backdrop = backdrop)
            } else if (settings.frontendMode == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
                com.animeow.app.ui.legacy.LegacyNavigationBar(destinations, current, { current = it },
                    modifier = Modifier.align(Alignment.BottomCenter), labelMode = settings.navigationLabelMode, applySystemInsets = false)
            } else {
                androidx.compose.material3.NavigationBar(modifier = Modifier.align(Alignment.BottomCenter), windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) {
                    destinations.forEach { destination ->
                        NavigationBarItem(selected = current == destination, onClick = { current = destination },
                            colors = com.animeow.app.ui.navigation.navigationAccentColors(),
                            icon = { Icon(if (current == destination) destination.selectedIcon else destination.icon, destination.label) },
                            label = if (settings.navigationLabelMode == NavigationLabelMode.ICONS_ONLY) null else { { Text(destination.label, maxLines = 1) } },
                            alwaysShowLabel = settings.navigationLabelMode == NavigationLabelMode.ALWAYS)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarPresetPreview(preset: CalendarLayoutPreset) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("日历预览 · ${preset.displayName}", style = MaterialTheme.typography.labelSmall)
        if (preset != CalendarLayoutPreset.AGENDA) {
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(day, style = MaterialTheme.typography.labelSmall) }
                }
            }
            val rows = if (preset == CalendarLayoutPreset.MONTH) 3 else 1
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(7) { index ->
                        val date = if (rows == 1) 7 + index else 1 + row * 7 + index
                        Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.small,
                            color = if (date == 12) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                            Box(Modifier.padding(vertical = 5.dp), contentAlignment = Alignment.Center) { Text("$date", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
        if (preset == CalendarLayoutPreset.AGENDA || preset == CalendarLayoutPreset.WEEK_AGENDA) {
            listOf("周六 20:00" to "山间来信 · 第 9 集", "周日 18:30" to "星海旅人 · 第 3 集").forEach { (time, title) ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.labelSmall)
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
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text("$label ${(draft * 100).roundToInt()}%")
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onValueChange(draft) },
        valueRange = minimum..1f,
        steps = 12,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
private fun DraftIntSlider(
    label: String,
    suffix: String,
    value: Int,
    onValueCommitted: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$label${draft.roundToInt()}$suffix", style = labelStyle)
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onValueCommitted(draft.roundToInt()) },
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier.semantics { contentDescription = label.trimEnd('：', ':') },
    )
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
                    text = if (showLibrary) "最近观看  第 08 集" else "今日精选  9.1 分",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = if (showLibrary) "接着上次看" else "来自发现页的在线条目",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private val LocalActiveCustomizationTitle = androidx.compose.runtime.compositionLocalOf<String?> { null }

@Composable
internal fun SettingSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hideDuplicateTitle = title == LocalActiveCustomizationTitle.current
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
            if (!hideDuplicateTitle) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceFlow(
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
internal fun ToggleRow(
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
    enabled: Boolean,
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
                Button(enabled = enabled, onClick = onApply) { Text("预览") }
                TextButton(enabled = enabled, onClick = onDuplicate) { Text("复制") }
                TextButton(enabled = enabled, onClick = onDelete) { Text("删除") }
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
    var alpha by remember { mutableIntStateOf(((initialColor shr 24) and 0xFF).toInt()) }
    var red by remember { mutableIntStateOf(((initialColor shr 16) and 0xFF).toInt()) }
    var green by remember { mutableIntStateOf(((initialColor shr 8) and 0xFF).toInt()) }
    var blue by remember { mutableIntStateOf((initialColor and 0xFF).toInt()) }
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
