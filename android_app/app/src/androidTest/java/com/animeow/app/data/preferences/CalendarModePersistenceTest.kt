package com.animeow.app.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animeow.app.ui.theme.CalendarLayoutPreset
import com.animeow.app.ui.theme.FrontendMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarModePersistenceTest {
    @Test fun selectedViewSurvivesRecreationAndAppearanceChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppearancePreferences(context)
        val original = prefs.snapshot()
        try {
            prefs.setFrontendMode(FrontendMode.MODERN)
            prefs.setCalendarLayoutPreset(CalendarLayoutPreset.WEEK)
            assertEquals(CalendarLayoutPreset.WEEK, AppearancePreferences(context).snapshot().activeCalendarLayoutPreset)
            CalendarPreferences(context).save(CalendarPreferences(context).snapshot())
            assertEquals(CalendarLayoutPreset.WEEK, prefs.snapshot().activeCalendarLayoutPreset)
            prefs.setFrontendMode(FrontendMode.LEGACY)
            prefs.setCalendarLayoutPreset(CalendarLayoutPreset.MONTH)
            prefs.setFrontendMode(FrontendMode.MODERN)
            assertEquals(CalendarLayoutPreset.WEEK, prefs.snapshot().activeCalendarLayoutPreset)
            prefs.setFrontendMode(FrontendMode.LEGACY)
            assertEquals(CalendarLayoutPreset.MONTH, AppearancePreferences(context).snapshot().activeCalendarLayoutPreset)
        } finally {
            prefs.restore(original)
        }
    }
}
