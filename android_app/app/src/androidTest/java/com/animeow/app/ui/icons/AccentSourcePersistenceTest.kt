package com.animeow.app.ui.icons

import androidx.test.core.app.ApplicationProvider
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.ui.theme.AppearanceSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccentSourcePersistenceTest {
    @Test fun manualSelectionStopsAutomaticSourcesButLaunchRandomizationRemainsEnabled() = runBlocking {
        val preferences = AppearancePreferences(ApplicationProvider.getApplicationContext())
        val original = preferences.snapshot()
        try {
            val automatic = AppearanceSettings(useDynamicColor = true, randomAccentOnLaunch = true)
            preferences.restore(automatic)
            preferences.setAccentColor(0x00123456L)
            val manual = preferences.snapshot()
            assertEquals(0xFF123456L, manual.accentColor)
            assertFalse(manual.useDynamicColor)
            assertFalse(manual.randomAccentOnLaunch)
            preferences.restore(automatic)
            preferences.setAccentColor(0xFF8B37DAL, manual = false)
            val random = preferences.snapshot()
            assertEquals(0xFF8B37DAL, random.accentColor)
            assertFalse(random.useDynamicColor)
            assertTrue(random.randomAccentOnLaunch)
        } finally { preferences.restore(original) }
    }
}
