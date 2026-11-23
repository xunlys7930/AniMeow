package com.animeow.app.data.community

import android.content.Context
import android.util.AtomicFile
import coil.imageLoader
import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.CloudSession
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Only the current user's identity, excluded from Android/cloud backups and scoped to the login. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal class CommunityIdentityCache(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.noBackupFilesDir, "community_identity.json"))

    fun read(session: CloudSession?): JSONObject? = synchronized(lock) {
        if (session == null) return@synchronized null
        val saved = readFile() ?: return@synchronized null
        saved.optJSONObject("profile")?.takeIf {
            saved.optString("scope") == scope(session) && it.optLong("user_id") == session.userId
        }
    }

    fun write(session: CloudSession, profile: JSONObject, avatarUrl: String?) = synchronized(lock) {
        if (profile.optLong("user_id") != session.userId) return@synchronized
        val avatarKey = avatarUrl?.let { communityImageCacheKey(it, session.token) }
        val previousKey = readFile()?.optString("avatar_key")?.takeIf(String::isNotBlank)
        if (previousKey != null && previousKey != avatarKey) {
            context.imageLoader.diskCache?.remove(previousKey)
            context.imageLoader.memoryCache?.remove(coil.memory.MemoryCache.Key(previousKey))
        }
        val json = JSONObject().put("scope", scope(session)).put("profile", profile).put("avatar_key", avatarKey)
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() = synchronized(lock) {
        readFile()?.optString("avatar_key")?.takeIf(String::isNotBlank)?.let { key ->
            context.imageLoader.diskCache?.remove(key)
            context.imageLoader.memoryCache?.remove(coil.memory.MemoryCache.Key(key))
        }
        file.delete()
    }

    private fun readFile(): JSONObject? = runCatching {
        if (!file.baseFile.isFile || file.baseFile.length() > 128 * 1024) return null
        JSONObject(file.readFully().toString(Charsets.UTF_8))
    }.getOrNull()

    private fun scope(session: CloudSession): String = MessageDigest.getInstance("SHA-256")
        .digest("${BuildConfig.CLOUD_API_BASE}:${session.userId}:${session.token}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object { val lock = Any() }
}
