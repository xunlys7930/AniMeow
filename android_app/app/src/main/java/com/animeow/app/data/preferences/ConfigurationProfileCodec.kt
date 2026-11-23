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
        .putOptional("tracker", bundle.tracker?.let(TrackerSettingsCodec::toJson))
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
            tracker = json.optJSONObject("tracker")?.let(TrackerSettingsCodec::fromJson),
            discovery = json.optJSONObject("discovery")?.let(::discoveryFromJson),
            calendar = json.optJSONObject("calendar")?.let(::calendarFromJson),
            editor = json.optJSONObject("editor")?.let(::editorFromJson),
            branding = json.optJSONObject("branding")?.let(::brandingFromJson),
        )
    }

    private fun discoveryToJson(value: DiscoveryNetworkSettings): JSONObject = JSONObject()
        .put("publicDataRoute", value.publicDataRoute.name)
        .put("bangumiApiMode", value.bangumiApiMode.storageKey)
    .put("metadataLookupOrder", value.metadataLookupOrder.joinToString(",") { it.storageKey })
    .put("enabledMetadataSources", value.enabledMetadataSources.joinToString(",") { it.storageKey })
    .put("metadataLookupStrategy", value.metadataLookupStrategy.storageKey)
    .put("metadataTimeoutSeconds", value.metadataTimeoutSeconds)
    .put("aniListProxyUrl", value.aniListProxyUrl)
    .put("allowAniListProxy", value.allowAniListProxy)
    .put("contributePublicMetadata", value.contributePublicMetadata)
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
            publicDataRoute = PublicDataRoute.fromStorage(json.optString("publicDataRoute")),
            bangumiApiMode = BangumiApiMode.fromStorage(json.optString("bangumiApiMode")),
        metadataLookupOrder = com.animeow.app.data.preferences.MetadataLookupStep.fromStorage(json.optString("metadataLookupOrder")),
    enabledMetadataSources = com.animeow.app.data.preferences.MetadataLookupStep.enabledFromStorage(json.optString("enabledMetadataSources").takeIf { json.has("enabledMetadataSources") }),
    metadataLookupStrategy = com.animeow.app.data.preferences.MetadataLookupStrategy.fromStorage(json.optString("metadataLookupStrategy")),
    metadataTimeoutSeconds = json.optInt("metadataTimeoutSeconds", 6).coerceIn(3, 12),
        aniListProxyUrl = json.optString("aniListProxyUrl"),
        allowAniListProxy = json.optBoolean("allowAniListProxy", true),
        contributePublicMetadata = json.optBoolean("contributePublicMetadata", true),
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
