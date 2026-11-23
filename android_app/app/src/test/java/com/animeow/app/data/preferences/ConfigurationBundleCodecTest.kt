package com.animeow.app.data.preferences

import com.animeow.app.data.branding.LauncherIcon
import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CalendarLayoutPreset
import com.animeow.app.ui.theme.CardInfoField
import com.animeow.app.ui.theme.CharacterLayout
import com.animeow.app.ui.theme.CommunityGroupView
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.ProfileSearchStyle
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.RatingIconStyle
import com.animeow.app.ui.theme.StatisticsChartStyle
import com.animeow.app.ui.theme.StatisticsLayoutStyle
import com.animeow.app.ui.theme.StatisticsMetric
import com.animeow.app.ui.theme.StatisticsModule
import com.animeow.app.ui.theme.StatisticsPreset
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.TierListStyle
import com.animeow.app.ui.preferences.defaultPortableConfiguration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class ConfigurationBundleCodecTest {
    @Test fun coverProgressOpacitySurvivesPortableConfigurations() {
        val source = ConfigurationBundle(AppearanceSettings(), tracker = TrackerSettings(coverProgressAlpha = 0.82f))
        val restored = ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(source))
        assertEquals(0.82f, restored.tracker!!.coverProgressAlpha, 0.0001f)
    }

    @Test
    fun roundTripsEveryPortableSection() {
        val source = ConfigurationBundle(
            appearance = AppearanceSettings(
                appStyle = AppStyle.RETRO_PIXEL,
                themeMode = ThemeMode.DARK,
                homeLayout = HomeLayout.COMPACT_INDEX,
                detailLayout = DetailLayout.MAGAZINE,
                contentDensity = ContentDensity.SPACIOUS,
                motionLevel = MotionLevel.REDUCED,
                useDynamicColor = true,
                fontScale = 1.4f,
                appFontChoice = com.animeow.app.ui.theme.AppFontChoice.CUSTOM,
                customFontId = "a".repeat(64),
                customFontName = "本机字体.ttf",
                cornerScale = 0.7f,
                gridColumns = 6,
                showTitle = false,
                showStatus = false,
                showRating = false,
                showProgress = false,
                coverBadgeStyle = CoverBadgeStyle.CORNER,
                ratingIconStyle = RatingIconStyle.TROPHY,
                coverTitlePosition = CoverTitlePosition.HIDDEN,
                coverAspectRatio = CoverAspectRatio.LANDSCAPE,
                detailCardStyle = DetailCardStyle.GLASS,
                profileSearchStyle = ProfileSearchStyle.HIDDEN,
                showDiscovery = false,
                showCalendar = false,
                showCommunity = true,
                showStatistics = true,
                detailModuleOrder = DetailModule.entries.reversed(),
                cardInfoOrder = CardInfoField.entries.reversed(),
                hiddenDetailModules = setOf(DetailModule.REMINDER, DetailModule.SERIES),
                cardSwipeActionsEnabled = true,
                swipeStartAction = SwipeAction.EDIT,
                swipeEndAction = SwipeAction.TRASH,
                hapticFeedback = true,
                soundFeedback = true,
                autoCompleteStatus = false,
                completionStatus = "归档",
                accentColor = 0xFF00A6A6L,
                randomAccentOnLaunch = true,
                navigationOrder = listOf("profile", "community", "statistics", "calendar", "discovery", "tracker"),
                startDestination = "profile",
                statisticsModuleOrder = StatisticsModule.entries.reversed(),
                hiddenStatisticsModules = setOf(StatisticsModule.ANALYSIS),
                statisticsPreset = StatisticsPreset.ANALYSIS,
                statisticsDensity = ContentDensity.SPACIOUS,
                statisticsStatusChart = StatisticsChartStyle.RANKED,
                statisticsTagChart = StatisticsChartStyle.RANKED,
                statisticsLayoutStyle = StatisticsLayoutStyle.DASHBOARD,
                hiddenStatisticsMetrics = setOf(StatisticsMetric.STREAK, StatisticsMetric.AVERAGE_RATING),
                communityGroupView = CommunityGroupView.LIST,
                communityDensity = ContentDensity.COMPACT,
                communityShowDescription = false,
                communityShowDownloads = false,
                tierListStyle = TierListStyle.MINIMAL,
                tierListCount = 3,
                tierBoardTitle = "完全自定义榜单",
                tierDisplayLabels = listOf("神", "佳", "良", "普", "弃"),
                characterLayout = CharacterLayout.GRID,
                characterShowMetadata = false,
                characterShowRating = false,
                calendarLayoutPreset = CalendarLayoutPreset.AGENDA,
                floatingBarHeight = 72,
                navigationBarStyle = com.animeow.app.ui.theme.NavigationBarStyle.LIQUID_GLASS,
                legacyNavigationBarStyle = com.animeow.app.ui.theme.NavigationBarStyle.MINIMAL,
                legacyCalendarLayoutPreset = CalendarLayoutPreset.MONTH,
                floatingBarOpacity = 0f,
                floatingBarBlur = 40,
                navigationLabelMode = com.animeow.app.ui.theme.NavigationLabelMode.ICONS_ONLY,
            ),
            tracker = TrackerSettings(
                showCoverRating = false,
                autoSyncNetworkCovers = true,
                infoOpacity = 0.62f,
                infoTextOpacity = 0.74f,
                infoDarkBackgroundOpacity = 0.46f,
                infoLightBackgroundOpacity = 0.83f,
                bentoCollectionLayout = BentoCollectionLayout.COMPACT,
                coverImageOpacity = 0.72f,
                coverSaturation = 0.4f,
                coverFitMode = CoverFitMode.FIT,
                statusBadgeBackground = StatusBadgeBackground.CUSTOM,
                statusBadgeCustomColor = 0xFFE7D8B1,
                showContinueWatching = false,
                showRecentAdded = true,
            ),
            discovery = DiscoveryNetworkSettings(
                metadataLookupOrder = MetadataLookupStep.entries.reversed(),
                aniListProxyUrl = "https://example.org/graphql",
                allowAniListProxy = false,
                contributePublicMetadata = false,
                showServerSource = true,
                includeServerInAllSources = true,
                display = DiscoveryDisplayConfig(
                    layout = DiscoveryResultLayout.GRID,
                    gridColumns = 5,
                    density = ContentDensity.COMPACT,
                    showSource = false,
                    showScore = true,
                    showAirDate = false,
                    showTags = false,
                ),
            ),
            calendar = CalendarDisplaySettings(showCovers = false, hiddenModules = setOf(CalendarModule.HISTORY)),
            editor = AnimeEditorSettings(
                preset = AnimeEditorPreset.CUSTOM,
                hiddenModules = setOf(AnimeEditorModule.REMINDER),
            ),
            branding = PortableBrandingSettings(launcherIcon = LauncherIcon.ICON_14, tapToSkip = false),
        )

        val restored = ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(source))

        assertEquals(source, restored)
        assertEquals(6, restored.sectionCount)
    }

    @Test
    fun normalizingV1ProfileDoesNotInventNewSections() {
        val legacy = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "profiles",
                JSONArray().put(
                    JSONObject()
                        .put("id", "legacy")
                        .put("name", "旧外观")
                        .put("settings", AppearanceSettingsCodec.toJson(AppearanceSettings())),
                ),
            )

        val normalized = JSONObject(ConfigurationProfileRepository.normalizeSnapshotJson(legacy.toString()))
        val profile = normalized.getJSONArray("profiles").getJSONObject(0)
        val configuration = profile.getJSONObject("configuration")

        assertEquals(2, normalized.getInt("schemaVersion"))
        assertTrue(configuration.has("appearance"))
        assertFalse(configuration.has("tracker"))
        assertFalse(configuration.has("branding"))
    }

    @Test
    fun defaultResetCoversEveryPortableCustomizationSection() {
        val defaults = defaultPortableConfiguration()

        assertEquals(AppearanceSettings(), defaults.appearance)
        assertEquals(TrackerSettings(), defaults.tracker)
        assertEquals(DiscoveryNetworkSettings(), defaults.discovery)
        assertEquals(CalendarDisplaySettings(), defaults.calendar)
        assertEquals(AnimeEditorSettings(), defaults.editor)
        assertEquals(PortableBrandingSettings(), defaults.branding)
        assertEquals(6, defaults.sectionCount)
    }

    @Test
    fun legacyAppearanceWithoutMetricSelectionUsesNewSafeDefault() {
        val restored = AppearanceSettingsCodec.fromJson(JSONObject())

        assertEquals(StatisticsLayoutStyle.CLASSIC, restored.statisticsLayoutStyle)
        assertEquals(setOf(StatisticsMetric.STREAK), restored.hiddenStatisticsMetrics)
    }

    @Test
    fun legacyProfilesKeepSafeNetworkDefaultsAndMigrateDuplicateLayout() {
        val restored = ConfigurationBundleCodec.fromJson(JSONObject("""{"appearance":{"homeLayout":"recommend_grid"},"discovery":{}}"""))
        assertEquals(HomeLayout.POSTER_WALL, restored.appearance.homeLayout)
        assertEquals(1f, restored.appearance.floatingBarOpacity)
        assertEquals(MetadataLookupStep.entries.toList(), restored.discovery!!.metadataLookupOrder)
        assertTrue(restored.discovery.allowAniListProxy)
        assertEquals(listOf(MetadataLookupStep.ANILIST, MetadataLookupStep.SERVER, MetadataLookupStep.BANGUMI, MetadataLookupStep.KITSU, MetadataLookupStep.TVMAZE, MetadataLookupStep.PROXY),
            MetadataLookupStep.fromStorage("anilist,unknown,anilist,server"))
        assertEquals(MetadataLookupStep.entries.toList(), MetadataLookupStep.fromStorage("server,bangumi,anilist,proxy"))
        assertEquals(MetadataLookupStep.PROXY, MetadataLookupStep.fromStorage("proxy,bangumi,server,anilist").first())
    }
}
