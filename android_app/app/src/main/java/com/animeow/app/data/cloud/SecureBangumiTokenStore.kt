package com.animeow.app.data.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机缓存的 Bangumi access token。服务端持有权威令牌（AES 信封落库），
 * 这里只是免去每次导入/同步都先打 /refresh 的读缓存；丢失或解密失败时
 * 走 /api/auth/bangumi/refresh 即可取回，绝不影响 AniMeow 会话。
 */
data class BangumiTokenSession(
    val userId: Long,
    val bangumiId: Long,
    val username: String,
    val nickname: String?,
    val avatar: String?,
    val accessToken: String,
    val expiresAt: String?,
)

class SecureBangumiTokenStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): BangumiTokenSession? = synchronized(TOKEN_LOCK) { loadLocked() }

    private fun loadLocked(): BangumiTokenSession? = runCatching {
        val userId = preferences.getLong(KEY_USER_ID, -1L).takeIf { it > 0 } ?: return@runCatching null
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return@runCatching null
        val iv = preferences.getString(KEY_IV, null) ?: return@runCatching null
        BangumiTokenSession(
            userId = userId,
            bangumiId = preferences.getLong(KEY_BANGUMI_ID, 0L),
            username = preferences.getString(KEY_USERNAME, null).orEmpty(),
            nickname = preferences.getString(KEY_NICKNAME, null),
            avatar = preferences.getString(KEY_AVATAR, null),
            accessToken = decrypt(encrypted, iv),
            expiresAt = preferences.getString(KEY_EXPIRES_AT, null),
        )
    }.getOrNull()

    fun save(session: BangumiTokenSession) = synchronized(TOKEN_LOCK) {
        require(session.userId > 0 && session.accessToken.isNotBlank()) {
            "Bangumi 授权信息不完整，请重试"
        }
        val (encrypted, iv) = encrypt(session.accessToken)
        check(
            preferences.edit()
                .putLong(KEY_USER_ID, session.userId)
                .putLong(KEY_BANGUMI_ID, session.bangumiId)
                .putString(KEY_USERNAME, session.username)
                .putString(KEY_NICKNAME, session.nickname)
                .putString(KEY_AVATAR, session.avatar)
                .putString(KEY_TOKEN, encrypted)
                .putString(KEY_IV, iv)
                .putString(KEY_EXPIRES_AT, session.expiresAt)
                .commit(),
        ) { "Bangumi 授权信息未能保存，请检查存储空间后重试" }
    }

    /** 仅更新令牌内容，保留绑定身份；无缓存行时不新建（由 /refresh 返回值兜底）。 */
    fun updateTokens(userId: Long, accessToken: String, expiresAt: String?) {
        synchronized(TOKEN_LOCK) {
            val current = loadLocked() ?: return
            if (current.userId != userId) return
            save(current.copy(accessToken = accessToken, expiresAt = expiresAt))
        }
    }

    fun clearIfUser(userId: Long) {
        synchronized(TOKEN_LOCK) {
            if (loadLocked()?.userId != userId) return
            preferences.edit().clear().commit()
        }
    }

    fun clear() = synchronized(TOKEN_LOCK) { preferences.edit().clear().commit() }

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
        check(createIfMissing) { "设备暂时无法读取 Bangumi 授权信息，请重试" }
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
        val TOKEN_LOCK = Any()
        const val PREFERENCES_NAME = "bangumi_account_secure"
        const val KEY_USER_ID = "user_id"
        const val KEY_BANGUMI_ID = "bangumi_id"
        const val KEY_USERNAME = "bangumi_username"
        const val KEY_NICKNAME = "bangumi_nickname"
        const val KEY_AVATAR = "bangumi_avatar"
        const val KEY_TOKEN = "access_token_ciphertext"
        const val KEY_IV = "access_token_iv"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        // 与云会话密钥分开：换绑/解绑只轮换 Bangumi 侧，不动登录凭据
        const val KEY_ALIAS = "animeow_bangumi_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
