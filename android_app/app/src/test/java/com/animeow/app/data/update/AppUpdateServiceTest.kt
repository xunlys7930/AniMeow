package com.animeow.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateServiceTest {
    @Test
    fun comparesStableAndPrereleaseVersions() {
        assertTrue(AppUpdateService.isNewerVersion("2.0.0-alpha02", "2.0.0"))
        assertTrue(AppUpdateService.isNewerVersion("2.0.0-alpha02", "2.0.0-alpha03"))
        assertTrue(AppUpdateService.isNewerVersion("2.0.0-beta9", "2.0.0-beta10"))
        assertTrue(AppUpdateService.isNewerVersion("2.0.0-beta10", "2.0.0-rc1"))
        assertTrue(AppUpdateService.isNewerVersion("1.3.9", "2.0.0-alpha02"))
        assertTrue(AppUpdateService.isNewerVersion("2.0.0", "2.0.1"))
        assertFalse(AppUpdateService.isNewerVersion("2.0.0-alpha02", "1.3.9"))
        assertFalse(AppUpdateService.isNewerVersion("2.0.1", "2.0.0"))
        assertFalse(AppUpdateService.isNewerVersion("2.0.0-rc1", "2.0.0-beta10"))
    }

    @Test
    fun versionCodeWinsBeforeNameFallback() {
        assertTrue(AppUpdateService.isUpdateAvailable(2, "2.0.0", 3, "1.0.0"))
        assertFalse(AppUpdateService.isUpdateAvailable(3, "1.0.0", 2, "9.0.0"))
        assertFalse(AppUpdateService.isUpdateAvailable(27, "2.0.0", 26, "1.3.9"))
        assertTrue(AppUpdateService.isUpdateAvailable(2, "2.0.0-alpha02", 2, "2.0.0-alpha03"))
        assertFalse(AppUpdateService.isUpdateAvailable(2, "2.0.0", 2, "2.0.0-alpha03"))
    }
}
