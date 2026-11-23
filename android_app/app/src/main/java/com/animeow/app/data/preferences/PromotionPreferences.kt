package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

private val Context.promotionDataStore by preferencesDataStore(name = "promotion_preferences")

enum class PromotionMode { OCCASIONAL, ONCE, EVERY_LAUNCH, NEVER }

internal const val PROMOTION_COOLDOWN_MILLIS = 14L * 24 * 60 * 60 * 1_000

internal fun shouldShowPromotion(
    launch: Long,
    mode: PromotionMode,
    alreadyShown: Boolean,
    lastShownAt: Long? = null,
    lastShownLaunch: Long? = null,
    now: Long = System.currentTimeMillis(),
): Boolean = when (mode) {
    PromotionMode.NEVER -> false
    PromotionMode.ONCE -> launch >= 2 && !alreadyShown
    PromotionMode.EVERY_LAUNCH -> launch >= 2
    PromotionMode.OCCASIONAL -> launch >= 5 && if (!alreadyShown) {
        true
    } else {
        lastShownAt != null && lastShownAt >= 0 && now >= lastShownAt &&
            now - lastShownAt >= PROMOTION_COOLDOWN_MILLIS &&
            lastShownLaunch != null && lastShownLaunch >= 0 && launch >= lastShownLaunch &&
            launch - lastShownLaunch >= 5
    }
}

class PromotionPreferences internal constructor(
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.applicationContext.promotionDataStore)

    suspend fun registerLaunch(): Boolean {
        var eligible = false
        store.edit { values ->
            val launch = (values[COUNT] ?: 0).coerceIn(0, Long.MAX_VALUE - 1) + 1
            values[COUNT] = launch
            val mode = runCatching { PromotionMode.valueOf(values[MODE].orEmpty()) }.getOrDefault(PromotionMode.OCCASIONAL)
            val shown = values[SHOWN] ?: false
            val now = clock()
            // Older installs recorded only shown_once. Start a quiet cooldown on upgrade
            // instead of immediately asking existing users to support development again.
            if (mode == PromotionMode.OCCASIONAL && shown &&
                (values[LAST_SHOWN_AT] == null || values[LAST_SHOWN_LAUNCH] == null)) {
                values[LAST_SHOWN_AT] = now
                values[LAST_SHOWN_LAUNCH] = launch
            }
            eligible = shouldShowPromotion(launch, mode, shown, values[LAST_SHOWN_AT], values[LAST_SHOWN_LAUNCH], now)
        }
        return eligible
    }

    suspend fun markShown() {
        store.edit {
            it[SHOWN] = true
            it[LAST_SHOWN_AT] = clock()
            it[LAST_SHOWN_LAUNCH] = (it[COUNT] ?: 0).coerceAtLeast(0)
        }
    }
    suspend fun setMode(mode: PromotionMode) { store.edit { it[MODE] = mode.name } }

    private companion object {
        val COUNT = longPreferencesKey("launch_count")
        val MODE = stringPreferencesKey("display_mode")
        val SHOWN = booleanPreferencesKey("shown_once")
        val LAST_SHOWN_AT = longPreferencesKey("last_shown_at")
        val LAST_SHOWN_LAUNCH = longPreferencesKey("last_shown_launch")
    }
}
