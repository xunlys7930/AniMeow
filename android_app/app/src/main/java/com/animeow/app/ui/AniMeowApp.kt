package com.animeow.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.ui.anime.AnimeDetailScreen
import com.animeow.app.ui.anime.AnimeEditorScreen
import com.animeow.app.ui.components.AniMeowEnterEasing
import com.animeow.app.ui.components.AniMeowExitEasing
import com.animeow.app.ui.components.AniMeowStandardEasing
import com.animeow.app.ui.components.StylePickerSheet
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.pageForwardEnterTransition
import com.animeow.app.ui.components.pageForwardExitTransition
import com.animeow.app.ui.components.pagePopEnterTransition
import com.animeow.app.ui.components.pagePopExitTransition
import com.animeow.app.ui.components.pageSwitchEnterTransition
import com.animeow.app.ui.components.pageSwitchExitTransition
import com.animeow.app.ui.components.scaledMotionDurationMillis
import com.animeow.app.ui.navigation.AppDestination
import com.animeow.app.ui.navigation.FloatingNavigationBar
import com.animeow.app.ui.legacy.LegacyAnimeDetailScreen
import com.animeow.app.ui.legacy.LegacyAnimeEditorScreen
import com.animeow.app.ui.legacy.LegacyCalendarScreen
import com.animeow.app.ui.legacy.LegacyDiscoveryScreen
import com.animeow.app.ui.legacy.LegacyNavigationBar
import com.animeow.app.ui.legacy.LegacyProfileScreen
import com.animeow.app.ui.legacy.LegacyTrackerScreen
import com.animeow.app.ui.management.LibraryManagementScreen
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
import com.animeow.app.ui.theme.PageTransitionStyle
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.trash.TrashScreen
import com.animeow.app.ui.duplicates.DuplicateCleanupScreen
import com.animeow.app.ui.series.SeriesDetailScreen
import com.animeow.app.ui.series.SeriesShelfScreen
import com.animeow.app.ui.discovery.ImageSearchScreen
import com.animeow.app.ui.statistics.StatisticsScreen
import com.animeow.app.ui.statistics.StatusAnimeScreen
import com.animeow.app.ui.statistics.UNCLASSIFIED_STATUS_ROUTE_TOKEN
import com.animeow.app.ui.tier.TierListScreen
import com.animeow.app.ui.character.CharacterManagementScreen
import com.animeow.app.ui.character.CharacterGroupsScreen
import com.animeow.app.ui.character.CharacterCommunityScreen
import com.animeow.app.ui.community.CommunityScreen
import com.animeow.app.ui.community.CommunityManagementScreen
import com.animeow.app.ui.community.CommunityPostDetailScreen
import com.animeow.app.ui.community.CommunityUserSpaceScreen
import com.animeow.app.ui.theme.CharacterLayout
import com.animeow.app.ui.theme.CharacterImageAlignment
import com.animeow.app.ui.importer.BangumiImportScreen
import com.animeow.app.ui.importer.SpreadsheetTransferScreen
import com.animeow.app.ui.preferences.CustomizationScreen
import com.animeow.app.ui.preferences.BrandingScreen
import com.animeow.app.ui.cloud.CloudAccountScreen
import com.animeow.app.ui.cloud.FeedbackPoolScreen
import com.animeow.app.ui.diagnostics.DiagnosticsScreen
import com.animeow.app.ui.analysis.AnimeAnalysisScreen
import com.animeow.app.ui.tags.TagCollectionScreen
import com.animeow.app.ui.tags.TagIndexScreen
import com.animeow.app.ui.maintenance.AboutCenterScreen
import com.animeow.app.ui.maintenance.AppUpdateDialog
import com.animeow.app.ui.maintenance.CacheManagementScreen
import com.animeow.app.ui.maintenance.ChangelogScreen
import com.animeow.app.ui.maintenance.CreditsSupportScreen
import com.animeow.app.ui.maintenance.DisclaimerScreen
import com.animeow.app.ui.maintenance.HelpCenterScreen
import com.animeow.app.ui.maintenance.UpdateViewModel
import com.animeow.app.ui.manual.UserManualScreen
import com.animeow.app.ui.manual.DailyManualDialog
import com.animeow.app.ui.manual.randomTipIndex
import com.animeow.app.data.preferences.ManualDisplayMode
import com.animeow.app.data.preferences.UserManualPreferences
import com.animeow.app.ui.reminder.ReminderManagementScreen
import com.animeow.app.data.remote.CommunityLaunchRequest
import com.animeow.app.ui.theme.isNavigationDestinationVisible
import com.animeow.app.ui.search.GlobalSearchAction
import com.animeow.app.ui.search.GlobalSearchScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    onAnimeDeepLinkConsumed: () -> Unit,
    pendingCommunityRequest: CommunityLaunchRequest?,
    onCommunityLaunchRequestConsumed: () -> Unit,
    onExitRequested: () -> Unit,
) {
    val navController = rememberNavController()
    val updateViewModel: UpdateViewModel = viewModel()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()
    val legacyMode = settings.frontendMode == FrontendMode.LEGACY
    val visibleDestinations = if (legacyMode) {
        listOf(
            AppDestination.TRACKER,
            AppDestination.DISCOVERY,
            AppDestination.CALENDAR,
            AppDestination.PROFILE,
        )
    } else {
        AppDestination.entries
            .filter { destination -> settings.isNavigationDestinationVisible(destination.route) }
            .sortedBy { destination -> settings.navigationOrder.indexOf(destination.route).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }
    val currentDestination = visibleDestinations.firstOrNull {
        it.route == currentEntry?.destination?.route
    }
    val isMainDestination = currentDestination != null
    val resolvedStartDestination = (if (legacyMode) AppDestination.TRACKER.route else settings.startDestination)
        .takeIf { route -> visibleDestinations.any { it.route == route } }
        ?: AppDestination.TRACKER.route
    var showStylePicker by rememberSaveable { mutableStateOf(false) }
    var trackerSettingsRequest by rememberSaveable { mutableIntStateOf(0) }
    var discoverySettingsRequest by rememberSaveable { mutableIntStateOf(0) }
    var calendarSettingsRequest by rememberSaveable { mutableIntStateOf(0) }
    var showQuickAdd by rememberSaveable { mutableStateOf(false) }
    var fabOffsetX by rememberSaveable { mutableStateOf(0f) }
    var fabOffsetY by rememberSaveable { mutableStateOf(0f) }
    var mainNavigationDirection by remember { mutableStateOf(1) }
    var approvedCommunityRequest by remember { mutableStateOf<CommunityLaunchRequest?>(null) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var showDailyManual by remember { mutableStateOf(false) }
    var dailyTipIndex by remember { mutableIntStateOf(0) }
    var showExitDialog by remember { mutableStateOf(false) }
    var doubleBackPressedAt by remember { mutableStateOf(0L) }
    val userManualPreferences = remember { UserManualPreferences(navController.context) }
    val scope = rememberCoroutineScope()
    val motionEnabled = settings.motionLevel != MotionLevel.NONE
    val mainNavigationDuration = scaledMotionDurationMillis(380, settings.motionLevel)
    val routeEnterDuration = scaledMotionDurationMillis(380, settings.motionLevel)
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
            navController.navigate("anime/$animeId") { launchSingleTop = true }
            onAnimeDeepLinkConsumed()
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
            val currentIndex = visibleDestinations.indexOf(currentDestination)
            val targetIndex = visibleDestinations.indexOf(destination)
            if (currentIndex >= 0 && targetIndex >= 0 && currentIndex != targetIndex) {
                mainNavigationDirection = if (targetIndex > currentIndex) 1 else -1
            }
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        fun navigateRelativeDestination(offset: Int) {
            val index = visibleDestinations.indexOf(currentDestination)
            visibleDestinations.getOrNull(index + offset)?.let(navigateToMainDestination)
        }
    Scaffold(
        modifier = Modifier.graphicsLayer {
            val progress = predictiveBackProgress.coerceIn(0f, 1f)
            translationX = size.width * 0.08f * progress
            scaleX = 1f - 0.018f * progress
            scaleY = 1f - 0.018f * progress
        },
        containerColor = MaterialTheme.colorScheme.background,
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
                if (legacyMode) {
                    LegacyNavigationBar(
                        destinations = visibleDestinations,
                        current = currentDestination,
                        onSelected = navigateToMainDestination,
                    )
                } else {
                    val floating = settings.navigationBarStyle == NavigationBarStyle.FLOATING
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
                                    onClick = {
                                        navigateToMainDestination(destination)
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) {
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
                                    label = {
                                        Text(destination.label)
                                    },
                                )
                            }
                        }
                    }
                    if (floating) {
                        FloatingNavigationBar(settings, visibleDestinations, currentDestination, navigateToMainDestination)
                    } else {
                        barContent()
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = currentDestination == AppDestination.TRACKER && !legacyMode,
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
                    onClick = { showQuickAdd = true },
                    modifier = Modifier
                        .size(48.dp)
                        .offset(x = fabOffsetX.dp, y = fabOffsetY.dp)
                        .pointerInput(minFabOffsetX, minFabOffsetY, density) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                fabOffsetX = (fabOffsetX + dragAmount.x / density).coerceIn(minFabOffsetX, 0f)
                                fabOffsetY = (fabOffsetY + dragAmount.y / density).coerceIn(minFabOffsetY, 0f)
                            }
                        },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "打开快速添加")
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
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
                                    imageVector = if (selected) destination.selectedIcon else destination.icon,
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
            composable(AppDestination.TRACKER.route) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("追番") },
                            actions = {
                                IconButton(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) }) {
                                    Icon(Icons.Outlined.Search, contentDescription = "全局搜索")
                                }
                                IconButton(onClick = { trackerSettingsRequest += 1 }) {
                                    Icon(Icons.Outlined.Tune, contentDescription = "定制追番页")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = chromeContainerColor,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    if (legacyMode) {
                        LegacyTrackerScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            onAnimeClick = { animeId -> navController.navigate("anime/$animeId") },
                            onSeriesClick = { seriesId -> navController.navigate("series/$seriesId") },
                            onAddAnime = { navController.navigate(EDITOR_ROUTE) },
                            onHomeLayoutSelected = onHomeLayoutSelected,
                            onContentDensitySelected = onContentDensitySelected,
                        )
                    } else {
                        TrackerScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            customizationRequest = trackerSettingsRequest,
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
            composable(AppDestination.DISCOVERY.route) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("发现") },
                            actions = {
                                IconButton(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) }) {
                                    Icon(Icons.Outlined.Search, contentDescription = "全局搜索")
                                }
                                IconButton(onClick = { discoverySettingsRequest += 1 }) {
                                    Icon(Icons.Outlined.Tune, contentDescription = "定制发现页")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = chromeContainerColor,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    if (legacyMode) {
                        LegacyDiscoveryScreen(
                            modifier = Modifier.padding(padding),
                            onAnimeImported = { animeId -> navController.navigate("anime/$animeId") },
                            onImageSearchRequested = { navController.navigate(IMAGE_SEARCH_ROUTE) },
                        )
                    } else {
                        DiscoveryScreen(
                            modifier = Modifier.padding(padding),
                            customizationRequest = discoverySettingsRequest,
                            onAnimeImported = { animeId -> navController.navigate("anime/$animeId") },
                            onImageSearchRequested = { navController.navigate(IMAGE_SEARCH_ROUTE) },
                            onCommunityRequested = { navigateToMainDestination(AppDestination.COMMUNITY) },
                        )
                    }
                }
            }
            composable(AppDestination.CALENDAR.route) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("日历") },
                            actions = {
                                IconButton(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) }) {
                                    Icon(Icons.Outlined.Search, contentDescription = "全局搜索")
                                }
                                IconButton(onClick = { calendarSettingsRequest += 1 }) {
                                    Icon(Icons.Outlined.Tune, contentDescription = "定制日历页")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = chromeContainerColor,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    if (legacyMode) {
                        LegacyCalendarScreen(
                            modifier = Modifier.padding(padding),
                            onAnimeClick = { animeId -> navController.navigate("anime/$animeId") },
                        )
                    } else {
                        CalendarScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            customizationRequest = calendarSettingsRequest,
                            onAnimeClick = { animeId -> navController.navigate("anime/$animeId") },
                            onEditAnime = { animeId -> navController.navigate("anime/edit?animeId=$animeId") },
                            onCalendarLayoutPresetSelected = onCalendarLayoutPresetSelected,
                        )
                    }
                }
            }
            composable(AppDestination.COMMUNITY.route) {
                CommunityScreen(
                    onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                    onCharacterCommunityRequested = { navController.navigate(CHARACTER_COMMUNITY_ROUTE) },
                    onManagementRequested = { navController.navigate(COMMUNITY_MANAGEMENT_ROUTE) },
                    onPostClick = { post -> navController.navigate("community/post/${post.id}") },
                    onUserClick = { userId -> navController.navigate("community/user/$userId") },
                    onBack = navController::navigateUp,
                    showBack = currentDestination != AppDestination.COMMUNITY,
                    topBarContainerColor = chromeContainerColor,
                )
            }
            composable(AppDestination.PROFILE.route) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("我的") },
                            actions = {
                                IconButton(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) }) {
                                    Icon(Icons.Outlined.Search, contentDescription = "全局搜索")
                                }
                                IconButton(onClick = { showStylePicker = true }) {
                                    Icon(Icons.Outlined.Palette, contentDescription = "界面版本与整体风格")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = chromeContainerColor,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    if (legacyMode) {
                        LegacyProfileScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            onFrontendModeSelected = onFrontendModeSelected,
                            onOverallStyleRequested = { showStylePicker = true },
                            onThemeModeSelected = onThemeModeSelected,
                            onPredictiveBackEnabledChanged = onPredictiveBackEnabledChanged,
                            onCustomizationRequested = { navController.navigate(CUSTOMIZATION_ROUTE) },
                            onAllToolsRequested = { navController.navigate(LEGACY_PROFILE_TOOLS_ROUTE) },
                            onLibraryManagementRequested = { navController.navigate(LIBRARY_MANAGEMENT_ROUTE) },
                            onReminderManagementRequested = { navController.navigate(REMINDER_MANAGEMENT_ROUTE) },
                            onTrashRequested = { navController.navigate(TRASH_ROUTE) },
                            onDuplicateCleanupRequested = { navController.navigate(DUPLICATES_ROUTE) },
                            onSeriesShelfRequested = { navController.navigate(SERIES_SHELF_ROUTE) },
                            onTagIndexRequested = { navController.navigate(TAG_INDEX_ROUTE) },
                            onStatisticsRequested = { navigateToMainDestination(AppDestination.STATISTICS) },
                            onAnimeAnalysisRequested = { navController.navigate(ANIME_ANALYSIS_ROUTE) },
                            onTierListRequested = { navController.navigate(TIER_LIST_ROUTE) },
                            onCharactersRequested = { navController.navigate(CHARACTERS_ROUTE) },
                            onCommunityRequested = { navController.navigate(CHARACTER_COMMUNITY_ROUTE) },
                            onImageSearchRequested = { navController.navigate(IMAGE_SEARCH_ROUTE) },
                            onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                            onBangumiImportRequested = { navController.navigate(BANGUMI_IMPORT_ROUTE) },
                            onSpreadsheetTransferRequested = { navController.navigate(SPREADSHEET_TRANSFER_ROUTE) },
                            onDiagnosticsRequested = { navController.navigate(DIAGNOSTICS_ROUTE) },
                            onFeedbackPoolRequested = { navController.navigate(FEEDBACK_POOL_ROUTE) },
                            onHelpRequested = { navController.navigate(HELP_ROUTE) },
                            onAboutRequested = { navController.navigate(ABOUT_ROUTE) },
                            onUserManualRequested = { navController.navigate(USER_MANUAL_ROUTE) },
                        )
                    } else {
                        ProfileScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            onFrontendModeSelected = onFrontendModeSelected,
                            onStylePickerRequested = { query -> navController.navigate(customizationRoute(query)) },
                            onCustomizationRequested = { navController.navigate(CUSTOMIZATION_ROUTE) },
                            onSettingsRequested = { navController.navigate(SETTINGS_ROUTE) },
                            onLibraryManagementRequested = { navController.navigate(LIBRARY_MANAGEMENT_ROUTE) },
                            onReminderManagementRequested = { navController.navigate(REMINDER_MANAGEMENT_ROUTE) },
                            onTrashRequested = { navController.navigate(TRASH_ROUTE) },
                            onDuplicateCleanupRequested = { navController.navigate(DUPLICATES_ROUTE) },
                            onSeriesShelfRequested = { navController.navigate(SERIES_SHELF_ROUTE) },
                            onTagIndexRequested = { navController.navigate(TAG_INDEX_ROUTE) },
                            onStatisticsRequested = { navigateToMainDestination(AppDestination.STATISTICS) },
                            onAnimeAnalysisRequested = { navController.navigate(ANIME_ANALYSIS_ROUTE) },
                            onTierListRequested = { navController.navigate(TIER_LIST_ROUTE) },
                            onCharactersRequested = { navController.navigate(CHARACTERS_ROUTE) },
                            onCommunityRequested = { navigateToMainDestination(AppDestination.COMMUNITY) },
                            onBangumiImportRequested = { navController.navigate(BANGUMI_IMPORT_ROUTE) },
                            onSpreadsheetTransferRequested = { navController.navigate(SPREADSHEET_TRANSFER_ROUTE) },
                            onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                            onPersonalCenterRequested = { navController.navigate(PERSONAL_CENTER_ROUTE) },
                            onDiagnosticsRequested = { navController.navigate(DIAGNOSTICS_ROUTE) },
                            onFeedbackPoolRequested = { navController.navigate(FEEDBACK_POOL_ROUTE) },
                            onHelpRequested = { navController.navigate(HELP_ROUTE) },
                            onAboutRequested = { navController.navigate(ABOUT_ROUTE) },
                            onUserManualRequested = { navController.navigate(USER_MANUAL_ROUTE) },
                        )
                    }
                }
            }
            composable(LEGACY_PROFILE_TOOLS_ROUTE) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("全部设置与工具") },
                            navigationIcon = {
                                IconButton(onClick = navController::navigateUp) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                                }
                            },
                        )
                    },
                ) { padding ->
                    ProfileScreen(
                        settings = settings,
                        onFrontendModeSelected = onFrontendModeSelected,
                        onStylePickerRequested = { query -> navController.navigate(customizationRoute(query)) },
                        onCustomizationRequested = { navController.navigate(CUSTOMIZATION_ROUTE) },
                        onSettingsRequested = { navController.navigate(SETTINGS_ROUTE) },
                        onLibraryManagementRequested = { navController.navigate(LIBRARY_MANAGEMENT_ROUTE) },
                        onReminderManagementRequested = { navController.navigate(REMINDER_MANAGEMENT_ROUTE) },
                        onTrashRequested = { navController.navigate(TRASH_ROUTE) },
                        onDuplicateCleanupRequested = { navController.navigate(DUPLICATES_ROUTE) },
                        onSeriesShelfRequested = { navController.navigate(SERIES_SHELF_ROUTE) },
                        onTagIndexRequested = { navController.navigate(TAG_INDEX_ROUTE) },
                        onStatisticsRequested = { navigateToMainDestination(AppDestination.STATISTICS) },
                        onAnimeAnalysisRequested = { navController.navigate(ANIME_ANALYSIS_ROUTE) },
                        onTierListRequested = { navController.navigate(TIER_LIST_ROUTE) },
                        onCharactersRequested = { navController.navigate(CHARACTERS_ROUTE) },
                        onCommunityRequested = { navigateToMainDestination(AppDestination.COMMUNITY) },
                        onBangumiImportRequested = { navController.navigate(BANGUMI_IMPORT_ROUTE) },
                        onSpreadsheetTransferRequested = { navController.navigate(SPREADSHEET_TRANSFER_ROUTE) },
                        onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                        onPersonalCenterRequested = { navController.navigate(PERSONAL_CENTER_ROUTE) },
                        onDiagnosticsRequested = { navController.navigate(DIAGNOSTICS_ROUTE) },
                        onFeedbackPoolRequested = { navController.navigate(FEEDBACK_POOL_ROUTE) },
                        onHelpRequested = { navController.navigate(HELP_ROUTE) },
                        onAboutRequested = { navController.navigate(ABOUT_ROUTE) },
                        onUserManualRequested = { navController.navigate(USER_MANUAL_ROUTE) },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
            composable(SETTINGS_ROUTE) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("设置") },
                            navigationIcon = {
                                IconButton(onClick = navController::navigateUp) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                                }
                            },
                        )
                    },
                ) { padding ->
                    SettingsScreen(
                        settings = settings,
                        onThemeModeSelected = onThemeModeSelected,
                        onOverallStyleRequested = { showStylePicker = true },
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
            composable(GLOBAL_SEARCH_ROUTE) {
                GlobalSearchScreen(
                    onBack = navController::navigateUp,
                    onAnimeClick = { navController.navigate("anime/$it") },
                    onSeriesClick = { navController.navigate("series/$it") },
                    onTagClick = { navController.navigate("tags/$it") },
                    onCharacterClick = { navController.navigate("$CHARACTERS_ROUTE?characterId=$it") },
                    onGroupClick = { navController.navigate("$CHARACTER_GROUPS_ROUTE?groupId=$it") },
                    actions = listOf(
                        GlobalSearchAction(
                            "外观、布局与高度自定义",
                            "主题、字体、圆角、卡片、动效、手势、配置档案与启动体验",
                            "外观 布局 主题 深色 miuix 字体 圆角 动画 手势 配置 启动 图标",
                            Icons.Outlined.Palette,
                        ) { query -> navController.navigate(customizationRoute(query)) },
                        GlobalSearchAction(
                            "预测性返回手势",
                            if (settings.predictiveBackEnabled) "当前已开启；前往“设置”可随时关闭" else "当前已关闭；前往“设置”可选择开启",
                            "预测性返回 返回手势 动画 安卓 手势 back",
                            Icons.Outlined.Settings,
                        ) { _ -> navController.navigate(SETTINGS_ROUTE) },
                        GlobalSearchAction(
                            "状态、标签与系列管理",
                            "新增、配色、排序、迁移和安全删除",
                            "状态 标签 系列 管理 颜色 排序",
                            Icons.Outlined.Settings,
                        ) { _ -> navController.navigate(LIBRARY_MANAGEMENT_ROUTE) },
                        GlobalSearchAction(
                            "追番提醒管理",
                            "权限检查、任务同步、测试通知与集中编辑",
                            "提醒 通知 权限 闹钟 时间",
                            Icons.Outlined.NotificationsActive,
                        ) { _ -> navController.navigate(REMINDER_MANAGEMENT_ROUTE) },
                        GlobalSearchAction(
                            "数据统计与 AI 分析",
                            "双布局、指标显隐、状态、评分、标签、活动图表和看番风格",
                            "统计 图表 ai 分析 评分 标签 时长 布局 仪表盘 连续打卡 平均评分",
                            Icons.Outlined.BarChart,
                        ) { _ -> navController.navigate(STATISTICS_ROUTE) },
                        GlobalSearchAction(
                            "云账号与设备备份",
                            "登录、上传、同步、冲突与跨设备恢复",
                            "云端 账号 登录 注册 同步 备份 恢复",
                            Icons.Outlined.CloudDone,
                        ) { _ -> navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                        GlobalSearchAction(
                            "导入原版与完整备份",
                            "兼容原版 ZIP / SQLite，并支持原生完整备份",
                            "原版 旧版 数据库 sqlite zip 迁移 备份",
                            Icons.Outlined.Storage,
                        ) { _ -> navController.navigate(AppDestination.PROFILE.route) },
                        GlobalSearchAction(
                            "Excel / CSV 搬家",
                            "自定义列映射、预检、批量导入与导出",
                            "excel csv xlsx 表格 导入 导出",
                            Icons.Outlined.TableView,
                        ) { _ -> navController.navigate(SPREADSHEET_TRANSFER_ROUTE) },
                        GlobalSearchAction(
                            "导入 Bangumi 收藏",
                            "按账号预检并安全合并公开收藏",
                            "bangumi 收藏 导入 用户名 uid",
                            Icons.Outlined.CloudDownload,
                        ) { _ -> navController.navigate(BANGUMI_IMPORT_ROUTE) },
                        GlobalSearchAction(
                            "登录社区",
                            "官方群聊、图文交流、个人统计名片与举报复审",
                            "社区 群聊 帖子 图片 名片 举报 群码",
                            Icons.Outlined.Groups,
                        ) { _ -> navController.navigate(AppDestination.COMMUNITY.route) },
                        GlobalSearchAction(
                            "角色群组分享工具",
                            "浏览公开角色组、分享码预览、导入与发布管理",
                            "角色 群组 分享码 导入 发布 公开",
                            Icons.Outlined.Groups,
                        ) { _ -> navController.navigate(CHARACTER_COMMUNITY_ROUTE) },
                        GlobalSearchAction(
                            "诊断与操作轨迹",
                            "崩溃恢复、错误堆栈、脱敏复制与导出",
                            "诊断 日志 崩溃 bug 错误 隐私",
                            Icons.Outlined.BugReport,
                        ) { _ -> navController.navigate(DIAGNOSTICS_ROUTE) },
                        GlobalSearchAction(
                            "开放反馈池",
                            "公开浏览建议、问题与功能想法；登录后可提交反馈",
                            "反馈 反馈池 建议 bug 意见 功能 公开",
                            Icons.Outlined.BugReport,
                        ) { _ -> navController.navigate(FEEDBACK_POOL_ROUTE) },
                        GlobalSearchAction(
                            "帮助与常见问题",
                            "Q1–Q10 使用指南、排障和数据安全说明",
                            "帮助 faq 常见问题 闪退 封面 提醒 备份",
                            Icons.AutoMirrored.Outlined.HelpOutline,
                        ) { _ -> navController.navigate(HELP_ROUTE) },
                        GlobalSearchAction(
                            "关于与维护",
                            "更新、缓存、更新日志、声明、鸣谢与开源渠道",
                            "关于 更新 缓存 日志 声明 鸣谢 开源 版本",
                            Icons.Outlined.Info,
                        ) { _ -> navController.navigate(ABOUT_ROUTE) },
                        GlobalSearchAction(
                            "用户使用手册",
                            "全部功能索引、设置位置与每日小贴士",
                            "手册 使用 帮助 指南 教程 说明 功能 位置 每日 提示 找不到",
                            Icons.AutoMirrored.Outlined.MenuBook,
                        ) { _ -> navController.navigate(USER_MANUAL_ROUTE) },
                        GlobalSearchAction(
                            "黑夜模式",
                            if (settings.themeMode == ThemeMode.DARK) "已固定深色护眼主题" else "当前明亮或跟随系统",
                            "黑夜 深色 暗色 主题 模式 护眼 夜间",
                            Icons.Outlined.DarkMode,
                        ) { _ -> navController.navigate(SETTINGS_ROUTE) },
                        GlobalSearchAction(
                            "显示发现/日历/社区/统计",
                            "控制底部导航入口的可见性",
                            "显示 发现 日历 社区 统计 入口 导航 隐藏 底部",
                            Icons.Outlined.Explore,
                        ) { _ -> navController.navigate(SETTINGS_ROUTE) },
                        GlobalSearchAction(
                            "导航顺序与启动落点",
                            "调整底部导航排列顺序和启动首页",
                            "导航 顺序 启动 落点 首页 排列 底部",
                            Icons.Outlined.SwapVert,
                        ) { _ -> navController.navigate(SETTINGS_ROUTE) },
                        GlobalSearchAction(
                            "返回退出方式",
                            "双击退出、弹窗确认或直接退出",
                            "返回 退出 双击 弹窗 直接 退出方式",
                            Icons.Outlined.Settings,
                        ) { _ -> navController.navigate(SETTINGS_ROUTE) },
                        GlobalSearchAction(
                            "显示版本号",
                            "在「我的」页面底部显示当前应用版本",
                            "版本 版本号 显示 底部",
                            Icons.Outlined.Tag,
                        ) { _ -> navController.navigate(SETTINGS_ROUTE) },
                    ),
                )
            }
            composable(LIBRARY_MANAGEMENT_ROUTE) {
                LibraryManagementScreen(
                    onBack = navController::navigateUp,
                    onTagIndexRequested = { navController.navigate(TAG_INDEX_ROUTE) },
                    onTagClick = { navController.navigate("tags/$it") },
                )
            }
            composable(REMINDER_MANAGEMENT_ROUTE) {
                ReminderManagementScreen(
                    onBack = navController::navigateUp,
                    onEditReminder = { navController.navigate("anime/edit?animeId=$it") },
                )
            }
            composable(TRASH_ROUTE) {
                TrashScreen(onBack = navController::navigateUp)
            }
            composable(DUPLICATES_ROUTE) {
                DuplicateCleanupScreen(onBack = navController::navigateUp)
            }
            composable(IMAGE_SEARCH_ROUTE) {
                ImageSearchScreen(
                    onBack = navController::navigateUp,
                    onAnimeImported = { animeId -> navController.navigate("anime/$animeId") },
                )
            }
            composable(STATISTICS_ROUTE) {
                StatisticsScreen(
                    onBack = navController::navigateUp,
                    showBack = currentDestination != AppDestination.STATISTICS,
                    topBarContainerColor = chromeContainerColor,
                    onSearchRequested = { navController.navigate(GLOBAL_SEARCH_ROUTE) },
                    onAnalysisRequested = { navController.navigate(ANIME_ANALYSIS_ROUTE) },
                    onTagIndexRequested = { navController.navigate(TAG_INDEX_ROUTE) },
                    onStatusSelected = { status ->
                        val routeValue = if (status.isBlank()) UNCLASSIFIED_STATUS_ROUTE_TOKEN else Uri.encode(status)
                        navController.navigate("statistics/status/$routeValue")
                    },
                    onTagSelected = { tagId -> navController.navigate("tags/$tagId") },
                )
            }
            composable(
                route = STATUS_COLLECTION_ROUTE,
                arguments = listOf(navArgument("statusName") { type = NavType.StringType }),
            ) {
                StatusAnimeScreen(
                    settings = settings,
                    onBack = navController::navigateUp,
                    onAnimeClick = { navController.navigate("anime/$it") },
                )
            }
            composable(TAG_INDEX_ROUTE) {
                TagIndexScreen(
                    onBack = navController::navigateUp,
                    onTagClick = { navController.navigate("tags/$it") },
                )
            }
            composable(
                route = TAG_COLLECTION_ROUTE,
                arguments = listOf(navArgument("tagId") { type = NavType.LongType }),
            ) {
                TagCollectionScreen(
                    settings = settings,
                    onBack = navController::navigateUp,
                    onAnimeClick = { navController.navigate("anime/$it") },
                )
            }
            composable(ANIME_ANALYSIS_ROUTE) {
                AnimeAnalysisScreen(onBack = navController::navigateUp)
            }
            composable(TIER_LIST_ROUTE) {
                TierListScreen(onBack = navController::navigateUp)
            }
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
            composable(CHARACTER_COMMUNITY_ROUTE) {
                CharacterCommunityScreen(
                    settings = settings,
                    onBack = navController::navigateUp,
                    initialRequest = approvedCommunityRequest,
                    onInitialRequestConsumed = { approvedCommunityRequest = null },
                )
            }
            composable(BANGUMI_IMPORT_ROUTE) {
                BangumiImportScreen(onBack = navController::navigateUp)
            }
            composable(SPREADSHEET_TRANSFER_ROUTE) {
                SpreadsheetTransferScreen(onBack = navController::navigateUp)
            }
            composable(
                route = CUSTOMIZATION_ROUTE_PATTERN,
                arguments = listOf(
                    navArgument("query") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                CustomizationScreen(
                    onBack = navController::navigateUp,
                    onBrandingRequested = { navController.navigate(BRANDING_ROUTE) },
                    initialQuery = entry.arguments?.getString("query").orEmpty(),
                )
            }
            composable(BRANDING_ROUTE) {
                BrandingScreen(onBack = navController::navigateUp)
            }
            composable(COMMUNITY_MANAGEMENT_ROUTE) {
                CommunityManagementScreen(onBack = navController::navigateUp)
            }
            composable(
                route = COMMUNITY_POST_DETAIL_ROUTE,
                arguments = listOf(navArgument("postId") { type = NavType.LongType }),
            ) { entry ->
                val postId = entry.arguments?.getLong("postId") ?: return@composable
                CommunityPostDetailScreen(
                    postId = postId,
                    onBack = navController::navigateUp,
                    onUserClick = { userId -> navController.navigate("community/user/$userId") },
                )
            }
            composable(
                route = COMMUNITY_USER_SPACE_ROUTE,
                arguments = listOf(navArgument("userId") { type = NavType.LongType }),
            ) { entry ->
                val userId = entry.arguments?.getLong("userId") ?: return@composable
                CommunityUserSpaceScreen(
                    userId = userId,
                    onBack = navController::navigateUp,
                    onPostClick = { post -> navController.navigate("community/post/${post.id}") },
                    onUserClick = { id -> navController.navigate("community/user/$id") },
                )
            }
            composable(CLOUD_ACCOUNT_ROUTE) {
                CloudAccountScreen(
                    onBack = navController::navigateUp,
                    onFeedbackPoolRequested = { navController.navigate(FEEDBACK_POOL_ROUTE) },
                )
            }
            composable(PERSONAL_CENTER_ROUTE) {
                PersonalCenterScreen(
                    onBack = navController::navigateUp,
                    onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                    onCommunityRequested = { navController.navigate(AppDestination.COMMUNITY.route) },
                )
            }
            composable(FEEDBACK_POOL_ROUTE) {
                FeedbackPoolScreen(
                    onBack = navController::navigateUp,
                    onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
                )
            }
            composable(DIAGNOSTICS_ROUTE) {
                DiagnosticsScreen(onBack = navController::navigateUp)
            }
            composable(ABOUT_ROUTE) {
                AboutCenterScreen(
                    updateState = updateState,
                    onBack = navController::navigateUp,
                    onCheckUpdate = { updateViewModel.check() },
                    onAutoCheckChanged = updateViewModel::setAutoCheckUpdates,
                    onClearIgnoredVersion = updateViewModel::clearIgnoredVersion,
                    onCacheRequested = { navController.navigate(CACHE_ROUTE) },
                    onHelpRequested = { navController.navigate(HELP_ROUTE) },
                    onChangelogRequested = { navController.navigate(CHANGELOG_ROUTE) },
                    onDisclaimerRequested = { navController.navigate(DISCLAIMER_ROUTE) },
                    onCreditsRequested = { navController.navigate(CREDITS_ROUTE) },
                )
            }
            composable(CACHE_ROUTE) { CacheManagementScreen(onBack = navController::navigateUp) }
            composable(HELP_ROUTE) {
                HelpCenterScreen(
                    onBack = navController::navigateUp,
                    onDiagnosticsRequested = { navController.navigate(DIAGNOSTICS_ROUTE) },
                    onCacheRequested = { navController.navigate(CACHE_ROUTE) },
                )
            }
            composable(CHANGELOG_ROUTE) { ChangelogScreen(onBack = navController::navigateUp) }
            composable(DISCLAIMER_ROUTE) { DisclaimerScreen(onBack = navController::navigateUp) }
            composable(CREDITS_ROUTE) { CreditsSupportScreen(onBack = navController::navigateUp) }
            composable(USER_MANUAL_ROUTE) { UserManualScreen(onBack = navController::navigateUp) }
            composable(SERIES_SHELF_ROUTE) {
                SeriesShelfScreen(
                    onBack = navController::navigateUp,
                    onSeriesClick = { navController.navigate("series/$it") },
                    onAnimeClick = { navController.navigate("anime/$it") },
                )
            }
            composable(
                route = SERIES_DETAIL_ROUTE,
                arguments = listOf(navArgument("seriesId") { type = NavType.LongType }),
            ) {
                SeriesDetailScreen(
                    onBack = navController::navigateUp,
                    onAnimeClick = { navController.navigate("anime/$it") },
                )
            }
            composable(
                route = DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("animeId") { type = NavType.LongType },
                ),
            ) {
                if (legacyMode) {
                    LegacyAnimeDetailScreen(
                        onBack = navController::navigateUp,
                        onEdit = { animeId -> navController.navigate("anime/edit?animeId=$animeId") },
                        settings = settings,
                    )
                } else {
                    AnimeDetailScreen(
                        onBack = navController::navigateUp,
                        onEdit = { animeId -> navController.navigate("anime/edit?animeId=$animeId") },
                        settings = settings,
                        onDetailModuleOrderChanged = onDetailModuleOrderChanged,
                        onHiddenDetailModulesChanged = onHiddenDetailModulesChanged,
                        onDetailCardStyleSelected = onDetailCardStyleSelected,
                        onManageCharacters = { navController.navigate(CHARACTERS_ROUTE) },
                        onAnimeOpen = { navController.navigate("anime/$it") },
                        onSeriesOpen = { navController.navigate("series/$it") },
                        onTagOpen = { navController.navigate("tags/$it") },
                          onCharacterOpen = { characterId -> navController.navigate("$CHARACTERS_ROUTE?characterId=$characterId") },
                    )
                }
            }
            composable(
                route = EDITOR_ROUTE_PATTERN,
                arguments = listOf(
                    navArgument("animeId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument("subjectType") {
                        type = NavType.StringType
                        defaultValue = "anime"
                    },
                ),
            ) {
                val onSaved: (Long) -> Unit = { animeId ->
                    navController.navigate("anime/$animeId") {
                        popUpTo(AppDestination.TRACKER.route)
                        launchSingleTop = true
                    }
                }
                if (legacyMode) {
                    LegacyAnimeEditorScreen(
                        onBack = navController::navigateUp,
                        onSaved = onSaved,
                    )
                } else {
                    AnimeEditorScreen(
                        onBack = navController::navigateUp,
                        onSaved = onSaved,
                    )
                }
            }
        }
        }
    }

        PredictiveBackHandler(
            enabled = settings.predictiveBackEnabled &&
                isMainDestination && currentDestination != AppDestination.TRACKER,
        ) { progress ->
            try {
                progress.collect { event -> predictiveBackProgress = event.progress }
                navigateToMainDestination(AppDestination.TRACKER)
            } catch (_: CancellationException) {
                // Cancelling the edge gesture keeps the current destination.
            } finally {
                predictiveBackProgress = 0f
            }
        }
        BackHandler(
            enabled = !settings.predictiveBackEnabled ||
                (isMainDestination && currentDestination == AppDestination.TRACKER &&
                    settings.exitBehavior != com.animeow.app.ui.theme.ExitBehavior.DIRECT),
        ) {
            predictiveBackProgress = 0f
            when {
                isMainDestination && currentDestination != AppDestination.TRACKER -> {
                    navigateToMainDestination(AppDestination.TRACKER)
                }
                navController.previousBackStackEntry != null -> navController.navigateUp()
                else -> handleExit(
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

    if (!settings.frontendModeChoiceMade) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("选择你习惯的 AniMeow") },
            text = {
                Text(
                    "经典版复刻 v1.3.9 的四栏导航、追番布局、发现筛选和追随日历；" +
                        "新版保留 2.0 的全部新设计。之后可在“我的”中随时切换。",
                )
            },
            confirmButton = {
                TextButton(onClick = { onFrontendModeSelected(FrontendMode.LEGACY) }) {
                    Text("返回经典版")
                }
            },
            dismissButton = {
                TextButton(onClick = { onFrontendModeSelected(FrontendMode.MODERN) }) {
                    Text("体验新版")
                }
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
    if (showQuickAdd) {
        QuickAddSheet(
            onDismissRequest = { showQuickAdd = false },
            onOnlineSearch = {
                showQuickAdd = false
                navController.navigate(AppDestination.DISCOVERY.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onImageSearch = {
                showQuickAdd = false
                navController.navigate(IMAGE_SEARCH_ROUTE)
            },
            onManualCreate = {
                showQuickAdd = false
                navController.navigate(EDITOR_ROUTE)
            },
            onManualCreateBook = {
                showQuickAdd = false
                navController.navigate(BOOK_EDITOR_ROUTE)
            },
            onBangumiImport = {
                showQuickAdd = false
                navController.navigate(BANGUMI_IMPORT_ROUTE)
            },
            onSpreadsheetImport = {
                showQuickAdd = false
                navController.navigate(SPREADSHEET_TRANSFER_ROUTE)
            },
        )
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
                        approvedCommunityRequest = request
                        onCommunityLaunchRequestConsumed()
                        navController.navigate(CHARACTER_COMMUNITY_ROUTE) { launchSingleTop = true }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddSheet(
    onDismissRequest: () -> Unit,
    onOnlineSearch: () -> Unit,
    onImageSearch: () -> Unit,
    onManualCreate: () -> Unit,
    onManualCreateBook: () -> Unit,
    onBangumiImport: () -> Unit,
    onSpreadsheetImport: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                "快速添加",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "选择最适合当前资料来源的入口，之后仍可继续完整编辑。",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuickAddAction(Icons.Outlined.EditNote, "手动创建", "从空白表单创建动画，之后仍可修改作品类型", onManualCreate)
            QuickAddAction(Icons.AutoMirrored.Outlined.MenuBook, "创建书籍", "直接以书籍类型创建作品", onManualCreateBook)
            QuickAddAction(Icons.Outlined.Search, "从发现中在线搜索", "前往发现，从 Bangumi、AniList 或资料库查找", onOnlineSearch)
            QuickAddAction(Icons.Outlined.ImageSearch, "以图搜番", "上传截图识别动画、集数和时间点", onImageSearch)
            QuickAddAction(Icons.Outlined.CloudDownload, "导入 Bangumi 收藏", "按账号预检并合并公开收藏", onBangumiImport)
            QuickAddAction(Icons.Outlined.TableView, "导入 Excel / CSV", "自定义列映射并预检批量数据", onSpreadsheetImport)
        }
    }
}

@Composable
private fun QuickAddAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = "打开$title",
                onClick = onClick,
            ),
    )
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

private const val DETAIL_ROUTE = "anime/{animeId}"
private const val GLOBAL_SEARCH_ROUTE = "search/global"
private const val LEGACY_PROFILE_TOOLS_ROUTE = "legacy/profile-tools"
private const val SETTINGS_ROUTE = "settings"
private const val EDITOR_ROUTE_PATTERN = "anime/edit?animeId={animeId}&subjectType={subjectType}"
private const val EDITOR_ROUTE = "anime/edit?animeId=-1&subjectType=anime"
private const val BOOK_EDITOR_ROUTE = "anime/edit?animeId=-1&subjectType=book"
private const val LIBRARY_MANAGEMENT_ROUTE = "library/manage"
private const val REMINDER_MANAGEMENT_ROUTE = "settings/reminders"
private const val TRASH_ROUTE = "library/trash"
private const val DUPLICATES_ROUTE = "library/duplicates"
private const val SERIES_SHELF_ROUTE = "library/series"
private const val SERIES_DETAIL_ROUTE = "series/{seriesId}"
private const val IMAGE_SEARCH_ROUTE = "discovery/image-search"
private const val STATISTICS_ROUTE = "statistics"
private const val STATUS_COLLECTION_ROUTE = "statistics/status/{statusName}"
private const val TAG_INDEX_ROUTE = "tags"
private const val TAG_COLLECTION_ROUTE = "tags/{tagId}"
private const val ANIME_ANALYSIS_ROUTE = "statistics/analysis"
private const val TIER_LIST_ROUTE = "tier-list"
private const val CHARACTERS_ROUTE = "characters"
private const val CHARACTERS_ROUTE_PATTERN = "characters?characterId={characterId}"
private const val CHARACTER_GROUPS_ROUTE = "characters/groups"
private const val CHARACTER_GROUPS_ROUTE_PATTERN = "characters/groups?groupId={groupId}"
private const val CHARACTER_COMMUNITY_ROUTE = "characters/community"
private const val BANGUMI_IMPORT_ROUTE = "data/bangumi-import"
private const val SPREADSHEET_TRANSFER_ROUTE = "data/spreadsheet"
private const val CUSTOMIZATION_ROUTE = "settings/customization"
private const val CUSTOMIZATION_ROUTE_PATTERN = "settings/customization?query={query}"
private const val BRANDING_ROUTE = "settings/customization/branding"
private const val CLOUD_ACCOUNT_ROUTE = "account/cloud"
private const val PERSONAL_CENTER_ROUTE = "account/personal"
private const val COMMUNITY_MANAGEMENT_ROUTE = "community/manage"
private const val COMMUNITY_POST_DETAIL_ROUTE = "community/post/{postId}"
private const val COMMUNITY_USER_SPACE_ROUTE = "community/user/{userId}"
private const val FEEDBACK_POOL_ROUTE = "account/feedback"
private const val DIAGNOSTICS_ROUTE = "settings/diagnostics"
private const val ABOUT_ROUTE = "settings/about"
private const val CACHE_ROUTE = "settings/about/cache"
private const val HELP_ROUTE = "settings/about/help"
private const val CHANGELOG_ROUTE = "settings/about/changelog"
private const val DISCLAIMER_ROUTE = "settings/about/disclaimer"
private const val CREDITS_ROUTE = "settings/about/credits"
private const val USER_MANUAL_ROUTE = "settings/manual"

private fun customizationRoute(query: String): String = query.trim().takeIf(String::isNotEmpty)
    ?.let { "$CUSTOMIZATION_ROUTE?query=${Uri.encode(it)}" }
    ?: CUSTOMIZATION_ROUTE

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
