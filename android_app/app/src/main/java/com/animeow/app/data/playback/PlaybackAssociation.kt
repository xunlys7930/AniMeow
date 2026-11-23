package com.animeow.app.data.playback

import android.content.Context
import androidx.core.content.edit
import com.animeow.app.data.remote.*
import org.json.JSONArray
import org.json.JSONObject

/** Keep the existing history key when a record's title or metadata is edited. */
internal class PlaybackIdentities(context: Context) {
    private val prefs = context.getSharedPreferences("playback_identities_v1", Context.MODE_PRIVATE)
    fun key(anime: com.animeow.app.data.local.AnimeEntity): String = key(anime, persist = true)

    fun key(anime: com.animeow.app.data.local.AnimeEntity, persist: Boolean): String {
        // Legacy rows without creation identity must retain the conservative metadata guard after a restore.
        if (anime.createdAt.isNullOrBlank()) return playbackHistoryKey(anime)
        val id = "${anime.id}:${anime.createdAt}"
        return prefs.getString(id, null) ?: playbackHistoryKey(anime).also { value ->
            if (persist) prefs.edit { putString(id, value) }
        }
    }
}

internal data class PlaybackAssociation(val source: String, val work: PlaybackWork, val line: String, val lines: List<PlaybackLine>)

internal class PlaybackAssociations(context: Context) {
    private val prefs = context.getSharedPreferences("playback_associations_v1", Context.MODE_PRIVATE)
    fun read(key: String): PlaybackAssociation? = runCatching {
        val json = JSONObject(prefs.getString(key, null) ?: return null)
        val rows = json.getJSONArray("lines")
        PlaybackAssociation(json.getString("source"), PlaybackWork(json.getString("work"), json.getString("title"), json.optString("coverUrl")), json.optString("line"),
            (0 until rows.length()).map { i ->
                val line = rows.getJSONObject(i); val episodes = line.getJSONArray("episodes")
                PlaybackLine(line.getString("id"), line.getString("name"), (0 until episodes.length()).map { n ->
                    val ep = episodes.getJSONObject(n)
                    PlaybackEpisode(ep.getString("id"), ep.getString("label"), ep.optInt("number").takeIf { it > 0 }) })
            })
    }.getOrNull()
    fun save(key: String, association: PlaybackAssociation) {
        val lines = JSONArray()
        association.lines.forEach { line ->
            val episodes = JSONArray()
            line.episodes.forEach { ep -> episodes.put(JSONObject().put("id", ep.id).put("label", ep.label).put("number", ep.number)) }
            lines.put(JSONObject().put("id", line.id).put("name", line.name).put("episodes", episodes))
        }
        val value = JSONObject().put("source", association.source).put("work", association.work.id).put("title", association.work.title)
            .put("coverUrl", association.work.coverUrl)
            .put("line", association.line).put("lines", lines).put("savedAt", System.currentTimeMillis()).toString()
        prefs.edit {
            if (!prefs.contains(key) && prefs.all.size >= 500) prefs.all.minByOrNull {
                runCatching { JSONObject(it.value.toString()).optLong("savedAt") }.getOrDefault(0)
            }?.key?.let(::remove)
            putString(key, value)
        }
    }
}
