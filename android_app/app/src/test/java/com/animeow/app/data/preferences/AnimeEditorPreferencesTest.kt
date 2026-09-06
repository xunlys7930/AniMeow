package com.animeow.app.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeEditorPreferencesTest {
    @Test
    fun quickPresetKeepsEssentialModulesVisible() {
        val settings = animeEditorPresetSettings(AnimeEditorPreset.QUICK)

        assertFalse(AnimeEditorModule.BASIC in settings.hiddenModules)
        assertFalse(AnimeEditorModule.PROGRESS in settings.hiddenModules)
        assertTrue(AnimeEditorModule.TAGS in settings.hiddenModules)
    }

    @Test
    fun normalizationRepairsOrderAndNeverHidesBasicModule() {
        val settings = AnimeEditorSettings(
            moduleOrder = listOf(AnimeEditorModule.TAGS, AnimeEditorModule.TAGS),
            hiddenModules = setOf(AnimeEditorModule.BASIC, AnimeEditorModule.REMINDER),
        ).normalized()

        assertEquals(AnimeEditorModule.entries.size, settings.moduleOrder.size)
        assertEquals(AnimeEditorModule.TAGS, settings.moduleOrder.first())
        assertFalse(AnimeEditorModule.BASIC in settings.hiddenModules)
        assertTrue(AnimeEditorModule.REMINDER in settings.hiddenModules)
    }

    @Test
    fun editorDensityChangesBothSpacingAndContentSize() {
        assertTrue(AnimeEditorDensity.COMPACT.spacingScale < 0.7f)
        assertTrue(AnimeEditorDensity.COMPACT.itemScale < AnimeEditorDensity.COMFORTABLE.itemScale)
        assertTrue(AnimeEditorDensity.RELAXED.spacingScale > 1.3f)
        assertTrue(AnimeEditorDensity.RELAXED.itemScale > AnimeEditorDensity.COMFORTABLE.itemScale)
    }
}
