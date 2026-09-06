package com.animeow.app.ui.preferences

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.PageTransitionStyle
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.StatisticsModule
import com.animeow.app.ui.theme.StatisticsPreset
import com.animeow.app.ui.theme.StatisticsChartStyle
import com.animeow.app.ui.theme.StatisticsLayoutStyle
import com.animeow.app.ui.theme.StatisticsMetric
import com.animeow.app.ui.theme.CommunityGroupView
import com.animeow.app.ui.theme.TierListStyle
import com.animeow.app.ui.theme.CharacterLayout
import com.animeow.app.ui.theme.CharacterImageAlignment
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.RatingIconStyle
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ProfileSearchStyle
import com.animeow.app.ui.theme.CalendarLayoutPreset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppearancePreferences(application)

    init {
        viewModelScope.launch {
            val snapshot = preferences.snapshot()
            if (snapshot.randomAccentOnLaunch) randomizeAccent(snapshot.accentColor)
        }
    }

    val settings: StateFlow<AppearanceSettings> = preferences.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        // Start with frontendModeChoiceMade = true so the version-selection
        // dialog doesn't flash on every launch for users who already chose.
        // New users will see the dialog once DataStore loads the real value.
        initialValue = AppearanceSettings(frontendModeChoiceMade = true),
    )

    fun selectFrontendMode(mode: FrontendMode) = viewModelScope.launch {
        preferences.setFrontendMode(mode)
    }

    fun selectStyle(style: AppStyle) {
        viewModelScope.launch {
            preferences.setAppStyle(style)
        }
    }

    fun selectThemeMode(mode: ThemeMode) = viewModelScope.launch {
        preferences.setThemeMode(mode)
    }

    fun selectHomeLayout(layout: HomeLayout) = viewModelScope.launch {
        preferences.setHomeLayout(layout)
    }

    fun selectDetailLayout(layout: DetailLayout) = viewModelScope.launch {
        preferences.setDetailLayout(layout)
    }

    fun selectContentDensity(density: ContentDensity) = viewModelScope.launch {
        preferences.setContentDensity(density)
    }

    fun selectSectionSpacing(spacing: com.animeow.app.ui.theme.SectionSpacing) = viewModelScope.launch {
        preferences.setSectionSpacing(spacing)
    }

    fun selectMotionLevel(level: MotionLevel) = viewModelScope.launch {
        preferences.setMotionLevel(level)
    }

    fun selectPageTransitionStyle(style: PageTransitionStyle) = viewModelScope.launch {
        preferences.setPageTransitionStyle(style)
    }

    fun setPredictiveBackEnabled(enabled: Boolean) = viewModelScope.launch {
        Log.d(TAG, "setPredictiveBackEnabled -> $enabled")
        preferences.setPredictiveBackEnabled(enabled)
    }

    fun setExitBehavior(behavior: com.animeow.app.ui.theme.ExitBehavior) = viewModelScope.launch {
        preferences.setExitBehavior(behavior)
    }

    fun setUseDynamicColor(enabled: Boolean) = viewModelScope.launch {
        preferences.setUseDynamicColor(enabled)
    }

    fun setFontScale(scale: Float) = viewModelScope.launch {
        preferences.setFontScale(scale)
    }

    fun setCornerScale(scale: Float) = viewModelScope.launch {
        preferences.setCornerScale(scale)
    }

    fun setGridColumns(columns: Int) = viewModelScope.launch {
        preferences.setGridColumns(columns)
    }

    fun setShowStatus(show: Boolean) = viewModelScope.launch {
        preferences.setShowStatus(show)
    }

    fun setShowTitle(show: Boolean) = viewModelScope.launch {
        preferences.setShowTitle(show)
    }

    fun setShowRating(show: Boolean) = viewModelScope.launch {
        preferences.setShowRating(show)
    }

    fun setShowProgress(show: Boolean) = viewModelScope.launch {
        preferences.setShowProgress(show)
    }

    fun setCoverBadgeStyle(style: CoverBadgeStyle) = viewModelScope.launch {
        preferences.setCoverBadgeStyle(style)
    }

    fun setRatingIconStyle(style: RatingIconStyle) = viewModelScope.launch {
        preferences.setRatingIconStyle(style)
    }

    fun setRatingBadgeColorStyle(style: RatingBadgeColorStyle) = viewModelScope.launch {
        preferences.setRatingBadgeColorStyle(style)
    }

    fun setRatingBadgeCustomColor(color: Long) = viewModelScope.launch {
        preferences.setRatingBadgeCustomColor(color)
    }

    fun setCoverTitlePosition(position: CoverTitlePosition) = viewModelScope.launch {
        preferences.setCoverTitlePosition(position)
    }

    fun setCoverAspectRatio(ratio: CoverAspectRatio) = viewModelScope.launch {
        preferences.setCoverAspectRatio(ratio)
    }

    fun setDetailCardStyle(style: DetailCardStyle) = viewModelScope.launch {
        preferences.setDetailCardStyle(style)
    }

    fun setProfileSearchStyle(style: ProfileSearchStyle) = viewModelScope.launch {
        preferences.setProfileSearchStyle(style)
    }

    fun setShowDiscovery(show: Boolean) = viewModelScope.launch {
        preferences.setShowDiscovery(show)
    }

    fun setShowCalendar(show: Boolean) = viewModelScope.launch {
        preferences.setShowCalendar(show)
    }

    fun setShowCommunity(show: Boolean) = viewModelScope.launch {
        preferences.setShowCommunity(show)
    }

    fun setShowStatistics(show: Boolean) = viewModelScope.launch {
        preferences.setShowStatistics(show)
    }

    fun setDetailModuleOrder(order: List<DetailModule>) = viewModelScope.launch {
        preferences.setDetailModuleOrder(order)
    }

    fun setHiddenDetailModules(hidden: Set<DetailModule>) = viewModelScope.launch {
        preferences.setHiddenDetailModules(hidden)
    }

    fun setCardSwipeActionsEnabled(enabled: Boolean) = viewModelScope.launch {
        preferences.setCardSwipeActionsEnabled(enabled)
    }

    fun setSwipeStartAction(action: SwipeAction) = viewModelScope.launch {
        preferences.setSwipeStartAction(action)
    }

    fun setSwipeEndAction(action: SwipeAction) = viewModelScope.launch {
        preferences.setSwipeEndAction(action)
    }

    fun setHapticFeedback(enabled: Boolean) = viewModelScope.launch {
        preferences.setHapticFeedback(enabled)
    }

    fun setSoundFeedback(enabled: Boolean) = viewModelScope.launch {
        preferences.setSoundFeedback(enabled)
    }

    fun setAutoCompleteStatus(enabled: Boolean) = viewModelScope.launch {
        preferences.setAutoCompleteStatus(enabled)
    }

    fun setCompletionStatus(status: String) = viewModelScope.launch {
        preferences.setCompletionStatus(status)
    }

    fun setAccentColor(color: Long?) = viewModelScope.launch {
        preferences.setAccentColor(color)
    }

    fun setRandomAccentOnLaunch(enabled: Boolean) = viewModelScope.launch {
        preferences.setRandomAccentOnLaunch(enabled)
        if (enabled) randomizeAccent(settings.value.accentColor)
    }

    fun randomizeAccentNow() = viewModelScope.launch {
        randomizeAccent(settings.value.accentColor)
    }

    fun setNavigationOrder(order: List<String>) = viewModelScope.launch {
        preferences.setNavigationOrder(order)
    }

    fun setStartDestination(route: String) = viewModelScope.launch {
        preferences.setStartDestination(route)
    }

    fun setStatisticsModuleOrder(order: List<StatisticsModule>) = viewModelScope.launch {
        preferences.setStatisticsModuleOrder(order)
    }

    fun setHiddenStatisticsModules(hidden: Set<StatisticsModule>) = viewModelScope.launch {
        preferences.setHiddenStatisticsModules(hidden)
    }

    fun applyStatisticsPreset(preset: StatisticsPreset) = viewModelScope.launch {
        preferences.applyStatisticsPreset(preset)
    }

    fun setStatisticsDensity(density: ContentDensity) = viewModelScope.launch {
        preferences.setStatisticsDensity(density)
    }

    fun setStatisticsStatusChart(style: StatisticsChartStyle) = viewModelScope.launch {
        preferences.setStatisticsStatusChart(style)
    }

    fun setStatisticsTagChart(style: StatisticsChartStyle) = viewModelScope.launch {
        preferences.setStatisticsTagChart(style)
    }

    fun setStatisticsLayoutStyle(style: StatisticsLayoutStyle) = viewModelScope.launch {
        preferences.setStatisticsLayoutStyle(style)
    }

    fun setHiddenStatisticsMetrics(hidden: Set<StatisticsMetric>) = viewModelScope.launch {
        preferences.setHiddenStatisticsMetrics(hidden)
    }

    fun setCommunityGroupView(view: CommunityGroupView) = viewModelScope.launch {
        preferences.setCommunityGroupView(view)
    }

    fun setCommunityDensity(density: ContentDensity) = viewModelScope.launch {
        preferences.setCommunityDensity(density)
    }

    fun setCommunityShowDescription(show: Boolean) = viewModelScope.launch {
        preferences.setCommunityShowDescription(show)
    }

    fun setCommunityShowDownloads(show: Boolean) = viewModelScope.launch {
        preferences.setCommunityShowDownloads(show)
    }

    private suspend fun randomizeAccent(current: Long?) {
        val next = RANDOM_ACCENTS.shuffled().firstOrNull { it != current } ?: RANDOM_ACCENTS.first()
        preferences.setAccentColor(next)
    }

    private companion object {
        const val TAG = "AppearanceViewModel"

        val RANDOM_ACCENTS = listOf(
            0xFF6750A4L,
            0xFFEC407AL,
            0xFF00A6A6L,
            0xFF7C4DFFL,
            0xFFFF7043L,
            0xFF3F8CFFL,
            0xFF66BB6AL,
            0xFFE6A700L,
            0xFF8E5CFFL,
            0xFF00897BL,
        )
    }

    fun setTierListStyle(style: TierListStyle) = viewModelScope.launch {
        preferences.setTierListStyle(style)
    }

    fun setTierListCount(count: Int) = viewModelScope.launch {
        preferences.setTierListCount(count)
    }

    fun setTierListCustomization(title: String, labels: List<String>) = viewModelScope.launch {
        preferences.setTierListCustomization(title, labels)
    }

    fun setCharacterLayout(layout: CharacterLayout) = viewModelScope.launch {
        preferences.setCharacterLayout(layout)
    }

    fun setCharacterImageAlignment(alignment: CharacterImageAlignment) = viewModelScope.launch {
        preferences.setCharacterImageAlignment(alignment)
    }

    fun setCharacterShowMetadata(show: Boolean) = viewModelScope.launch {
        preferences.setCharacterShowMetadata(show)
    }

    fun setCharacterShowRating(show: Boolean) = viewModelScope.launch {
        preferences.setCharacterShowRating(show)
    }

    fun setNavigationBarStyle(style: NavigationBarStyle) = viewModelScope.launch {
        preferences.setNavigationBarStyle(style)
    }

    fun setFloatingBarHorizontalMargin(value: Int) = viewModelScope.launch {
        preferences.setFloatingBarHorizontalMargin(value)
    }

    fun setFloatingBarBottomMargin(value: Int) = viewModelScope.launch {
        preferences.setFloatingBarBottomMargin(value)
    }

    fun setFloatingBarCornerRadius(value: Int) = viewModelScope.launch {
        preferences.setFloatingBarCornerRadius(value)
    }

    fun setFloatingBarShadowElevation(value: Int) = viewModelScope.launch {
        preferences.setFloatingBarShadowElevation(value)
    }

    fun setCalendarLayoutPreset(preset: CalendarLayoutPreset) = viewModelScope.launch {
        preferences.setCalendarLayoutPreset(preset)
    }

    fun setClipboardShareDetection(enabled: Boolean) = viewModelScope.launch {
        preferences.setClipboardShareDetection(enabled)
    }

    fun setStatusChipFilled(filled: Boolean) = viewModelScope.launch {
        preferences.setStatusChipFilled(filled)
    }

    fun setShowVersionInProfile(show: Boolean) = viewModelScope.launch {
        preferences.setShowVersionInProfile(show)
    }

    fun restore(settings: AppearanceSettings) = viewModelScope.launch {
        preferences.restore(settings)
    }

    fun resetToDefaults() = restore(AppearanceSettings())
}
