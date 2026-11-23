package com.animeow.app.data.preferences

import com.animeow.app.ui.theme.*
import com.animeow.app.ui.preferences.forAppearanceSharing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonalizationPolicyTest {
    @Test fun categoryResetPreservesSourcesAndOtherCategories() {
        val current = ConfigurationBundle(AppearanceSettings(fontScale = 1.2f, navigationBarStyle = NavigationBarStyle.MINIMAL),
            tracker = TrackerSettings(showWeeklyRecap = true), discovery = DiscoveryNetworkSettings(metadataLookupStrategy = MetadataLookupStrategy.ORDERED))
        val reset = resetAppearanceCategory(current, "size")
        assertEquals(1f, reset.appearance.fontScale)
        assertEquals(NavigationBarStyle.MINIMAL, reset.appearance.navigationBarStyle)
        assertEquals(current.discovery, reset.discovery)
        assertEquals(true, reset.tracker?.showWeeklyRecap)
    }
    @Test fun sharingStripsCustomFontsAndNetworkSettings() {
        val source = ConfigurationBundle(AppearanceSettings(appFontChoice = AppFontChoice.CUSTOM, customFontId = "a".repeat(64), customFontName = "Private"),
            discovery = DiscoveryNetworkSettings(aniListProxyUrl = "https://private.test"), branding = PortableBrandingSettings(customSplashEnabled = true))
        val portable = source.forAppearanceSharing()
        assertNull(portable.discovery)
        assertNull(portable.appearance.customFontId)
        assertEquals(AppFontChoice.SYSTEM, portable.appearance.appFontChoice)
        assertEquals(false, portable.branding?.customSplashEnabled)
    }
    @Test fun sourceAndHomePoliciesSurviveProfileRoundTrip() {
        val value = ConfigurationBundle(AppearanceSettings(glassQuality = GlassQuality.BLUR, pageDensities = mapOf("anime" to ContentDensity.SPACIOUS)),
            tracker = TrackerSettings(homeModuleOrder = HomeModule.entries.reversed(), showHomeCalendar = true, showWeeklyRecap = true),
            discovery = DiscoveryNetworkSettings(enabledMetadataSources = setOf(MetadataLookupStep.KITSU), metadataLookupStrategy = MetadataLookupStrategy.ORDERED, metadataTimeoutSeconds = 3))
        assertEquals(value, ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(value)))
    }
    @Test fun arbitraryAccentColorsRemainReadableOnLightAndDarkSurfaces() {
        for (background in listOf(Color.White, Color(0xFF111111))) for (color in listOf(Color.Yellow, Color.Green, Color(0xFF777777), Color.Black, Color.White)) {
            val accent = readableAccent(color, background)
            val contrast = (maxOf(accent.luminance(), background.luminance()) + .05f) / (minOf(accent.luminance(), background.luminance()) + .05f)
            assertTrue(contrast >= 4.5f)
        }
    }

    @Test fun visualResetsNeverResetOperatingHabitsOrNavigationDestinations() {
        val current = ConfigurationBundle(AppearanceSettings(
            detailProgressMode = DetailProgressMode.COMBINED, exitBehavior = ExitBehavior.DIRECT,
            predictiveBackEnabled = false, cardSwipeActionsEnabled = true, hapticFeedback = true,
            startDestination = "calendar", profileSearchStyle = ProfileSearchStyle.HIDDEN,
            clipboardShareDetection = true, navigationBarStyle = NavigationBarStyle.MINIMAL,
        ))
        for (category in listOf("visual", "icons", "layout", "size", "card", "cover", "motion", "nav")) {
            val reset = resetAppearanceCategory(current, category).appearance
            assertEquals(category, current.appearance.detailProgressMode, reset.detailProgressMode)
            assertEquals(category, current.appearance.exitBehavior, reset.exitBehavior)
            assertEquals(category, current.appearance.predictiveBackEnabled, reset.predictiveBackEnabled)
            assertEquals(category, current.appearance.cardSwipeActionsEnabled, reset.cardSwipeActionsEnabled)
            assertEquals(category, current.appearance.hapticFeedback, reset.hapticFeedback)
            assertEquals(category, current.appearance.startDestination, reset.startDestination)
            assertEquals(category, current.appearance.profileSearchStyle, reset.profileSearchStyle)
            assertEquals(category, current.appearance.clipboardShareDetection, reset.clipboardShareDetection)
        }
    }

    @Test fun eachAppearanceFieldHasOneCanonicalResetCategory() {
        val keys = appearanceSections.values.flatMap { it }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue("detailProgressMode" in appearanceSections.getValue("behavior"))
        assertTrue("clipboardShareDetection" in appearanceSections.getValue("navigation"))
        assertTrue("navigationBarStyle" in appearanceSections.getValue("nav"))
        assertTrue("cornerScale" in appearanceSections.getValue("layout"))
    }

    @Test fun draftComparisonSeparatesVisualAndBehaviorAndRedactsWallpaperPaths() {
        val before = ConfigurationBundle(AppearanceSettings())
        val after = before.copy(appearance = before.appearance.copy(fontScale = 1.2f,
            detailProgressMode = DetailProgressMode.COMBINED, clipboardShareDetection = true,
            backgroundImagePath = "/private/device/wallpaper.jpg"))
        val changes = appearanceChanges(before, after)
        assertTrue(changes.any { it.category == "size" && it.label == "文字大小" })
        val fontChange = changes.single { it.label == "文字大小" }
        assertEquals("100%", fontChange.before)
        assertEquals("120%", fontChange.after)
        assertTrue(changes.any { it.category == "behavior" && it.label == "详情进度操作" })
        assertTrue(changes.any { it.category == "navigation" && it.label == "剪贴板分享码识别" })
        assertFalse(changes.any { it.before.contains("/private/") || it.after.contains("/private/") })
    }
}
