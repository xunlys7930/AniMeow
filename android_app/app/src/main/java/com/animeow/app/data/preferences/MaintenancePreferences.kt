package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.maintenanceDataStore by preferencesDataStore(name = "maintenance_preferences")

data class MaintenanceSettings(
    val autoCheckUpdates: Boolean = true,
    val ignoredUpdateVersion: String = "",
)

class MaintenancePreferences(context: Context) {
    private val dataStore = context.applicationContext.maintenanceDataStore

    val settings: Flow<MaintenanceSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values ->
            MaintenanceSettings(
                autoCheckUpdates = values[Keys.AUTO_CHECK_UPDATES] ?: true,
                ignoredUpdateVersion = values[Keys.IGNORED_UPDATE_VERSION].orEmpty(),
            )
        }

    suspend fun snapshot(): MaintenanceSettings = settings.first()

    suspend fun save(settings: MaintenanceSettings) {
        dataStore.edit { values ->
            values[Keys.AUTO_CHECK_UPDATES] = settings.autoCheckUpdates
            values[Keys.IGNORED_UPDATE_VERSION] = settings.ignoredUpdateVersion.trim()
        }
    }

    private object Keys {
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val IGNORED_UPDATE_VERSION = stringPreferencesKey("ignored_update_version")
    }
}
