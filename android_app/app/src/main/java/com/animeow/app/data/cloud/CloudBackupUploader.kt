package com.animeow.app.data.cloud

import com.animeow.app.util.withDataTransferLock
import java.time.Instant

/** Shared by the account page and appearance configuration; uses the same complete backup API. */
internal suspend fun uploadDeviceBackup(
    session: CloudSession,
    service: CloudAccountService,
    syncPreferences: CloudSyncPreferences,
): CloudBackupInfo = withDataTransferLock {
    check(service.loadSession()?.token == session.token) { "当前账号已变化，请重新确认备份账号" }
    val sync = syncPreferences.snapshot()
    val prepared = service.prepareUpload()
    val fingerprint = prepared.summary.dataFingerprint
    val uploaded = service.uploadPrepared(session, prepared, sync.deviceId)
    if (service.loadSession()?.token == session.token) {
        syncPreferences.markSynced(
            fingerprint = fingerprint,
            remoteBackupId = uploaded.id,
            message = "已手动上传本机完整备份",
            syncedAt = Instant.now().toString(),
        )
    }
    uploaded
}
