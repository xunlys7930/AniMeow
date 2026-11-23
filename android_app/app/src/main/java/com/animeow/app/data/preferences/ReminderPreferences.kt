package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.reminderDataStore by preferencesDataStore(name = "reminder_preferences")
data class ReminderSettings(
    val paused: Boolean = false,
    val leadMinutes: Int = 0,
    val autoStopWhenCompleted: Boolean = true,
)

class ReminderPreferences(context: Context) {
    private val store = context.applicationContext.reminderDataStore
    private val pausedKey = booleanPreferencesKey("paused")
    private val leadKey = intPreferencesKey("lead_minutes")
    private val autoStopKey = booleanPreferencesKey("auto_stop_when_completed")
    val settings = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map {
            ReminderSettings(
                paused = it[pausedKey] ?: false,
                leadMinutes = (it[leadKey] ?: 0).coerceIn(0, 120),
                autoStopWhenCompleted = it[autoStopKey] ?: true,
            )
        }
    suspend fun snapshot() = settings.first()
    suspend fun save(value: ReminderSettings) {
        store.edit {
            it[pausedKey] = value.paused
            it[leadKey] = value.leadMinutes.coerceIn(0, 120)
            it[autoStopKey] = value.autoStopWhenCompleted
        }
    }
}
