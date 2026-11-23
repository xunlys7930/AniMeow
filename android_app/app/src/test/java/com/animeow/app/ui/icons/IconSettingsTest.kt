package com.animeow.app.ui.icons

import com.animeow.app.ui.theme.MotionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconSettingsTest {
    @Test
    fun globalSwitchPreservesAnIndependentStyleAndVariant() {
        val settings = IconSettings()
            .withOverride(AppIconId.SEARCH, IconOverride(IconStyle.DUO, IconVariant.B))
            .copy(style = IconStyle.SOFT, variant = IconVariant.B)
        assertEquals(ResolvedIcon(IconStyle.DUO, IconVariant.B), settings.resolve(AppIconId.SEARCH))
        assertEquals(ResolvedIcon(IconStyle.SOFT, IconVariant.B), settings.resolve(AppIconId.CALENDAR))
    }

    @Test
    fun variantOnlyChoiceFollowsLaterStyleChanges() {
        val settings = IconSettings()
            .withOverride(AppIconId.SEARCH, IconOverride(variant = IconVariant.B))
            .copy(style = IconStyle.DUO)
        assertEquals(ResolvedIcon(IconStyle.DUO, IconVariant.B), settings.resolve(AppIconId.SEARCH))
        assertEquals(ResolvedIcon(IconStyle.DUO, IconVariant.A), settings.resolve(AppIconId.CALENDAR))
    }

    @Test
    fun followingGlobalRemovesTheOverrideAndUnpinsFutureChanges() {
        val settings = IconSettings(overrides = mapOf(AppIconId.SEARCH to IconOverride(IconStyle.SOFT, IconVariant.B)))
            .withOverride(AppIconId.SEARCH, IconOverride())
            .copy(style = IconStyle.DUO, variant = IconVariant.A)
        assertTrue(settings.overrides.isEmpty())
        assertEquals(ResolvedIcon(IconStyle.DUO, IconVariant.A), settings.resolve(AppIconId.SEARCH))
    }

    @Test
    fun selectedStateNeverChangesTheUsersDesignChoice() {
        val settings = IconSettings(variant = IconVariant.B)
        assertEquals(AppIcons.Heart.id, AppIcons.HeartFilled.id)
        assertFalse(AppIcons.Heart.active)
        assertTrue(AppIcons.HeartFilled.active)
        assertEquals(settings.resolve(AppIcons.Heart.id), settings.resolve(AppIcons.HeartFilled.id))
        assertTrue(IconDrawables.resource(AppIconId.HEART, IconStyle.DUO, IconVariant.B, true) !=
            IconDrawables.resource(AppIconId.HEART, IconStyle.DUO, IconVariant.B, false))
    }

    @Test
    fun everyMotionOptOutIsRespected() {
        val settings = IconSettings()
        assertTrue(iconAnimationsEnabled(settings, MotionLevel.FULL, true))
        assertFalse(iconAnimationsEnabled(settings.copy(animated = false), MotionLevel.FULL, true))
        assertFalse(iconAnimationsEnabled(settings, MotionLevel.REDUCED, true))
        assertFalse(iconAnimationsEnabled(settings, MotionLevel.NONE, true))
        assertFalse(iconAnimationsEnabled(settings, MotionLevel.FULL, false))
    }
}
