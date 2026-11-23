package com.animeow.app.data.media

import android.content.Context
import com.animeow.app.data.remote.RemoteAnime
import org.json.JSONObject

/** Re-creatable URL hints only; never part of the user's library or cloud archive. */
internal class RemoteCoverAlternatives(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("public_cover_alternatives", Context.MODE_PRIVATE)
    fun remember(remote: RemoteAnime) {
        val primary = remote.coverUrl?.takeIf(::isRemoteImageUrl) ?: return
        val urls = remote.coverUrls.filter { (source, url) -> source in sources && validUrl(url) }
        if (urls.isEmpty()) return
        synchronized(lock) {
            val editor = prefs.edit()
            val keys = prefs.all.keys
            if (keys.size >= 512 && !prefs.contains(key(primary))) keys.take(keys.size - 510).forEach(editor::remove)
            // A later single-source response must not discard already verified alternatives.
            val previous = runCatching {
                val stored = JSONObject(prefs.getString(key(primary), "{}").orEmpty())
                sources.mapNotNull { source -> stored.optString(source).takeIf(::validUrl)?.let { source to it } }.toMap()
            }.getOrDefault(emptyMap())
            val merged = previous + urls
            val encoded = JSONObject(merged).toString()
            (listOf(primary) + merged.values).distinct().forEach { editor.putString(key(it), encoded) }
            editor.apply()
        }
    }
    fun candidates(primary: String, allowProxy: Boolean): List<String> = runCatching {
        val data = JSONObject(prefs.getString(key(primary), "{}").orEmpty())
        listOf("bangumi", "anilist", "proxy").filter { allowProxy || it != "proxy" }
            .mapNotNull { data.optString(it).takeIf(::validUrl) }
    }.getOrDefault(emptyList())
    private fun key(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun validUrl(value: String) = runCatching {
        val uri = java.net.URI(value)
        value.length <= 500 && uri.scheme in listOf("https", "http") && uri.host != null && uri.userInfo == null
    }.getOrDefault(false)
    private companion object {
        val lock = Any()
        val sources = setOf("bangumi", "anilist", "proxy")
    }
}
