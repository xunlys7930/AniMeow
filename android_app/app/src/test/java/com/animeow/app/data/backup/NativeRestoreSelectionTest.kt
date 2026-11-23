package com.animeow.app.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRestoreSelectionTest {
    @Test
    fun requiresAtLeastOneSelectedScope() {
        assertFalse(NativeRestoreSelection().isEmpty)
        assertFalse(NativeRestoreSelection(libraryAndCharacters = false).isEmpty)
        assertTrue(
            NativeRestoreSelection(
                libraryAndCharacters = false,
                analysisHistory = false,
                appearanceSettings = false,
            ).isEmpty,
        )
    }
}
