package com.animeow.app.data.playback

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import com.animeow.app.data.local.AnimeEntity

internal fun playbackCompleted(checkpoint: PlaybackCheckpoint?): Boolean = checkpoint != null &&
    checkpoint.durationMs > 0 && checkpoint.positionMs > 0 && checkpoint.positionMs >= (checkpoint.durationMs - 1_000).coerceAtLeast(0)

internal fun playbackResumeToken(checkpoint: PlaybackCheckpoint): String = JSONObject()
    .put("source", checkpoint.source).put("work", checkpoint.workId).put("episode", checkpoint.episodeId).toString()

internal fun completedPlaybackNext(checkpoint: PlaybackCheckpoint?, association: PlaybackAssociation?): com.animeow.app.data.remote.PlaybackEpisode? {
    if (!playbackCompleted(checkpoint) || checkpoint == null || association == null ||
        checkpoint.source != association.source || checkpoint.workId != association.work.id) return null
    val current = association.lines.flatMap { it.episodes }.firstOrNull { it.id == checkpoint.episodeId } ?: return null
    return nextPlaybackEpisode(current, association.lines)
}

internal fun playbackHistoryKey(anime: AnimeEntity): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(listOf(anime.id, anime.createdAt, anime.title, anime.externalSource, anime.externalId).joinToString("|").toByteArray())
    .joinToString("") { "%02x".format(it) }

data class PlaybackCheckpoint(val source: String, val workId: String, val episodeId: String,
    val positionMs: Long = 0, val durationMs: Long = 0, val ranges: List<LongRange> = emptyList(), val handled: Boolean = false)

/** Coverage counts played intervals, so seeking over an episode cannot mark it watched. */
internal fun mergePlaybackRanges(ranges: List<LongRange>, start: Long, end: Long): List<LongRange> {
    if (start < 0 || end <= start) return ranges
    val sorted = (ranges + listOf(start..end)).sortedBy { it.first }
    val result = mutableListOf<LongRange>()
    for (range in sorted) {
        val previous = result.lastOrNull()
        if (previous != null && range.first <= previous.last) result[result.lastIndex] = previous.first..maxOf(previous.last, range.last)
        else result += range
    }
    // shortcut: retain at most 256 disjoint intervals; excessive seeking undercounts rather than falsely completing.
    return result.take(256)
}
internal fun playbackComplete(ranges: List<LongRange>, durationMs: Long): Boolean =
    durationMs >= 30_000 && ranges.sumOf { (it.last - it.first).coerceAtLeast(0) } >= durationMs * 9 / 10

class PlaybackHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback_history_v1", Context.MODE_PRIVATE)
    private val index = context.applicationContext.getSharedPreferences("playback_history_index_v1", Context.MODE_PRIVATE)
    private val identities = PlaybackIdentities(context.applicationContext)
    fun entries(): List<PlaybackHistoryEntry> = index.all.mapNotNull { (key, value) -> runCatching {
        val row = JSONObject(value as String)
        PlaybackHistoryEntry(key, row.getLong("animeId"), row.getString("title"), row.optString("cover"),
            row.optString("episode"), row.optLong("updatedAt"), read(key))
    }.getOrNull() }.sortedByDescending { it.updatedAt }
    fun describe(key: String, anime: AnimeEntity, episode: String, updatedAt: Long = System.currentTimeMillis()) = synchronized(lock) {
        val row = historyRow(anime, episode, updatedAt)
        index.edit {
            remove("removed:$key")
            if (!index.contains(key) && index.all.size >= 500) entries().lastOrNull()?.let { remove(it.key) }
            putString(key, row.toString())
        }
    }
    fun importExisting(key: String, anime: AnimeEntity, episode: String) = synchronized(lock) {
        if (index.contains(key) || index.getBoolean("removed:$key", false) || read(key) == null) return@synchronized
        val savedAt = runCatching { JSONObject(prefs.getString(key, null)!!).optLong("savedAt") }.getOrDefault(0)
        describe(key, anime, episode, savedAt)
    }
    fun remove(key: String) = synchronized(lock) {
        prefs.edit { prefs.all.keys.filter { it == key || it.startsWith("$key:") }.forEach { remove(it) } }
        index.edit { remove(key); putBoolean("removed:$key", true) }
    }
    fun read(workKey: String): PlaybackCheckpoint? = decode(workKey)
    fun readEpisode(workKey: String, source: String, workId: String, episodeId: String): PlaybackCheckpoint? = decode("$workKey:$source:$workId:$episodeId")
    private fun decode(key: String): PlaybackCheckpoint? = runCatching {
        val root = JSONObject(prefs.getString(key, null) ?: return null)
        val rows = root.optJSONArray("ranges") ?: JSONArray()
        PlaybackCheckpoint(root.getString("source"), root.getString("workId"), root.getString("episodeId"),
            root.optLong("position").coerceAtLeast(0), root.optLong("duration").coerceAtLeast(0),
            (0 until rows.length()).mapNotNull { i -> val row = rows.optJSONArray(i) ?: return@mapNotNull null
                val start = row.optLong(0, -1); val end = row.optLong(1, -1)
                if (start >= 0 && end > start) start..end else null }.take(256), root.optBoolean("handled"))
    }.getOrNull()
    fun save(workKey: String, checkpoint: PlaybackCheckpoint) = synchronized(lock) {
        if (index.getBoolean("removed:$workKey", false)) return@synchronized
        val ranges = JSONArray(); checkpoint.ranges.forEach { ranges.put(JSONArray().put(it.first).put(it.last)) }
        val value = JSONObject().put("source", checkpoint.source).put("workId", checkpoint.workId).put("episodeId", checkpoint.episodeId)
            .put("position", checkpoint.positionMs.coerceAtLeast(0)).put("duration", checkpoint.durationMs.coerceAtLeast(0))
            .put("ranges", ranges).put("handled", checkpoint.handled)
            .put("savedAt", System.currentTimeMillis())
        val expired = if (prefs.all.size >= 500) prefs.all.entries.sortedBy { runCatching { JSONObject(it.value.toString()).optLong("savedAt") }.getOrDefault(0) }
            .take(50).map { it.key } else emptyList()
        prefs.edit {
            expired.forEach { remove(it) }
            putString(workKey, value.toString())
            putString("$workKey:${checkpoint.source}:${checkpoint.workId}:${checkpoint.episodeId}", value.toString())
        }
    }
    internal fun backupRows(animes: Sequence<AnimeEntity>): JSONArray = synchronized(lock) {
        val saved = prefs.all
        val indexed = index.all
        if (saved.isEmpty() && indexed.isEmpty()) return@synchronized JSONArray()
        val rows = animes.mapNotNull { anime ->
            val key = identities.key(anime, persist = false)
            if (indexed["removed:$key"] == true) return@mapNotNull null
            val checkpoint = (saved[key] as? String)?.let(::parseObject)?.validatedCheckpoint()
            val entry = (indexed[key] as? String)?.let(::parseObject)?.takeIf { it.optLong("animeId") == anime.id }
            if (entry == null && checkpoint == null) return@mapNotNull null
            JSONObject().put("animeId", anime.id)
                .put("identity", playbackHistoryKey(anime))
                .put("episode", entry?.optString("episode").orEmpty().take(300))
                .put("updatedAt", entry?.optLong("updatedAt") ?: checkpoint?.optLong("savedAt") ?: 0L)
                .put("checkpoint", checkpoint)
        }.take(500).sortedWith(compareByDescending<JSONObject> { it.optLong("updatedAt") }.thenBy { it.optLong("animeId") })
        JSONArray(rows.toList())
    }

    internal fun restoreRows(
        rows: JSONArray,
        targets: Map<Long, AnimeEntity>,
        registerRollback: (() -> Unit) -> Unit,
    ) = synchronized(lock) {
        val oldPrefs = prefs.all
        val oldIndex = index.all
        val historyUpdates = mutableMapOf<String, String>()
        val indexUpdates = mutableMapOf<String, String>()
        for (i in 0 until minOf(rows.length(), 500)) {
            val row = rows.optJSONObject(i) ?: continue
            val anime = targets[row.optLong("animeId")] ?: continue
            if (anime.deletedAt != null || anime.subjectType != "anime") continue
            val checkpoint = row.optJSONObject("checkpoint")?.validatedCheckpoint()
            if (row.has("checkpoint") && checkpoint == null) continue
            val updatedAt = row.optLong("updatedAt", -1)
            if (updatedAt < 0) continue
            val key = identities.key(anime)
            val local = (historyUpdates[key] ?: oldPrefs[key] as? String)?.let(::parseObject)
            val localEntry = (indexUpdates[key] ?: oldIndex[key] as? String)?.let(::parseObject)
            if (oldIndex["removed:$key"] == true) continue
            if (updatedAt > (localEntry?.optLong("updatedAt") ?: -1)) {
                indexUpdates[key] = historyRow(anime, row.optString("episode").take(300), updatedAt).toString()
            }
            if (checkpoint != null && checkpoint.optLong("savedAt") > (local?.optLong("savedAt") ?: -1)) {
                val source = checkpoint.getString("source")
                val work = checkpoint.getString("workId")
                val episode = checkpoint.getString("episodeId")
                val episodeKey = "$key:$source:$work:$episode"
                val value = checkpoint.toString()
                historyUpdates[key] = value
                val localEpisode = (historyUpdates[episodeKey] ?: oldPrefs[episodeKey] as? String)?.let(::parseObject)
                if (localEpisode == null || localEpisode.optLong("savedAt") < checkpoint.optLong("savedAt")) {
                    historyUpdates[episodeKey] = value
                }
            }
        }
        if (indexUpdates.isEmpty() && historyUpdates.isEmpty()) return@synchronized
        registerRollback {
            synchronized(lock) {
                val indexRestored = index.writeRestoreChanges(indexUpdates, oldIndex)
                val historyRestored = prefs.writeRestoreChanges(historyUpdates, oldPrefs)
                check(indexRestored && historyRestored) { "无法撤回播放历史恢复" }
            }
        }
        check(prefs.writeRestoreChanges(historyUpdates) && index.writeRestoreChanges(indexUpdates)) { "无法保存恢复的播放历史" }
    }

    private companion object {
        val lock = Any()
    }
}

private fun historyRow(anime: AnimeEntity, episode: String, updatedAt: Long): JSONObject =
    JSONObject().put("animeId", anime.id).put("title", anime.title).put("cover", anime.coverUrl.orEmpty())
        .put("episode", episode).put("updatedAt", updatedAt)

private fun parseObject(value: String): JSONObject? = runCatching { JSONObject(value) }.getOrNull()

private fun JSONObject.validatedCheckpoint(): JSONObject? {
    val result = JSONObject()
    for (field in listOf("source", "workId", "episodeId")) {
        val value = (opt(field) as? String)?.takeIf { it.isNotBlank() && it.length <= 1024 } ?: return null
        result.put(field, value)
    }
    for (field in listOf("position", "duration", "savedAt")) result.put(field, optLong(field).coerceAtLeast(0))
    val ranges = optJSONArray("ranges") ?: JSONArray()
    val safeRanges = JSONArray()
    for (i in 0 until minOf(ranges.length(), 256)) {
        val range = ranges.optJSONArray(i) ?: continue
        val start = range.optLong(0, -1)
        val end = range.optLong(1, -1)
        if (start >= 0 && end > start) safeRanges.put(JSONArray(listOf(start, end)))
    }
    return result.put("ranges", safeRanges).put("handled", optBoolean("handled"))
}

private fun android.content.SharedPreferences.writeRestoreChanges(
    updates: Map<String, String>,
    rollbackValues: Map<String, *>? = null,
): Boolean {
    if (updates.isEmpty()) return true
    val editor = edit()
    for ((key, value) in updates) {
        if (rollbackValues == null) editor.putString(key, value)
        else if (getString(key, null) == value) editor.putString(key, rollbackValues[key] as? String)
    }
    return editor.commit()
}

data class PlaybackHistoryEntry(val key: String, val animeId: Long, val title: String, val cover: String,
    val episode: String, val updatedAt: Long, val checkpoint: PlaybackCheckpoint?)
