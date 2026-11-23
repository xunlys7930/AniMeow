package com.animeow.app.data.preferences

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.animeow.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class AccentPaletteIntegrityTest {
    @Test fun customStopsSurviveThemeAdaptationReversalAndPortableRoundTripExactly() {
        val palette = listOf(0xFF010203L, 0xFFFFFDFAL, 0xFFF6FF00L, 0xFF210DFEL, 0xFFFA006CL)
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            var value = AppearanceSettings(useDynamicColor = false, themeMode = theme, accentColor = palette.first(),
                accentGradient = AccentGradientSettings(AccentMode.FLOW, palette.drop(1), 127f, 17_000))
            val background = if (theme == ThemeMode.LIGHT) Color.White else Color.Black
            val adjusted = readableAccent(Color(palette.first()), background)
            repeat(2) {
                val actual = accentGradientColors(value.accentSourceColor(adjusted), Color.Magenta, value.accentGradient)
                    .map { it.toArgb().toLong() and 0xFFFFFFFFL }
                assertEquals(if (it == 0) palette else palette.reversed(), actual)
                value = AppearanceSettingsCodec.fromJson(AppearanceSettingsCodec.toJson(value.withAccentPalette(actual.reversed())))
            }
            assertEquals(palette.first(), value.accentColor)
            assertEquals(palette.drop(1), value.accentGradient.colors)
            assertEquals(127f, value.accentGradient.angleDegrees, 0f)
            assertEquals(17_000, value.accentGradient.durationMillis)
        }
    }

    @Test fun choosingAnExplicitPaletteLeavesAutomaticSourcesAndPreservesAllRgbBits() {
        val previous = AppearanceSettings(useDynamicColor = true, randomAccentOnLaunch = true, accentColor = 0xFF665533L)
        assertEquals(Color.Cyan, previous.accentSourceColor(Color.Cyan))
        val chosen = previous.withAccentPalette(listOf(0x00000000L, 0x00123456L, 0x00FFFFFFL))
        assertFalse(chosen.useDynamicColor)
        assertFalse(chosen.randomAccentOnLaunch)
        assertEquals(0xFF000000L, chosen.accentColor)
        assertEquals(listOf(0xFF123456L, 0xFFFFFFFFL), chosen.accentGradient.colors)
    }
}
