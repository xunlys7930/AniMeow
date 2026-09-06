package com.animeow.app.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPreferencesTest {
    @Test
    fun monthFocusPresetKeepsCalendarCompact() {
        val settings = calendarPresetSettings(CalendarPagePreset.MONTH_FOCUS)

        assertEquals(CalendarMarkerStyle.DOTS, settings.markerStyle)
        assertEquals(CalendarContentDensity.COMPACT, settings.density)
        assertFalse(settings.showCovers)
        assertTrue(CalendarModule.HISTORY in settings.hiddenModules)
    }

    @Test
    fun normalizationRepairsInvalidModuleAndFilterState() {
        val settings = CalendarDisplaySettings(
            moduleOrder = listOf(CalendarModule.SCHEDULE, CalendarModule.SCHEDULE),
            hiddenModules = CalendarModule.entries.toSet(),
            enabledEventTypes = emptySet(),
        ).normalized()

        assertEquals(CalendarModule.entries.size, settings.moduleOrder.size)
        assertTrue(settings.hiddenModules.size < CalendarModule.entries.size)
        assertEquals(DEFAULT_CALENDAR_EVENT_TYPES, settings.enabledEventTypes)
    }

    @Test
    fun calendarDensityPresetsHaveVisibleSizeDifferences() {
        assertTrue(CalendarContentDensity.COMPACT.spacingScale < 0.7f)
        assertTrue(CalendarContentDensity.COMPACT.itemScale < CalendarContentDensity.COMFORTABLE.itemScale)
        assertTrue(CalendarContentDensity.RELAXED.spacingScale > 1.3f)
        assertTrue(CalendarContentDensity.RELAXED.itemScale > CalendarContentDensity.COMFORTABLE.itemScale)
    }
}
