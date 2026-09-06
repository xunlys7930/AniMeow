package com.animeow.app.data.preferences

import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.APP_FONT_SCALE_MAX
import com.animeow.app.ui.theme.APP_FONT_SCALE_MIN
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CalendarLayoutPreset
import com.animeow.app.ui.theme.CharacterLayout
import com.animeow.app.ui.theme.CharacterImageAlignment
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.NavigationLabelMode
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ProfileSearchStyle
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.PageTransitionStyle
import com.animeow.app.ui.theme.RatingIconStyle
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.theme.StatisticsModule
import com.animeow.app.ui.theme.StatisticsPreset
import com.animeow.app.ui.theme.StatisticsChartStyle
import com.animeow.app.ui.theme.StatisticsLayoutStyle
import com.animeow.app.ui.theme.StatisticsMetric
import com.animeow.app.ui.theme.CommunityGroupView
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.TierListStyle
import com.animeow.app.ui.theme.TopBarSpacing
import com.animeow.app.ui.theme.normalizeTierBoardTitle
import com.animeow.app.ui.theme.normalizeTierDisplayLabels
import com.animeow.app.ui.theme.parseNavigationOrder
import com.animeow.app.ui.theme.normalizedNavigationVisibility
import org.json.JSONArray
import org.json.JSONObject

object AppearanceSettingsCodec {
    const val SCHEMA_VERSION = 14

    fun toJson(settings: AppearanceSettings): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("frontendMode", settings.frontendMode.storageKey)
        .put("frontendModeChoiceMade", settings.frontendModeChoiceMade)
        .put("appStyle", settings.appStyle.storageKey)
        .put("themeMode", settings.themeMode.storageKey)
        .put("homeLayout", settings.homeLayout.storageKey)
        .put("detailLayout", settings.detailLayout.storageKey)
        .put("contentDensity", settings.contentDensity.storageKey)
        .put("topBarSpacing", settings.topBarSpacing.storageKey)
        .put("motionLevel", settings.motionLevel.storageKey)
        .put("pageTransitionStyle", settings.pageTransitionStyle.storageKey)
        .put("predictiveBackEnabled", settings.predictiveBackEnabled)
        .put("exitBehavior", settings.exitBehavior.storageKey)
        .put("useDynamicColor", settings.useDynamicColor)
        .put("fontScale", settings.fontScale)
        .put("cornerScale", settings.cornerScale)
        .put("gridColumns", settings.gridColumns)
        .put("showTitle", settings.showTitle)
        .put("showStatus", settings.showStatus)
        .put("showRating", settings.showRating)
        .put("showProgress", settings.showProgress)
        .put("coverBadgeStyle", settings.coverBadgeStyle.storageKey)
        .put("ratingIconStyle", settings.ratingIconStyle.storageKey)
        .put("ratingBadgeColorStyle", settings.ratingBadgeColorStyle.storageKey)
        .put("ratingBadgeCustomColor", settings.ratingBadgeCustomColor)
        .put("coverTitlePosition", settings.coverTitlePosition.storageKey)
        .put("coverAspectRatio", settings.coverAspectRatio.storageKey)
        .put("detailCardStyle", settings.detailCardStyle.storageKey)
        .put("profileSearchStyle", settings.profileSearchStyle.storageKey)
        .put("showDiscovery", settings.showDiscovery)
        .put("showCalendar", settings.showCalendar)
        .put("showCommunity", settings.showCommunity)
        .put("showStatistics", settings.showStatistics)
        .put("detailModuleOrder", settings.detailModuleOrder.joinToString(",") { it.storageKey })
        .put("hiddenDetailModules", settings.hiddenDetailModules.joinToString(",") { it.storageKey })
        .put("cardSwipeActionsEnabled", settings.cardSwipeActionsEnabled)
        .put("swipeStartAction", settings.swipeStartAction.storageKey)
        .put("swipeEndAction", settings.swipeEndAction.storageKey)
        .put("hapticFeedback", settings.hapticFeedback)
        .put("soundFeedback", settings.soundFeedback)
        .put("autoCompleteStatus", settings.autoCompleteStatus)
        .put("completionStatus", settings.completionStatus)
        .put("accentColor", settings.accentColor ?: JSONObject.NULL)
        .put("randomAccentOnLaunch", settings.randomAccentOnLaunch)
        .put("navigationOrder", settings.navigationOrder.joinToString(","))
        .put("startDestination", settings.startDestination)
        .put("statisticsModuleOrder", settings.statisticsModuleOrder.joinToString(",") { it.storageKey })
        .put("hiddenStatisticsModules", settings.hiddenStatisticsModules.joinToString(",") { it.storageKey })
        .put("statisticsPreset", settings.statisticsPreset.storageKey)
        .put("statisticsDensity", settings.statisticsDensity.storageKey)
        .put("statisticsStatusChart", settings.statisticsStatusChart.storageKey)
        .put("statisticsTagChart", settings.statisticsTagChart.storageKey)
        .put("statisticsLayoutStyle", settings.statisticsLayoutStyle.storageKey)
        .put("hiddenStatisticsMetrics", settings.hiddenStatisticsMetrics.joinToString(",") { it.storageKey })
        .put("communityGroupView", settings.communityGroupView.storageKey)
        .put("communityDensity", settings.communityDensity.storageKey)
        .put("communityShowDescription", settings.communityShowDescription)
        .put("communityShowDownloads", settings.communityShowDownloads)
        .put("tierListStyle", settings.tierListStyle.storageKey)
        .put("tierListCount", settings.tierListCount.coerceIn(3, 5))
        .put("tierBoardTitle", settings.tierBoardTitle)
        .put("tierDisplayLabels", JSONArray(settings.tierDisplayLabels))
        .put("characterLayout", settings.characterLayout.storageKey)
        .put("characterImageAlignment", settings.characterImageAlignment.storageKey)
        .put("characterShowMetadata", settings.characterShowMetadata)
        .put("characterShowRating", settings.characterShowRating)
        .put("navigationBarStyle", settings.navigationBarStyle.storageKey)
        .put("floatingBarHorizontalMargin", settings.floatingBarHorizontalMargin)
        .put("floatingBarBottomMargin", settings.floatingBarBottomMargin)
        .put("floatingBarCornerRadius", settings.floatingBarCornerRadius)
        .put("floatingBarShadowElevation", settings.floatingBarShadowElevation)
        .put("floatingBarHeight", settings.floatingBarHeight)
        .put("navigationLabelMode", settings.navigationLabelMode.storageKey)
        .put("calendarLayoutPreset", settings.calendarLayoutPreset.storageKey)
        .put("clipboardShareDetection", settings.clipboardShareDetection)
        .put("statusChipFilled", settings.statusChipFilled)
        .put("showVersionInProfile", settings.showVersionInProfile)

    fun fromJson(json: JSONObject): AppearanceSettings = AppearanceSettings(
        frontendMode = FrontendMode.fromStorage(json.optString("frontendMode")),
        frontendModeChoiceMade = json.optBoolean("frontendModeChoiceMade", false),
        appStyle = AppStyle.fromStorage(json.optString("appStyle")),
        themeMode = ThemeMode.fromStorage(json.optString("themeMode")),
        homeLayout = HomeLayout.fromStorage(json.optString("homeLayout")),
        detailLayout = DetailLayout.fromStorage(json.optString("detailLayout")),
        contentDensity = ContentDensity.fromStorage(json.optString("contentDensity")),
        topBarSpacing = TopBarSpacing.fromStorage(json.optString("topBarSpacing")),
        motionLevel = MotionLevel.fromStorage(json.optString("motionLevel")),
        pageTransitionStyle = PageTransitionStyle.fromStorage(json.optString("pageTransitionStyle")),
        predictiveBackEnabled = json.optBoolean("predictiveBackEnabled", true),
        exitBehavior = com.animeow.app.ui.theme.ExitBehavior.fromStorage(json.optString("exitBehavior")),
        useDynamicColor = json.optBoolean("useDynamicColor", false),
        fontScale = json.optDouble("fontScale", 1.0).toFloat().coerceIn(APP_FONT_SCALE_MIN, APP_FONT_SCALE_MAX),
        cornerScale = json.optDouble("cornerScale", 1.0).toFloat().coerceIn(0.5f, 1.5f),
        gridColumns = json.optInt("gridColumns", 3).coerceIn(2, 6),
        showTitle = json.optBoolean("showTitle", true),
        showStatus = json.optBoolean("showStatus", true),
        showRating = json.optBoolean("showRating", true),
        showProgress = json.optBoolean("showProgress", true),
        coverBadgeStyle = CoverBadgeStyle.fromStorage(json.optString("coverBadgeStyle")),
        ratingIconStyle = RatingIconStyle.fromStorage(json.optString("ratingIconStyle")),
        ratingBadgeColorStyle = RatingBadgeColorStyle.fromStorage(json.optString("ratingBadgeColorStyle")),
        ratingBadgeCustomColor = if (json.has("ratingBadgeCustomColor")) json.getLong("ratingBadgeCustomColor") else 0xFFFF9800,
        coverTitlePosition = CoverTitlePosition.fromStorage(json.optString("coverTitlePosition")),
        coverAspectRatio = CoverAspectRatio.fromStorage(json.optString("coverAspectRatio")),
        detailCardStyle = DetailCardStyle.fromStorage(json.optString("detailCardStyle")),
        profileSearchStyle = ProfileSearchStyle.fromStorage(json.optString("profileSearchStyle")),
        showDiscovery = json.optBoolean("showDiscovery", true),
        showCalendar = json.optBoolean("showCalendar", true),
        showCommunity = json.optBoolean("showCommunity", true),
        showStatistics = json.optBoolean("showStatistics", false),
        detailModuleOrder = DetailModule.parseOrder(json.optString("detailModuleOrder")),
        hiddenDetailModules = DetailModule.parseSet(json.optString("hiddenDetailModules")),
        cardSwipeActionsEnabled = json.optBoolean("cardSwipeActionsEnabled", false),
        swipeStartAction = SwipeAction.fromStorage(json.optString("swipeStartAction"), SwipeAction.INCREMENT),
        swipeEndAction = SwipeAction.fromStorage(json.optString("swipeEndAction"), SwipeAction.CYCLE_STATUS),
        hapticFeedback = json.optBoolean("hapticFeedback", true),
        soundFeedback = json.optBoolean("soundFeedback", false),
        autoCompleteStatus = json.optBoolean("autoCompleteStatus", true),
        completionStatus = json.optString("completionStatus", "看完"),
        accentColor = json.optLong("accentColor").takeIf { json.has("accentColor") && !json.isNull("accentColor") },
        randomAccentOnLaunch = json.optBoolean("randomAccentOnLaunch", false),
        navigationOrder = parseNavigationOrder(json.optString("navigationOrder")),
        startDestination = json.optString("startDestination", "tracker"),
        statisticsModuleOrder = StatisticsModule.parseOrder(json.optString("statisticsModuleOrder")),
        hiddenStatisticsModules = StatisticsModule.parseSet(json.optString("hiddenStatisticsModules")),
        statisticsPreset = StatisticsPreset.fromStorage(json.optString("statisticsPreset")),
        statisticsDensity = ContentDensity.fromStorage(json.optString("statisticsDensity")),
        statisticsStatusChart = StatisticsChartStyle.fromStorage(json.optString("statisticsStatusChart")),
        statisticsTagChart = StatisticsChartStyle.fromStorage(json.optString("statisticsTagChart")),
        statisticsLayoutStyle = StatisticsLayoutStyle.fromStorage(json.optString("statisticsLayoutStyle")),
        hiddenStatisticsMetrics = StatisticsMetric.parseSet(
            json.optString("hiddenStatisticsMetrics").takeIf { json.has("hiddenStatisticsMetrics") },
        ),
        communityGroupView = CommunityGroupView.fromStorage(json.optString("communityGroupView")),
        communityDensity = ContentDensity.fromStorage(json.optString("communityDensity")),
        communityShowDescription = json.optBoolean("communityShowDescription", true),
        communityShowDownloads = json.optBoolean("communityShowDownloads", true),
        tierListStyle = TierListStyle.fromStorage(json.optString("tierListStyle")),
        tierListCount = json.optInt("tierListCount", 5).coerceIn(3, 5),
        tierBoardTitle = normalizeTierBoardTitle(json.optString("tierBoardTitle")),
        tierDisplayLabels = normalizeTierDisplayLabels(
            json.optJSONArray("tierDisplayLabels")?.let { array ->
                List(array.length()) { index -> array.optString(index) }
            },
        ),
        characterLayout = CharacterLayout.fromStorage(json.optString("characterLayout")),
        characterImageAlignment = CharacterImageAlignment.fromStorage(json.optString("characterImageAlignment")),
        characterShowMetadata = json.optBoolean("characterShowMetadata", true),
        characterShowRating = json.optBoolean("characterShowRating", true),
        navigationBarStyle = NavigationBarStyle.fromStorage(json.optString("navigationBarStyle")),
        floatingBarHorizontalMargin = json.optInt("floatingBarHorizontalMargin", 20).coerceIn(0, 60),
        floatingBarBottomMargin = json.optInt("floatingBarBottomMargin", 6).coerceIn(0, 40),
        floatingBarCornerRadius = json.optInt("floatingBarCornerRadius", 32).coerceIn(0, 48),
        floatingBarShadowElevation = json.optInt("floatingBarShadowElevation", 12).coerceIn(0, 24),
        floatingBarHeight = json.optInt("floatingBarHeight", 56).coerceIn(48, 80),
        navigationLabelMode = NavigationLabelMode.fromStorage(json.optString("navigationLabelMode")),
        calendarLayoutPreset = CalendarLayoutPreset.fromStorage(json.optString("calendarLayoutPreset")),
        clipboardShareDetection = json.optBoolean("clipboardShareDetection", false),
        statusChipFilled = json.optBoolean("statusChipFilled", true),
        showVersionInProfile = json.optBoolean("showVersionInProfile", true),
    ).normalizedNavigationVisibility()
}
