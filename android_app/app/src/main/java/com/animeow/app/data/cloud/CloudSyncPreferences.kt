package com.animeow.app.data.cloud

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cloudSyncDataStore by preferencesDataStore(name = "cloud_sync_preferences")

enum class CloudConflictPolicy(val storageKey: String, val label: String) {
    ASK("ask", "每次询问"),
    PREFER_LOCAL("prefer_local", "优先本机"),
    PREFER_CLOUD("prefer_cloud", "优先云端");

    companion object {
        fun fromStorage(value: String?): CloudConflictPolicy =
            entries.firstOrNull { it.storageKey == value } ?: ASK
    }
}

data class CloudSyncSettings(
    val autoSyncEnabled: Boolean = false,
    val intervalHours: Int = 24,
    val unmeteredOnly: Boolean = false,
    val conflictPolicy: CloudConflictPolicy = CloudConflictPolicy.ASK,
    val deviceId: String = "",
    val lastSyncedFingerprint: String? = null,
    val lastRemoteBackupId: Long? = null,
    val lastSyncAt: String? = null,
    val lastSyncMessage: String? = null,
    val pendingConflictBackupId: Long? = null,
    val bangumiProgressSync: Boolean = false,
    val bangumiRatingSync: Boolean = false,
    /**
     * 默认 false（2026-09-25 起）：旧默认 true 让移动数据用户的改动一直排队不上报。
     * 用户显式保存过的值（含旧版本随开关一起写入的 true）保持不变。
     */
    val bangumiSyncWifiOnly: Boolean = false,
    /** 最近一次处理了待上报改动的 Bangumi 同步结果；从未同步过为 null。 */
    val bangumiLastReport: com.animeow.app.data.bangumi.BangumiSyncReport? = null,
    /** 前往 Bangumi 官网授权前的网络提示是否已选择「不再提示」。 */
    val bangumiLoginTipHidden: Boolean = false,
    /** 是否在收藏首页顶部显示 Bangumi 同步状态提示（关闭后不影响后台自动同步）。 */
    val bangumiShowHomeStatus: Boolean = true,
    /** Bangumi 直连最近一次失败的时间戳（毫秒），用于冷启动时优先走中转避免反复超时。 */
    val bangumiDirectFailedAtMs: Long? = null,
)

/**
 * Portable cloud-sync preferences that are safe to move between devices.
 *
 * Account credentials, device identity and synchronization baselines deliberately stay out of
 * native backups. Restoring those values on another phone could impersonate the source device or
 * incorrectly hide a real synchronization conflict.
 */
data class CloudSyncConfiguration(
    val autoSyncEnabled: Boolean = false,
    val intervalHours: Int = 24,
    val unmeteredOnly: Boolean = false,
    val conflictPolicy: CloudConflictPolicy = CloudConflictPolicy.ASK,
)

class CloudSyncPreferences(context: Context) {
    private val dataStore = context.applicationContext.cloudSyncDataStore

    val settings: Flow<CloudSyncSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values ->
            CloudSyncSettings(
                autoSyncEnabled = values[Keys.AUTO_SYNC_ENABLED] ?: false,
                intervalHours = normalizeInterval(values[Keys.INTERVAL_HOURS] ?: 24),
                unmeteredOnly = values[Keys.UNMETERED_ONLY] ?: false,
                conflictPolicy = CloudConflictPolicy.fromStorage(values[Keys.CONFLICT_POLICY]),
                deviceId = values[Keys.DEVICE_ID].orEmpty(),
                lastSyncedFingerprint = values[Keys.LAST_SYNCED_FINGERPRINT],
                lastRemoteBackupId = values[Keys.LAST_REMOTE_BACKUP_ID],
                lastSyncAt = values[Keys.LAST_SYNC_AT],
                lastSyncMessage = values[Keys.LAST_SYNC_MESSAGE],
                pendingConflictBackupId = values[Keys.PENDING_CONFLICT_BACKUP_ID],
                bangumiProgressSync = values[Keys.BANGUMI_PROGRESS_SYNC] ?: false,
                bangumiRatingSync = values[Keys.BANGUMI_RATING_SYNC] ?: false,
                bangumiSyncWifiOnly = values[Keys.BANGUMI_SYNC_WIFI_ONLY] ?: false,
                bangumiLastReport = com.animeow.app.data.bangumi.BangumiSyncReport.decode(values[Keys.BANGUMI_LAST_REPORT]),
                bangumiLoginTipHidden = values[Keys.BANGUMI_LOGIN_TIP_HIDDEN] ?: false,
                bangumiShowHomeStatus = values[Keys.BANGUMI_SHOW_HOME_STATUS] ?: true,
                bangumiDirectFailedAtMs = values[Keys.BANGUMI_DIRECT_FAILED_AT_MS],
            )
        }

    suspend fun snapshot(): CloudSyncSettings {
        ensureDeviceId()
        return settings.first()
    }

    suspend fun configurationSnapshot(): CloudSyncConfiguration {
        val current = settings.first()
        return CloudSyncConfiguration(
            autoSyncEnabled = current.autoSyncEnabled,
            intervalHours = current.intervalHours,
            unmeteredOnly = current.unmeteredOnly,
            conflictPolicy = current.conflictPolicy,
        )
    }

    suspend fun updateConfiguration(
        enabled: Boolean,
        intervalHours: Int,
        unmeteredOnly: Boolean,
        conflictPolicy: CloudConflictPolicy,
    ) {
        dataStore.edit { values ->
            values[Keys.AUTO_SYNC_ENABLED] = enabled
            values[Keys.INTERVAL_HOURS] = normalizeInterval(intervalHours)
            values[Keys.UNMETERED_ONLY] = unmeteredOnly
            values[Keys.CONFLICT_POLICY] = conflictPolicy.storageKey
            if (values[Keys.DEVICE_ID].isNullOrBlank()) values[Keys.DEVICE_ID] = UUID.randomUUID().toString()
        }
    }

    suspend fun restoreConfiguration(configuration: CloudSyncConfiguration) {
        dataStore.edit { values ->
            values[Keys.AUTO_SYNC_ENABLED] = configuration.autoSyncEnabled
            values[Keys.INTERVAL_HOURS] = normalizeInterval(configuration.intervalHours)
            values[Keys.UNMETERED_ONLY] = configuration.unmeteredOnly
            values[Keys.CONFLICT_POLICY] = configuration.conflictPolicy.storageKey
        }
    }

    /** Bangumi 同步开关（设备本地配置，不进备份/设备间同步，故不在 [CloudSyncConfiguration]）。 */
    suspend fun updateBangumiSyncConfiguration(
        progressSync: Boolean,
        ratingSync: Boolean,
        wifiOnly: Boolean,
    ) {
        dataStore.edit { values ->
            values[Keys.BANGUMI_PROGRESS_SYNC] = progressSync
            values[Keys.BANGUMI_RATING_SYNC] = ratingSync
            values[Keys.BANGUMI_SYNC_WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun recordBangumiSyncReport(report: com.animeow.app.data.bangumi.BangumiSyncReport) {
        dataStore.edit { values -> values[Keys.BANGUMI_LAST_REPORT] = report.encode() }
    }

    suspend fun setBangumiLoginTipHidden(hidden: Boolean) {
        dataStore.edit { values -> values[Keys.BANGUMI_LOGIN_TIP_HIDDEN] = hidden }
    }

    suspend fun setBangumiShowHomeStatus(visible: Boolean) {
        dataStore.edit { values -> values[Keys.BANGUMI_SHOW_HOME_STATUS] = visible }
    }

    suspend fun setBangumiDirectFailedAtMs(timestampMs: Long?) {
        dataStore.edit { values ->
            if (timestampMs != null) values[Keys.BANGUMI_DIRECT_FAILED_AT_MS] = timestampMs
            else values.remove(Keys.BANGUMI_DIRECT_FAILED_AT_MS)
        }
    }

    suspend fun markSynced(fingerprint: String, remoteBackupId: Long?, message: String, syncedAt: String) {
        dataStore.edit { values ->
            values[Keys.LAST_SYNCED_FINGERPRINT] = fingerprint
            remoteBackupId?.let { values[Keys.LAST_REMOTE_BACKUP_ID] = it }
                ?: values.remove(Keys.LAST_REMOTE_BACKUP_ID)
            values[Keys.LAST_SYNC_AT] = syncedAt
            values[Keys.LAST_SYNC_MESSAGE] = message
            values.remove(Keys.PENDING_CONFLICT_BACKUP_ID)
        }
    }

    suspend fun markConflict(remoteBackupId: Long, message: String, detectedAt: String) {
        dataStore.edit { values ->
            values[Keys.PENDING_CONFLICT_BACKUP_ID] = remoteBackupId
            values[Keys.LAST_SYNC_AT] = detectedAt
            values[Keys.LAST_SYNC_MESSAGE] = message
        }
    }

    suspend fun markResult(message: String, at: String) {
        dataStore.edit { values ->
            values[Keys.LAST_SYNC_AT] = at
            values[Keys.LAST_SYNC_MESSAGE] = message
        }
    }

    suspend fun clearPendingConflict() {
        dataStore.edit { it.remove(Keys.PENDING_CONFLICT_BACKUP_ID) }
    }

    suspend fun resetBaseline(message: String, at: String) {
        dataStore.edit { values ->
            values.remove(Keys.LAST_SYNCED_FINGERPRINT)
            values.remove(Keys.LAST_REMOTE_BACKUP_ID)
            values.remove(Keys.PENDING_CONFLICT_BACKUP_ID)
            values[Keys.LAST_SYNC_AT] = at
            values[Keys.LAST_SYNC_MESSAGE] = message
        }
    }

    private suspend fun ensureDeviceId() {
        dataStore.edit { values ->
            if (values[Keys.DEVICE_ID].isNullOrBlank()) values[Keys.DEVICE_ID] = UUID.randomUUID().toString()
        }
    }

    private object Keys {
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        val INTERVAL_HOURS = intPreferencesKey("interval_hours")
        val UNMETERED_ONLY = booleanPreferencesKey("unmetered_only")
        val CONFLICT_POLICY = stringPreferencesKey("conflict_policy")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LAST_SYNCED_FINGERPRINT = stringPreferencesKey("last_synced_fingerprint")
        val LAST_REMOTE_BACKUP_ID = longPreferencesKey("last_remote_backup_id")
        val LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
        val LAST_SYNC_MESSAGE = stringPreferencesKey("last_sync_message")
        val PENDING_CONFLICT_BACKUP_ID = longPreferencesKey("pending_conflict_backup_id")
        val BANGUMI_PROGRESS_SYNC = booleanPreferencesKey("bangumi_progress_sync")
        val BANGUMI_RATING_SYNC = booleanPreferencesKey("bangumi_rating_sync")
        val BANGUMI_SYNC_WIFI_ONLY = booleanPreferencesKey("bangumi_sync_wifi_only")
        val BANGUMI_LAST_REPORT = stringPreferencesKey("bangumi_last_sync_report")
        val BANGUMI_LOGIN_TIP_HIDDEN = booleanPreferencesKey("bangumi_login_tip_hidden")
        val BANGUMI_SHOW_HOME_STATUS = booleanPreferencesKey("bangumi_show_home_status")
        val BANGUMI_DIRECT_FAILED_AT_MS = longPreferencesKey("bangumi_direct_failed_at_ms")
    }

    private companion object {
        val ALLOWED_INTERVALS = listOf(6, 12, 24, 72, 168)

        fun normalizeInterval(value: Int): Int = ALLOWED_INTERVALS.minBy { kotlin.math.abs(it - value) }
    }
}
