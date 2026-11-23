package com.animeow.app.ui.preferences

import android.app.Application
import android.net.Uri
import com.animeow.app.AniMeowApplication
import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.CloudAccountService
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.cloud.uploadDeviceBackup
import com.animeow.app.data.fonts.AppFontStore
import com.animeow.app.data.fonts.ImportedAppFont
import com.animeow.app.ui.theme.AppFontChoice
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
import com.animeow.app.data.preferences.withModuleVisible
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
import com.animeow.app.ui.theme.withAccentPalette
import com.animeow.app.ui.theme.opaqueAccentColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CustomizationUiState(
    val fonts: List<ImportedAppFont> = emptyList(),
    val profiles: List<ConfigurationProfile> = emptyList(),
    val canUndo: Boolean = false,
    val hasChanges: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val changesFromDefault: List<com.animeow.app.data.preferences.AppearanceChange> = emptyList(),
    val pendingProfile: ConfigurationProfile? = null,
    val pendingProfileIsSaved: Boolean = false,
    val pendingChanges: List<com.animeow.app.data.preferences.AppearanceChange> = emptyList(),
    val confirmClassicSuite: Boolean = false,
    val classicUndoDeadline: Long? = null,
    val cloudConfigured: Boolean = false,
    val cloudUsername: String? = null,
    val cloudSaving: Boolean = false,
    val draftChanges: List<com.animeow.app.data.preferences.AppearanceChange> = emptyList(),
)

class CustomizationViewModel @JvmOverloads constructor(
    application: Application,
    cloudApiBase: String = BuildConfig.CLOUD_API_BASE,
) : AndroidViewModel(application) {
    private val preferences = AppearancePreferences(application)
    private val trackerPreferences = TrackerPreferences(application)
    private val profiles = ConfigurationProfileRepository(application, preferences)
    private val fontStore = AppFontStore(application)
    private val wallpaperStore = com.animeow.app.data.background.WallpaperAssetStore(application)
    private val cloudService by lazy {
        val app = application as AniMeowApplication
        CloudAccountService(application, app.nativeBackupService, app.legacyBackupImporter, apiBase = cloudApiBase)
    }
    private val cloudSyncPreferences by lazy { CloudSyncPreferences(application) }
    private val mutationMutex = Mutex()
    private val draft = CustomizationDraft()

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
                draft.begin(current)
                updateDraftState(current)
            }
            reloadProfiles()
            _uiState.value = _uiState.value.copy(fonts = fontStore.list())
            refreshCloudAccount()
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
                draft.commit(current)
                finishClassicUndo()
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
                draft.openBranding(profiles.snapshotCurrent())
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
        val snapshot = mutationMutex.withLock { draft.original }
        if (snapshot == null) {
            onComplete()
            return@launch
        }
        _uiState.value = _uiState.value.copy(busy = true, message = null)
        runCatchingCancellable { profiles.applyConfiguration(snapshot) }.fold(
            onSuccess = {
                mutationMutex.withLock {
                    draft.discarded()
                    finishClassicUndo()
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
                val target = draft.undoTarget ?: return@withLock
                profiles.applyConfiguration(target)
                draft.undoCompleted()
                finishClassicUndo()
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

    fun setAppStyle(value: AppStyle) = mutate { it.copy(appStyle = value, useDynamicColor = false, accentColor = null) }
    fun setIconStyle(value: com.animeow.app.ui.icons.IconStyle) = mutate { it.copy(icons = it.icons.copy(style = value)) }
    fun setIconVariant(value: com.animeow.app.ui.icons.IconVariant) = mutate { it.copy(icons = it.icons.copy(variant = value)) }
    fun setIconAnimated(value: Boolean) = mutate { it.copy(icons = it.icons.copy(animated = value)) }
    fun setIconOverride(id: com.animeow.app.ui.icons.AppIconId, value: com.animeow.app.ui.icons.IconOverride?) =
        mutate { it.copy(icons = it.icons.withOverride(id, value)) }
    fun resetIconOverrides() = mutate { it.copy(icons = it.icons.copy(overrides = emptyMap())) }
    fun setIconColor(value: com.animeow.app.ui.icons.IconColorSettings) = mutate { it.copy(icons = it.icons.copy(color = value)) }
    fun setAccentGradient(value: com.animeow.app.ui.theme.AccentGradientSettings) = mutate { it.copy(accentGradient = value.normalized()) }
    fun setAccentPalette(colors: List<Long>) = mutate { it.withAccentPalette(colors) }
    fun applyAccentPreset(value: com.animeow.app.ui.theme.AccentPalettePreset) = mutate { value.apply(it) }
    fun setThemeMode(value: ThemeMode) = mutate { it.copy(themeMode = value) }
    fun setOverlayStyle(value: com.animeow.app.ui.theme.OverlayStyle) = mutate { it.copy(overlayStyle = value) }
    fun setDynamicColor(value: Boolean) = mutate { it.copy(useDynamicColor = value) }
    fun setThemeColor(value: Long?) = mutate { it.copy(themeColor = value?.let(::opaqueAccentColor)) }
    fun setAccentColor(value: Long) = mutate {
        it.copy(accentColor = opaqueAccentColor(value), useDynamicColor = false, randomAccentOnLaunch = false)
    }
    fun setHomeLayout(value: HomeLayout) = mutate { it.copy(homeLayout = value) }
    fun setDetailLayout(value: DetailLayout) = mutate { it.copy(detailLayout = value) }
    fun setDetailProgressMode(value: com.animeow.app.ui.theme.DetailProgressMode) = mutate { it.copy(detailProgressMode = value) }
    fun setDensity(value: ContentDensity) = mutate { it.copy(contentDensity = value) }
    fun setPageDensity(page: String, value: ContentDensity?) = mutate {
        it.copy(pageDensities = if (value == null) it.pageDensities - page else it.pageDensities + (page to value))
    }

    fun refreshCloudAccount() {
        _uiState.value = _uiState.value.copy(
            cloudConfigured = cloudService.isConfigured,
            cloudUsername = cloudService.loadSession()?.username,
        )
    }

    fun saveConfigurationToCloud() = viewModelScope.launch {
        runBusy {
            _uiState.value = _uiState.value.copy(cloudSaving = true)
            try {
                mutationMutex.withLock {
                    check(cloudService.isConfigured) { "云端服务暂未开放" }
                    val session = cloudService.loadSession() ?: error("请先登录云账号")
                    uploadDeviceBackup(session, cloudService, cloudSyncPreferences)
                    "当前配置已随设备备份保存到云端，可在“云账号与设备备份”中查看和恢复"
                }
            } finally {
                _uiState.value = _uiState.value.copy(cloudSaving = false)
                refreshCloudAccount()
            }
        }
    }
    fun setGlassQuality(value: com.animeow.app.ui.theme.GlassQuality) = mutate { it.copy(glassQuality = value) }
    fun setHighContrast(value: Boolean) = mutate { it.copy(highContrast = value) }
    fun applySuite(value: com.animeow.app.ui.theme.AppearanceSuite) = viewModelScope.launch {
        if (_uiState.value.busy) return@launch
        if (value == com.animeow.app.ui.theme.AppearanceSuite.CLASSIC && preferences.snapshot().frontendMode != com.animeow.app.ui.theme.FrontendMode.LEGACY) {
            _uiState.value = _uiState.value.copy(confirmClassicSuite = true)
        } else {
            mutate(successMessage = "已预览“${value.label}”，可继续微调或撤销") { value.apply(it) }.join()
        }
    }

    fun dismissClassicSuite() { _uiState.value = _uiState.value.copy(confirmClassicSuite = false) }

    fun confirmClassicSuite() = viewModelScope.launch {
        if (_uiState.value.busy || !_uiState.value.confirmClassicSuite) return@launch
        _uiState.value = _uiState.value.copy(confirmClassicSuite = false, busy = true)
        runCatchingCancellable {
            mutationMutex.withLock {
                mutateNow(classic = true) { com.animeow.app.ui.theme.AppearanceSuite.CLASSIC.apply(it) }
                _uiState.value = _uiState.value.copy(message = null, classicUndoDeadline = android.os.SystemClock.elapsedRealtime() + 5_000)
            }
        }.onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "切换失败") }
        _uiState.value = _uiState.value.copy(busy = false)
    }

    fun undoClassicSuite() = viewModelScope.launch {
        runCatchingCancellable {
            mutationMutex.withLock {
                val deadline = _uiState.value.classicUndoDeadline ?: return@withLock
                val before = draft.classicTarget ?: return@withLock
                if (android.os.SystemClock.elapsedRealtime() > deadline) return@withLock
                profiles.applyConfiguration(before)
                draft.classicUndoCompleted()
                finishClassicUndo()
                _uiState.value = _uiState.value.copy(classicUndoDeadline = null, message = "已恢复切换前的设置")
                updateDraftState(before)
            }
        }.onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "撤销失败") }
    }

    fun finishClassicUndo() {
        draft.finishClassicUndo()
        _uiState.value = _uiState.value.copy(classicUndoDeadline = null)
    }
    fun setHomeModuleVisible(module: com.animeow.app.data.preferences.HomeModule, visible: Boolean) = mutateTracker { it.withModuleVisible(module, visible) }
    fun moveHomeModule(module: com.animeow.app.data.preferences.HomeModule, offset: Int) = mutateTracker { current ->
        val order = current.homeModuleOrder.toMutableList()
        val index = order.indexOf(module)
        if (index >= 0) order.add((index + offset).coerceIn(0, order.lastIndex), order.removeAt(index))
        current.copy(homeModuleOrder = order)
    }
    fun resetCategory(category: String) = viewModelScope.launch {
        runBusy {
            mutationMutex.withLock {
                applyConfigurationNow(com.animeow.app.data.preferences.resetAppearanceCategory(profiles.snapshotCurrent(), category))
            }
            "本分类已恢复默认，可以撤销"
        }
    }
    fun setSectionSpacing(value: com.animeow.app.ui.theme.SectionSpacing) = mutate { it.copy(sectionSpacing = value) }
    fun setTopBarSpacing(value: com.animeow.app.ui.theme.TopBarSpacing) = mutate { it.copy(topBarSpacing = value) }
    fun setMotion(value: MotionLevel) = mutate { it.copy(motionLevel = value) }
    fun setPageTransitionStyle(value: PageTransitionStyle) = mutate { it.copy(pageTransitionStyle = value) }
    fun setPredictiveBackEnabled(value: Boolean) = mutate { it.copy(predictiveBackEnabled = value) }
    fun setNavigationOrder(value: List<String>) = mutate { it.copy(navigationOrder = com.animeow.app.ui.theme.parseNavigationOrder(value.joinToString(","))) }
    fun setStartDestination(value: String) = mutate { it.copy(startDestination = value) }
    fun setExitBehavior(value: com.animeow.app.ui.theme.ExitBehavior) = mutate { it.copy(exitBehavior = value) }
    fun setFontScale(value: Float) = mutate {
        it.copy(fontScale = value.coerceIn(APP_FONT_SCALE_MIN, APP_FONT_SCALE_MAX))
    }
    fun setCornerScale(value: Float) = mutate { it.copy(cornerScale = value.coerceIn(0.5f, 1.5f)) }
    fun setFontChoice(value: AppFontChoice) = mutate { it.copy(appFontChoice = value) }

    fun selectFont(font: ImportedAppFont) = mutate {
        it.copy(appFontChoice = AppFontChoice.CUSTOM, customFontId = font.id, customFontName = font.name)
    }

    fun importFont(uri: Uri) = viewModelScope.launch {
        runBusy {
            val font = fontStore.import(uri)
            mutationMutex.withLock {
                mutateNow { it.copy(appFontChoice = AppFontChoice.CUSTOM, customFontId = font.id, customFontName = font.name) }
            }
            _uiState.value = _uiState.value.copy(fonts = fontStore.list())
            "字体已导入并预览，点击“应用”保留本次选择"
        }
    }

    fun removeFont(font: ImportedAppFont) = viewModelScope.launch {
        runBusy {
            mutationMutex.withLock {
                fontStore.remove(font.id)
                mutateNow { current ->
                    if (current.customFontId == font.id) current.copy(appFontChoice = AppFontChoice.SYSTEM, customFontId = null, customFontName = "") else current
                }
            }
            _uiState.value = _uiState.value.copy(fonts = fontStore.list())
            "字体文件已移除；引用它的配置会使用系统字体"
        }
    }

    fun setBackgroundColor(value: Long?) = mutate {
        it.copy(backgroundColor = value?.let { c -> (c and 0xFFFFFFL) or 0xFF000000L })
    }

    fun setBackgroundScaleMode(value: com.animeow.app.data.background.BackgroundScaleMode) = mutate {
        it.copy(backgroundScaleMode = value)
    }

    fun setBackgroundImageOpacity(value: Float) = mutate {
        it.copy(backgroundImageOpacity = value.coerceIn(0f, 1f))
    }

    fun setBackgroundImageBlur(value: Int) = mutate {
        it.copy(backgroundImageBlur = value.coerceIn(0, 30))
    }

    fun setBackgroundDimOverlay(value: Float) = mutate {
        it.copy(backgroundDimOverlay = value.coerceIn(0f, 1f))
    }

    fun resolveWallpaper(path: String?): java.io.File? = wallpaperStore.resolve(path)

    fun importWallpaper(uri: Uri) = viewModelScope.launch {
        runBusy {
            val path = wallpaperStore.importWallpaper(uri)
            mutationMutex.withLock {
                mutateNow { it.copy(backgroundImagePath = path) }
            }
            wallpaperStore.cleanupOldWallpapers(path)
            "背景壁纸已应用"
        }
    }

    fun removeWallpaper() = viewModelScope.launch {
        runBusy {
            mutationMutex.withLock {
                val currentPath = profiles.snapshotCurrent().appearance.backgroundImagePath
                wallpaperStore.discard(currentPath)
                mutateNow { it.copy(backgroundImagePath = null) }
            }
            wallpaperStore.cleanupOldWallpapers(null)
            "背景壁纸已移除"
        }
    }

    fun setGridColumns(value: Int) = mutate { it.copy(gridColumns = value.coerceIn(2, 6)) }
    fun setShowTitle(value: Boolean) = mutate { it.copy(showTitle = value) }
    fun setShowStatus(value: Boolean) = mutate { it.copy(showStatus = value) }
    fun setShowRating(value: Boolean) = mutate { it.copy(showRating = value) }
    fun setShowProgress(value: Boolean) = mutate { it.copy(showProgress = value) }
    fun setCardInfoOrder(value: List<com.animeow.app.ui.theme.CardInfoField>) = mutate { it.copy(cardInfoOrder = value) }
    fun setShowSeriesEditButton(value: Boolean) = mutate { it.copy(showSeriesEditButton = value) }
    fun setShowPinBadges(value: Boolean) = mutate { it.copy(showPinBadges = value) }
    fun setBadgeStyle(value: CoverBadgeStyle) = mutate { it.copy(coverBadgeStyle = value) }
    fun setRatingIcon(value: RatingIconStyle) = mutate { it.copy(ratingIconStyle = value) }
    fun setRatingBadgeColor(value: RatingBadgeColorStyle) = mutate { it.copy(ratingBadgeColorStyle = value) }
    fun setRatingBadgeCustomColor(value: Long) = mutate { it.copy(ratingBadgeCustomColor = value) }
    fun setTitlePosition(value: CoverTitlePosition) = mutate { it.copy(coverTitlePosition = value) }
    fun setCoverAspectRatio(value: CoverAspectRatio) = mutate { it.copy(coverAspectRatio = value) }
    fun setDetailCardStyle(value: DetailCardStyle) = mutate { it.copy(detailCardStyle = value) }
    fun setProfileSearchStyle(value: ProfileSearchStyle) = mutate { it.copy(profileSearchStyle = value) }
    fun setClipboardShareDetection(value: Boolean) = mutate { it.copy(clipboardShareDetection = value) }
    fun setNavigationBarStyle(value: NavigationBarStyle) = mutate { if (it.frontendMode == com.animeow.app.ui.theme.FrontendMode.LEGACY) it.copy(legacyNavigationBarStyle = value) else it.copy(navigationBarStyle = value) }
    fun setFloatingBarHorizontalMargin(value: Int) = mutate { it.copy(floatingBarHorizontalMargin = value) }
    fun setFloatingBarBottomMargin(value: Int) = mutate { it.copy(floatingBarBottomMargin = value) }
    fun setFloatingBarCornerRadius(value: Int) = mutate { it.copy(floatingBarCornerRadius = value) }
    fun setFloatingBarShadowElevation(value: Int) = mutate { it.copy(floatingBarShadowElevation = value) }
    fun setFloatingBarHeight(value: Int) = mutate { it.copy(floatingBarHeight = value.coerceIn(48, 80)) }
    fun setFloatingBarOpacity(value: Float) = mutate { it.copy(floatingBarOpacity = value.coerceIn(0f, 1f)) }
    fun setFloatingBarBlur(value: Int) = mutate { it.copy(floatingBarBlur = value.coerceIn(0, 40)) }
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
    fun setCalendarPreset(value: CalendarLayoutPreset) = mutate { if (it.frontendMode == com.animeow.app.ui.theme.FrontendMode.LEGACY) it.copy(legacyCalendarLayoutPreset = value) else it.copy(calendarLayoutPreset = value) }

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

    fun applyProfile(profile: ConfigurationProfile) = viewModelScope.launch {
        runBusy {
            previewProfile(profile, alreadySaved = true)
            "请核对个性化配置的应用范围"
        }
    }

    fun importProfile(uri: Uri) = viewModelScope.launch {
        runBusy {
            val profile = profiles.import(uri, persist = false)
            previewProfile(profile, alreadySaved = false)
            "已读取配置，请查看导入对照"
        }
    }

    private suspend fun previewProfile(profile: ConfigurationProfile, alreadySaved: Boolean) {
        _uiState.value = _uiState.value.copy(pendingProfile = profile, pendingProfileIsSaved = alreadySaved,
            pendingChanges = com.animeow.app.data.preferences.appearanceChanges(profiles.snapshotCurrent(), profile.configuration))
    }

    fun dismissImport() { _uiState.value = _uiState.value.copy(pendingProfile = null, pendingProfileIsSaved = false, pendingChanges = emptyList()) }
    fun confirmImport() = viewModelScope.launch {
        val pending = _uiState.value.pendingProfile ?: return@launch
        val alreadySaved = _uiState.value.pendingProfileIsSaved
        runBusy {
            mutationMutex.withLock {
                val saved = if (alreadySaved) pending else profiles.save(pending.name, pending.configuration)
                applyConfigurationNow(saved.configuration)
            }
            reloadProfiles()
            dismissImport()
            "已预览个性化配置“${pending.name}”，查看变化并应用保留，或撤销恢复"
        }
    }

    fun exportCurrent(uri: Uri, name: String) = viewModelScope.launch {
        runBusy {
            profiles.export(uri, name.ifBlank { "我的配置" }, profiles.snapshotCurrent().forAppearanceSharing())
            "个性化配置已导出，不包含字体文件、背景图片或网络配置"
        }
    }

    fun shareCurrent(name: String) = viewModelScope.launch {
        runBusy {
            val context = getApplication<Application>()
            val file = java.io.File(context.cacheDir, "shared/appearance/AniMeow-appearance.json")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.parentFile?.mkdirs() }
            profiles.export(Uri.fromFile(file), name.ifBlank { "我的外观方案" }, profiles.snapshotCurrent().forAppearanceSharing())
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/json")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(send, "分享外观方案").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            "方案不包含本地字体、背景图片或网络地址"
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
            mutationMutex.withLock { mutateNow(transform = transform) }
        }.fold(
            onSuccess = {
                if (successMessage != null) _uiState.value = _uiState.value.copy(message = successMessage)
            },
            onFailure = { error ->
                _uiState.value = _uiState.value.copy(message = error.message ?: "设置保存失败")
            },
        )
    }

    private suspend fun mutateNow(classic: Boolean = false, transform: (AppearanceSettings) -> AppearanceSettings) {
        val current = profiles.snapshotCurrent()
        val updated = transform(current.appearance)
        if (updated == current.appearance) return
        preferences.restore(updated)
        recordApplied(current, current.copy(appearance = updated), classic)
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
                recordApplied(current, current.copy(tracker = updated))
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
        // 云备份恢复、导入他人方案、撤销、恢复本分类默认之后，外观设置里可能留下一个
        // 指向不存在文件的壁纸路径（壁纸文件本身不进备份也不进方案）。统一在这里清掉，
        // 否则界面表现为"壁纸莫名消失"，而磁盘上的旧文件变成没人引用的孤儿。
        val safe = configuration.withoutMissingWallpaper()
        if (safe == current) return
        profiles.applyConfiguration(safe)
        recordApplied(current, profiles.snapshotCurrent())
    }

    private fun ConfigurationBundle.withoutMissingWallpaper(): ConfigurationBundle {
        val path = appearance.backgroundImagePath ?: return this
        return if (wallpaperStore.resolve(path) != null) this
        else copy(appearance = appearance.copy(backgroundImagePath = null))
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

    private fun recordApplied(before: ConfigurationBundle, after: ConfigurationBundle, classic: Boolean = false) {
        if (com.animeow.app.data.preferences.appearanceChanges(before, after).isNotEmpty()) draft.record(before, classic)
        updateDraftState(after)
    }

    private fun updateDraftState(current: ConfigurationBundle) {
        val changes = draft.original?.let { com.animeow.app.data.preferences.appearanceChanges(it, current) }.orEmpty()
        _uiState.value = _uiState.value.copy(
            canUndo = draft.canUndo,
            hasChanges = changes.isNotEmpty(),
            draftChanges = changes,
            changesFromDefault = com.animeow.app.data.preferences.appearanceChanges(defaultPortableConfiguration(), current),
        )
    }

    private companion object {
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

internal fun ConfigurationBundle.forAppearanceSharing(): ConfigurationBundle = copy(
    appearance = if (appearance.appFontChoice == AppFontChoice.CUSTOM) appearance.copy(appFontChoice = AppFontChoice.SYSTEM, customFontId = null, customFontName = "")
        else appearance.copy(customFontId = null, customFontName = ""),
    discovery = null,
    tracker = tracker?.copy(lastSelectedStatus = "全部", lastSubjectType = "all", lastSortKey = "recent"),
    branding = branding?.copy(customSplashEnabled = false),
).let { bundle ->
    // 背景壁纸图片文件不进方案（导出提示里也是这么写的），但它的**路径**以前会被一起带出去。
    // 别人导入后本机没有这个文件，界面就表现为"设置了壁纸却什么都没有"，
    // 而且旧文件在我们这边变成再没人引用的孤儿。路径属于本机资源引用，必须清掉。
    bundle.copy(appearance = bundle.appearance.copy(backgroundImagePath = null))
}
