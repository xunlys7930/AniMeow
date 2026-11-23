package com.animeow.app.data.preferences

import com.animeow.app.ui.icons.*
import com.animeow.app.ui.theme.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class AccentGradientCodecTest {
    @Test fun portableProfileRetainsAdvancedGradientAndIndependentIconColor() {
        val gradient = AccentGradientSettings(AccentMode.FLOW, listOf(0xFF9DBAB5L, 0xFFDA9FBBL), 142f, 16_000)
        val source = ConfigurationBundle(AppearanceSettings(accentColor = 0xFF596FAAL, accentGradient = gradient,
            icons = IconSettings(color = IconColorSettings(IconColorMode.ACCENT), overrides = mapOf(
                AppIconId.HEART to IconOverride(color = IconColorSettings(IconColorMode.CUSTOM, 0xFFDA7991L))))))
        assertEquals(source, ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(source)))
        val reset = resetAppearanceCategory(source, "visual")
        assertEquals(AccentGradientSettings(), reset.appearance.accentGradient)
        assertEquals(source.appearance.icons, reset.appearance.icons)
    }

    @Test fun malformedGradientIsBoundedAndOlderSettingsRemainSolid() {
        val value = AccentGradientCodec.fromJson(JSONObject("""{"mode":"future","colors":[1,2,3,4,5],"angle":"NaN","durationMillis":-10}"""))
        assertEquals(AccentMode.SOLID, value.mode)
        assertEquals(4, value.colors.size)
        assertEquals(35f, value.angleDegrees, 0f)
        assertEquals(4_000, value.durationMillis)
        assertEquals(AccentGradientSettings(), AppearanceSettingsCodec.fromJson(JSONObject()).accentGradient)
    }

    @Test fun individualColorFollowsItsOwnRuleAcrossSuiteAndAccentChanges() {
        val fixed = IconColorSettings(IconColorMode.CUSTOM, 0xFFDA7991L)
        val settings = IconSettings(color = IconColorSettings(IconColorMode.ACCENT))
            .withOverride(AppIconId.SEARCH, IconOverride(IconStyle.DUO, IconVariant.B, fixed))
            .copy(style = IconStyle.SOFT, color = IconColorSettings(IconColorMode.CUSTOM, 0xFF336655L))
        assertEquals(fixed, settings.resolve(AppIconId.SEARCH).color)
        assertEquals(settings.color, settings.resolve(AppIconId.CALENDAR).color)
        assertEquals(settings, IconSettingsCodec.fromStorage(IconSettingsCodec.toJson(settings).toString()))
    }
}
