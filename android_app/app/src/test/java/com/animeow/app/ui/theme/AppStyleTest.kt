package com.animeow.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStyleTest {
    @Test
    fun defaultStyleIsMiuix() {
        assertEquals(AppStyle.MIUIX, AppearanceSettings().appStyle)
    }

    @Test
    fun storedStyleCanBeRestored() {
        assertEquals(
            AppStyle.CYBER_GLASS,
            AppStyle.fromStorage("cyber_glass"),
        )
    }

    @Test
    fun unknownStyleFallsBackToMiuix() {
        assertEquals(AppStyle.MIUIX, AppStyle.fromStorage("unknown"))
    }

    @Test
    fun removedSimilarStylesMigrateToMiuix() {
        assertEquals(AppStyle.MIUIX, AppStyle.fromStorage("material_expressive"))
        assertEquals(AppStyle.MIUIX, AppStyle.fromStorage("cupertino_minimal"))
    }

    @Test
    fun homeLayoutFallsBackToBento() {
        assertEquals(HomeLayout.BENTO, HomeLayout.fromStorage("unknown"))
    }

    @Test
    fun motionLevelCanBeRestored() {
        assertEquals(MotionLevel.REDUCED, MotionLevel.fromStorage("reduced"))
    }

    @Test
    fun contentDensityPresetsHaveClearlySeparatedVisualScales() {
        assertTrue(ContentDensity.COMPACT.scale <= 0.75f)
        assertTrue(ContentDensity.SPACIOUS.scale >= 1.25f)
        assertTrue(ContentDensity.COMPACT.itemScale < ContentDensity.COMFORTABLE.itemScale)
        assertTrue(ContentDensity.SPACIOUS.itemScale > ContentDensity.COMFORTABLE.itemScale)
    }

    @Test
    fun appFontScaleKeepsSystemAccessibilityScaling() {
        val combined = combinedFontScale(systemFontScale = 1.6f, appFontScale = 1.2f)
        assertTrue(combined > 1.6f)
        assertEquals(1.92f, combined, 0.001f)
    }

    @Test
    fun appFontScaleKeepsOriginalCustomizationRange() {
        assertEquals(0.8f, combinedFontScale(systemFontScale = 1f, appFontScale = 0.1f), 0.001f)
        assertEquals(1.4f, combinedFontScale(systemFontScale = 1f, appFontScale = 2f), 0.001f)
    }

    @Test
    fun themeModeDefaultsToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("unknown"))
    }

    @Test
    fun detailLayoutCanBeRestored() {
        assertEquals(DetailLayout.MAGAZINE, DetailLayout.fromStorage("magazine"))
    }

    @Test
    fun coverAspectRatioCanBeRestored() {
        assertEquals(CoverAspectRatio.LANDSCAPE, CoverAspectRatio.fromStorage("landscape"))
    }

    @Test
    fun detailCardStyleCanBeRestored() {
        assertEquals(DetailCardStyle.GLASS, DetailCardStyle.fromStorage("glass"))
    }

    @Test
    fun profileSearchStyleCanBeRestored() {
        assertEquals(ProfileSearchStyle.ON_DEMAND, ProfileSearchStyle.fromStorage("on_demand"))
    }

    @Test
    fun titlesRemainVisibleByDefault() {
        assertEquals(true, AppearanceSettings().showTitle)
    }

    @Test
    fun detailModuleOrderRestoresMissingModulesSafely() {
        assertEquals(
            listOf(
                DetailModule.REVIEW,
                DetailModule.PROGRESS,
                DetailModule.WATCH_DATES,
                DetailModule.HEADER,
                DetailModule.METADATA,
                DetailModule.SYNOPSIS,
                DetailModule.TAGS,
                DetailModule.REMINDER,
                DetailModule.SERIES,
                DetailModule.CHARACTERS,
            ),
            DetailModule.parseOrder("review,progress"),
        )
    }

    @Test
    fun swipeActionsHaveStableFallbacks() {
        assertEquals(
            SwipeAction.INCREMENT,
            SwipeAction.fromStorage("unknown", SwipeAction.INCREMENT),
        )
        assertEquals(SwipeAction.EDIT, SwipeAction.fromStorage("edit", SwipeAction.NONE))
    }

    @Test
    fun cardSwipeActionsAreDisabledByDefault() {
        assertEquals(false, AppearanceSettings().cardSwipeActionsEnabled)
    }

    @Test
    fun navigationOrderRestoresMissingDestinations() {
        assertEquals(
            listOf("profile", "tracker", "discovery", "calendar", "community", "statistics"),
            parseNavigationOrder("profile,tracker,unknown"),
        )
    }

    @Test
    fun navigationVisibilityStaysWithinTwoToFiveDestinations() {
        val minimum = AppearanceSettings(
            showDiscovery = false,
            showCalendar = false,
            showCommunity = false,
            showStatistics = false,
        ).normalizedNavigationVisibility()
        assertEquals(2, minimum.visibleNavigationDestinationCount())
        assertEquals(false, minimum.showDiscovery)

        val maximum = AppearanceSettings(
            showDiscovery = true,
            showCalendar = true,
            showCommunity = true,
            showStatistics = true,
        ).normalizedNavigationVisibility()
        assertEquals(5, maximum.visibleNavigationDestinationCount())
        assertEquals(false, maximum.showStatistics)
    }

    @Test
    fun navigationToggleRefusesToCrossSafetyBoundaries() {
        val minimum = AppearanceSettings(showDiscovery = false, showCalendar = false, showCommunity = false, showStatistics = false)
        assertEquals(minimum, minimum.withNavigationDestinationVisible("discovery", false))

        val maximum = AppearanceSettings(showCommunity = true, showStatistics = false)
        assertEquals(maximum, maximum.withNavigationDestinationVisible("statistics", true))
    }

    @Test
    fun statisticsLayoutCanBeRestored() {
        assertEquals(StatisticsLayoutStyle.DASHBOARD, StatisticsLayoutStyle.fromStorage("dashboard"))
        assertEquals(StatisticsLayoutStyle.CLASSIC, StatisticsLayoutStyle.fromStorage("unknown"))
    }

    @Test
    fun streakMetricIsHiddenByDefaultButExplicitEmptySelectionIsPreserved() {
        assertEquals(setOf(StatisticsMetric.STREAK), AppearanceSettings().hiddenStatisticsMetrics)
        assertEquals(setOf(StatisticsMetric.STREAK), StatisticsMetric.parseSet(null))
        assertTrue(StatisticsMetric.parseSet("").isEmpty())
    }
}
