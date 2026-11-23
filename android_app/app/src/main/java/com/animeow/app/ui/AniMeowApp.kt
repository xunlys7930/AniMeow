package com.animeow.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.animeow.app.ui.icons.AppIcons
import androidx.compose.foundation.layout.PaddingValues
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import com.animeow.app.ui.navigation.appComposable as composable
import com.animeow.app.ui.navigation.AppBackNavigation
import com.animeow.app.ui.navigation.LocalAppBackNavigation
import com.animeow.app.ui.navigation.pageRoute
import com.animeow.app.ui.navigation.selectMainDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.ui.components.AniMeowEnterEasing
import com.animeow.app.ui.components.AniMeowExitEasing
import com.animeow.app.ui.components.AniMeowStandardEasing
import com.animeow.app.ui.components.StylePickerSheet
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.components.pageForwardEnterTransition
import com.animeow.app.ui.components.pageForwardExitTransition
import com.animeow.app.ui.components.pagePopEnterTransition
import com.animeow.app.ui.components.pagePopExitTransition
import com.animeow.app.ui.components.pageSwitchEnterTransition
import com.animeow.app.ui.components.pageSwitchExitTransition
import com.animeow.app.ui.components.scaledMotionDurationMillis
import com.animeow.app.ui.navigation.AppDestination
import com.animeow.app.ui.navigation.FloatingNavigationBar
import com.animeow.app.ui.navigation.LocalFloatingNavigationInset
import com.animeow.app.ui.components.rememberGlassBackdrop
import com.animeow.app.ui.components.glassBackdropSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import com.animeow.app.ui.community.CommunityInboxViewModel
import com.animeow.app.ui.community.CommunityInboxScreen
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.animeow.app.ui.legacy.LegacyNavigationBar
import com.animeow.app.ui.screens.CalendarScreen
import com.animeow.app.ui.screens.DiscoveryScreen
import com.animeow.app.ui.screens.PersonalCenterScreen
import com.animeow.app.ui.screens.ProfileScreen
import com.animeow.app.ui.screens.SettingsScreen
import com.animeow.app.ui.screens.TrackerScreen
import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.CalendarLayoutPreset
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.character.CharacterManagementScreen
import com.animeow.app.ui.character.CharacterGroupsScreen
import com.animeow.app.ui.character.CharacterCommunityScreen
import com.animeow.app.ui.community.CommunityScreen
import com.animeow.app.ui.theme.CharacterLayout
import com.animeow.app.ui.theme.CharacterImageAlignment
import com.animeow.app.ui.cloud.CloudAccountScreen
import com.animeow.app.ui.cloud.FeedbackPoolScreen
import com.animeow.app.ui.maintenance.AppUpdateDialog
import com.animeow.app.ui.maintenance.UpdateViewModel
import com.animeow.app.ui.manual.DailyManualDialog
import com.animeow.app.ui.manual.randomTipIndex
import com.animeow.app.data.preferences.UserManualPreferences
import com.animeow.app.data.remote.CommunityLaunchRequest
import com.animeow.app.ui.theme.isNavigationDestinationVisible
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.animeow.app.ui.navigation.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniMeowApp(
    settings: AppearanceSettings,
    onFrontendModeSelected: (FrontendMode) -> Unit,
    onStyleSelected: (AppStyle) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onPredictiveBackEnabledChanged: (Boolean) -> Unit,
    onExitBehaviorChanged: (com.animeow.app.ui.theme.ExitBehavior) -> Unit = {},
    onCalendarLayoutPresetSelected: (CalendarLayoutPreset) -> Unit = {},
    onHomeLayoutSelected: (HomeLayout) -> Unit,
    onDetailLayoutSelected: (DetailLayout) -> Unit,
    onContentDensitySelected: (ContentDensity) -> Unit,
    onMotionLevelSelected: (MotionLevel) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onCornerScaleChanged: (Float) -> Unit,
    onGridColumnsChanged: (Int) -> Unit,
    onCoverAspectRatioSelected: (CoverAspectRatio) -> Unit,
    onCoverTitlePositionSelected: (CoverTitlePosition) -> Unit,
    onDetailCardStyleSelected: (DetailCardStyle) -> Unit,
    onShowTitleChanged: (Boolean) -> Unit,
    onShowStatusChanged: (Boolean) -> Unit,
    onShowRatingChanged: (Boolean) -> Unit,
    onShowProgressChanged: (Boolean) -> Unit,
    onCardInfoOrderChanged: (List<com.animeow.app.ui.theme.CardInfoField>) -> Unit,
    onRatingBadgeColorStyleSelected: (RatingBadgeColorStyle) -> Unit,
    onRatingBadgeCustomColorSelected: (Long) -> Unit,
    onShowDiscoveryChanged: (Boolean) -> Unit,
    onShowCalendarChanged: (Boolean) -> Unit,
    onShowCommunityChanged: (Boolean) -> Unit,
    onShowStatisticsChanged: (Boolean) -> Unit,
    onShowVersionInProfileChanged: (Boolean) -> Unit,
    onDetailModuleOrderChanged: (List<DetailModule>) -> Unit,
    onHiddenDetailModulesChanged: (Set<DetailModule>) -> Unit,
    onCardSwipeActionsEnabledChanged: (Boolean) -> Unit,
    onSwipeStartActionSelected: (SwipeAction) -> Unit,
    onSwipeEndActionSelected: (SwipeAction) -> Unit,
    onAutoCompleteStatusChanged: (Boolean) -> Unit,
    onCompletionStatusSelected: (String) -> Unit,
    onAccentColorSelected: (Long?) -> Unit,
    onNavigationOrderChanged: (List<String>) -> Unit,
    onStartDestinationSelected: (String) -> Unit,
    onCharacterLayoutSelected: (CharacterLayout) -> Unit,
    onCharacterImageAlignmentSelected: (CharacterImageAlignment) -> Unit,
    onCharacterShowMetadataChanged: (Boolean) -> Unit,
    onCharacterShowRatingChanged: (Boolean) -> Unit,
    onNavigationBarStyleSelected: (NavigationBarStyle) -> Unit,
    pendingAnimeId: Long?,
    animeDeepLinkAsRoot: Boolean = false,
    returnToPlaybackHistory: Boolean = false,
    onAnimeDeepLinkConsumed: () -> Unit,
    pendingCommunityRequest: CommunityLaunchRequest?,
    onCommunityLaunchRequestConsumed: () -> Unit,
    pendingReviewRequest: Boolean = false,
    onReviewRequestConsumed: () -> Unit = {},
    pendingAnnouncementRequest: Boolean = false,
    onAnnouncementRequestConsumed: () -> Unit = {},
    pendingLibraryRequest: Boolean = false,
    onLibraryRequestConsumed: () -> Unit = {},
    pendingShortcut: com.animeow.app.data.branding.LauncherAction? = null,
    onShortcutConsumed: () -> Unit = {},
    pendingBangumiOAuth: com.animeow.app.data.remote.BangumiOAuthCallback? = null,
    onBangumiOAuthConsumed: () -> Unit = {},
    startupReady: Boolean = true,
    onExitRequested: () -> Unit,
) {
    val navController = rememberNavController()
    val updateViewModel: UpdateViewModel = viewModel()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()
    val legacyMode = settings.frontendMode == FrontendMode.LEGACY
    val visibleDestinations = AppDestination.entries
        .filter { destination -> settings.isNavigationDestinationVisible(destination.route) }
        .sortedBy { destination -> settings.navigationOrder.indexOf(destination.route).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    val currentDestination = visibleDestinations.firstOrNull {
        it.route == currentEntry?.destination?.route
    }
    val isMainDestination = currentDestination != null
    // Preference edits affect the next launch, not the back stack being edited now.
    val resolvedStartDestination = rememberSaveable {
        settings.startDestination.takeIf { route -> visibleDestinations.any { it.route == route } }
            ?: AppDestination.TRACKER.route
    }
    var showStylePicker by rememberSaveable { mutableStateOf(false) }
    var collectionType by rememberSaveable { mutableStateOf("all") }
    val categoryContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(categoryContext) { com.animeow.app.data.preferences.LibraryCategories.init(categoryContext) }
    val visibleCategories by com.animeow.app.data.preferences.LibraryCategories.visible.collectAsStateWithLifecycle()
    LaunchedEffect(collectionType, visibleCategories) {
        if (collectionType != "all" && collectionType !in visibleCategories) collectionType = "all"
    }
    val quickAddPending = isPageActionPending(currentEntry, PageAction.QUICK_ADD)
    var fabOffsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var fabOffsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveBackDirection by remember { mutableFloatStateOf(1f) }
    var showDailyManual by remember { mutableStateOf(false) }
    var dailyTipIndex by remember { mutableIntStateOf(0) }
    var showExitDialog by remember { mutableStateOf(false) }
    var doubleBackPressedAt by remember { mutableLongStateOf(0L) }
    val userManualPreferences = remember { UserManualPreferences(navController.context) }
    val scope = rememberCoroutineScope()
    val actionFeedback = com.animeow.app.ui.components.LocalActionFeedback.current
    val inboxViewModel: CommunityInboxViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val inboxState by inboxViewModel.state.collectAsStateWithLifecycle()
    val announcementRepository = remember {
        (navController.context.applicationContext as com.animeow.app.AniMeowApplication).announcementRepository
    }
    val announcements by announcementRepository.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isMainDestination, lifecycleOwner) {
        if (isMainDestination) lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                inboxViewModel.refreshSummary()
                com.animeow.app.util.runCatchingCancellable { announcementRepository.refresh() }
                kotlinx.coroutines.delay(60_000)
            }
        }
    }
    val motionEnabled = settings.motionLevel != MotionLevel.NONE && com.animeow.app.ui.icons.LocalIconSystemAnimationsEnabled.current
    val mainNavigationDuration = scaledMotionDurationMillis(320, settings.motionLevel)
    val routeEnterDuration = scaledMotionDurationMillis(320, settings.motionLevel)
    val routeExitDuration = scaledMotionDurationMillis(320, settings.motionLevel)
    val chromeAnimationDuration = scaledMotionDurationMillis(220, settings.motionLevel)
    val reducedMotion = settings.motionLevel == MotionLevel.REDUCED

    fun mainRouteDirection(fromRoute: String?, toRoute: String?): Int? {
        val fromDest = AppDestination.entries.find { it.route == fromRoute }
        val toDest = AppDestination.entries.find { it.route == toRoute }
        if (fromDest == null || toDest == null || fromDest == toDest) return null
        val fromOrder = visibleDestinations.indexOf(fromDest)
            .let { if (it >= 0) it else AppDestination.entries.indexOf(fromDest) }
        val toOrder = visibleDestinations.indexOf(toDest)
            .let { if (it >= 0) it else AppDestination.entries.indexOf(toDest) }
        return if (toOrder > fromOrder) 1 else -1
    }

    LaunchedEffect(pendingAnimeId) {
        pendingAnimeId?.let { animeId ->
            navController.navigate("anime/$animeId") {
                launchSingleTop = true
                // Opened from the playback history/download screens: this activity exists only for
                // the detail, so back must leave the activity and return to that list.
                if (animeDeepLinkAsRoot) popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
            onAnimeDeepLinkConsumed()
        }
    }
    LaunchedEffect(pendingAnnouncementRequest) {
        if (pendingAnnouncementRequest) {
            navController.navigate(ANNOUNCEMENTS_ROUTE) { launchSingleTop = true }
            onAnnouncementRequestConsumed()
        }
    }

    LaunchedEffect(pendingLibraryRequest) {
        if (pendingLibraryRequest) {
            navController.selectMainDestination(AppDestination.TRACKER)
            onLibraryRequestConsumed()
        }
    }

    LaunchedEffect(pendingShortcut) {
        pendingShortcut?.let { action ->
            val route = when (action) {
                com.animeow.app.data.branding.LauncherAction.ADD_ANIME -> EDITOR_ROUTE
                com.animeow.app.data.branding.LauncherAction.IMAGE_SEARCH -> IMAGE_SEARCH_ROUTE
                com.animeow.app.data.branding.LauncherAction.SEARCH_LIBRARY -> "$GLOBAL_SEARCH_ROUTE?scope=local"
            }
            navController.navigate(route) { launchSingleTop = true }
            onShortcutConsumed()
        }
    }

    LaunchedEffect(pendingReviewRequest) {
        if (pendingReviewRequest) {
            navController.navigate(COMMUNITY_MANAGEMENT_ROUTE) { launchSingleTop = true }
            onReviewRequestConsumed()
        }
    }

    LaunchedEffect(Unit) {
        if (userManualPreferences.shouldShowToday()) {
            dailyTipIndex = randomTipIndex()
            showDailyManual = true
            userManualPreferences.markShownToday()
        }
    }

    BoxWithConstraints {
        val useNavigationRail = maxWidth >= 840.dp && !legacyMode
        val navigationBackdrop = rememberGlassBackdrop()
        var floatingBarHeightPx by remember { mutableIntStateOf(0) }
        val floatingBarStyle = !useNavigationRail && settings.activeNavigationBarStyle.isFloating
        val floatingNavigation = isMainDestination && floatingBarStyle
        val navigationImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val floatingInset = with(LocalDensity.current) { if (floatingBarStyle && !navigationImeVisible) floatingBarHeightPx.toDp() else 0.dp }
        val density = LocalDensity.current.density
        val minFabOffsetX = -(maxWidth.value - 72f).coerceAtLeast(0f)
        val minFabOffsetY = -(maxHeight.value - 180f).coerceAtLeast(72f)
        val chromeContainerColor = when (settings.appStyle) {
            AppStyle.MIUIX -> MaterialTheme.colorScheme.background
            AppStyle.ANIME_DYNAMIC -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            AppStyle.CYBER_GLASS -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
            AppStyle.RETRO_PIXEL -> MaterialTheme.colorScheme.surface
        }
        val navigateToMainDestination: (AppDestination) -> Unit = { destination ->
            if (destination != currentDestination) actionFeedback.selection()
            navController.selectMainDestination(destination)
        }
        fun navigateRelativeDestination(offset: Int) {
            val index = visibleDestinations.indexOf(currentDestination)
            visibleDestinations.getOrNull(index + offset)?.let(navigateToMainDestination)
        }
        fun handleMainBack() {
            if (currentDestination != AppDestination.TRACKER) {
                navigateToMainDestination(AppDestination.TRACKER)
            } else handleExit(
                behavior = settings.exitBehavior,
                onExit = onExitRequested,
                onShowDialog = { showExitDialog = true },
                onDoubleBackFirst = {
                    doubleBackPressedAt = System.currentTimeMillis()
                    Toast.makeText(navController.context, "再按一次退出 AniMeow", Toast.LENGTH_SHORT).show()
                },
                doubleBackPressedAt = doubleBackPressedAt,
            )
        }
        val interceptMainBack = isMainDestination && (currentDestination != AppDestination.TRACKER ||
            settings.exitBehavior != com.animeow.app.ui.theme.ExitBehavior.DIRECT)
        val context = androidx.compose.ui.platform.LocalContext.current
        val wallpaperAssetStore = remember(context) { com.animeow.app.data.background.WallpaperAssetStore(context) }
        val wallpaperFile = remember(settings.backgroundImagePath) { wallpaperAssetStore.resolve(settings.backgroundImagePath) }
        val hasCustomWallpaper = wallpaperFile != null && wallpaperFile.exists()
        val rootScaffoldContainerColor = if (hasCustomWallpaper) {
            MaterialTheme.colorScheme.background.copy(alpha = settings.backgroundDimOverlay.coerceIn(0f, 1f))
        } else {
            MaterialTheme.colorScheme.background
        }
        val childScaffoldContainerColor = if (hasCustomWallpaper) Color.Transparent else MaterialTheme.colorScheme.background
    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCustomWallpaper) {
            com.animeow.app.ui.components.WallpaperLayer(
                imageFile = wallpaperFile,
                scaleMode = settings.backgroundScaleMode,
                opacity = settings.backgroundImageOpacity,
                blurDp = settings.backgroundImageBlur,
            )
        }
        var trackerSelectionMode by remember { mutableStateOf(false) }
        Scaffold(
            modifier = Modifier.graphicsLayer {
                val progress = predictiveBackProgress.coerceIn(0f, 1f)
                translationX = size.width * 0.12f * progress * predictiveBackDirection
                scaleX = 1f - 0.08f * progress
                scaleY = 1f - 0.08f * progress
                shape = androidx.compose.foundation.shape.RoundedCornerShape((28f * progress).dp)
                clip = progress > 0f
                shadowElevation = 12.dp.toPx() * progress
            },
            containerColor = rootScaffoldContainerColor,
            // Child routes own their system-bar insets. Keeping the root inset-free avoids
            // stacking a second status-bar gap above nested top app bars.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {},
        bottomBar = {
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            AnimatedVisibility(
                visible = isMainDestination && !useNavigationRail && !imeVisible,
                enter = if (!motionEnabled) EnterTransition.None else
                    fadeIn(tween(chromeAnimationDuration, easing = AniMeowEnterEasing)) +
                        expandVertically(
                            animationSpec = tween(chromeAnimationDuration, easing = AniMeowEnterEasing),
                            expandFrom = Alignment.Bottom,
                        ),
                exit = if (!motionEnabled) ExitTransition.None else
                    fadeOut(tween(chromeAnimationDuration, easing = AniMeowExitEasing)) +
                        shrinkVertically(
                            animationSpec = tween(chromeAnimationDuration, easing = AniMeowExitEasing),
                            shrinkTowards = Alignment.Bottom,
                        ),
            ) {
                if (legacyMode && !settings.activeNavigationBarStyle.isFloating) {
                    LegacyNavigationBar(
                        destinations = visibleDestinations,
                        current = currentDestination,
                        onSelected = navigateToMainDestination,
                        labelMode = settings.navigationLabelMode,
                    )
                } else {
                    val floating = settings.activeNavigationBarStyle.isFloating
                    val barContent: @Composable () -> Unit = {
                        NavigationBar(
                            containerColor = if (floating) Color.Transparent else chromeContainerColor,
                            tonalElevation = if (floating) 0.dp else 3.dp,
                            windowInsets = if (floating) WindowInsets(0, 0, 0, 0) else NavigationBarDefaults.windowInsets,
                        ) {
                            visibleDestinations.forEach { destination ->
                                val selected = currentDestination == destination
                                val iconScale by animateFloatAsState(
                                    targetValue = if (selected && motionEnabled) 1.08f else 1f,
                                    animationSpec = tween(
                                        durationMillis = chromeAnimationDuration,
                                        easing = AniMeowStandardEasing,
                                    ),
                                    label = "${destination.route}_navigation_icon_scale",
                                )
                                NavigationBarItem(
                                    selected = selected,
                                    colors = com.animeow.app.ui.navigation.navigationAccentColors(),
                                    onClick = {
                                        navigateToMainDestination(destination)
                                    },
                                    icon = {
                                        Icon(
                                            icon = if (selected) {
                                                destination.selectedIcon
                                            } else {
                                                destination.icon
                                            },
                                            contentDescription = destination.label,
                                            modifier = Modifier.graphicsLayer {
                                                scaleX = iconScale
                                                scaleY = iconScale
                                            },
                                        )
                                    },
                                    label = if (settings.navigationLabelMode == com.animeow.app.ui.theme.NavigationLabelMode.ICONS_ONLY) null else { { Text(destination.label) } },
                                    alwaysShowLabel = settings.navigationLabelMode == com.animeow.app.ui.theme.NavigationLabelMode.ALWAYS,
                                )
                            }
                        }
                    }
                    if (floating) {
                        FloatingNavigationBar(settings, visibleDestinations, currentDestination, navigateToMainDestination,
                            modifier = Modifier.onSizeChanged { floatingBarHeightPx = it.height }, backdrop = navigationBackdrop)
                    } else {
                        barContent()
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = currentDestination == AppDestination.TRACKER && !trackerSelectionMode,
                enter = if (!motionEnabled) EnterTransition.None else
                    fadeIn(tween(chromeAnimationDuration, easing = AniMeowEnterEasing)) +
                        scaleIn(
                            animationSpec = tween(chromeAnimationDuration, easing = AniMeowEnterEasing),
                            initialScale = 0.82f,
                        ),
                exit = if (!motionEnabled) ExitTransition.None else
                    fadeOut(tween(chromeAnimationDuration, easing = AniMeowExitEasing)) +
                        scaleOut(
                            animationSpec = tween(chromeAnimationDuration, easing = AniMeowExitEasing),
                            targetScale = 0.88f,
                        ),
            ) {
                FloatingActionButton(
                    onClick = { when (collectionType) { "game" -> navController.requestPageAction(PageAction.ADD_GAME); "audio" -> navController.requestPageAction(PageAction.ADD_AUDIO); else -> navController.requestPageAction(PageAction.QUICK_ADD) } },
                    modifier = Modifier
                        .size(48.dp)
                        .offset { IntOffset(fabOffsetX.dp.roundToPx(), fabOffsetY.dp.roundToPx()) }
                        .pointerInput(minFabOffsetX, minFabOffsetY, density) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                fabOffsetX = (fabOffsetX + dragAmount.x / density).coerceIn(minFabOffsetX, 0f)
                                fabOffsetY = (fabOffsetY + dragAmount.y / density).coerceIn(minFabOffsetY, 0f)
                            }
                        },
                ) {
                    Icon(AppIcons.Add, contentDescription = "打开快速添加")
                }
            }
        },
    ) { innerPadding ->
        val direction = LocalLayoutDirection.current
        val pagePadding = PaddingValues(
            start = innerPadding.calculateStartPadding(direction), top = innerPadding.calculateTopPadding(),
            end = innerPadding.calculateEndPadding(direction), bottom = if (floatingBarStyle) 0.dp else innerPadding.calculateBottomPadding(),
        )
        CompositionLocalProvider(LocalFloatingNavigationInset provides floatingInset,
            LocalAppBackNavigation provides AppBackNavigation(
                currentRoute = currentEntry?.destination?.route,
                predictive = settings.predictiveBackEnabled,
                mainRoutes = visibleDestinations.map { it.route }.toSet(),
                interceptMainBack = interceptMainBack,
                onMainBack = ::handleMainBack,
                onPageBack = { if (!navController.navigateUp()) handleMainBack() },
                onMainPredictiveProgress = { event ->
                    predictiveBackDirection = if (event.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT) 1f else -1f
                    predictiveBackProgress = if (motionEnabled) event.progress else 0f
                },
                onMainPredictiveEnd = {
                    androidx.compose.animation.core.animate(predictiveBackProgress, 0f,
                        animationSpec = tween(if (motionEnabled) 160 else 0)) { value, _ -> predictiveBackProgress = value }
                },
            ),
            com.animeow.app.ui.theme.LocalContentDensity provides (settings.pageDensities[currentEntry?.destination?.route?.substringBefore('/')?.substringBefore('?')] ?: settings.contentDensity)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .consumeWindowInsets(pagePadding)
                .glassBackdropSource(navigationBackdrop, floatingNavigation && settings.activeNavigationBarStyle == NavigationBarStyle.LIQUID_GLASS)
                .mainNavigationSwipe(
                    enabled = isMainDestination && !useNavigationRail,
                    onSwipeLeft = { navigateRelativeDestination(1) },
                    onSwipeRight = { navigateRelativeDestination(-1) },
                ),
        ) {
            AnimatedVisibility(
                visible = isMainDestination && useNavigationRail,
                enter = if (!motionEnabled) EnterTransition.None else
                    fadeIn(tween(chromeAnimationDuration, easing = AniMeowEnterEasing)) +
                        expandHorizontally(
                            animationSpec = tween(chromeAnimationDuration, easing = AniMeowEnterEasing),
                            expandFrom = Alignment.Start,
                        ),
                exit = if (!motionEnabled) ExitTransition.None else
                    fadeOut(tween(chromeAnimationDuration, easing = AniMeowExitEasing)) +
                        shrinkHorizontally(
                            animationSpec = tween(chromeAnimationDuration, easing = AniMeowExitEasing),
                            shrinkTowards = Alignment.Start,
                        ),
            ) {
                NavigationRail(containerColor = chromeContainerColor) {
                    visibleDestinations.forEach { destination ->
                        val selected = currentDestination == destination
                        val iconScale by animateFloatAsState(
                            targetValue = if (selected && motionEnabled) 1.08f else 1f,
                            animationSpec = tween(
                                durationMillis = chromeAnimationDuration,
                                easing = AniMeowStandardEasing,
                            ),
                            label = "${destination.route}_rail_icon_scale",
                        )
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                navigateToMainDestination(destination)
                            },
                            icon = {
                                Icon(
                                    icon = if (selected) destination.selectedIcon else destination.icon,
                                    contentDescription = destination.label,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        NavHost(
            navController = navController,
            startDestination = resolvedStartDestination,
            modifier = Modifier.weight(1f),
            enterTransition = {
                if (!motionEnabled) {
                    EnterTransition.None
                } else {
                    val direction = mainRouteDirection(
                        initialState.destination.route,
                        targetState.destination.route,
                    )
                    if (direction != null) {
                        pageSwitchEnterTransition(
                            style = settings.pageTransitionStyle,
                            durationMillis = mainNavigationDuration,
                            direction = direction,
                            reduced = reducedMotion,
                        )
                    } else {
                        pageForwardEnterTransition(
                            style = settings.pageTransitionStyle,
                            durationMillis = routeEnterDuration,
                            reduced = reducedMotion,
                        )
                    }
                }
            },
            exitTransition = {
                if (!motionEnabled) {
                    ExitTransition.None
                } else {
                    val direction = mainRouteDirection(
                        initialState.destination.route,
                        targetState.destination.route,
                    )
                    if (direction != null) {
                        pageSwitchExitTransition(
                            style = settings.pageTransitionStyle,
                            durationMillis = mainNavigationDuration,
                            direction = direction,
                            reduced = reducedMotion,
                        )
                    } else {
                        pageForwardExitTransition(
                            style = settings.pageTransitionStyle,
                            durationMillis = routeExitDuration,
                            reduced = reducedMotion,
                        )
                    }
                }
            },
            popEnterTransition = {
                if (!motionEnabled) {
                    EnterTransition.None
                } else {
                    val direction = mainRouteDirection(
                        initialState.destination.route,
                        targetState.destination.route,
                    )
                    if (direction != null) {
                        pageSwitchEnterTransition(
                            style = settings.pageTransitionStyle,
                            durationMillis = mainNavigationDuration,
                            direction = direction,
                            reduced = reducedMotion,
                        )
                    } else {
                        pagePopEnterTransition(
                            style = settings.pageTransitionStyle,
                            durationMillis = routeEnterDuration,
                            reduced = reducedMotion,
                        )
                    }
                }
            },
            popExitTransition = {
                if (!motionEnabled) {
                    ExitTransition.None
                } else {
                    val direction = mainRouteDirection(
                        initialState.destination.route,
                        targetState.destination.route,
                    )
                    if (direction != null) {
                        pageSwitchExitTransition(
                            style = settings.pageTransitionStyle,
                            durationMillis = mainNavigationDuration,
                            direction = direction,
                            reduced = reducedMotion,
                        )
                    } else {
                        pagePopExitTransition(
                            style = settings.pageTransitionStyle,
                            durationMillis = routeExitDuration,
                            reduced = reducedMotion,
                        )
                    }
                }
            },
        ) {
            composable(AppDestination.TRACKER.route) { entry ->
                val pageActions = rememberPageActions(entry)
                LaunchedEffect(collectionType) { pageActions.discardOtherCollections(collectionType) }
                Scaffold(
                    topBar = {
                        if (legacyMode) com.animeow.app.ui.legacy.ClassicTopAppBar("收藏", "动画 · 书籍 · 游戏") {
                            IconButton(onClick = { when (collectionType) { "game" -> navController.requestPageAction(PageAction.SEARCH_GAME); "audio" -> navController.requestPageAction(PageAction.SEARCH_AUDIO); else -> navController.navigate(GLOBAL_SEARCH_ROUTE) } }) { Icon(AppIcons.Search, when (collectionType) { "game" -> "搜索游戏"; "audio" -> "查找音声"; else -> "全局搜索" }) }
                            if (collectionType != "audio") IconButton(onClick = { if (collectionType == "game") navController.requestPageAction(PageAction.CUSTOMIZE_GAME) else navController.requestPageAction(PageAction.CUSTOMIZE_TRACKER) }) { Icon(AppIcons.Tune, if (collectionType == "game") "游戏显示设置" else "定制收藏页") }
                        }
                    },
                    containerColor = childScaffoldContainerColor,
                    contentWindowInsets = if (legacyMode) WindowInsets(0, 0, 0, 0) else WindowInsets.statusBars,
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        com.animeow.app.ui.games.CollectionTypeTabs(collectionType, { collectionType = it }, visibleCategories)
                        val motionLevel = LocalMotionLevel.current
                        AnimatedContent(
                            targetState = collectionType,
                            transitionSpec = { motionFadeThroughSpec(motionLevel, 180, 120) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            label = "collection_type_content",
                        ) { targetType ->
                            if (targetType == "game") {
                                com.animeow.app.ui.games.GameScreen(
                                    settings,
                                    Modifier.fillMaxSize(),
                                    pageActions = pageActions,
                                    onSelectionModeChanged = { trackerSelectionMode = it },
                                    onSeriesOpen = { seriesId -> navController.navigate("series/$seriesId") },
                                )
                            } else if (targetType == "audio") {
                                com.animeow.app.ui.audio.AudioWorkScreen(
                                    Modifier.fillMaxSize(),
                                    pageActions = pageActions,
                                    onSeriesOpen = { seriesId -> navController.navigate("series/$seriesId") },
                                )
                            } else TrackerScreen(
                                modifier = Modifier.fillMaxSize(),
                                collectionSubjectType = targetType,
                                settings = settings,
                                pageActions = pageActions,
                                onGlobalSearchRequested = { navController.navigate(GLOBAL_SEARCH_ROUTE) },
                                onSelectionModeChanged = { trackerSelectionMode = it },
                                onAddAnime = { navController.navigate(EDITOR_ROUTE) },
                                onDiscoveryRequested = { navController.navigate(AppDestination.DISCOVERY.pageRoute) },
                                onImportRequested = { navController.navigate(PROFILE_TOOLS_ROUTE) },
                                onAnimeClick = { animeId -> navController.navigate("anime/$animeId") },
                                onSeriesClick = { seriesId -> navController.navigate("series/$seriesId") },
                                onHomeLayoutSelected = onHomeLayoutSelected,
                                onDetailLayoutSelected = onDetailLayoutSelected,
                                onContentDensitySelected = onContentDensitySelected,
                                onMotionLevelSelected = onMotionLevelSelected,
                                onFontScaleChanged = onFontScaleChanged,
                                onCornerScaleChanged = onCornerScaleChanged,
                                onGridColumnsChanged = onGridColumnsChanged,
                                onCoverAspectRatioSelected = onCoverAspectRatioSelected,
                                onCoverTitlePositionSelected = onCoverTitlePositionSelected,
                                onShowTitleChanged = onShowTitleChanged,
                                onShowStatusChanged = onShowStatusChanged,
                                onShowRatingChanged = onShowRatingChanged,
                                onShowProgressChanged = onShowProgressChanged,
                                onCardInfoOrderChanged = onCardInfoOrderChanged,
                                onRatingBadgeColorStyleSelected = onRatingBadgeColorStyleSelected,
                                onRatingBadgeCustomColorSelected = onRatingBadgeCustomColorSelected,
                                onCardSwipeActionsEnabledChanged = onCardSwipeActionsEnabledChanged,
                                onSwipeStartActionSelected = onSwipeStartActionSelected,
                                onSwipeEndActionSelected = onSwipeEndActionSelected,
                                onAnimeEdit = { animeId -> navController.navigate("anime/edit?animeId=$animeId") },
                                onAutoCompleteStatusChanged = onAutoCompleteStatusChanged,
                                onCompletionStatusSelected = onCompletionStatusSelected,
                            )
                        }
                    }
                }
                com.animeow.app.ui.tracker.QuickAddHost(pageActions) { navController.navigate(it) }
            }
            listOf(AppDestination.DISCOVERY.route, AppDestination.DISCOVERY.pageRoute).forEach { route ->
            composable(route) { entry ->
                val pageActions = rememberPageActions(entry)
                val showBack = route == AppDestination.DISCOVERY.pageRoute
                Scaffold(
                    topBar = {
                        if (legacyMode && !showBack) com.animeow.app.ui.legacy.ClassicTopAppBar("发现", "新番 · 榜单 · 作品探索") {
                            IconButton(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) }) { Icon(AppIcons.Search, "全局搜索") }
                            IconButton(onClick = { navController.requestPageAction(PageAction.CUSTOMIZE_DISCOVERY) }) { Icon(AppIcons.Tune, "定制发现页") }
                        } else if (legacyMode) TopAppBar(
                            title = { Text("发现") },
                            navigationIcon = {
                                if (showBack) IconButton(onClick = { navController.navigateUp() }) {
                                    Icon(AppIcons.Back, "返回")
                                }
                            },
                            actions = {
                                IconButton(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) }) {
                                    Icon(AppIcons.Search, contentDescription = "全局搜索")
                                }
                                IconButton(onClick = { navController.requestPageAction(PageAction.CUSTOMIZE_DISCOVERY) }) {
                                    Icon(AppIcons.Tune, contentDescription = "定制发现页")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = chromeContainerColor,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    },
                    containerColor = childScaffoldContainerColor,
                    contentWindowInsets = if (legacyMode) WindowInsets(0, 0, 0, 0) else WindowInsets.statusBars,
                ) { padding ->
                        DiscoveryScreen(
                            modifier = Modifier.padding(padding),
                            pageActions = pageActions,
                            onBackRequested = if (showBack && !legacyMode) ({ navController.navigateUp(); Unit }) else null,
                            onGlobalSearchRequested = { navController.navigate(GLOBAL_SEARCH_ROUTE) },
                            onAnimeImported = { animeId -> navController.navigate("anime/$animeId") },
                            onImageSearchRequested = { navController.navigate(IMAGE_SEARCH_ROUTE) },
                            onCommunityRequested = { navController.navigate(AppDestination.COMMUNITY.pageRoute) },
                        )
                }
            }
            }
            composable(AppDestination.CALENDAR.route) { entry ->
                val pageActions = rememberPageActions(entry)
                Scaffold(
                    topBar = {
                        if (legacyMode) com.animeow.app.ui.legacy.ClassicTopAppBar("追番日历", "每周追番，一眼掌握") {
                            IconButton(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) }) { Icon(AppIcons.Search, "全局搜索") }
                            IconButton(onClick = { navController.requestPageAction(PageAction.CUSTOMIZE_CALENDAR) }) { Icon(AppIcons.Tune, "定制追番日历页") }
                        } else TopAppBar(
                            title = { Text("日历") },
                            actions = {
                                IconButton(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) }) {
                                    Icon(AppIcons.Search, contentDescription = "全局搜索")
                                }
                                IconButton(onClick = { navController.requestPageAction(PageAction.CUSTOMIZE_CALENDAR) }) {
                                    Icon(AppIcons.Tune, contentDescription = "定制日历页")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = chromeContainerColor,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    },
                    containerColor = childScaffoldContainerColor,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                        CalendarScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            pageActions = pageActions,
                            onAnimeClick = { animeId -> navController.navigate("anime/$animeId") },
                            onEditAnime = { animeId -> navController.navigate("anime/edit?animeId=$animeId") },
                            onCalendarLayoutPresetSelected = onCalendarLayoutPresetSelected,
                        )
                }
            }
            listOf(AppDestination.COMMUNITY.route, AppDestination.COMMUNITY.pageRoute).forEach { route ->
            composable(route) { entry ->
                val pageActions = rememberPageActions(entry)
                CommunityScreen(
                    onMessagesRequested = { navController.navigate(COMMUNITY_INBOX_ROUTE) },
                    unreadMessages = inboxState.unreadCount,
                    pageActions = pageActions,
                    onPersonalCenterRequested = { navController.navigate(PERSONAL_CENTER_ROUTE) },
                    onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                    onCharacterCommunityRequested = { navController.navigate(CHARACTER_COMMUNITY_ROUTE) },
                    onManagementRequested = { navController.navigate(COMMUNITY_MANAGEMENT_ROUTE) },
                    onPostClick = { post -> navController.navigate("community/post/${post.id}") },
                    onUserClick = { userId -> navController.navigate("community/user/$userId") },
                    onBack = navController::navigateUp,
                    showBack = route == AppDestination.COMMUNITY.pageRoute,
                    topBarContainerColor = chromeContainerColor,
                )
            }
            }
            composable(AppDestination.PROFILE.route) {
                PersonalCenterScreen(
                    onSupportRequested = { navController.navigate(SUPPORT_ROUTE) },
                    onAnnouncementsRequested = { navController.navigate(ANNOUNCEMENTS_ROUTE) },
                    unreadAnnouncements = announcements.unreadCount,
                    onMessagesRequested = { navController.navigate(COMMUNITY_INBOX_ROUTE) },
                    unreadMessages = inboxState.unreadCount,
                    onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                    onCommunityRequested = { navController.navigate(AppDestination.COMMUNITY.pageRoute) },
                    onCreatePostRequested = {
                        navController.navigate(AppDestination.COMMUNITY.pageRoute)
                        navController.requestPageAction(PageAction.COMPOSE_POST)
                    },
                    onPostClick = { navController.navigate("community/post/$it") },
                    onSettingsRequested = { navController.navigate(SETTINGS_ROUTE) },
                    onToolsRequested = { navController.navigate(PROFILE_TOOLS_ROUTE) },
                    onStatisticsRequested = { navController.navigate(AppDestination.STATISTICS.pageRoute) },
                    onRemindersRequested = { navController.navigate(REMINDER_MANAGEMENT_ROUTE) },
                    onCustomizationRequested = { navController.navigate(CUSTOMIZATION_ROUTE) },
                    onOpenAnimeDetail = { navController.navigate("anime/$it") },
                    onLegadoSyncRequested = { navController.navigate(LEGADO_SYNC_ROUTE) },
                    showVersion = settings.showVersionInProfile,
                    showCommunityActivity = settings.showCommunityActivityInProfile,
                )
            }
            composable(COMMUNITY_INBOX_ROUTE) {
                CommunityInboxScreen(
                    onBack = navController::navigateUp,
                    onLogin = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                    onPostClick = { navController.navigate("community/post/$it") },
                    onUserClick = { navController.navigate("community/user/$it") },
                    viewModel = inboxViewModel,
                )
            }
            composable(
                "$PROFILE_TOOLS_ROUTE?section={section}",
                arguments = listOf(navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("全部工具") },
                            actions = {
                                IconButton(onClick = { navController.navigate(SETTINGS_ROUTE) }) {
                                    Icon(AppIcons.Settings, contentDescription = "设置")
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = navController::navigateUp) {
                                    Icon(AppIcons.Back, contentDescription = "返回")
                                }
                            },
                        )
                    },
                ) { padding ->
                    ProfileScreen(
                        initialSection = entry.arguments?.getString("section"),
                        settings = settings,
                        onCustomizationRequested = { navController.navigate(CUSTOMIZATION_ROUTE) },
                        onLibraryManagementRequested = { navController.navigate(LIBRARY_MANAGEMENT_ROUTE) },
                        onReminderManagementRequested = { navController.navigate(REMINDER_MANAGEMENT_ROUTE) },
                        onTrashRequested = { navController.navigate(TRASH_ROUTE) },
                        onDuplicateCleanupRequested = { navController.navigate(DUPLICATES_ROUTE) },
                        onSeriesShelfRequested = { navController.navigate(SERIES_SHELF_ROUTE) },
                        onTagIndexRequested = { navController.navigate(TAG_INDEX_ROUTE) },
                        onStatisticsRequested = { navController.navigate(AppDestination.STATISTICS.pageRoute) },
                        onAnimeAnalysisRequested = { navController.navigate(ANIME_ANALYSIS_ROUTE) },
                        onTierListRequested = { navController.navigate(TIER_LIST_ROUTE) },
                        onCharactersRequested = { navController.navigate(CHARACTERS_ROUTE) },
                        onCommunityRequested = { navController.navigate(AppDestination.COMMUNITY.pageRoute) },
                        onBangumiImportRequested = { navController.navigate(BANGUMI_IMPORT_ROUTE) },
                        onBangumiUploadRequested = { navController.navigate(BANGUMI_UPLOAD_ROUTE) },
                        onLegadoSyncRequested = { navController.navigate(LEGADO_SYNC_ROUTE) },
                        onOpenAnimeDetail = { navController.navigate("anime/$it") },
                        onSpreadsheetTransferRequested = { navController.navigate(SPREADSHEET_TRANSFER_ROUTE) },
                        onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                        onDiagnosticsRequested = { navController.navigate(DIAGNOSTICS_ROUTE) },
                        onFeedbackPoolRequested = { navController.navigate(FEEDBACK_POOL_ROUTE) },
                        onHelpRequested = { navController.navigate(HELP_ROUTE) },
                        onAboutRequested = { navController.navigate(ABOUT_ROUTE) },
                        onUserManualRequested = { navController.navigate(USER_MANUAL_ROUTE) },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
            composable(
                "$SETTINGS_ROUTE?section={section}",
                arguments = listOf(navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(when (entry.arguments?.getString("section")) {
                                "data", "account" -> "账号与数据"
                                "tools", "community", "widget" -> "工具与内容"
                                "help" -> "帮助与维护"
                                else -> "设置"
                            }) },
                            navigationIcon = {
                                IconButton(onClick = navController::navigateUp) {
                                    Icon(AppIcons.Back, contentDescription = "返回")
                                }
                            },
                        )
                    },
                ) { padding ->
                    SettingsScreen(
                        initialSection = entry.arguments?.getString("section"),
                        onSearchRequested = { navController.navigate("$GLOBAL_SEARCH_ROUTE?scope=settings") },
                        onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                        onToolsRequested = { navController.navigate(PROFILE_TOOLS_ROUTE) },
                        onSectionRequested = { section -> navController.navigate("$SETTINGS_ROUTE?section=${Uri.encode(section)}") },
                        onCustomizationSectionRequested = { section -> navController.navigate(customizationRoute(section = section)) },
                        onDataToolsRequested = { navController.navigate("$PROFILE_TOOLS_ROUTE?section=data") },
                        onBangumiUploadRequested = { navController.navigate(BANGUMI_UPLOAD_ROUTE) },
                        onBangumiImportRequested = { navController.navigate(BANGUMI_IMPORT_ROUTE) },
                        onLegadoSyncRequested = { navController.navigate(LEGADO_SYNC_ROUTE) },
                        onUserManualRequested = { navController.navigate(USER_MANUAL_ROUTE) },
                        onHelpRequested = { navController.navigate(HELP_ROUTE) },
                        onDiagnosticsRequested = { navController.navigate(DIAGNOSTICS_ROUTE) },
                        onFeedbackPoolRequested = { navController.navigate(FEEDBACK_POOL_ROUTE) },
                        onAboutRequested = { navController.navigate(ABOUT_ROUTE) },
                        onBlockManagementRequested = { navController.navigate(COMMUNITY_BLOCKLIST_ROUTE) },
                        settings = settings,
                        onThemeModeSelected = onThemeModeSelected,
                        onFrontendModeSelected = onFrontendModeSelected,
                        onPredictiveBackEnabledChanged = onPredictiveBackEnabledChanged,
                        onExitBehaviorChanged = onExitBehaviorChanged,
                        onShowDiscoveryChanged = onShowDiscoveryChanged,
                        onShowCalendarChanged = onShowCalendarChanged,
                        onShowCommunityChanged = onShowCommunityChanged,
                        onShowStatisticsChanged = onShowStatisticsChanged,
                        onShowVersionInProfileChanged = onShowVersionInProfileChanged,
                        onNavigationOrderChanged = onNavigationOrderChanged,
                        onStartDestinationSelected = onStartDestinationSelected,
                        onCustomizationRequested = { navController.navigate(CUSTOMIZATION_ROUTE) },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
            searchRoutes(navController, settings)
            libraryRoutes(navController, settings, chromeContainerColor)
            composable(
                route = CHARACTERS_ROUTE_PATTERN,
                arguments = listOf(
                    navArgument("characterId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry ->
                CharacterManagementScreen(
                    settings = settings,
                    onBack = navController::navigateUp,
                    onGroupsRequested = { navController.navigate(CHARACTER_GROUPS_ROUTE) },
                    onLayoutSelected = onCharacterLayoutSelected,
                    onImageAlignmentSelected = onCharacterImageAlignmentSelected,
                    onShowMetadataChanged = onCharacterShowMetadataChanged,
                    onShowRatingChanged = onCharacterShowRatingChanged,
                    initialCharacterId = entry.arguments?.getLong("characterId")?.takeIf { it > 0 },
                )
            }
            composable(
                route = CHARACTER_GROUPS_ROUTE_PATTERN,
                arguments = listOf(
                    navArgument("groupId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry ->
                CharacterGroupsScreen(
                    settings = settings,
                    onBack = navController::navigateUp,
                    onCommunityRequested = { navController.navigate(CHARACTER_COMMUNITY_ROUTE) },
                    initialGroupId = entry.arguments?.getLong("groupId")?.takeIf { it > 0 },
                )
            }
            composable(CHARACTER_COMMUNITY_ROUTE) { entry ->
                val shares = remember(entry) { CharacterShareRequests(entry.savedStateHandle) }
                val pendingShare by shares.pending.collectAsStateWithLifecycle()
                val approvedShare = remember(pendingShare) { shares.decode(pendingShare) }
                CharacterCommunityScreen(
                    settings = settings,
                    onBack = navController::navigateUp,
                    initialRequest = approvedShare,
                    onInitialRequestConsumed = { approvedShare?.let(shares::consume) },
                )
            }
            preferenceRoutes(navController)
            communityRoutes(navController)
            composable(CLOUD_ACCOUNT_ROUTE) {
                CloudAccountScreen(
                    onBack = navController::navigateUp,
                    onFeedbackPoolRequested = { navController.navigate(FEEDBACK_POOL_ROUTE) },
                    onBangumiImportRequested = { navController.navigate(BANGUMI_IMPORT_ROUTE) },
                    onBangumiUploadRequested = { navController.navigate(BANGUMI_UPLOAD_ROUTE) },
                    pendingBangumiOAuth = pendingBangumiOAuth,
                    onBangumiOAuthConsumed = onBangumiOAuthConsumed,
                )
            }
            composable(ANNOUNCEMENTS_ROUTE) {
                com.animeow.app.ui.announcements.AnnouncementScreen(
                    onBack = { navController.navigateUp() },
                    onLogin = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                )
            }
            composable(PERSONAL_CENTER_ROUTE) {
                PersonalCenterScreen(
                    onSupportRequested = { navController.navigate(SUPPORT_ROUTE) },
                    onAnnouncementsRequested = { navController.navigate(ANNOUNCEMENTS_ROUTE) },
                    unreadAnnouncements = announcements.unreadCount,
                    onBack = navController::navigateUp,
                    onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                    onCommunityRequested = { navController.navigate(AppDestination.COMMUNITY.pageRoute) },
                    onCreatePostRequested = {
                        navController.navigate(AppDestination.COMMUNITY.pageRoute)
                        navController.requestPageAction(PageAction.COMPOSE_POST)
                    },
                    onPostClick = { navController.navigate("community/post/$it") },
                    onSettingsRequested = { navController.navigate(SETTINGS_ROUTE) },
                    onToolsRequested = { navController.navigate(PROFILE_TOOLS_ROUTE) },
                    onStatisticsRequested = { navController.navigate(AppDestination.STATISTICS.pageRoute) },
                    onRemindersRequested = { navController.navigate(REMINDER_MANAGEMENT_ROUTE) },
                    onCustomizationRequested = { navController.navigate(CUSTOMIZATION_ROUTE) },
                    onOpenAnimeDetail = { navController.navigate("anime/$it") },
                    onLegadoSyncRequested = { navController.navigate(LEGADO_SYNC_ROUTE) },
                    showVersion = settings.showVersionInProfile,
                    showCommunityActivity = settings.showCommunityActivityInProfile,
                )
            }
            composable(FEEDBACK_POOL_ROUTE) {
                FeedbackPoolScreen(
                    onBack = navController::navigateUp,
                    onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                )
            }
            maintenanceRoutes(navController, updateState, updateViewModel)
            animeRoutes(navController, settings, legacyMode, returnToPlaybackHistory,
                onDetailModuleOrderChanged, onHiddenDetailModulesChanged, onDetailCardStyleSelected,
                onCollectionReset = { collectionType = "all" })
        }
        }
        }
    }


    }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出 AniMeow") },
            text = { Text("确定要退出应用吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onExitRequested()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("取消") }
            },
        )
    }

    if (showStylePicker) {
        StylePickerSheet(
            currentFrontendMode = settings.frontendMode,
            currentStyle = settings.appStyle,
            currentThemeMode = settings.themeMode,
            currentAccentColor = settings.accentColor,
            onFrontendModeSelected = onFrontendModeSelected,
            onStyleSelected = onStyleSelected,
            onThemeModeSelected = onThemeModeSelected,
            onAccentColorSelected = onAccentColorSelected,
            onDismissRequest = { showStylePicker = false },
        )
    }
    // 授权回跳只在账号与同步页消费；确保回到前台后自动进入该页
    if (pendingBangumiOAuth != null) {
        LaunchedEffect(pendingBangumiOAuth.key) {
            navController.navigate(CLOUD_ACCOUNT_ROUTE) { launchSingleTop = true }
        }
    }
    pendingCommunityRequest?.let { request ->
        AlertDialog(
            onDismissRequest = onCommunityLaunchRequestConsumed,
            title = { Text("识别到角色群组分享") },
            text = {
                Text(
                    "检测到 ${request.displayToken}。打开后只会先加载群组与本地差异预览，" +
                        "不会自动导入或修改资料库。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        navController.navigate(CHARACTER_COMMUNITY_ROUTE) { launchSingleTop = true }
                        val entry = navController.currentBackStackEntry
                        if (entry?.destination?.route == CHARACTER_COMMUNITY_ROUTE &&
                            CharacterShareRequests(entry.savedStateHandle).request(request)) {
                            onCommunityLaunchRequestConsumed()
                        }
                    },
                ) { Text("查看预览") }
            },
            dismissButton = {
                TextButton(onClick = onCommunityLaunchRequestConsumed) { Text("忽略") }
            },
        )
    }
    AppUpdateDialog(
        dialog = updateState.dialog,
        onDownload = updateViewModel::downloadAvailableUpdate,
        onInstall = updateViewModel::installDownloadedUpdate,
        onIgnore = updateViewModel::ignoreAvailableVersion,
        onDismiss = updateViewModel::dismissDialog,
        onCancelDownload = updateViewModel::cancelDownload,
    )
    com.animeow.app.ui.promotion.PromotionPrompt(
        onSupportRequested = { navController.navigate(SUPPORT_ROUTE) },
        eligible = startupReady && isMainDestination,
        blocked = showDailyManual || showExitDialog || showStylePicker || quickAddPending ||
            pendingCommunityRequest != null || pendingAnimeId != null || pendingShortcut != null ||
            pendingBangumiOAuth != null ||
            pendingReviewRequest || pendingLibraryRequest || pendingAnnouncementRequest || updateState.dialog != null,
    )
    if (showDailyManual) {
        DailyManualDialog(
            tipIndex = dailyTipIndex,
            onMode = { mode ->
                scope.launch { userManualPreferences.setDisplayMode(mode) }
            },
            onOpenManual = {
                showDailyManual = false
                navController.navigate(USER_MANUAL_ROUTE)
            },
            onDismiss = { showDailyManual = false },
        )
    }
}

private fun Modifier.mainNavigationSwipe(
    enabled: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(onSwipeLeft, onSwipeRight) {
        val triggerDistance = 64.dp.toPx()
        val edgeTriggerDistance = 38.dp.toPx()
        val flickDistance = 24.dp.toPx()
        val edgeWidth = 44.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val edgeGesture = down.position.x <= edgeWidth || down.position.x >= size.width - edgeWidth
            var totalX = 0f
            var totalY = 0f
            var cancelledByChild = false
            var horizontalLocked = false
            var tracking = true
            var lastUptimeMillis = down.uptimeMillis
            while (tracking) {
                val event = awaitPointerEvent(
                    if (edgeGesture) PointerEventPass.Initial else PointerEventPass.Main,
                )
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - change.previousPosition
                totalX += delta.x
                totalY += delta.y
                lastUptimeMillis = change.uptimeMillis
                if (
                    edgeGesture &&
                    !horizontalLocked &&
                    abs(totalX) >= viewConfiguration.touchSlop &&
                    abs(totalX) > abs(totalY) * 1.2f
                ) {
                    horizontalLocked = true
                }
                if (edgeGesture && horizontalLocked) {
                    change.consume()
                } else if (change.isConsumed) {
                    cancelledByChild = true
                }
                tracking = change.pressed
            }
            val requiredDistance = if (edgeGesture) edgeTriggerDistance else triggerDistance
            val elapsedMillis = (lastUptimeMillis - down.uptimeMillis).coerceAtLeast(1L)
            val horizontalVelocity = abs(totalX) / elapsedMillis * 1_000f
            val distanceOrFlickReached = abs(totalX) >= requiredDistance ||
                (abs(totalX) >= flickDistance && horizontalVelocity >= 720f)
            if (
                (
                    edgeGesture && horizontalLocked ||
                        !edgeGesture && !cancelledByChild
                    ) &&
                distanceOrFlickReached &&
                abs(totalX) > abs(totalY) * 1.2f
            ) {
                if (totalX < 0f) onSwipeLeft() else onSwipeRight()
            }
        }
    }
}


private fun handleExit(
    behavior: com.animeow.app.ui.theme.ExitBehavior,
    onExit: () -> Unit,
    onShowDialog: () -> Unit,
    onDoubleBackFirst: () -> Unit,
    doubleBackPressedAt: Long,
) {
    when (behavior) {
        com.animeow.app.ui.theme.ExitBehavior.DIRECT -> onExit()
        com.animeow.app.ui.theme.ExitBehavior.CONFIRM_DIALOG -> onShowDialog()
        com.animeow.app.ui.theme.ExitBehavior.DOUBLE_PRESS -> {
            val now = System.currentTimeMillis()
            if (now - doubleBackPressedAt < 2000) {
                onExit()
            } else {
                onDoubleBackFirst()
            }
        }
    }
}
