package com.animeow.app.data.preferences

import com.animeow.app.ui.theme.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class ThemeColorCompatibilityTest {
    @Test fun oldConfigurationsKeepLinkedColorsAndTheirRatingChoice() {
        val old = JSONObject().put("schemaVersion", 17).put("accentColor", 0xFF295DABL)
            .put("ratingBadgeColorStyle", "theme_primary")
        val restored = AppearanceSettingsCodec.fromJson(old)
        assertNull(restored.themeColor)
        assertEquals(0xFF295DABL, restored.accentColor)
        assertEquals(RatingBadgeColorStyle.THEME_PRIMARY, restored.ratingBadgeColorStyle)
    }

    @Test fun profilesRoundTripSeparateThemeAndFlowColorsWithoutRgbChanges() {
        val chosen = AppearanceSettings(themeColor = 0xFF09034CL, accentColor = 0xFFFEF7E1L,
            accentGradient = AccentGradientSettings(AccentMode.FLOW, listOf(0xFFFB05ACL, 0xFF0D17ABL)),
            ratingBadgeColorStyle = RatingBadgeColorStyle.ACCENT)
        assertEquals(chosen, AppearanceSettingsCodec.fromJson(AppearanceSettingsCodec.toJson(chosen)))
        val bundle = ConfigurationBundle(chosen)
        assertEquals(bundle, ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(bundle)))
    }

    @Test fun accentPresetsAndReversalPreserveIndependentTheme() {
        val chosen = AppearanceSettings(themeColor = 0xFF246A50L, accentColor = 0xFF152774L,
            accentGradient = AccentGradientSettings(AccentMode.FLOW, listOf(0xFFF37694L)))
        AccentPalettePreset.entries.forEach { assertEquals(chosen.themeColor, it.apply(chosen).themeColor) }
        val reversed = chosen.withAccentPalette(listOf(0xFFF37694L, 0xFF152774L))
        assertEquals(chosen.themeColor, reversed.themeColor)
        assertEquals(listOf(0xFF152774L), reversed.accentGradient.colors)
    }

    @Test fun categoryResetAndFullSuiteHaveExplicitColorScope() {
        val chosen = ConfigurationBundle(AppearanceSettings(themeColor = 0xFFA4345DL, accentColor = 0xFF125EACL,
            detailProgressMode = DetailProgressMode.COMBINED))
        assertEquals(chosen.appearance.themeColor, resetAppearanceCategory(chosen, "layout").appearance.themeColor)
        val reset = resetAppearanceCategory(chosen, "visual")
        assertNull(reset.appearance.themeColor)
        assertEquals(DetailProgressMode.COMBINED, reset.appearance.detailProgressMode)
        assertTrue(appearanceChanges(reset, chosen).any { it.label == "独立主题色" && it.before == "跟随强调色" })
        AppearanceSuite.entries.forEach { assertNull(it.apply(chosen.appearance).themeColor) }
    }
}
