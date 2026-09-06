package com.animeow.app.data.cloud

import com.animeow.app.data.backup.NativeBackupSummary
import com.animeow.app.data.backup.NativeBackupFormatException
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.diagnostics.OperationLogService
import com.animeow.app.util.withDataTransferLock
import java.time.Instant
import kotlinx.coroutines.CancellationException

enum class CloudSyncResultType {
    NO_CHANGE,
    UPLOADED,
    RESTORED,
    MERGED,
    CONFLICT,
}

data class CloudSyncResult(
    val type: CloudSyncResultType,
    val message: String,
    val localSummary: NativeBackupSummary,
    val remoteBackup: CloudBackupInfo? = null,
)

internal class CloudSyncDeferredException(
    message: String = "应用正在使用，已延后可能覆盖本机资料的后台同步",
) : IllegalStateException(message)

internal class CloudSyncCoordinator(
    private val service: CloudAccountService,
    private val preferences: CloudSyncPreferences,
    private val operationLog: OperationLogService? = null,
    private val canApplyBackgroundRestore: () -> Boolean = { true },
) {
    suspend fun synchronize(
        session: CloudSession,
        policyOverride: CloudConflictPolicy? = null,
        background: Boolean = false,
    ): CloudSyncResult = withDataTransferLock {
        synchronizeUnlocked(session, policyOverride, background)
    }

    private suspend fun synchronizeUnlocked(
        session: CloudSession,
        policyOverride: CloudConflictPolicy?,
        background: Boolean,
    ): CloudSyncResult {
        val settings = preferences.snapshot()
        val policy = policyOverride ?: settings.conflictPolicy
        val local = service.prepareUpload()
        var preparedRemote: PreparedCloudRestore? = null
        try {
            val backups = service.listBackups(session)
            val newest = backups.firstOrNull()
            if (newest == null) {
                val uploaded = service.uploadPrepared(session, local, settings.deviceId)
                val message = "本机资料已作为第一份云端备份上传"
                preferences.markSynced(local.summary.dataFingerprint, uploaded.id, message, Instant.now().toString())
                record("首次云同步上传", uploaded.id)
                return CloudSyncResult(CloudSyncResultType.UPLOADED, message, local.summary, uploaded)
            }

            var remoteFingerprint = newest.contentHash
            if (
                remoteFingerprint.isNullOrBlank() &&
                canReuseCachedCloudFingerprint(newest, settings.lastRemoteBackupId, settings.lastSyncedFingerprint)
            ) {
                remoteFingerprint = settings.lastSyncedFingerprint
            }
            if (remoteFingerprint.isNullOrBlank() || !newest.isNativeCloudBackupName()) {
                try {
                    preparedRemote = service.prepareRestore(session, newest)
                    if (preparedRemote.format == CloudBackupFormat.LEGACY) {
                        service.discard(preparedRemote)
                        preparedRemote = null
                        val uploaded = service.uploadPrepared(session, local, settings.deviceId)
                        val message = "检测到旧版云备份，已保留旧文件并上传 2.0 格式的新快照"
                        preferences.markSynced(local.summary.dataFingerprint, uploaded.id, message, Instant.now().toString())
                        record("旧版云备份迁移到 2.0", uploaded.id)
                        return CloudSyncResult(CloudSyncResultType.UPLOADED, message, local.summary, uploaded)
                    }
                    remoteFingerprint = requireNotNull(preparedRemote.nativeSummary).dataFingerprint
                } catch (_: NativeBackupFormatException) {
                    val uploaded = service.uploadPrepared(session, local, settings.deviceId)
                    val message = "检测到旧版云备份，已保留旧文件并上传 2.0 格式的新快照"
                    preferences.markSynced(local.summary.dataFingerprint, uploaded.id, message, Instant.now().toString())
                    record("旧版云备份迁移到 2.0", uploaded.id)
                    return CloudSyncResult(CloudSyncResultType.UPLOADED, message, local.summary, uploaded)
                }
            }
            val remoteHash = remoteFingerprint?.takeIf(String::isNotBlank)
                ?: error("云端备份缺少可比较的数据指纹")

            if (remoteHash == local.summary.dataFingerprint) {
                preparedRemote?.let(service::discard)
                preparedRemote = null
                service.discard(local)
                val message = "本机与云端内容一致"
                preferences.markSynced(remoteHash, newest.id, message, Instant.now().toString())
                return CloudSyncResult(CloudSyncResultType.NO_CHANGE, message, local.summary, newest)
            }

            val baseline = settings.lastSyncedFingerprint
            val localChanged = baseline == null || local.summary.dataFingerprint != baseline
            val remoteChanged = baseline == null || remoteHash != baseline

            if (baseline != null && localChanged && !remoteChanged) {
                preparedRemote?.let(service::discard)
                preparedRemote = null
                val uploaded = service.uploadPrepared(session, local, settings.deviceId)
                val message = "本机变更已同步到云端"
                preferences.markSynced(local.summary.dataFingerprint, uploaded.id, message, Instant.now().toString())
                record("云同步上传本机变更", uploaded.id)
                return CloudSyncResult(CloudSyncResultType.UPLOADED, message, local.summary, uploaded)
            }

            if (baseline != null && !localChanged && remoteChanged) {
                service.discard(local)
                val remote = preparedRemote ?: service.prepareRestore(session, newest)
                preparedRemote = remote
                ensureBackgroundRestoreAllowed(background)
                val restored = service.restore(remote)
                preparedRemote = null
                val message = "已同步云端变更，并保留恢复点"
                preferences.markSynced(remoteHash, newest.id, message, Instant.now().toString())
                record("云同步恢复云端变更", newest.id)
                return CloudSyncResult(CloudSyncResultType.RESTORED, message, restored.summary, newest)
            }

            if (baseline != null && !localChanged && !remoteChanged) {
                preparedRemote?.let(service::discard)
                preparedRemote = null
                service.discard(local)
                val message = "没有需要同步的变更"
                preferences.markSynced(baseline, newest.id, message, Instant.now().toString())
                return CloudSyncResult(CloudSyncResultType.NO_CHANGE, message, local.summary, newest)
            }

            return when (policy) {
                CloudConflictPolicy.PREFER_LOCAL -> {
                    preparedRemote?.let(service::discard)
                    preparedRemote = null
                    val uploaded = service.uploadPrepared(session, local, settings.deviceId)
                    val message = "检测到冲突，已按设置保留本机版本"
                    preferences.markSynced(local.summary.dataFingerprint, uploaded.id, message, Instant.now().toString())
                    record("云同步冲突采用本机", uploaded.id)
                    CloudSyncResult(CloudSyncResultType.UPLOADED, message, local.summary, uploaded)
                }
                CloudConflictPolicy.PREFER_CLOUD -> {
                    service.discard(local)
                    val remote = preparedRemote ?: service.prepareRestore(session, newest)
                    preparedRemote = remote
                    ensureBackgroundRestoreAllowed(background)
                    val restored = service.restore(remote)
                    preparedRemote = null
                    val message = "检测到冲突，已按设置采用云端版本并保留恢复点"
                    preferences.markSynced(remoteHash, newest.id, message, Instant.now().toString())
                    record("云同步冲突采用云端", newest.id)
                    CloudSyncResult(CloudSyncResultType.RESTORED, message, restored.summary, newest)
                }
                CloudConflictPolicy.ASK -> {
                    preparedRemote?.let(service::discard)
                    preparedRemote = null
                    service.discard(local)
                    val message = if (background) {
                        "发现设备间数据冲突，请打开云账号手动处理"
                    } else {
                        "本机和云端都有新变更，请选择保留方式"
                    }
                    preferences.markConflict(newest.id, message, Instant.now().toString())
                    record("云同步检测到冲突", newest.id)
                    CloudSyncResult(CloudSyncResultType.CONFLICT, message, local.summary, newest)
                }
            }
        } catch (error: CancellationException) {
            preparedRemote?.let(service::discard)
            service.discard(local)
            throw error
        } catch (error: CloudSyncDeferredException) {
            preparedRemote?.let(service::discard)
            service.discard(local)
            preferences.markResult(error.message.orEmpty(), Instant.now().toString())
            throw error
        } catch (error: Throwable) {
            preparedRemote?.let(service::discard)
            service.discard(local)
            preferences.markResult(error.message ?: "云同步失败", Instant.now().toString())
            operationLog?.recordError(error, "云同步")
            throw error
        }
    }

    suspend fun resolveWithLocal(session: CloudSession): CloudSyncResult =
        synchronize(session, CloudConflictPolicy.PREFER_LOCAL)

    suspend fun resolveWithCloud(
        session: CloudSession,
        backup: CloudBackupInfo,
    ): CloudSyncResult = withDataTransferLock {
        val local = service.prepareUpload()
        var remote: PreparedCloudRestore? = null
        try {
            val preparedRemote = service.prepareRestore(session, backup)
            remote = preparedRemote
            val legacy = preparedRemote.format == CloudBackupFormat.LEGACY
            service.discard(local)
            val restored = service.restore(preparedRemote)
            remote = null
            val message = if (legacy) {
                "已迁移旧版云备份并创建本机恢复点；下一次同步将上传 2.0 格式快照"
            } else {
                "已采用云端版本，并创建可回退的本机恢复点"
            }
            if (legacy) {
                preferences.resetBaseline(message, Instant.now().toString())
            } else {
                preferences.markSynced(restored.summary.dataFingerprint, backup.id, message, Instant.now().toString())
            }
            record("手动解决冲突采用云端", backup.id)
            CloudSyncResult(CloudSyncResultType.RESTORED, message, restored.summary, backup)
        } catch (error: CancellationException) {
            remote?.let(service::discard)
            service.discard(local)
            throw error
        } catch (error: Throwable) {
            remote?.let(service::discard)
            service.discard(local)
            preferences.markResult(error.message ?: "云端冲突处理失败", Instant.now().toString())
            operationLog?.recordError(error, "云同步")
            throw error
        }
    }

    suspend fun resolveSelectively(
        session: CloudSession,
        backup: CloudBackupInfo,
        selection: NativeRestoreSelection,
    ): CloudSyncResult = withDataTransferLock {
        require(!selection.isEmpty) { "至少选择一类云端数据" }
        var remote: PreparedCloudRestore? = null
        try {
            val preparedRemote = service.prepareRestore(session, backup)
            remote = preparedRemote
            require(preparedRemote.format == CloudBackupFormat.NATIVE) {
                "旧版云备份只能完整迁移，不能选择部分内容合并"
            }
            val remoteFingerprint = requireNotNull(preparedRemote.nativeSummary).dataFingerprint
            val restored = service.merge(preparedRemote, selection)
            remote = null
            val message = "已非破坏式合并到本机并保留恢复点；冲突字段保留本机值，远端独有记录已加入"
            // The remote snapshot is now the common ancestor. A later sync sees the merged
            // local fingerprint as a local-only change and uploads it without another conflict.
            preferences.markSynced(remoteFingerprint, backup.id, message, Instant.now().toString())
            record("手动逐项合并云端数据", backup.id)
            CloudSyncResult(CloudSyncResultType.MERGED, message, restored.summary, backup)
        } catch (error: CancellationException) {
            remote?.let(service::discard)
            throw error
        } catch (error: Throwable) {
            remote?.let(service::discard)
            preferences.markResult(error.message ?: "逐项合并失败", Instant.now().toString())
            operationLog?.recordError(error, "云同步")
            throw error
        }
    }

    private fun record(action: String, backupId: Long) {
        operationLog?.record(action, "云同步", mapOf("backupId" to backupId))
    }

    private fun ensureBackgroundRestoreAllowed(background: Boolean) {
        if (background && !canApplyBackgroundRestore()) throw CloudSyncDeferredException()
    }
}

internal fun canReuseCachedCloudFingerprint(
    backup: CloudBackupInfo,
    lastRemoteBackupId: Long?,
    lastSyncedFingerprint: String?,
): Boolean = backup.id == lastRemoteBackupId &&
    !lastSyncedFingerprint.isNullOrBlank() &&
    backup.isNativeCloudBackupName()

private fun CloudBackupInfo.isNativeCloudBackupName(): Boolean =
    fileName.endsWith(".animeow.zip", ignoreCase = true)
