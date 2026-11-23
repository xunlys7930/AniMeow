package com.animeow.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateServiceTest {
    @Test
    fun legacyDefaultAliasDoesNotCreateASecondServerButton() {
        val options = normalizeUpdateDownloads(
            listOf(AppUpdateDownloadOption("默认", "https://example.com/api/download/latest/0")),
            "https://example.com/api/download/latest",
        )
        assertEquals(listOf(AppUpdateDownloadOption("服务器线路", "https://example.com/api/download/latest/0")), options)
    }

    @Test
    fun browserChannelsAndInvalidAddressesAreNotTreatedAsApks() {
        val options = normalizeUpdateDownloads(listOf(
            AppUpdateDownloadOption("默认", "https://example.com/package.apk"),
            AppUpdateDownloadOption("重复", "https://example.com/package.apk"),
            AppUpdateDownloadOption("GitHub", "https://example.com/api/download/latest/1"),
            AppUpdateDownloadOption("Gitee", "https://example.com/api/download/latest/2"),
            AppUpdateDownloadOption("蓝奏云", "https://wwbik.lanzoup.com/b018806eod"),
            AppUpdateDownloadOption("失效", "file:///tmp/app.apk"),
            AppUpdateDownloadOption("失效", "https://user:password@example.com/app.apk"),
        ), null)
        assertEquals(listOf(AppUpdateDownloadOption("服务器线路", "https://example.com/package.apk")), options)
    }

    @Test
    fun oldServerWithOnlyDownloadUrlStillWorks() {
        assertEquals("https://example.com/app.apk", normalizeUpdateDownloads(emptyList(), "https://example.com/app.apk").single().url)
        assertTrue(normalizeUpdateDownloads(emptyList(), "").isEmpty())
        assertTrue(normalizeUpdateDownloads(listOf(AppUpdateDownloadOption("Gitee", "https://example.com/old")), "https://example.com/old").isEmpty())
    }

    @Test
    fun legacyHttpAndSecureDownloadsAndRelativeAddressesRemainSupported() {
        val options = normalizeUpdateDownloads(listOf(
            AppUpdateDownloadOption("旧线路", "http://legacy.example:3000/api/download/latest"),
            AppUpdateDownloadOption("安全线路", "https://example.com/api/download/latest/0"),
            AppUpdateDownloadOption("相对地址", "/packages/AniMeow.apk"),
        ), null, "https://example.com/api/check-update")
        assertEquals(3, options.size)
        assertEquals("http://legacy.example:3000/api/download/latest", options[0].url)
        assertEquals("https://example.com/packages/AniMeow.apk", options[2].url)
        assertEquals("https://example.com/package.apk", updateDownloadUri("../package.apk", "https://example.com/files/start")?.toString())
    }

    @Test
    fun redirectsCanUpgradeToHttpsButCannotPointToLocalFilesOrCredentials() {
        assertEquals("https://example.com/package.apk", updateDownloadUri("https://example.com/package.apk", "http://example.com/start")?.toString())
        assertEquals(null, updateDownloadUri("file:///etc/test.apk", "https://example.com/start"))
        assertEquals(null, updateDownloadUri("https://fixture:secret@example.com/package.apk"))
    }

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
