package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.APP_FONT_SCALE_MAX
import com.animeow.app.ui.theme.APP_FONT_SCALE_MIN
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.PageTransitionStyle
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.parseNavigationOrder
import com.animeow.app.ui.theme.normalizedNavigationVisibility
import com.animeow.app.ui.theme.withNavigationDestinationVisible
import com.animeow.app.ui.theme.StatisticsModule
import com.animeow.app.ui.theme.StatisticsPreset
import com.animeow.app.ui.theme.StatisticsChartStyle
import com.animeow.app.ui.theme.StatisticsLayoutStyle
import com.animeow.app.ui.theme.StatisticsMetric
import com.animeow.app.ui.theme.CommunityGroupView
import com.animeow.app.ui.theme.TierListStyle
import com.animeow.app.ui.theme.normalizeTierBoardTitle
import com.animeow.app.ui.theme.normalizeTierDisplayLabels
import com.animeow.app.ui.theme.CharacterLayout
import com.animeow.app.ui.theme.CharacterImageAlignment
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.RatingIconStyle
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.NavigationLabelMode
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ProfileSearchStyle
import com.animeow.app.ui.theme.CalendarLayoutPreset
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.appearanceDataStore by preferencesDataStore(
    name = "appearance_preferences",
)

private data class StatisticsPresetValues(
    val density: ContentDensity,
    val hidden: Set<StatisticsModule>,
    val statusChart: StatisticsChartStyle,
    val tagChart: StatisticsChartStyle,
)

class AppearancePreferences(context: Context) {
    private val dataStore = context.applicationContext.appearanceDataStore

    val settings: Flow<AppearanceSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            AppearanceSettings(
                frontendMode = FrontendMode.fromStorage(preferences[Keys.FRONTEND_MODE]),
                frontendModeChoiceMade = preferences[Keys.FRONTEND_MODE_CHOICE_MADE] ?: false,
                appStyle = AppStyle.fromStorage(preferences[Keys.APP_STYLE]),
                themeMode = ThemeMode.fromStorage(preferences[Keys.THEME_MODE]),
                homeLayout = HomeLayout.fromStorage(preferences[Keys.HOME_LAYOUT]),
                detailLayout = DetailLayout.fromStorage(preferences[Keys.DETAIL_LAYOUT]),
                contentDensity = ContentDensity.fromStorage(preferences[Keys.CONTENT_DENSITY]),
                sectionSpacing = com.animeow.app.ui.theme.SectionSpacing.fromStorage(preferences[Keys.SECTION_SPACING]),
                topBarSpacing = com.animeow.app.ui.theme.TopBarSpacing.fromStorage(preferences[Keys.TOP_BAR_SPACING]),
                motionLevel = MotionLevel.fromStorage(preferences[Keys.MOTION_LEVEL]),
                pageTransitionStyle = PageTransitionStyle.fromStorage(preferences[Keys.PAGE_TRANSITION_STYLE]),
                predictiveBackEnabled = preferences[Keys.PREDICTIVE_BACK_ENABLED] ?: true,
                exitBehavior = com.animeow.app.ui.theme.ExitBehavior.fromStorage(preferences[Keys.EXIT_BEHAVIOR]),
                useDynamicColor = preferences[Keys.USE_DYNAMIC_COLOR] ?: false,
                fontScale = (preferences[Keys.FONT_SCALE] ?: 1f).coerceIn(APP_FONT_SCALE_MIN, APP_FONT_SCALE_MAX),
                cornerScale = (preferences[Keys.CORNER_SCALE] ?: 1f).coerceIn(0.5f, 1.5f),
                gridColumns = (preferences[Keys.GRID_COLUMNS] ?: 3).coerceIn(2, 6),
                showTitle = preferences[Keys.SHOW_TITLE] ?: true,
                showStatus = preferences[Keys.SHOW_STATUS] ?: true,
                showRating = preferences[Keys.SHOW_RATING] ?: true,
                showProgress = preferences[Keys.SHOW_PROGRESS] ?: true,
                coverBadgeStyle = CoverBadgeStyle.fromStorage(preferences[Keys.COVER_BADGE_STYLE]),
                ratingIconStyle = RatingIconStyle.fromStorage(preferences[Keys.RATING_ICON_STYLE]),
                ratingBadgeColorStyle = RatingBadgeColorStyle.fromStorage(preferences[Keys.RATING_BADGE_COLOR_STYLE]),
                ratingBadgeCustomColor = preferences[Keys.RATING_BADGE_CUSTOM_COLOR] ?: 0xFFFF9800,
                coverTitlePosition = CoverTitlePosition.fromStorage(preferences[Keys.COVER_TITLE_POSITION]),
                coverAspectRatio = CoverAspectRatio.fromStorage(preferences[Keys.COVER_ASPECT_RATIO]),
                detailCardStyle = DetailCardStyle.fromStorage(preferences[Keys.DETAIL_CARD_STYLE]),
                profileSearchStyle = ProfileSearchStyle.fromStorage(preferences[Keys.PROFILE_SEARCH_STYLE]),
                showDiscovery = preferences[Keys.SHOW_DISCOVERY] ?: true,
                showCalendar = preferences[Keys.SHOW_CALENDAR] ?: true,
                showCommunity = preferences[Keys.SHOW_COMMUNITY] ?: true,
                showStatistics = preferences[Keys.SHOW_STATISTICS] ?: false,
                detailModuleOrder = DetailModule.parseOrder(preferences[Keys.DETAIL_MODULE_ORDER]),
                hiddenDetailModules = DetailModule.parseSet(preferences[Keys.HIDDEN_DETAIL_MODULES]),
                cardSwipeActionsEnabled = preferences[Keys.CARD_SWIPE_ACTIONS_ENABLED] ?: false,
                swipeStartAction = SwipeAction.fromStorage(
                    preferences[Keys.SWIPE_START_ACTION],
                    SwipeAction.INCREMENT,
                ),
                swipeEndAction = SwipeAction.fromStorage(
                    preferences[Keys.SWIPE_END_ACTION],
                    SwipeAction.CYCLE_STATUS,
                ),
                hapticFeedback = preferences[Keys.HAPTIC_FEEDBACK] ?: true,
                soundFeedback = preferences[Keys.SOUND_FEEDBACK] ?: false,
                autoCompleteStatus = preferences[Keys.AUTO_COMPLETE_STATUS] ?: true,
                completionStatus = preferences[Keys.COMPLETION_STATUS] ?: "看完",
                accentColor = preferences[Keys.ACCENT_COLOR],
                randomAccentOnLaunch = preferences[Keys.RANDOM_ACCENT_ON_LAUNCH] ?: false,
                navigationOrder = parseNavigationOrder(preferences[Keys.NAVIGATION_ORDER]),
                startDestination = preferences[Keys.START_DESTINATION] ?: "tracker",
                statisticsModuleOrder = StatisticsModule.parseOrder(preferences[Keys.STATISTICS_MODULE_ORDER]),
                hiddenStatisticsModules = StatisticsModule.parseSet(preferences[Keys.HIDDEN_STATISTICS_MODULES]),
                statisticsPreset = StatisticsPreset.fromStorage(preferences[Keys.STATISTICS_PRESET]),
                statisticsDensity = ContentDensity.fromStorage(preferences[Keys.STATISTICS_DENSITY]),
                statisticsStatusChart = StatisticsChartStyle.fromStorage(preferences[Keys.STATISTICS_STATUS_CHART]),
                statisticsTagChart = StatisticsChartStyle.fromStorage(preferences[Keys.STATISTICS_TAG_CHART]),
                statisticsLayoutStyle = StatisticsLayoutStyle.fromStorage(preferences[Keys.STATISTICS_LAYOUT_STYLE]),
                hiddenStatisticsMetrics = StatisticsMetric.parseSet(preferences[Keys.HIDDEN_STATISTICS_METRICS]),
                communityGroupView = CommunityGroupView.fromStorage(preferences[Keys.COMMUNITY_GROUP_VIEW]),
                communityDensity = ContentDensity.fromStorage(preferences[Keys.COMMUNITY_DENSITY]),
                communityShowDescription = preferences[Keys.COMMUNITY_SHOW_DESCRIPTION] ?: true,
                communityShowDownloads = preferences[Keys.COMMUNITY_SHOW_DOWNLOADS] ?: true,
                tierListStyle = TierListStyle.fromStorage(preferences[Keys.TIER_LIST_STYLE]),
                tierListCount = (preferences[Keys.TIER_LIST_COUNT] ?: 5).coerceIn(3, 5),
                tierBoardTitle = normalizeTierBoardTitle(preferences[Keys.TIER_BOARD_TITLE]),
                tierDisplayLabels = normalizeTierDisplayLabels(
                    listOf(
                        preferences[Keys.TIER_LABEL_S].orEmpty(),
                        preferences[Keys.TIER_LABEL_A].orEmpty(),
                        preferences[Keys.TIER_LABEL_B].orEmpty(),
                        preferences[Keys.TIER_LABEL_C].orEmpty(),
                        preferences[Keys.TIER_LABEL_D].orEmpty(),
                    ).takeIf { labels -> labels.any(String::isNotEmpty) },
                ),
                characterLayout = CharacterLayout.fromStorage(preferences[Keys.CHARACTER_LAYOUT]),
                characterImageAlignment = CharacterImageAlignment.fromStorage(preferences[Keys.CHARACTER_IMAGE_ALIGNMENT]),
                characterShowMetadata = preferences[Keys.CHARACTER_SHOW_METADATA] ?: true,
                characterShowRating = preferences[Keys.CHARACTER_SHOW_RATING] ?: true,
                navigationBarStyle = NavigationBarStyle.fromStorage(preferences[Keys.NAVIGATION_BAR_STYLE]),
                floatingBarHorizontalMargin = preferences[Keys.FLOATING_BAR_HORIZONTAL_MARGIN] ?: 20,
                floatingBarBottomMargin = preferences[Keys.FLOATING_BAR_BOTTOM_MARGIN] ?: 6,
                floatingBarCornerRadius = preferences[Keys.FLOATING_BAR_CORNER_RADIUS] ?: 32,
                floatingBarShadowElevation = preferences[Keys.FLOATING_BAR_SHADOW_ELEVATION] ?: 12,
                floatingBarHeight = (preferences[Keys.FLOATING_BAR_HEIGHT] ?: 56).coerceIn(48, 80),
                navigationLabelMode = NavigationLabelMode.fromStorage(preferences[Keys.NAVIGATION_LABEL_MODE]),
                calendarLayoutPreset = CalendarLayoutPreset.fromStorage(preferences[Keys.CALENDAR_LAYOUT_PRESET]),
                clipboardShareDetection = preferences[Keys.CLIPBOARD_SHARE_DETECTION] ?: false,
                statusChipFilled = preferences[Keys.STATUS_CHIP_FILLED] ?: true,
                showVersionInProfile = preferences[Keys.SHOW_VERSION_IN_PROFILE] ?: true,
            ).normalizedNavigationVisibility()
        }

    suspend fun setFrontendMode(mode: FrontendMode) {
        dataStore.edit { preferences ->
            preferences[Keys.FRONTEND_MODE] = mode.storageKey
            preferences[Keys.FRONTEND_MODE_CHOICE_MADE] = true
        }
    }

    suspend fun setAppStyle(style: AppStyle) {
        dataStore.edit { preferences ->
            preferences[Keys.APP_STYLE] = style.storageKey
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences -> preferences[Keys.THEME_MODE] = mode.storageKey }
    }

    suspend fun setHomeLayout(layout: HomeLayout) {
        dataStore.edit { preferences -> preferences[Keys.HOME_LAYOUT] = layout.storageKey }
    }

    suspend fun setDetailLayout(layout: DetailLayout) {
        dataStore.edit { preferences -> preferences[Keys.DETAIL_LAYOUT] = layout.storageKey }
    }

    suspend fun setContentDensity(density: ContentDensity) {
        dataStore.edit { preferences -> preferences[Keys.CONTENT_DENSITY] = density.storageKey }
    }

    suspend fun setSectionSpacing(spacing: com.animeow.app.ui.theme.SectionSpacing) {
        dataStore.edit { preferences -> preferences[Keys.SECTION_SPACING] = spacing.storageKey }
    }

    suspend fun setTopBarSpacing(spacing: com.animeow.app.ui.theme.TopBarSpacing) {
        dataStore.edit { preferences -> preferences[Keys.TOP_BAR_SPACING] = spacing.storageKey }
    }

    suspend fun setMotionLevel(level: MotionLevel) {
        dataStore.edit { preferences -> preferences[Keys.MOTION_LEVEL] = level.storageKey }
    }

    suspend fun setPageTransitionStyle(style: PageTransitionStyle) {
        dataStore.edit { preferences -> preferences[Keys.PAGE_TRANSITION_STYLE] = style.storageKey }
    }

    suspend fun setPredictiveBackEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.PREDICTIVE_BACK_ENABLED] = enabled }
    }

    suspend fun setExitBehavior(behavior: com.animeow.app.ui.theme.ExitBehavior) {
        dataStore.edit { preferences -> preferences[Keys.EXIT_BEHAVIOR] = behavior.storageKey }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.USE_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setFontScale(scale: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.FONT_SCALE] = scale.coerceIn(APP_FONT_SCALE_MIN, APP_FONT_SCALE_MAX)
        }
    }

    suspend fun setCornerScale(scale: Float) {
        dataStore.edit { preferences -> preferences[Keys.CORNER_SCALE] = scale.coerceIn(0.5f, 1.5f) }
    }

    suspend fun setGridColumns(columns: Int) {
        dataStore.edit { preferences -> preferences[Keys.GRID_COLUMNS] = columns.coerceIn(2, 6) }
    }

    suspend fun setShowStatus(show: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SHOW_STATUS] = show }
    }

    suspend fun setShowTitle(show: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SHOW_TITLE] = show }
    }

    suspend fun setShowRating(show: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SHOW_RATING] = show }
    }

    suspend fun setShowProgress(show: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SHOW_PROGRESS] = show }
    }

    suspend fun setCoverBadgeStyle(style: CoverBadgeStyle) {
        dataStore.edit { preferences -> preferences[Keys.COVER_BADGE_STYLE] = style.storageKey }
    }

    suspend fun setRatingIconStyle(style: RatingIconStyle) {
        dataStore.edit { preferences -> preferences[Keys.RATING_ICON_STYLE] = style.storageKey }
    }

    suspend fun setRatingBadgeColorStyle(style: RatingBadgeColorStyle) {
        dataStore.edit { preferences -> preferences[Keys.RATING_BADGE_COLOR_STYLE] = style.storageKey }
    }

    suspend fun setRatingBadgeCustomColor(color: Long) {
        dataStore.edit { preferences -> preferences[Keys.RATING_BADGE_CUSTOM_COLOR] = color }
    }

    suspend fun setCoverTitlePosition(position: CoverTitlePosition) {
        dataStore.edit { preferences -> preferences[Keys.COVER_TITLE_POSITION] = position.storageKey }
    }

    suspend fun setCoverAspectRatio(ratio: CoverAspectRatio) {
        dataStore.edit { preferences -> preferences[Keys.COVER_ASPECT_RATIO] = ratio.storageKey }
    }

    suspend fun setDetailCardStyle(style: DetailCardStyle) {
        dataStore.edit { preferences -> preferences[Keys.DETAIL_CARD_STYLE] = style.storageKey }
    }

    suspend fun setProfileSearchStyle(style: ProfileSearchStyle) {
        dataStore.edit { preferences -> preferences[Keys.PROFILE_SEARCH_STYLE] = style.storageKey }
    }

    suspend fun setShowDiscovery(show: Boolean) {
        setNavigationDestinationVisible("discovery", show)
    }

    suspend fun setShowCalendar(show: Boolean) {
        setNavigationDestinationVisible("calendar", show)
    }

    suspend fun setShowCommunity(show: Boolean) {
        setNavigationDestinationVisible("community", show)
    }

    suspend fun setShowStatistics(show: Boolean) {
        setNavigationDestinationVisible("statistics", show)
    }

    private suspend fun setNavigationDestinationVisible(route: String, show: Boolean) {
        dataStore.edit { preferences ->
            val current = AppearanceSettings(
                showDiscovery = preferences[Keys.SHOW_DISCOVERY] ?: true,
                showCalendar = preferences[Keys.SHOW_CALENDAR] ?: true,
                showCommunity = preferences[Keys.SHOW_COMMUNITY] ?: true,
                showStatistics = preferences[Keys.SHOW_STATISTICS] ?: false,
                navigationOrder = parseNavigationOrder(preferences[Keys.NAVIGATION_ORDER]),
                startDestination = preferences[Keys.START_DESTINATION] ?: "tracker",
            ).normalizedNavigationVisibility()
            val updated = current.withNavigationDestinationVisible(route, show)
            preferences[Keys.SHOW_DISCOVERY] = updated.showDiscovery
            preferences[Keys.SHOW_CALENDAR] = updated.showCalendar
            preferences[Keys.SHOW_COMMUNITY] = updated.showCommunity
            preferences[Keys.SHOW_STATISTICS] = updated.showStatistics
            preferences[Keys.START_DESTINATION] = updated.startDestination
        }
    }

    suspend fun setDetailModuleOrder(order: List<DetailModule>) {
        val normalized = order.distinct() + DetailModule.entries.filterNot(order::contains)
        dataStore.edit { preferences ->
            preferences[Keys.DETAIL_MODULE_ORDER] = normalized.joinToString(",", transform = DetailModule::storageKey)
        }
    }

    suspend fun setHiddenDetailModules(hidden: Set<DetailModule>) {
        dataStore.edit { preferences ->
            preferences[Keys.HIDDEN_DETAIL_MODULES] = hidden.joinToString(",", transform = DetailModule::storageKey)
        }
    }

    suspend fun setSwipeStartAction(action: SwipeAction) {
        dataStore.edit { preferences -> preferences[Keys.SWIPE_START_ACTION] = action.storageKey }
    }

    suspend fun setCardSwipeActionsEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.CARD_SWIPE_ACTIONS_ENABLED] = enabled }
    }

    suspend fun setSwipeEndAction(action: SwipeAction) {
        dataStore.edit { preferences -> preferences[Keys.SWIPE_END_ACTION] = action.storageKey }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setSoundFeedback(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SOUND_FEEDBACK] = enabled }
    }

    suspend fun setAutoCompleteStatus(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.AUTO_COMPLETE_STATUS] = enabled }
    }

    suspend fun setCompletionStatus(status: String) {
        dataStore.edit { preferences -> preferences[Keys.COMPLETION_STATUS] = status }
    }

    suspend fun setAccentColor(color: Long?) {
        dataStore.edit { preferences ->
            if (color == null) preferences.remove(Keys.ACCENT_COLOR)
            else preferences[Keys.ACCENT_COLOR] = color
        }
    }

    suspend fun setRandomAccentOnLaunch(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.RANDOM_ACCENT_ON_LAUNCH] = enabled }
    }

    suspend fun setNavigationOrder(order: List<String>) {
        dataStore.edit { preferences ->
            preferences[Keys.NAVIGATION_ORDER] = parseNavigationOrder(order.joinToString(",")).joinToString(",")
        }
    }

    suspend fun setStartDestination(route: String) {
        dataStore.edit { preferences -> preferences[Keys.START_DESTINATION] = route }
    }

    suspend fun setStatisticsModuleOrder(order: List<StatisticsModule>) {
        val normalized = order.distinct() + StatisticsModule.entries.filterNot(order::contains)
        dataStore.edit { preferences ->
            preferences[Keys.STATISTICS_MODULE_ORDER] = normalized.joinToString(",") { it.storageKey }
            preferences[Keys.STATISTICS_PRESET] = StatisticsPreset.CUSTOM.storageKey
        }
    }

    suspend fun setHiddenStatisticsModules(hidden: Set<StatisticsModule>) {
        dataStore.edit { preferences ->
            preferences[Keys.HIDDEN_STATISTICS_MODULES] = hidden.joinToString(",") { it.storageKey }
            preferences[Keys.STATISTICS_PRESET] = StatisticsPreset.CUSTOM.storageKey
        }
    }

    suspend fun applyStatisticsPreset(preset: StatisticsPreset) {
        val (density, hidden, statusChart, tagChart) = when (preset) {
            StatisticsPreset.CONCISE -> StatisticsPresetValues(
                ContentDensity.COMPACT,
                setOf(StatisticsModule.RATING, StatisticsModule.TAGS, StatisticsModule.ACTIVITY, StatisticsModule.ANALYSIS),
                StatisticsChartStyle.RANKED,
                StatisticsChartStyle.RANKED,
            )
            StatisticsPreset.BALANCED -> StatisticsPresetValues(
                ContentDensity.COMFORTABLE,
                emptySet(),
                StatisticsChartStyle.DONUT,
                StatisticsChartStyle.DONUT,
            )
            StatisticsPreset.ANALYSIS -> StatisticsPresetValues(
                ContentDensity.SPACIOUS,
                emptySet(),
                StatisticsChartStyle.DONUT,
                StatisticsChartStyle.RANKED,
            )
            StatisticsPreset.CUSTOM -> return
        }
        dataStore.edit { preferences ->
            preferences[Keys.STATISTICS_PRESET] = preset.storageKey
            preferences[Keys.STATISTICS_DENSITY] = density.storageKey
            preferences[Keys.HIDDEN_STATISTICS_MODULES] = hidden.joinToString(",") { it.storageKey }
            preferences[Keys.STATISTICS_STATUS_CHART] = statusChart.storageKey
            preferences[Keys.STATISTICS_TAG_CHART] = tagChart.storageKey
        }
    }

    suspend fun setStatisticsDensity(density: ContentDensity) {
        dataStore.edit { preferences ->
            preferences[Keys.STATISTICS_DENSITY] = density.storageKey
            preferences[Keys.STATISTICS_PRESET] = StatisticsPreset.CUSTOM.storageKey
        }
    }

    suspend fun setStatisticsStatusChart(style: StatisticsChartStyle) {
        dataStore.edit { preferences ->
            preferences[Keys.STATISTICS_STATUS_CHART] = style.storageKey
            preferences[Keys.STATISTICS_PRESET] = StatisticsPreset.CUSTOM.storageKey
        }
    }

    suspend fun setStatisticsTagChart(style: StatisticsChartStyle) {
        dataStore.edit { preferences ->
            preferences[Keys.STATISTICS_TAG_CHART] = style.storageKey
            preferences[Keys.STATISTICS_PRESET] = StatisticsPreset.CUSTOM.storageKey
        }
    }

    suspend fun setStatisticsLayoutStyle(style: StatisticsLayoutStyle) {
        dataStore.edit { preferences ->
            preferences[Keys.STATISTICS_LAYOUT_STYLE] = style.storageKey
            preferences[Keys.STATISTICS_PRESET] = StatisticsPreset.CUSTOM.storageKey
        }
    }

    suspend fun setHiddenStatisticsMetrics(hidden: Set<StatisticsMetric>) {
        dataStore.edit { preferences ->
            preferences[Keys.HIDDEN_STATISTICS_METRICS] = hidden.joinToString(",") { it.storageKey }
            preferences[Keys.STATISTICS_PRESET] = StatisticsPreset.CUSTOM.storageKey
        }
    }

    suspend fun setCommunityGroupView(view: CommunityGroupView) {
        dataStore.edit { it[Keys.COMMUNITY_GROUP_VIEW] = view.storageKey }
    }

    suspend fun setCommunityDensity(density: ContentDensity) {
        dataStore.edit { it[Keys.COMMUNITY_DENSITY] = density.storageKey }
    }

    suspend fun setCommunityShowDescription(show: Boolean) {
        dataStore.edit { it[Keys.COMMUNITY_SHOW_DESCRIPTION] = show }
    }

    suspend fun setCommunityShowDownloads(show: Boolean) {
        dataStore.edit { it[Keys.COMMUNITY_SHOW_DOWNLOADS] = show }
    }

    suspend fun setTierListStyle(style: TierListStyle) {
        dataStore.edit { preferences -> preferences[Keys.TIER_LIST_STYLE] = style.storageKey }
    }

    suspend fun setTierListCount(count: Int) {
        dataStore.edit { preferences -> preferences[Keys.TIER_LIST_COUNT] = count.coerceIn(3, 5) }
    }

    suspend fun setTierListCustomization(title: String, labels: List<String>) {
        val normalizedTitle = normalizeTierBoardTitle(title)
        val normalizedLabels = normalizeTierDisplayLabels(labels)
        dataStore.edit { preferences ->
            preferences[Keys.TIER_BOARD_TITLE] = normalizedTitle
            preferences[Keys.TIER_LABEL_S] = normalizedLabels[0]
            preferences[Keys.TIER_LABEL_A] = normalizedLabels[1]
            preferences[Keys.TIER_LABEL_B] = normalizedLabels[2]
            preferences[Keys.TIER_LABEL_C] = normalizedLabels[3]
            preferences[Keys.TIER_LABEL_D] = normalizedLabels[4]
        }
    }

    suspend fun setCharacterLayout(layout: CharacterLayout) {
        dataStore.edit { preferences -> preferences[Keys.CHARACTER_LAYOUT] = layout.storageKey }
    }

    suspend fun setCharacterImageAlignment(alignment: CharacterImageAlignment) {
        dataStore.edit { preferences -> preferences[Keys.CHARACTER_IMAGE_ALIGNMENT] = alignment.storageKey }
    }

    suspend fun setCharacterShowMetadata(show: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.CHARACTER_SHOW_METADATA] = show }
    }

    suspend fun setCharacterShowRating(show: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.CHARACTER_SHOW_RATING] = show }
    }

    suspend fun setNavigationBarStyle(style: NavigationBarStyle) {
        dataStore.edit { preferences -> preferences[Keys.NAVIGATION_BAR_STYLE] = style.storageKey }
    }

    suspend fun setFloatingBarHorizontalMargin(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.FLOATING_BAR_HORIZONTAL_MARGIN] = value.coerceIn(0, 60) }
    }

    suspend fun setFloatingBarBottomMargin(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.FLOATING_BAR_BOTTOM_MARGIN] = value.coerceIn(0, 40) }
    }

    suspend fun setFloatingBarCornerRadius(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.FLOATING_BAR_CORNER_RADIUS] = value.coerceIn(0, 48) }
    }

    suspend fun setFloatingBarShadowElevation(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.FLOATING_BAR_SHADOW_ELEVATION] = value.coerceIn(0, 24) }
    }

    suspend fun setCalendarLayoutPreset(preset: CalendarLayoutPreset) {
        dataStore.edit { preferences -> preferences[Keys.CALENDAR_LAYOUT_PRESET] = preset.storageKey }
    }

    suspend fun setClipboardShareDetection(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.CLIPBOARD_SHARE_DETECTION] = enabled }
    }

    suspend fun setStatusChipFilled(filled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.STATUS_CHIP_FILLED] = filled }
    }

    suspend fun setShowVersionInProfile(show: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SHOW_VERSION_IN_PROFILE] = show }
    }

    suspend fun snapshot(): AppearanceSettings = settings.first()

    suspend fun restore(rawSnapshot: AppearanceSettings) {
        val snapshot = rawSnapshot.normalizedNavigationVisibility()
        dataStore.edit { preferences ->
            preferences[Keys.FRONTEND_MODE] = snapshot.frontendMode.storageKey
            preferences[Keys.FRONTEND_MODE_CHOICE_MADE] = snapshot.frontendModeChoiceMade
            preferences[Keys.APP_STYLE] = snapshot.appStyle.storageKey
            preferences[Keys.THEME_MODE] = snapshot.themeMode.storageKey
            preferences[Keys.HOME_LAYOUT] = snapshot.homeLayout.storageKey
            preferences[Keys.DETAIL_LAYOUT] = snapshot.detailLayout.storageKey
            preferences[Keys.CONTENT_DENSITY] = snapshot.contentDensity.storageKey
            preferences[Keys.MOTION_LEVEL] = snapshot.motionLevel.storageKey
            preferences[Keys.PAGE_TRANSITION_STYLE] = snapshot.pageTransitionStyle.storageKey
            preferences[Keys.PREDICTIVE_BACK_ENABLED] = snapshot.predictiveBackEnabled
            preferences[Keys.EXIT_BEHAVIOR] = snapshot.exitBehavior.storageKey
            preferences[Keys.USE_DYNAMIC_COLOR] = snapshot.useDynamicColor
            preferences[Keys.FONT_SCALE] = snapshot.fontScale
            preferences[Keys.CORNER_SCALE] = snapshot.cornerScale
            preferences[Keys.GRID_COLUMNS] = snapshot.gridColumns
            preferences[Keys.SHOW_TITLE] = snapshot.showTitle
            preferences[Keys.SHOW_STATUS] = snapshot.showStatus
            preferences[Keys.SHOW_RATING] = snapshot.showRating
            preferences[Keys.SHOW_PROGRESS] = snapshot.showProgress
            preferences[Keys.COVER_BADGE_STYLE] = snapshot.coverBadgeStyle.storageKey
            preferences[Keys.RATING_ICON_STYLE] = snapshot.ratingIconStyle.storageKey
            preferences[Keys.RATING_BADGE_COLOR_STYLE] = snapshot.ratingBadgeColorStyle.storageKey
            preferences[Keys.RATING_BADGE_CUSTOM_COLOR] = snapshot.ratingBadgeCustomColor
            preferences[Keys.COVER_TITLE_POSITION] = snapshot.coverTitlePosition.storageKey
            preferences[Keys.COVER_ASPECT_RATIO] = snapshot.coverAspectRatio.storageKey
            preferences[Keys.DETAIL_CARD_STYLE] = snapshot.detailCardStyle.storageKey
            preferences[Keys.PROFILE_SEARCH_STYLE] = snapshot.profileSearchStyle.storageKey
            preferences[Keys.SHOW_DISCOVERY] = snapshot.showDiscovery
            preferences[Keys.SHOW_CALENDAR] = snapshot.showCalendar
            preferences[Keys.SHOW_COMMUNITY] = snapshot.showCommunity
            preferences[Keys.SHOW_STATISTICS] = snapshot.showStatistics
            preferences[Keys.DETAIL_MODULE_ORDER] = snapshot.detailModuleOrder.joinToString(",") { it.storageKey }
            preferences[Keys.HIDDEN_DETAIL_MODULES] = snapshot.hiddenDetailModules.joinToString(",") { it.storageKey }
            preferences[Keys.CARD_SWIPE_ACTIONS_ENABLED] = snapshot.cardSwipeActionsEnabled
            preferences[Keys.SWIPE_START_ACTION] = snapshot.swipeStartAction.storageKey
            preferences[Keys.SWIPE_END_ACTION] = snapshot.swipeEndAction.storageKey
            preferences[Keys.HAPTIC_FEEDBACK] = snapshot.hapticFeedback
            preferences[Keys.SOUND_FEEDBACK] = snapshot.soundFeedback
            preferences[Keys.AUTO_COMPLETE_STATUS] = snapshot.autoCompleteStatus
            preferences[Keys.COMPLETION_STATUS] = snapshot.completionStatus
            snapshot.accentColor?.let { preferences[Keys.ACCENT_COLOR] = it }
                ?: preferences.remove(Keys.ACCENT_COLOR)
            preferences[Keys.RANDOM_ACCENT_ON_LAUNCH] = snapshot.randomAccentOnLaunch
            preferences[Keys.NAVIGATION_ORDER] = snapshot.navigationOrder.joinToString(",")
            preferences[Keys.START_DESTINATION] = snapshot.startDestination
            preferences[Keys.STATISTICS_MODULE_ORDER] = snapshot.statisticsModuleOrder.joinToString(",") { it.storageKey }
            preferences[Keys.HIDDEN_STATISTICS_MODULES] = snapshot.hiddenStatisticsModules.joinToString(",") { it.storageKey }
            preferences[Keys.STATISTICS_PRESET] = snapshot.statisticsPreset.storageKey
            preferences[Keys.STATISTICS_DENSITY] = snapshot.statisticsDensity.storageKey
            preferences[Keys.STATISTICS_STATUS_CHART] = snapshot.statisticsStatusChart.storageKey
            preferences[Keys.STATISTICS_TAG_CHART] = snapshot.statisticsTagChart.storageKey
            preferences[Keys.STATISTICS_LAYOUT_STYLE] = snapshot.statisticsLayoutStyle.storageKey
            preferences[Keys.HIDDEN_STATISTICS_METRICS] = snapshot.hiddenStatisticsMetrics.joinToString(",") { it.storageKey }
            preferences[Keys.COMMUNITY_GROUP_VIEW] = snapshot.communityGroupView.storageKey
            preferences[Keys.COMMUNITY_DENSITY] = snapshot.communityDensity.storageKey
            preferences[Keys.COMMUNITY_SHOW_DESCRIPTION] = snapshot.communityShowDescription
            preferences[Keys.COMMUNITY_SHOW_DOWNLOADS] = snapshot.communityShowDownloads
            preferences[Keys.TIER_LIST_STYLE] = snapshot.tierListStyle.storageKey
            preferences[Keys.TIER_LIST_COUNT] = snapshot.tierListCount.coerceIn(3, 5)
            preferences[Keys.TIER_BOARD_TITLE] = normalizeTierBoardTitle(snapshot.tierBoardTitle)
            val tierLabels = normalizeTierDisplayLabels(snapshot.tierDisplayLabels)
            preferences[Keys.TIER_LABEL_S] = tierLabels[0]
            preferences[Keys.TIER_LABEL_A] = tierLabels[1]
            preferences[Keys.TIER_LABEL_B] = tierLabels[2]
            preferences[Keys.TIER_LABEL_C] = tierLabels[3]
            preferences[Keys.TIER_LABEL_D] = tierLabels[4]
            preferences[Keys.CHARACTER_LAYOUT] = snapshot.characterLayout.storageKey
            preferences[Keys.CHARACTER_IMAGE_ALIGNMENT] = snapshot.characterImageAlignment.storageKey
            preferences[Keys.CHARACTER_SHOW_METADATA] = snapshot.characterShowMetadata
            preferences[Keys.CHARACTER_SHOW_RATING] = snapshot.characterShowRating
            preferences[Keys.NAVIGATION_BAR_STYLE] = snapshot.navigationBarStyle.storageKey
            preferences[Keys.FLOATING_BAR_HORIZONTAL_MARGIN] = snapshot.floatingBarHorizontalMargin
            preferences[Keys.FLOATING_BAR_BOTTOM_MARGIN] = snapshot.floatingBarBottomMargin
            preferences[Keys.FLOATING_BAR_CORNER_RADIUS] = snapshot.floatingBarCornerRadius
            preferences[Keys.FLOATING_BAR_SHADOW_ELEVATION] = snapshot.floatingBarShadowElevation
            preferences[Keys.FLOATING_BAR_HEIGHT] = snapshot.floatingBarHeight.coerceIn(48, 80)
            preferences[Keys.NAVIGATION_LABEL_MODE] = snapshot.navigationLabelMode.storageKey
            preferences[Keys.CALENDAR_LAYOUT_PRESET] = snapshot.calendarLayoutPreset.storageKey
            preferences[Keys.CLIPBOARD_SHARE_DETECTION] = snapshot.clipboardShareDetection
            preferences[Keys.STATUS_CHIP_FILLED] = snapshot.statusChipFilled
            preferences[Keys.SHOW_VERSION_IN_PROFILE] = snapshot.showVersionInProfile
            preferences[Keys.SECTION_SPACING] = snapshot.sectionSpacing.storageKey
            preferences[Keys.TOP_BAR_SPACING] = snapshot.topBarSpacing.storageKey
        }
    }

    private object Keys {
        val FRONTEND_MODE = stringPreferencesKey("frontend_mode")
        val FRONTEND_MODE_CHOICE_MADE = booleanPreferencesKey("frontend_mode_choice_made")
        val APP_STYLE = stringPreferencesKey("app_style")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HOME_LAYOUT = stringPreferencesKey("home_layout")
        val DETAIL_LAYOUT = stringPreferencesKey("detail_layout")
        val CONTENT_DENSITY = stringPreferencesKey("content_density")
        val SECTION_SPACING = stringPreferencesKey("section_spacing")
        val TOP_BAR_SPACING = stringPreferencesKey("top_bar_spacing")
        val MOTION_LEVEL = stringPreferencesKey("motion_level")
        val PAGE_TRANSITION_STYLE = stringPreferencesKey("page_transition_style")
        val PREDICTIVE_BACK_ENABLED = booleanPreferencesKey("predictive_back_enabled")
        val EXIT_BEHAVIOR = stringPreferencesKey("exit_behavior")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val CORNER_SCALE = floatPreferencesKey("corner_scale")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val SHOW_TITLE = booleanPreferencesKey("show_title")
        val SHOW_STATUS = booleanPreferencesKey("show_status")
        val SHOW_RATING = booleanPreferencesKey("show_rating")
        val SHOW_PROGRESS = booleanPreferencesKey("show_progress")
        val COVER_BADGE_STYLE = stringPreferencesKey("cover_badge_style")
        val RATING_ICON_STYLE = stringPreferencesKey("rating_icon_style")
        val RATING_BADGE_COLOR_STYLE = stringPreferencesKey("rating_badge_color_style")
        val RATING_BADGE_CUSTOM_COLOR = longPreferencesKey("rating_badge_custom_color")
        val COVER_TITLE_POSITION = stringPreferencesKey("cover_title_position")
        val COVER_ASPECT_RATIO = stringPreferencesKey("cover_aspect_ratio")
        val DETAIL_CARD_STYLE = stringPreferencesKey("detail_card_style")
        val PROFILE_SEARCH_STYLE = stringPreferencesKey("profile_search_style")
        val SHOW_DISCOVERY = booleanPreferencesKey("show_discovery")
        val SHOW_CALENDAR = booleanPreferencesKey("show_calendar")
        val SHOW_COMMUNITY = booleanPreferencesKey("show_community")
        val SHOW_STATISTICS = booleanPreferencesKey("show_statistics")
        val DETAIL_MODULE_ORDER = stringPreferencesKey("detail_module_order")
        val HIDDEN_DETAIL_MODULES = stringPreferencesKey("hidden_detail_modules")
        val CARD_SWIPE_ACTIONS_ENABLED = booleanPreferencesKey("card_swipe_actions_enabled")
        val SWIPE_START_ACTION = stringPreferencesKey("swipe_start_action")
        val SWIPE_END_ACTION = stringPreferencesKey("swipe_end_action")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val SOUND_FEEDBACK = booleanPreferencesKey("sound_feedback")
        val AUTO_COMPLETE_STATUS = booleanPreferencesKey("auto_complete_status")
        val COMPLETION_STATUS = stringPreferencesKey("completion_status")
        val ACCENT_COLOR = longPreferencesKey("accent_color")
        val RANDOM_ACCENT_ON_LAUNCH = booleanPreferencesKey("random_accent_on_launch")
        val NAVIGATION_ORDER = stringPreferencesKey("navigation_order")
        val START_DESTINATION = stringPreferencesKey("start_destination")
        val STATISTICS_MODULE_ORDER = stringPreferencesKey("statistics_module_order")
        val HIDDEN_STATISTICS_MODULES = stringPreferencesKey("hidden_statistics_modules")
        val STATISTICS_PRESET = stringPreferencesKey("statistics_preset")
        val STATISTICS_DENSITY = stringPreferencesKey("statistics_density")
        val STATISTICS_STATUS_CHART = stringPreferencesKey("statistics_status_chart")
        val STATISTICS_TAG_CHART = stringPreferencesKey("statistics_tag_chart")
        val STATISTICS_LAYOUT_STYLE = stringPreferencesKey("statistics_layout_style")
        val HIDDEN_STATISTICS_METRICS = stringPreferencesKey("hidden_statistics_metrics")
        val COMMUNITY_GROUP_VIEW = stringPreferencesKey("community_group_view")
        val COMMUNITY_DENSITY = stringPreferencesKey("community_density")
        val COMMUNITY_SHOW_DESCRIPTION = booleanPreferencesKey("community_show_description")
        val COMMUNITY_SHOW_DOWNLOADS = booleanPreferencesKey("community_show_downloads")
        val TIER_LIST_STYLE = stringPreferencesKey("tier_list_style")
        val TIER_LIST_COUNT = intPreferencesKey("tier_list_count")
        val TIER_BOARD_TITLE = stringPreferencesKey("tier_board_title")
        val TIER_LABEL_S = stringPreferencesKey("tier_label_s")
        val TIER_LABEL_A = stringPreferencesKey("tier_label_a")
        val TIER_LABEL_B = stringPreferencesKey("tier_label_b")
        val TIER_LABEL_C = stringPreferencesKey("tier_label_c")
        val TIER_LABEL_D = stringPreferencesKey("tier_label_d")
        val CHARACTER_LAYOUT = stringPreferencesKey("character_layout")
        val CHARACTER_IMAGE_ALIGNMENT = stringPreferencesKey("character_image_alignment")
        val CHARACTER_SHOW_METADATA = booleanPreferencesKey("character_show_metadata")
        val CHARACTER_SHOW_RATING = booleanPreferencesKey("character_show_rating")
        val NAVIGATION_BAR_STYLE = stringPreferencesKey("navigation_bar_style")
        val FLOATING_BAR_HORIZONTAL_MARGIN = intPreferencesKey("floating_bar_horizontal_margin")
        val FLOATING_BAR_BOTTOM_MARGIN = intPreferencesKey("floating_bar_bottom_margin")
        val FLOATING_BAR_CORNER_RADIUS = intPreferencesKey("floating_bar_corner_radius")
        val FLOATING_BAR_SHADOW_ELEVATION = intPreferencesKey("floating_bar_shadow_elevation")
        val FLOATING_BAR_HEIGHT = intPreferencesKey("floating_bar_height")
        val NAVIGATION_LABEL_MODE = stringPreferencesKey("navigation_label_mode")
        val CALENDAR_LAYOUT_PRESET = stringPreferencesKey("calendar_layout_preset")
        val CLIPBOARD_SHARE_DETECTION = booleanPreferencesKey("clipboard_share_detection")
        val STATUS_CHIP_FILLED = booleanPreferencesKey("status_chip_filled")
        val SHOW_VERSION_IN_PROFILE = booleanPreferencesKey("show_version_in_profile")
    }
}
