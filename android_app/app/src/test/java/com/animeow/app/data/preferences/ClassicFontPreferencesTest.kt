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
class ClassicFontPreferencesTest {
    @Test fun oldConfigurationsKeepWorkingAndReceiveClassicDefaults() {
        val old = AppearanceSettingsCodec.fromJson(JSONObject("""{"frontendMode":"legacy","navigationBarStyle":"liquid_glass","calendarLayoutPreset":"agenda"}"""))
        assertEquals(AppFontChoice.SYSTEM, old.appFontChoice)
        assertEquals(NavigationBarStyle.DEFAULT, old.activeNavigationBarStyle)
        assertEquals(CalendarLayoutPreset.MONTH, old.activeCalendarLayoutPreset)
        assertEquals(NavigationBarStyle.LIQUID_GLASS, old.copy(frontendMode = FrontendMode.MODERN).activeNavigationBarStyle)
    }

    @Test fun eachModeRetainsItsNavigationAndCalendarWhileSharingFontChoice() {
        val source = AppearanceSettings(frontendMode = FrontendMode.LEGACY,
            navigationBarStyle = NavigationBarStyle.LIQUID_GLASS, legacyNavigationBarStyle = NavigationBarStyle.MINIMAL,
            calendarLayoutPreset = CalendarLayoutPreset.AGENDA, legacyCalendarLayoutPreset = CalendarLayoutPreset.MONTH,
            appFontChoice = AppFontChoice.WENKAI)
        val restored = AppearanceSettingsCodec.fromJson(AppearanceSettingsCodec.toJson(source))
        assertEquals(source, restored)
        assertEquals(NavigationBarStyle.MINIMAL, restored.activeNavigationBarStyle)
        assertEquals(CalendarLayoutPreset.AGENDA, restored.copy(frontendMode = FrontendMode.MODERN).activeCalendarLayoutPreset)
    }

    @Test fun importedFontReferencesRejectPathsAndUnknownChoicesFallBack() {
        val restored = AppearanceSettingsCodec.fromJson(JSONObject("""{"appFontChoice":"unknown","customFontId":"../../font","customFontName":"字体"}"""))
        assertEquals(AppFontChoice.SYSTEM, restored.appFontChoice)
        assertNull(restored.customFontId)
    }
}
