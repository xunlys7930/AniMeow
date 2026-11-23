package com.animeow.app.data.analysis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CustomModelKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("custom_model_secure", Context.MODE_PRIVATE)

    fun load(endpoint: String): String {
        val stored = preferences.getString(customModelBaseUrl(endpoint), null) ?: return ""
        return try {
            val parts = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            error("暂时无法读取已记住的 Key，请重新填写或稍后重试")
        }
    }

    fun save(endpoint: String, apiKey: String, remember: Boolean) {
        val base = customModelBaseUrl(endpoint)
        require(!remember || apiKey.isNotBlank()) { "请填写需要记住的 API Key" }
        val edit = preferences.edit()
        if (remember && apiKey.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(true))
            val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
            edit.putString(base, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP))
        } else {
            edit.remove(base)
        }
        check(edit.commit()) { "Key 记忆设置保存失败，请检查存储空间后重试" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Key 清除失败，请重试" }
    }

    private fun secretKey(create: Boolean): SecretKey {
        val alias = "animeow_custom_model_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "暂时无法读取 Key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}
