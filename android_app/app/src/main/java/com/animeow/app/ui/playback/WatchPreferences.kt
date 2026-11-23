package com.animeow.app.ui.playback

import android.content.Context
import android.annotation.SuppressLint
import androidx.core.content.edit
import com.animeow.app.data.community.WatchProgram
import com.animeow.app.data.community.watchProgram
import org.json.JSONObject
import java.security.MessageDigest

internal interface WatchStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
    fun writeAll(values: Map<String, String?>)
    fun draft(key: String, value: String) = write(key, value)
}
internal class WatchPreferences(context: Context) : WatchStorage {
    private val prefs = context.applicationContext.getSharedPreferences("watch_interaction_v1", Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    // The KTX Unit-returning helper would discard commit failure before a network write.
    @SuppressLint("UseKtx")
    override fun write(key: String, value: String?) {
        check(prefs.edit().putString(key, value).commit()) { "本机暂时无法保存互动草稿，请稍后重试" }
    }
    @SuppressLint("UseKtx")
    override fun writeAll(values: Map<String, String?>) {
        val previous = values.keys.associateWith(::read)
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        if (!editor.commit()) {
            // A failed commit has already changed SharedPreferences' in-memory values.
            val rollback = prefs.edit()
            previous.forEach { (key, value) -> rollback.putString(key, value) }
            rollback.commit()
            error("本机暂时无法保存互动关联，请稍后重试")
        }
    }
    override fun draft(key: String, value: String) { prefs.edit { putString(key, value) } }
    fun playbackLink(subject: Int, episode: Int): JSONObject? = prefs.all.entries.asSequence()
        .filter { it.key.startsWith("program-link:") && it.value is String }
        .mapNotNull { entry ->
            val program = watchProgramOrNull(entry.value as String)
            if (program?.subjectId != subject || program.episodeId != episode) return@mapNotNull null
            val key = entry.key.removePrefix("program-link:")
            runCatching { JSONObject(read("link:$key").orEmpty()).put("offset", danmakuSettings(read("settings:$key")).offsetMs) }.getOrNull()
        }.sortedByDescending { it.optLong("updated_at") }.firstOrNull()
}
internal fun watchKey(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun WatchProgram.json() = JSONObject().put("subject_id", subjectId).put("episode_id", episodeId).put("title", title)
    .put("episode_title", episodeTitle).put("episode_type", type).put("episode_number", number)
internal fun watchProgramOrNull(value: String?) = runCatching { JSONObject(value.orEmpty()).watchProgram() }.getOrNull()

internal data class PlaybackStart(val positionMs: Long, val autoplay: Boolean)
internal fun playbackStart(checkpoint: com.animeow.app.data.playback.PlaybackCheckpoint, jump: Long?, foreground: Boolean) = PlaybackStart(
    jump ?: if (com.animeow.app.data.playback.playbackCompleted(checkpoint)) 0 else checkpoint.positionMs,
    jump == null && foreground,
)

internal data class DanmakuSettings(val enabled: Boolean = true, val size: Float = 18f, val opacity: Float = .85f,
    val density: Int = 4, val area: Float = .5f, val offsetMs: Long = 0)
internal fun DanmakuSettings.json() = JSONObject().put("enabled", enabled).put("size", size).put("opacity", opacity)
    .put("density", density).put("area", area).put("offset", offsetMs).toString()
internal fun danmakuSettings(value: String?) = runCatching {
    val j = JSONObject(value.orEmpty())
    DanmakuSettings(j.optBoolean("enabled", true), j.optDouble("size", 18.0).toFloat().coerceIn(12f, 30f),
        j.optDouble("opacity", .85).toFloat().coerceIn(.3f, 1f), j.optInt("density", 4).coerceIn(1, 8),
        j.optDouble("area", .5).toFloat().coerceIn(.25f, .75f), j.optLong("offset").coerceIn(-300000, 300000))
}.getOrDefault(DanmakuSettings())
