package com.animeow.app.data.reader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LegadoWebDavConfig(
    val serverUrl: String = "",
    val remotePath: String = "/legado/",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

data class LegadoLanConfig(
    val host: String = "",
    val port: Int = 1122,
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && port in 1..65535
}

/**
 * 《开源阅读》同步连接配置存储。
 *
 * WebDAV 应用密码通过 Android Keystore AES-GCM 加密保存（对齐 [com.animeow.app.data.cloud.SecureCloudSessionStore]），
 * 同时该偏好文件被排除在系统自动云备份与原生 `.animeow.zip` 备份之外，防止凭据外泄。
 */
class SecureLegadoSyncStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadWebDav(): LegadoWebDavConfig = synchronized(LOCK) {
        val serverUrl = preferences.getString(KEY_WEBDAV_URL, "").orEmpty()
        val remotePath = preferences.getString(KEY_WEBDAV_PATH, "/legado/").orEmpty().ifBlank { "/legado/" }
        val username = preferences.getString(KEY_WEBDAV_USER, "").orEmpty()
        val encrypted = preferences.getString(KEY_WEBDAV_PASS_ENC, null)
        val iv = preferences.getString(KEY_WEBDAV_PASS_IV, null)
        val password = if (!encrypted.isNullOrBlank() && !iv.isNullOrBlank()) {
            runCatching { decrypt(encrypted, iv) }.getOrDefault("")
        } else {
            ""
        }
        LegadoWebDavConfig(
            serverUrl = serverUrl,
            remotePath = remotePath,
            username = username,
            password = password,
        )
    }

    fun saveWebDav(config: LegadoWebDavConfig) = synchronized(LOCK) {
        val editor = preferences.edit()
            .putString(KEY_WEBDAV_URL, config.serverUrl.trim())
            .putString(KEY_WEBDAV_PATH, config.remotePath.trim().ifBlank { "/legado/" })
            .putString(KEY_WEBDAV_USER, config.username.trim())
        if (config.password.isBlank()) {
            editor.remove(KEY_WEBDAV_PASS_ENC).remove(KEY_WEBDAV_PASS_IV)
        } else {
            val (encrypted, iv) = encrypt(config.password)
            editor.putString(KEY_WEBDAV_PASS_ENC, encrypted)
            editor.putString(KEY_WEBDAV_PASS_IV, iv)
        }
        check(editor.commit()) { "WebDAV 连接信息未能保存，请检查设备存储空间" }
    }

    fun clearWebDav() = synchronized(LOCK) {
        preferences.edit()
            .remove(KEY_WEBDAV_URL)
            .remove(KEY_WEBDAV_PATH)
            .remove(KEY_WEBDAV_USER)
            .remove(KEY_WEBDAV_PASS_ENC)
            .remove(KEY_WEBDAV_PASS_IV)
            .commit()
    }

    fun loadLan(): LegadoLanConfig = synchronized(LOCK) {
        LegadoLanConfig(
            host = preferences.getString(KEY_LAN_HOST, "").orEmpty(),
            port = preferences.getInt(KEY_LAN_PORT, 1122).coerceIn(1, 65535),
        )
    }

    fun saveLan(config: LegadoLanConfig) = synchronized(LOCK) {
        preferences.edit()
            .putString(KEY_LAN_HOST, config.host.trim())
            .putInt(KEY_LAN_PORT, config.port.coerceIn(1, 65535))
            .apply()
    }

    fun loadLastSyncedAt(): String? = synchronized(LOCK) {
        preferences.getString(KEY_LAST_SYNCED_AT, null)?.takeIf(String::isNotBlank)
    }

    fun saveLastSyncedAt(isoTimestamp: String) = synchronized(LOCK) {
        preferences.edit().putString(KEY_LAST_SYNCED_AT, isoTimestamp).apply()
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true))
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(encrypted: String, encodedIv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(createIfMissing = false), GCMParameterSpec(128, iv))
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun secretKey(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(createIfMissing) { "设备安全密钥暂不可用，请重新输入 WebDAV 密码" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private val LOCK = Any()
        const val PREFERENCES_NAME = "legado_sync_secure"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "animeow_legado_sync_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_PATH = "webdav_path"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_PASS_ENC = "webdav_pass_enc"
        private const val KEY_WEBDAV_PASS_IV = "webdav_pass_iv"
        private const val KEY_LAN_HOST = "lan_host"
        private const val KEY_LAN_PORT = "lan_port"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
    }
}
