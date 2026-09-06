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
    }

    private companion object {
        val ALLOWED_INTERVALS = listOf(6, 12, 24, 72, 168)

        fun normalizeInterval(value: Int): Int = ALLOWED_INTERVALS.minBy { kotlin.math.abs(it - value) }
    }
}
