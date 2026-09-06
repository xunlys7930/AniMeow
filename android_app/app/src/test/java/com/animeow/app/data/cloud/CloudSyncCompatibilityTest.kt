package com.animeow.app.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncCompatibilityTest {
    @Test
    fun cachedFingerprintIsOnlyTrustedForNativeTwoPointZeroBackups() {
        val native = backup(id = 7, fileName = "AniMeow_123.animeow.zip")
        val legacy = backup(id = 7, fileName = "AniMeow_backup_123.zip")

        assertTrue(canReuseCachedCloudFingerprint(native, 7, "fingerprint"))
        assertFalse(canReuseCachedCloudFingerprint(legacy, 7, "fingerprint"))
        assertFalse(canReuseCachedCloudFingerprint(native, 8, "fingerprint"))
        assertFalse(canReuseCachedCloudFingerprint(native, 7, null))
    }

    private fun backup(id: Long, fileName: String) = CloudBackupInfo(
        id = id,
        fileName = fileName,
        payloadSize = 1,
        uploadDate = "2026-08-11",
        createdAt = "2026-08-11 10:00:00",
    )
}
