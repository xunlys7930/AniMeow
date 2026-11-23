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
        // 用途声明：ANDROID_ID 在此仅用于生成"设备注册标识"（云端多设备去重与安全
        // 绑定），并非用于追踪或广告。为降低该标识被当作跨应用稳定追踪器的风险，
        // 我们绝不对 ANDROID_ID 做明文持久化或直接上报，而是与 APP_NAMESPACE 拼接后
        // 经 SHA-256 派生为不可逆的十六进制字符串；若 ANDROID_ID 不可用（如被厂商重置
        // 或返回 null），再回退到应用私有 SharedPreferences 内的一次性 UUID。
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
