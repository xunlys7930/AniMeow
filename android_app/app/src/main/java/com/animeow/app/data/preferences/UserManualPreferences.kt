package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userManualDataStore by preferencesDataStore(name = "user_manual_preferences")

/** 每日手册弹窗的显示模式。 */
enum class ManualDisplayMode {
    /** 每天首次打开时弹出。 */
    DAILY,

    /** 永不弹出。 */
    NEVER,

    /** 今日不再弹出，明天恢复（按日期判断）。 */
    SKIP_TODAY,
}

data class UserManualSettings(
    val displayMode: ManualDisplayMode = ManualDisplayMode.DAILY,
    /** 上次弹窗日期（ISO 格式 yyyy-MM-dd），用于判断今天是否已弹过。 */
    val lastShownDate: String = "",
)

class UserManualPreferences(context: Context) {
    private val dataStore = context.applicationContext.userManualDataStore

    val settings: Flow<UserManualSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values ->
            UserManualSettings(
                displayMode = values[Keys.DISPLAY_MODE]?.let { name ->
                    runCatching { ManualDisplayMode.valueOf(name) }.getOrNull()
                } ?: ManualDisplayMode.DAILY,
                lastShownDate = values[Keys.LAST_SHOWN_DATE].orEmpty(),
            )
        }

    suspend fun snapshot(): UserManualSettings = settings.first()

    suspend fun setDisplayMode(mode: ManualDisplayMode) {
        dataStore.edit { it[Keys.DISPLAY_MODE] = mode.name }
    }

    /** 记录今天已弹过弹窗。 */
    suspend fun markShownToday() {
        dataStore.edit { it[Keys.LAST_SHOWN_DATE] = LocalDate.now().toString() }
    }

    /** 判断今天是否需要弹窗。 */
    suspend fun shouldShowToday(): Boolean {
        val current = snapshot()
        return when (current.displayMode) {
            ManualDisplayMode.NEVER -> false
            ManualDisplayMode.DAILY -> current.lastShownDate != LocalDate.now().toString()
            ManualDisplayMode.SKIP_TODAY -> current.lastShownDate != LocalDate.now().toString()
        }
    }

    private object Keys {
        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val LAST_SHOWN_DATE = stringPreferencesKey("last_shown_date")
    }
}
