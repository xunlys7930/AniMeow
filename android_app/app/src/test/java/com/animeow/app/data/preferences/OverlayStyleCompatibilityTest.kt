package com.animeow.app.data.preferences

import androidx.test.core.app.ApplicationProvider
import androidx.core.graphics.ColorUtils
import com.animeow.app.data.overlay.palette
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.OverlayStyle
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class OverlayStyleCompatibilityTest {
    @Test fun allPanelThemesKeepSmallTextReadable() {
        for (style in OverlayStyle.entries) for (dark in listOf(false, true)) {
            val palette = style.palette(dark)
            assertTrue("$style primary text contrast", ColorUtils.calculateContrast(palette.onPrimary, palette.primary) >= 4.5)
            assertTrue("$style panel text contrast", ColorUtils.calculateContrast(palette.text, palette.surface) >= 4.5)
            assertTrue("$style progress text contrast", ColorUtils.calculateContrast(palette.secondaryText, palette.surface) >= 4.5)
        }
    }

    @Test fun oldAndUnknownStylesDefaultToTheThirdConcept() {
        assertEquals(OverlayStyle.QUIET_PAW, AppearanceSettings().overlayStyle)
        listOf(JSONObject(), JSONObject().put("schemaVersion", 20), JSONObject().put("overlayStyle", "future-style"))
            .forEach { assertEquals(OverlayStyle.QUIET_PAW, AppearanceSettingsCodec.fromJson(it).overlayStyle) }
    }

    @Test fun choicesSurvivePersistenceBackupAndCategoryReset() = runBlocking {
        val preferences = AppearancePreferences(ApplicationProvider.getApplicationContext())
        for (style in OverlayStyle.entries) {
            preferences.setOverlayStyle(style)
            assertEquals(style, AppearancePreferences(ApplicationProvider.getApplicationContext()).snapshot().overlayStyle)
            val original = preferences.snapshot()
            val restored = AppearanceSettingsCodec.fromJson(AppearanceSettingsCodec.toJson(original))
            assertEquals(original, restored)
            preferences.restore(restored)
            assertEquals(style, preferences.snapshot().overlayStyle)
            val bundle = ConfigurationBundle(original)
            assertEquals(bundle, ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(bundle)))
            assertEquals(style, resetAppearanceCategory(bundle, "layout").appearance.overlayStyle)
            assertEquals(OverlayStyle.QUIET_PAW, resetAppearanceCategory(bundle, "visual").appearance.overlayStyle)
        }
    }
}
