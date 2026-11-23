package com.animeow.app.data.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    internal val rejectedToken: String? = null,
) : IllegalStateException(message) {
    /** A response from an earlier login must not reset the current account's UI. */
    fun isFor(session: CloudSession?): Boolean = session != null &&
        (rejectedToken == null || rejectedToken == session.token)
}

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
    sessionToken: String? = null,
): IllegalStateException {
    val payload = runCatching { JSONObject(responseBody) }.getOrNull()
    val serverMessage = safeCloudMessage(payload?.optString("message"))
    // Older API gateways also use 401 for unsupported routes/shared API credentials.
    // Only the user-session contract can invalidate a saved login, never a bare 401.
    val sessionRejected = payload?.optString("code") in setOf("SESSION_EXPIRED", "INVALID_USER_TOKEN") ||
        serverMessage in setOf("请先登录账号", "登录会话已失效，请重新登录", "登录会话已过期，请重新登录")
    if (authenticated && statusCode == 401 && sessionRejected) {
        return CloudSessionExpiredException(serverMessage ?: "登录会话已失效，请重新登录", sessionToken)
    }
    return when (statusCode) {
        401 -> IllegalStateException(serverMessage ?: "此云端功能暂时无法访问，请稍后重试")
        403 -> IllegalStateException(serverMessage ?: "当前账号没有使用此功能的权限")
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

internal object CloudSessionSignals {
    private val version = MutableStateFlow(0L)
    val sessionVersion = version.asStateFlow()
    fun notifyChanged() {
        version.value += 1L
    }
}

class SecureCloudSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CloudSession? = synchronized(SESSION_LOCK) { loadLocked() }

    private fun loadLocked(): CloudSession? = runCatching {
        val userId = preferences.getLong(KEY_USER_ID, -1L).takeIf { it > 0 } ?: return@runCatching null
        val username = preferences.getString(KEY_USERNAME, null)?.takeIf(String::isNotBlank) ?: return@runCatching null
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return@runCatching null
        val iv = preferences.getString(KEY_IV, null) ?: return@runCatching null
        // The server decides expiry. A changed device clock, a temporarily unavailable
        // Keystore, or process restart during an upgrade must not erase credentials.
        CloudSession(userId, username, decrypt(encrypted, iv))
    }.getOrNull()

    fun save(session: CloudSession) = synchronized(SESSION_LOCK) {
        require(session.userId > 0 && session.username.isNotBlank() && session.token.isNotBlank()) {
            "服务器返回的登录信息不完整，请重试"
        }
        val previousUserId = preferences.getLong(KEY_USER_ID, -1L)
        val (encrypted, iv) = encrypt(session.token)
        check(preferences.edit()
            .putLong(KEY_USER_ID, session.userId)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_TOKEN, encrypted)
            .putString(KEY_IV, iv)
            .commit()) { "登录信息未能保存，请检查存储空间后重试" }
        if (previousUserId != session.userId) {
            com.animeow.app.data.community.CommunityReviewNotifications.cancelForSessionChange(appContext)
        }
        com.animeow.app.data.community.CommunityIdentityCache(appContext).clear()
        CloudSessionSignals.notifyChanged()
    }

    /** Compare and clear under the same lock shared by all account/community workers. */
    fun clearIfCurrent(rejectedToken: String): Boolean = synchronized(SESSION_LOCK) {
        if (loadLocked()?.token != rejectedToken) return@synchronized false
        clearLocked()
        true
    }

    fun clear() = synchronized(SESSION_LOCK) { clearLocked() }

    private fun clearLocked() {
        preferences.edit().clear().commit()
        com.animeow.app.data.community.CommunityIdentityCache(appContext).clear()
        com.animeow.app.data.community.CommunityReviewNotifications.cancelForSessionChange(appContext)
        CloudSessionSignals.notifyChanged()
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
        check(createIfMissing) { "设备暂时无法读取登录信息，请重试" }
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
        val SESSION_LOCK = Any()
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
