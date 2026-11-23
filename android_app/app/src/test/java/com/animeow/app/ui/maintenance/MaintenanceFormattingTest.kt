package com.animeow.app.ui.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenanceFormattingTest {
    @Test
    fun formatsStorageSizesForPeople() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1572864))
    }
}
