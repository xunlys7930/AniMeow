package com.animeow.app.ui.preferences

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.preferences.AnimeEditorSettings
import com.animeow.app.data.preferences.CalendarDisplaySettings
import com.animeow.app.data.preferences.ConfigurationBundle
import com.animeow.app.data.preferences.ConfigurationProfile
import com.animeow.app.data.preferences.ConfigurationProfileRepository
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.PortableBrandingSettings
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.data.preferences.TrackerPreferences
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.APP_FONT_SCALE_MAX
import com.animeow.app.ui.theme.APP_FONT_SCALE_MIN
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CalendarLayoutPreset
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.PageTransitionStyle
import com.animeow.app.ui.theme.ProfileSearchStyle
import com.animeow.app.ui.theme.RatingIconStyle
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.withNavigationDestinationVisible
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CustomizationUiState(
    val profiles: List<ConfigurationProfile> = emptyList(),
    val canUndo: Boolean = false,
    val hasChanges: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class CustomizationViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppearancePreferences(application)
    private val trackerPreferences = TrackerPreferences(application)
    private val profiles = ConfigurationProfileRepository(application, preferences)
    private val mutationMutex = Mutex()
    private var original: ConfigurationBundle? = null
    private var refreshBrandingBaselineOnResume = false
    private val history = ArrayDeque<ConfigurationBundle>()

    val settings: StateFlow<AppearanceSettings> = preferences.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppearanceSettings(),
    )
    val trackerSettings: StateFlow<TrackerSettings> = trackerPreferences.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackerSettings(),
    )
    private val _uiState = MutableStateFlow(CustomizationUiState())
    val uiState: StateFlow<CustomizationUiState> = _uiState.asStateFlow()

    fun beginDraft() = viewModelScope.launch {
        runCatchingCancellable {
            mutationMutex.withLock {
                val current = profiles.snapshotCurrent()
                if (original == null) original = current
                if (refreshBrandingBaselineOnResume) {
                    original = original?.copy(branding = current.branding)
                    refreshBrandingBaselineOnResume = false
                }
                updateDraftState(current)
            }
            reloadProfiles()
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(message = error.message ?: "无法读取当前配置")
        }
    }

    fun commit(onComplete: () -> Unit) = viewModelScope.launch {
        if (_uiState.value.busy) return@launch
        _uiState.value = _uiState.value.copy(busy = true, message = null)
        runCatchingCancellable {
            mutationMutex.withLock {
                val current = profiles.snapshotCurrent()
                original = current
                refreshBrandingBaselineOnResume = false
                history.clear()
                updateDraftState(current)
            }
        }.fold(
            onSuccess = {
                _uiState.value = _uiState.value.copy(busy = false)
                onComplete()
            },
            onFailure = { error ->
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    message = error.message ?: "无法应用当前配置",
                )
            },
        )
    }

    /**
     * The branding page saves device-local image changes immediately. Keep those changes as an
     * independent baseline while preserving any other pending customization draft.
     */
    fun openBranding(onOpen: () -> Unit) = viewModelScope.launch {
        if (_uiState.value.busy) return@launch
        runCatchingCancellable {
            mutationMutex.withLock {
                if (original == null) original = profiles.snapshotCurrent()
                refreshBrandingBaselineOnResume = true
            }
        }.fold(
            onSuccess = { onOpen() },
            onFailure = { error ->
                _uiState.value = _uiState.value.copy(message = error.message ?: "无法打开品牌定制")
            },
        )
    }

    fun discard(onComplete: () -> Unit) = viewModelScope.launch {
        if (_uiState.value.busy) return@launch
        val snapshot = mutationMutex.withLock { original }
        if (snapshot == null) {
            onComplete()
            return@launch
        }
        _uiState.value = _uiState.value.copy(busy = true, message = null)
        runCatchingCancellable { profiles.applyConfiguration(snapshot) }.fold(
            onSuccess = {
                mutationMutex.withLock {
                    original = null
                    refreshBrandingBaselineOnResume = false
                    history.clear()
                    updateDraftState(snapshot)
                }
                _uiState.value = _uiState.value.copy(busy = false)
                onComplete()
            },
            onFailure = { error ->
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    message = error.message ?: "恢复进入前配置失败",
                )
            },
        )
    }

    fun undo() = viewModelScope.launch {
        if (_uiState.value.busy) return@launch
        runCatchingCancellable {
            mutationMutex.withLock {
                if (history.isEmpty()) return@withLock
                val target = history.last()
                profiles.applyConfiguration(target)
                history.removeLast()
                updateDraftState(target)
            }
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(message = error.message ?: "撤销失败")
        }
    }

    fun resetToDefaults() = mutate(
        configuration = defaultPortableConfiguration(),
        successMessage = "已恢复全部可迁移个性化设置的默认值",
    )

    fun setAppStyle(value: AppStyle) = mutate { it.copy(appStyle = value) }
    fun setThemeMode(value: ThemeMode) = mutate { it.copy(themeMode = value) }
    fun setDynamicColor(value: Boolean) = mutate { it.copy(useDynamicColor = value) }
    fun setAccentColor(value: Long) = mutate {
        it.copy(accentColor = value, useDynamicColor = false)
    }
    fun setHomeLayout(value: HomeLayout) = mutate { it.copy(homeLayout = value) }
    fun setDetailLayout(value: DetailLayout) = mutate { it.copy(detailLayout = value) }
    fun setDensity(value: ContentDensity) = mutate { it.copy(contentDensity = value) }
    fun setSectionSpacing(value: com.animeow.app.ui.theme.SectionSpacing) = mutate { it.copy(sectionSpacing = value) }
    fun setTopBarSpacing(value: com.animeow.app.ui.theme.TopBarSpacing) = mutate { it.copy(topBarSpacing = value) }
    fun setMotion(value: MotionLevel) = mutate { it.copy(motionLevel = value) }
    fun setPageTransitionStyle(value: PageTransitionStyle) = mutate { it.copy(pageTransitionStyle = value) }
    fun setExitBehavior(value: com.animeow.app.ui.theme.ExitBehavior) = mutate { it.copy(exitBehavior = value) }
    fun setFontScale(value: Float) = mutate {
        it.copy(fontScale = value.coerceIn(APP_FONT_SCALE_MIN, APP_FONT_SCALE_MAX))
    }
    fun setCornerScale(value: Float) = mutate { it.copy(cornerScale = value.coerceIn(0.5f, 1.5f)) }
    fun setGridColumns(value: Int) = mutate { it.copy(gridColumns = value.coerceIn(2, 6)) }
    fun setShowTitle(value: Boolean) = mutate { it.copy(showTitle = value) }
    fun setShowStatus(value: Boolean) = mutate { it.copy(showStatus = value) }
    fun setShowRating(value: Boolean) = mutate { it.copy(showRating = value) }
    fun setShowProgress(value: Boolean) = mutate { it.copy(showProgress = value) }
    fun setBadgeStyle(value: CoverBadgeStyle) = mutate { it.copy(coverBadgeStyle = value) }
    fun setRatingIcon(value: RatingIconStyle) = mutate { it.copy(ratingIconStyle = value) }
    fun setRatingBadgeColor(value: RatingBadgeColorStyle) = mutate { it.copy(ratingBadgeColorStyle = value) }
    fun setRatingBadgeCustomColor(value: Long) = mutate { it.copy(ratingBadgeCustomColor = value) }
    fun setTitlePosition(value: CoverTitlePosition) = mutate { it.copy(coverTitlePosition = value) }
    fun setCoverAspectRatio(value: CoverAspectRatio) = mutate { it.copy(coverAspectRatio = value) }
    fun setDetailCardStyle(value: DetailCardStyle) = mutate { it.copy(detailCardStyle = value) }
    fun setProfileSearchStyle(value: ProfileSearchStyle) = mutate { it.copy(profileSearchStyle = value) }
    fun setClipboardShareDetection(value: Boolean) = mutate { it.copy(clipboardShareDetection = value) }
    fun setNavigationBarStyle(value: NavigationBarStyle) = mutate { it.copy(navigationBarStyle = value) }
    fun setFloatingBarHorizontalMargin(value: Int) = mutate { it.copy(floatingBarHorizontalMargin = value) }
    fun setFloatingBarBottomMargin(value: Int) = mutate { it.copy(floatingBarBottomMargin = value) }
    fun setFloatingBarCornerRadius(value: Int) = mutate { it.copy(floatingBarCornerRadius = value) }
    fun setFloatingBarShadowElevation(value: Int) = mutate { it.copy(floatingBarShadowElevation = value) }
    fun setFloatingBarHeight(value: Int) = mutate { it.copy(floatingBarHeight = value.coerceIn(48, 80)) }
    fun setNavigationLabelMode(value: com.animeow.app.ui.theme.NavigationLabelMode) = mutate { it.copy(navigationLabelMode = value) }
    fun setStatusChipFilled(value: Boolean) = mutate { it.copy(statusChipFilled = value) }
    fun setShowVersionInProfile(value: Boolean) = mutate { it.copy(showVersionInProfile = value) }
    fun setRandomAccentOnLaunch(value: Boolean) = mutate { current ->
        current.copy(
            randomAccentOnLaunch = value,
            accentColor = if (value) randomAccent(current.accentColor) else current.accentColor,
            useDynamicColor = if (value) false else current.useDynamicColor,
        )
    }
    fun randomizeAccent() = mutate(successMessage = "已更换强调色；系统动态色已自动关闭") { current ->
        current.copy(accentColor = randomAccent(current.accentColor), useDynamicColor = false)
    }
    fun setTrackerInfoTextOpacity(value: Float) = mutateTracker { it.copy(infoTextOpacity = value) }
    fun setStatusBadgeBackground(value: com.animeow.app.data.preferences.StatusBadgeBackground) = mutateTracker { it.copy(statusBadgeBackground = value) }
    fun setStatusBadgeCustomColor(value: Long) = mutateTracker { it.copy(statusBadgeCustomColor = value) }
    fun setTrackerDarkBackgroundOpacity(value: Float) = mutateTracker {
        it.copy(infoDarkBackgroundOpacity = value)
    }
    fun setTrackerLightBackgroundOpacity(value: Float) = mutateTracker {
        it.copy(infoLightBackgroundOpacity = value)
    }
    fun setTrackerCoverImageOpacity(value: Float) = mutateTracker { it.copy(coverImageOpacity = value) }
    fun setTrackerCoverSaturation(value: Float) = mutateTracker { it.copy(coverSaturation = value) }
    fun setShowStatusCount(value: Boolean) = mutateTracker { it.copy(showStatusCount = value) }
    fun setTimelineGroupField(value: com.animeow.app.ui.theme.TimelineGroupField) = mutateTracker { it.copy(timelineGroupField = value) }
    fun setCardSwipeActionsEnabled(value: Boolean) = mutate { it.copy(cardSwipeActionsEnabled = value) }
    fun setSwipeStart(value: SwipeAction) = mutate { it.copy(swipeStartAction = value) }
    fun setSwipeEnd(value: SwipeAction) = mutate { it.copy(swipeEndAction = value) }
    fun setHaptic(value: Boolean) = mutate { it.copy(hapticFeedback = value) }
    fun setSound(value: Boolean) = mutate { it.copy(soundFeedback = value) }
    fun setShowDiscovery(value: Boolean) = mutate { it.withNavigationDestinationVisible("discovery", value) }
    fun setShowCalendar(value: Boolean) = mutate { it.withNavigationDestinationVisible("calendar", value) }
    fun setShowCommunity(value: Boolean) = mutate { it.withNavigationDestinationVisible("community", value) }
    fun setShowStatistics(value: Boolean) = mutate { it.withNavigationDestinationVisible("statistics", value) }
    fun setCalendarPreset(value: CalendarLayoutPreset) = mutate { it.copy(calendarLayoutPreset = value) }

    private fun randomAccent(current: Long?): Long =
        RANDOM_ACCENTS.shuffled().firstOrNull { it != current } ?: RANDOM_ACCENTS.first()

    fun saveProfile(name: String) = viewModelScope.launch {
        runBusy {
            profiles.saveCurrent(name)
            reloadProfiles()
            "已保存配置“${name.trim()}”"
        }
    }

    fun duplicateProfile(profile: ConfigurationProfile) = viewModelScope.launch {
        runBusy {
            profiles.duplicate(profile)
            reloadProfiles()
            "已复制“${profile.name}”"
        }
    }

    fun deleteProfile(profile: ConfigurationProfile) = viewModelScope.launch {
        runBusy {
            profiles.delete(profile.id)
            reloadProfiles()
            "已删除“${profile.name}”"
        }
    }

    fun applyProfile(profile: ConfigurationProfile) = mutate(
        configuration = profile.configuration,
        successMessage = "已实时预览“${profile.name}”",
    )

    fun importProfile(uri: Uri) = viewModelScope.launch {
        runBusy {
            val profile = profiles.import(uri)
            reloadProfiles()
            mutationMutex.withLock { applyConfigurationNow(profile.configuration) }
            "已导入并预览“${profile.name}”"
        }
    }

    fun exportCurrent(uri: Uri, name: String) = viewModelScope.launch {
        runBusy {
            profiles.exportCurrent(uri, name.ifBlank { "我的配置" })
            "配置已导出"
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun mutate(
        successMessage: String? = null,
        transform: (AppearanceSettings) -> AppearanceSettings,
    ) = viewModelScope.launch {
        if (_uiState.value.busy) return@launch
        runCatchingCancellable {
            mutationMutex.withLock { mutateNow(transform) }
        }.fold(
            onSuccess = {
                if (successMessage != null) _uiState.value = _uiState.value.copy(message = successMessage)
            },
            onFailure = { error ->
                _uiState.value = _uiState.value.copy(message = error.message ?: "设置保存失败")
            },
        )
    }

    private suspend fun mutateNow(transform: (AppearanceSettings) -> AppearanceSettings) {
        val current = profiles.snapshotCurrent()
        val updated = transform(current.appearance)
        if (updated == current.appearance) return
        preferences.restore(updated)
        pushHistory(current)
        updateDraftState(current.copy(appearance = updated))
    }

    private fun mutateTracker(transform: (TrackerSettings) -> TrackerSettings) = viewModelScope.launch {
        if (_uiState.value.busy) return@launch
        runCatchingCancellable {
            mutationMutex.withLock {
                val current = profiles.snapshotCurrent()
                val currentTracker = current.tracker ?: trackerPreferences.snapshot()
                val updated = transform(currentTracker).normalized()
                if (updated == currentTracker) return@withLock
                trackerPreferences.save(updated)
                pushHistory(current)
                updateDraftState(current.copy(tracker = updated))
            }
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(message = error.message ?: "封面设置保存失败")
        }
    }

    private fun mutate(
        configuration: ConfigurationBundle,
        successMessage: String,
    ) = viewModelScope.launch {
        runBusy {
            mutationMutex.withLock { applyConfigurationNow(configuration) }
            successMessage
        }
    }

    private suspend fun applyConfigurationNow(configuration: ConfigurationBundle) {
        val current = profiles.snapshotCurrent()
        if (configuration == current) return
        profiles.applyConfiguration(configuration)
        pushHistory(current)
        updateDraftState(profiles.snapshotCurrent())
    }

    private suspend fun reloadProfiles() {
        _uiState.value = _uiState.value.copy(profiles = profiles.list())
    }

    private suspend fun runBusy(block: suspend () -> String) {
        if (_uiState.value.busy) return
        _uiState.value = _uiState.value.copy(busy = true, message = null)
        _uiState.value = runCatchingCancellable { block() }.fold(
            onSuccess = { _uiState.value.copy(busy = false, message = it) },
            onFailure = { _uiState.value.copy(busy = false, message = it.message ?: "操作失败") },
        )
    }

    private fun pushHistory(configuration: ConfigurationBundle) {
        history.addLast(configuration)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun updateDraftState(current: ConfigurationBundle) {
        _uiState.value = _uiState.value.copy(
            canUndo = history.isNotEmpty(),
            hasChanges = original?.let { it != current } ?: false,
        )
    }

    private companion object {
        const val MAX_HISTORY = 20

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
}

internal fun defaultPortableConfiguration(): ConfigurationBundle = ConfigurationBundle(
    appearance = AppearanceSettings(),
    tracker = TrackerSettings(),
    discovery = DiscoveryNetworkSettings(),
    calendar = CalendarDisplaySettings(),
    editor = AnimeEditorSettings(),
    branding = PortableBrandingSettings(),
)
