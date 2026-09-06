package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.animeow.app.data.branding.BrandingSettings
import com.animeow.app.data.branding.LauncherIcon
import com.animeow.app.data.branding.LauncherIconManager
import com.animeow.app.data.branding.SplashBackgroundMode
import com.animeow.app.data.branding.SplashScaleMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.brandingDataStore by preferencesDataStore(name = "branding_preferences")

class BrandingPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.brandingDataStore
    private val launcherIconManager = LauncherIconManager(appContext)

    val settings: Flow<BrandingSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            BrandingSettings(
                customSplashEnabled = preferences[Keys.CUSTOM_SPLASH_ENABLED] ?: false,
                splashImagePath = preferences[Keys.SPLASH_IMAGE_PATH],
                splashDurationMillis = preferences[Keys.SPLASH_DURATION_MILLIS] ?: 1_000,
                splashScaleMode = SplashScaleMode.fromStorage(preferences[Keys.SPLASH_SCALE_MODE]),
                splashBackgroundMode = SplashBackgroundMode.fromStorage(
                    preferences[Keys.SPLASH_BACKGROUND_MODE],
                ),
                splashFocalX = preferences[Keys.SPLASH_FOCAL_X] ?: 0.5f,
                splashFocalY = preferences[Keys.SPLASH_FOCAL_Y] ?: 0.5f,
                tapToSkip = preferences[Keys.TAP_TO_SKIP] ?: true,
                launcherIcon = LauncherIcon.fromStorage(preferences[Keys.LAUNCHER_ICON]),
            ).normalized()
        }

    suspend fun setCustomSplashEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CUSTOM_SPLASH_ENABLED] = enabled }
    }

    suspend fun setSplashImage(path: String?, enabled: Boolean) {
        dataStore.edit { preferences ->
            if (path.isNullOrBlank()) preferences.remove(Keys.SPLASH_IMAGE_PATH)
            else preferences[Keys.SPLASH_IMAGE_PATH] = path
            preferences[Keys.CUSTOM_SPLASH_ENABLED] = enabled && !path.isNullOrBlank()
        }
    }

    suspend fun setSplashDurationMillis(value: Int) {
        dataStore.edit { it[Keys.SPLASH_DURATION_MILLIS] = value.coerceIn(500, 5_000) }
    }

    suspend fun setSplashScaleMode(value: SplashScaleMode) {
        dataStore.edit { it[Keys.SPLASH_SCALE_MODE] = value.storageKey }
    }

    suspend fun setSplashBackgroundMode(value: SplashBackgroundMode) {
        dataStore.edit { it[Keys.SPLASH_BACKGROUND_MODE] = value.storageKey }
    }

    suspend fun setSplashFocalPoint(x: Float, y: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.SPLASH_FOCAL_X] = x.coerceIn(0f, 1f)
            preferences[Keys.SPLASH_FOCAL_Y] = y.coerceIn(0f, 1f)
        }
    }

    suspend fun setTapToSkip(enabled: Boolean) {
        dataStore.edit { it[Keys.TAP_TO_SKIP] = enabled }
    }

    suspend fun setLauncherIcon(icon: LauncherIcon) {
        val previous = snapshot().launcherIcon
        if (previous == icon) return
        launcherIconManager.reconcile(icon)
        try {
            dataStore.edit { it[Keys.LAUNCHER_ICON] = icon.storageKey }
        } catch (error: Throwable) {
            runCatching { launcherIconManager.reconcile(previous) }
            throw error
        }
    }

    suspend fun snapshot(): BrandingSettings = settings.first()

    suspend fun save(value: BrandingSettings) {
        val normalized = value.normalized()
        val previous = snapshot()
        if (previous.launcherIcon != normalized.launcherIcon) {
            launcherIconManager.reconcile(normalized.launcherIcon)
        }
        try {
            dataStore.edit { preferences ->
                preferences[Keys.CUSTOM_SPLASH_ENABLED] = normalized.customSplashEnabled
                normalized.splashImagePath?.let { preferences[Keys.SPLASH_IMAGE_PATH] = it }
                    ?: preferences.remove(Keys.SPLASH_IMAGE_PATH)
                preferences[Keys.SPLASH_DURATION_MILLIS] = normalized.splashDurationMillis
                preferences[Keys.SPLASH_SCALE_MODE] = normalized.splashScaleMode.storageKey
                preferences[Keys.SPLASH_BACKGROUND_MODE] = normalized.splashBackgroundMode.storageKey
                preferences[Keys.SPLASH_FOCAL_X] = normalized.splashFocalX
                preferences[Keys.SPLASH_FOCAL_Y] = normalized.splashFocalY
                preferences[Keys.TAP_TO_SKIP] = normalized.tapToSkip
                preferences[Keys.LAUNCHER_ICON] = normalized.launcherIcon.storageKey
            }
        } catch (error: Throwable) {
            if (previous.launcherIcon != normalized.launcherIcon) {
                runCatching { launcherIconManager.reconcile(previous.launcherIcon) }
            }
            throw error
        }
    }

    private object Keys {
        val CUSTOM_SPLASH_ENABLED = booleanPreferencesKey("custom_splash_enabled")
        val SPLASH_IMAGE_PATH = stringPreferencesKey("splash_image_path")
        val SPLASH_DURATION_MILLIS = intPreferencesKey("splash_duration_millis")
        val SPLASH_SCALE_MODE = stringPreferencesKey("splash_scale_mode")
        val SPLASH_BACKGROUND_MODE = stringPreferencesKey("splash_background_mode")
        val SPLASH_FOCAL_X = floatPreferencesKey("splash_focal_x")
        val SPLASH_FOCAL_Y = floatPreferencesKey("splash_focal_y")
        val TAP_TO_SKIP = booleanPreferencesKey("tap_to_skip")
        val LAUNCHER_ICON = stringPreferencesKey("launcher_icon")
    }
}

