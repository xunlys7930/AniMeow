package com.animeow.app.data.preferences

import com.animeow.app.data.branding.BrandingSettings
import com.animeow.app.data.branding.LauncherIcon
import com.animeow.app.data.branding.SplashBackgroundMode
import com.animeow.app.data.branding.SplashScaleMode
import com.animeow.app.ui.theme.AppearanceSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * A portable configuration bundle. Nullable sections preserve the semantics of v1 profiles,
 * which only contained appearance settings and therefore must not reset newer preferences.
 */
data class ConfigurationBundle(
    val appearance: AppearanceSettings,
    val tracker: TrackerSettings? = null,
    val discovery: DiscoveryNetworkSettings? = null,
    val calendar: CalendarDisplaySettings? = null,
    val editor: AnimeEditorSettings? = null,
    val branding: PortableBrandingSettings? = null,
) {
    val sectionCount: Int
        get() = 1 + listOf(tracker, discovery, calendar, editor, branding).count { it != null }
}

/**
 * Branding behavior that can safely travel between devices. The local splash-image path is
 * intentionally excluded: it is device-specific and may reveal private filesystem information.
 */
data class PortableBrandingSettings(
    val customSplashEnabled: Boolean = false,
    val splashDurationMillis: Int = 1_000,
    val splashScaleMode: SplashScaleMode = SplashScaleMode.COVER,
    val splashBackgroundMode: SplashBackgroundMode = SplashBackgroundMode.THEME,
    val splashFocalX: Float = 0.5f,
    val splashFocalY: Float = 0.5f,
    val tapToSkip: Boolean = true,
    val launcherIcon: LauncherIcon = LauncherIcon.DEFAULT,
) {
    fun normalized(): PortableBrandingSettings = copy(
        splashDurationMillis = splashDurationMillis.coerceIn(500, 5_000),
        splashFocalX = splashFocalX.coerceIn(0f, 1f),
        splashFocalY = splashFocalY.coerceIn(0f, 1f),
    )

    fun applyTo(current: BrandingSettings): BrandingSettings = current.copy(
        customSplashEnabled = customSplashEnabled && !current.splashImagePath.isNullOrBlank(),
        splashDurationMillis = splashDurationMillis,
        splashScaleMode = splashScaleMode,
        splashBackgroundMode = splashBackgroundMode,
        splashFocalX = splashFocalX,
        splashFocalY = splashFocalY,
        tapToSkip = tapToSkip,
        launcherIcon = launcherIcon,
    ).normalized()

    companion object {
        fun from(settings: BrandingSettings): PortableBrandingSettings = PortableBrandingSettings(
            customSplashEnabled = settings.customSplashEnabled,
            splashDurationMillis = settings.splashDurationMillis,
            splashScaleMode = settings.splashScaleMode,
            splashBackgroundMode = settings.splashBackgroundMode,
            splashFocalX = settings.splashFocalX,
            splashFocalY = settings.splashFocalY,
            tapToSkip = settings.tapToSkip,
            launcherIcon = settings.launcherIcon,
        ).normalized()
    }
}

internal object ConfigurationBundleCodec {
    const val SCHEMA_VERSION = 2

    fun toJson(bundle: ConfigurationBundle): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("appearance", AppearanceSettingsCodec.toJson(bundle.appearance))
        .putOptional("tracker", bundle.tracker?.let(::trackerToJson))
        .putOptional("discovery", bundle.discovery?.let(::discoveryToJson))
        .putOptional("calendar", bundle.calendar?.let(::calendarToJson))
        .putOptional("editor", bundle.editor?.let(::editorToJson))
        .putOptional("branding", bundle.branding?.let(::brandingToJson))

    fun fromJson(json: JSONObject): ConfigurationBundle {
        val appearanceJson = json.optJSONObject("appearance")
            ?: json.optJSONObject("settings")
            ?: json
        return ConfigurationBundle(
            appearance = AppearanceSettingsCodec.fromJson(appearanceJson),
            tracker = json.optJSONObject("tracker")?.let(::trackerFromJson),
            discovery = json.optJSONObject("discovery")?.let(::discoveryFromJson),
            calendar = json.optJSONObject("calendar")?.let(::calendarFromJson),
            editor = json.optJSONObject("editor")?.let(::editorFromJson),
            branding = json.optJSONObject("branding")?.let(::brandingFromJson),
        )
    }

    private fun trackerToJson(value: TrackerSettings): JSONObject = JSONObject()
        .put("defaultStartStatus", value.defaultStartStatus)
        .put("lastSelectedStatus", value.lastSelectedStatus)
        .put("lastSubjectType", value.lastSubjectType)
        .put("lastSortKey", value.lastSortKey)
        .put("sortAscending", value.sortAscending)
        .put("groupSeriesOnHome", value.groupSeriesOnHome)
        .put("showStandaloneBookshelf", value.showStandaloneBookshelf)
        .put("homeBottomScope", value.homeBottomScope.storageKey)
        .put("bentoCollectionLayout", value.bentoCollectionLayout.storageKey)
        .put("showContinueWatching", value.showContinueWatching)
        .put("showRecentAdded", value.showRecentAdded)
        .put("showSubjectType", value.showSubjectType)
        .put("showSeriesCount", value.showSeriesCount)
        .put("showUpdateWeekday", value.showUpdateWeekday)
        .put("showCoverStatus", value.showCoverStatus)
        .put("statusBadgeBackground", value.statusBadgeBackground.storageKey)
        .put("statusBadgeCustomColor", value.statusBadgeCustomColor)
        .put("showCoverRating", value.showCoverRating)
        .put("showCoverProgress", value.showCoverProgress)
        .put("showCoverSubjectType", value.showCoverSubjectType)
        .put("showCoverSeriesCount", value.showCoverSeriesCount)
        .put("showCoverUpdateWeekday", value.showCoverUpdateWeekday)
        .put("autoSyncNetworkCovers", value.autoSyncNetworkCovers)
        .put("infoScale", value.infoScale)
        .put("infoOpacity", value.infoOpacity)
        .put("infoTextOpacity", value.infoTextOpacity)
        .put("infoDarkBackgroundOpacity", value.infoDarkBackgroundOpacity)
        .put("infoLightBackgroundOpacity", value.infoLightBackgroundOpacity)
        .put("infoCornerDp", value.infoCornerDp)
        .put("coverCornerDp", value.coverCornerDp)
        .put("coverImageOpacity", value.coverImageOpacity)
        .put("coverSaturation", value.coverSaturation)
        .put("coverFitMode", value.coverFitMode.storageKey)

    private fun trackerFromJson(json: JSONObject): TrackerSettings = TrackerSettings(
        defaultStartStatus = json.optString("defaultStartStatus", TRACKER_START_LAST_STATUS),
        lastSelectedStatus = json.optString("lastSelectedStatus", "全部"),
        lastSubjectType = json.optString("lastSubjectType", "all"),
        lastSortKey = json.optString("lastSortKey", "recent"),
        sortAscending = json.optBoolean("sortAscending", false),
        groupSeriesOnHome = json.optBoolean("groupSeriesOnHome", true),
        showStandaloneBookshelf = json.optBoolean("showStandaloneBookshelf", true),
        homeBottomScope = HomeBottomScope.fromStorage(json.optString("homeBottomScope")),
        bentoCollectionLayout = BentoCollectionLayout.fromStorage(json.optString("bentoCollectionLayout")),
        showContinueWatching = json.optBoolean("showContinueWatching", true),
        showRecentAdded = json.optBoolean("showRecentAdded", false),
        showSubjectType = json.optBoolean("showSubjectType", false),
        showSeriesCount = json.optBoolean("showSeriesCount", true),
        showUpdateWeekday = json.optBoolean("showUpdateWeekday", false),
        showCoverStatus = json.optBoolean("showCoverStatus", true),
        statusBadgeBackground = StatusBadgeBackground.fromStorage(json.optString("statusBadgeBackground")),
        statusBadgeCustomColor = json.optLong("statusBadgeCustomColor", 0xFF466B59),
        showCoverRating = json.optBoolean("showCoverRating", true),
        showCoverProgress = json.optBoolean("showCoverProgress", true),
        showCoverSubjectType = json.optBoolean("showCoverSubjectType", false),
        showCoverSeriesCount = json.optBoolean("showCoverSeriesCount", true),
        showCoverUpdateWeekday = json.optBoolean("showCoverUpdateWeekday", false),
        autoSyncNetworkCovers = json.optBoolean("autoSyncNetworkCovers", false),
        infoScale = json.optDouble("infoScale", 1.0).toFloat(),
        infoOpacity = json.optDouble("infoOpacity", 0.88).toFloat(),
        infoTextOpacity = json.optDouble("infoTextOpacity", 1.0).toFloat(),
        infoDarkBackgroundOpacity = json.optDouble(
            "infoDarkBackgroundOpacity",
            json.optDouble("infoOpacity", 0.88),
        ).toFloat(),
        infoLightBackgroundOpacity = json.optDouble(
            "infoLightBackgroundOpacity",
            json.optDouble("infoOpacity", 0.88),
        ).toFloat(),
        infoCornerDp = json.optDouble("infoCornerDp", 8.0).toFloat(),
        coverCornerDp = json.optDouble("coverCornerDp", 12.0).toFloat(),
        coverImageOpacity = json.optDouble("coverImageOpacity", 1.0).toFloat(),
        coverSaturation = json.optDouble("coverSaturation", 1.0).toFloat(),
        coverFitMode = CoverFitMode.fromStorage(json.optString("coverFitMode")),
    ).normalized()

    private fun discoveryToJson(value: DiscoveryNetworkSettings): JSONObject = JSONObject()
        .put("bangumiApiMode", value.bangumiApiMode.storageKey)
        .put("bangumiApiProxyBase", value.bangumiApiProxyBase)
        .put("useBangumiImageProxy", value.useBangumiImageProxy)
        .put("bangumiImageProxyBase", value.bangumiImageProxyBase)
        .put("showServerSource", value.showServerSource)
        .put("includeServerInAllSources", value.includeServerInAllSources)
        .put(
            "display",
            JSONObject()
                .put("layout", value.display.layout.storageKey)
                .put("gridColumns", value.display.gridColumns)
                .put("density", value.display.density.storageKey)
                .put("showSource", value.display.showSource)
                .put("showScore", value.display.showScore)
                .put("showAirDate", value.display.showAirDate)
                .put("showTags", value.display.showTags),
        )

    private fun discoveryFromJson(json: JSONObject): DiscoveryNetworkSettings {
        val display = json.optJSONObject("display")
        return DiscoveryNetworkSettings(
            bangumiApiMode = BangumiApiMode.fromStorage(json.optString("bangumiApiMode")),
            bangumiApiProxyBase = json.optString("bangumiApiProxyBase", DEFAULT_BANGUMI_API_PROXY),
            useBangumiImageProxy = json.optBoolean("useBangumiImageProxy", true),
            bangumiImageProxyBase = json.optString("bangumiImageProxyBase", DEFAULT_BANGUMI_IMAGE_PROXY),
            showServerSource = json.optBoolean("showServerSource", false),
            includeServerInAllSources = json.optBoolean("includeServerInAllSources", false),
            display = DiscoveryDisplayConfig(
                layout = DiscoveryResultLayout.fromStorage(display?.optString("layout")),
                gridColumns = display?.optInt("gridColumns", 3)?.coerceIn(2, 5) ?: 3,
                density = com.animeow.app.ui.theme.ContentDensity.fromStorage(display?.optString("density")),
                showSource = display?.optBoolean("showSource", true) ?: true,
                showScore = display?.optBoolean("showScore", true) ?: true,
                showAirDate = display?.optBoolean("showAirDate", true) ?: true,
                showTags = display?.optBoolean("showTags", true) ?: true,
            ),
        )
    }

    private fun calendarToJson(value: CalendarDisplaySettings): JSONObject = JSONObject()
        .put("preset", value.preset.storageKey)
        .put("markerStyle", value.markerStyle.storageKey)
        .put("density", value.density.storageKey)
        .put("showCovers", value.showCovers)
        .put("showLegend", value.showLegend)
        .put("moduleOrder", JSONArray(value.moduleOrder.map(CalendarModule::storageKey)))
        .put("hiddenModules", JSONArray(value.hiddenModules.map(CalendarModule::storageKey)))
        .put("enabledEventTypes", JSONArray(value.enabledEventTypes.sorted()))

    private fun calendarFromJson(json: JSONObject): CalendarDisplaySettings = CalendarDisplaySettings(
        preset = CalendarPagePreset.fromStorage(json.optString("preset")),
        markerStyle = CalendarMarkerStyle.fromStorage(json.optString("markerStyle")),
        density = CalendarContentDensity.fromStorage(json.optString("density")),
        showCovers = json.optBoolean("showCovers", true),
        showLegend = json.optBoolean("showLegend", true),
        moduleOrder = json.stringValues("moduleOrder").mapNotNull(CalendarModule::fromStorage),
        hiddenModules = json.stringValues("hiddenModules").mapNotNull(CalendarModule::fromStorage).toSet(),
        enabledEventTypes = json.stringValues("enabledEventTypes").toSet(),
    ).normalized()

    private fun editorToJson(value: AnimeEditorSettings): JSONObject = JSONObject()
        .put("preset", value.preset.storageKey)
        .put("density", value.density.storageKey)
        .put("exitBehavior", value.exitBehavior.storageKey)
        .put("moduleOrder", JSONArray(value.moduleOrder.map(AnimeEditorModule::storageKey)))
        .put("hiddenModules", JSONArray(value.hiddenModules.map(AnimeEditorModule::storageKey)))

    private fun editorFromJson(json: JSONObject): AnimeEditorSettings = AnimeEditorSettings(
        preset = AnimeEditorPreset.fromStorage(json.optString("preset")),
        density = AnimeEditorDensity.fromStorage(json.optString("density")),
        exitBehavior = EditorExitBehavior.fromStorage(json.optString("exitBehavior")),
        moduleOrder = json.stringValues("moduleOrder").mapNotNull(AnimeEditorModule::fromStorage),
        hiddenModules = json.stringValues("hiddenModules").mapNotNull(AnimeEditorModule::fromStorage).toSet(),
    ).normalized()

    private fun brandingToJson(value: PortableBrandingSettings): JSONObject = JSONObject()
        .put("customSplashEnabled", value.customSplashEnabled)
        .put("splashDurationMillis", value.splashDurationMillis)
        .put("splashScaleMode", value.splashScaleMode.storageKey)
        .put("splashBackgroundMode", value.splashBackgroundMode.storageKey)
        .put("splashFocalX", value.splashFocalX)
        .put("splashFocalY", value.splashFocalY)
        .put("tapToSkip", value.tapToSkip)
        .put("launcherIcon", value.launcherIcon.storageKey)

    private fun brandingFromJson(json: JSONObject): PortableBrandingSettings = PortableBrandingSettings(
        customSplashEnabled = json.optBoolean("customSplashEnabled", false),
        splashDurationMillis = json.optInt("splashDurationMillis", 1_000),
        splashScaleMode = SplashScaleMode.fromStorage(json.optString("splashScaleMode")),
        splashBackgroundMode = SplashBackgroundMode.fromStorage(json.optString("splashBackgroundMode")),
        splashFocalX = json.optDouble("splashFocalX", 0.5).toFloat(),
        splashFocalY = json.optDouble("splashFocalY", 0.5).toFloat(),
        tapToSkip = json.optBoolean("tapToSkip", true),
        launcherIcon = LauncherIcon.fromStorage(json.optString("launcherIcon")),
    ).normalized()
}

private fun JSONObject.putOptional(key: String, value: Any?): JSONObject = apply {
    if (value != null) put(key, value)
}

private fun JSONObject.stringValues(key: String): List<String> {
    val array = optJSONArray(key)
    if (array != null) return List(array.length()) { index -> array.optString(index) }
    return optString(key).split(',').map(String::trim).filter(String::isNotEmpty)
}
