package com.animeow.app.data.preferences

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.animeow.app.data.background.BackgroundScaleMode
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.applyCustomBackgroundColor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class BackgroundColorCompatibilityTest {

    @Test
    fun legacyConfigsWithoutBackgroundRestoreSafeDefaults() {
        val legacy = JSONObject()
            .put("schemaVersion", 18)
            .put("themeMode", "dark")
            .put("appStyle", "miuix")
        val restored = AppearanceSettingsCodec.fromJson(legacy)
        assertNull(restored.backgroundColor)
        assertNull(restored.backgroundImagePath)
        assertEquals(BackgroundScaleMode.COVER, restored.backgroundScaleMode)
        assertEquals(0.45f, restored.backgroundImageOpacity, 0.001f)
        assertEquals(0, restored.backgroundImageBlur)
        assertEquals(0.65f, restored.backgroundDimOverlay, 0.001f)
    }

    @Test
    fun backgroundPropertiesRoundTripAccurately() {
        val custom = AppearanceSettings(
            backgroundColor = 0xFF000000L,
            backgroundImagePath = "wallpapers/wallpaper_test.jpg",
            backgroundScaleMode = BackgroundScaleMode.CONTAIN,
            backgroundImageOpacity = 0.85f,
            backgroundImageBlur = 12,
            backgroundDimOverlay = 0.50f,
        )
        val json = AppearanceSettingsCodec.toJson(custom)
        val decoded = AppearanceSettingsCodec.fromJson(json)
        assertEquals(custom.backgroundColor, decoded.backgroundColor)
        assertEquals(custom.backgroundImagePath, decoded.backgroundImagePath)
        assertEquals(custom.backgroundScaleMode, decoded.backgroundScaleMode)
        assertEquals(custom.backgroundImageOpacity, decoded.backgroundImageOpacity, 0.001f)
        assertEquals(custom.backgroundImageBlur, decoded.backgroundImageBlur)
        assertEquals(custom.backgroundDimOverlay, decoded.backgroundDimOverlay, 0.001f)
    }

    @Test
    fun visualCategoryResetResetsBackground() {
        val bundle = ConfigurationBundle(
            AppearanceSettings(
                backgroundColor = 0xFF121216L,
                backgroundImagePath = "wallpapers/custom.jpg",
            ),
        )
        val reset = resetAppearanceCategory(bundle, "visual")
        assertNull(reset.appearance.backgroundColor)
        assertNull(reset.appearance.backgroundImagePath)
    }

    @Test
    fun applyCustomBackgroundColorOledPureBlackGeneratesReadableSurfaces() {
        val base = darkColorScheme()
        val oled = applyCustomBackgroundColor(base, Color.Black)
        assertEquals(Color.Black, oled.background)
        assertEquals(Color.White, oled.onBackground)
        assertTrue("Surface must be darker than 0.2 luminance for dark background", oled.surface.luminance() < 0.2f)
        assertEquals(Color.White, oled.onSurface)
    }

    @Test
    fun applyCustomBackgroundColorPureWhiteGeneratesReadableSurfaces() {
        val base = lightColorScheme()
        val white = applyCustomBackgroundColor(base, Color.White)
        assertEquals(Color.White, white.background)
        assertEquals(Color.Black, white.onBackground)
        assertTrue("Surface must be brighter than 0.8 luminance for white background", white.surface.luminance() > 0.8f)
        assertEquals(Color.Black, white.onSurface)
    }
}
