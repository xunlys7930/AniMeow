package com.animeow.app.data.cloud

import android.content.Context
import android.provider.Settings
import com.animeow.app.BuildConfig
import java.security.MessageDigest
import java.util.UUID

internal object DeviceRegistrationId {
    private const val FALLBACK_PREFERENCES = "device_registration"
    private const val FALLBACK_KEY = "fallback_id"

    fun from(context: Context): String {
        val appContext = context.applicationContext
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val stableSource = androidId ?: appContext
            .getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
            .let { preferences ->
                preferences.getString(FALLBACK_KEY, null) ?: UUID.randomUUID().toString().also { generated ->
                    preferences.edit().putString(FALLBACK_KEY, generated).apply()
                }
            }
        return derive(stableSource, BuildConfig.APP_NAMESPACE)
    }

    internal fun derive(source: String, namespace: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest("$namespace:$source".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
