package com.animeow.app.data.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class CloudSession(
    val userId: Long,
    val username: String,
    val token: String,
)

class CloudSessionExpiredException(
    message: String = "登录会话已失效，请重新登录",
) : IllegalStateException(message)

internal fun cloudSessionExpiryEpochSeconds(token: String): Long? = runCatching {
    val payload = token.split('.').getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
    val decoded = java.util.Base64.getUrlDecoder().decode(payload)
    JSONObject(decoded.toString(Charsets.UTF_8)).optLong("exp").takeIf { it > 0L }
}.getOrNull()

internal fun isCloudSessionExpired(
    token: String,
    nowEpochSeconds: Long = Instant.now().epochSecond,
): Boolean = cloudSessionExpiryEpochSeconds(token)?.let { it <= nowEpochSeconds } == true

internal fun cloudRemoteFailure(
    statusCode: Int,
    responseBody: String,
    authenticated: Boolean,
): IllegalStateException {
    val serverMessage = runCatching { JSONObject(responseBody).optString("message") }
        .getOrNull()
        ?.let(::safeCloudMessage)
    if (authenticated && statusCode == 401) {
        return CloudSessionExpiredException(serverMessage ?: "登录会话已过期，请重新登录")
    }
    return when (statusCode) {
        401, 403 -> IllegalStateException(serverMessage ?: "当前账号无法使用此云端功能，请重新登录或稍后重试")
        404, 405, 410, 501 -> IllegalStateException(serverMessage ?: "当前服务器版本暂不支持此功能；本地资料不受影响")
        in 500..599 -> IllegalStateException(serverMessage ?: "云端服务暂时不可用；本地资料不受影响")
        else -> IllegalStateException(serverMessage ?: "云端请求失败，请稍后重试")
    }
}

internal fun safeCloudMessage(value: String?): String? {
    val message = value.orEmpty().trim().takeIf(String::isNotEmpty) ?: return null
    val lowered = message.lowercase(java.util.Locale.ROOT)
    if (
        message.startsWith("{") || message.startsWith("<") ||
        "access denied" in lowered || "unauthorized" in lowered ||
        "http " in lowered || "traceback" in lowered
    ) return null
    return message.take(120)
}

class SecureCloudSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CloudSession? = runCatching {
        val userId = preferences.getLong(KEY_USER_ID, -1L).takeIf { it > 0 } ?: return null
        val username = preferences.getString(KEY_USERNAME, null)?.takeIf(String::isNotBlank) ?: return null
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val token = decrypt(encrypted, iv)
        if (isCloudSessionExpired(token)) {
            clear()
            return null
        }
        CloudSession(userId, username, token)
    }.getOrElse {
        clear()
        null
    }

    fun save(session: CloudSession) {
        val (encrypted, iv) = encrypt(session.token)
        preferences.edit()
            .putLong(KEY_USER_ID, session.userId)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_TOKEN, encrypted)
            .putString(KEY_IV, iv)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(encrypted: String, encodedIv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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

    private companion object {
        const val PREFERENCES_NAME = "cloud_account_secure"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_TOKEN = "token_ciphertext"
        const val KEY_IV = "token_iv"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "animeow_cloud_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
