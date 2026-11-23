package com.animeow.app.data.playback

import android.content.Context
import androidx.core.content.edit
import com.animeow.app.data.remote.PlaybackEpisode
import com.animeow.app.data.remote.PlaybackLine

enum class WatchingLayout(val title: String, val description: String) {
    TABBED("分区观看台", "视频下分区显示选集与下载"),
    COMPACT("紧凑选集", "视频下直接显示集数网格"),
    CONTINUE("接续观看", "突出下一集与接续列表")
}
data class PlaybackSettings(val speed: Float = 1f, val skipSeconds: Int = 10, val autoNext: Boolean = false,
    val layout: WatchingLayout = WatchingLayout.TABBED, val crop: Boolean = false, val autoLock: Boolean = false,
    val upscale: PlaybackUpscale = PlaybackUpscale.OFF, val aiQuality: PlaybackAiQuality = PlaybackAiQuality.EFFICIENT,
    val autoMarkProgress: Boolean = true)

enum class PlaybackAiQuality(val title: String, val model: String, val description: String) {
    EFFICIENT("效率", "S", "轻量修复并放大画质，兼顾流畅与省电"),
    QUALITY("质量", "M", "精细修复并放大画质，效果更好但更耗电；卡顿时请切回效率")
}
enum class PlaybackUpscale(val title: String, val description: String) {
    OFF("关闭 · 原画", "按原始视频画质播放"),
    SPATIAL("清晰锐化放大", "平滑锐化放大画面，低耗电，适合日常观看"),
    AI("AI 超清放大", "动漫线条修复与 2× 放大（1080p 及以下），可能增加耗电与发热")
}
val playbackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
val playbackSkipSeconds = listOf(5, 10, 15, 30, 60)

internal class PlaybackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("playback_preferences_v1", Context.MODE_PRIVATE)
    fun read() = PlaybackSettings(
        prefs.getFloat("speed", 1f).takeIf { it in playbackSpeeds } ?: 1f,
        prefs.getInt("skip", 10).takeIf { it in playbackSkipSeconds } ?: 10,
        prefs.getBoolean("autoNext", false),
        WatchingLayout.entries.firstOrNull { it.name == prefs.getString("layout", null) } ?: WatchingLayout.TABBED,
        prefs.getBoolean("crop", false), prefs.getBoolean("autoLock", false),
        PlaybackUpscale.entries.firstOrNull { it.name == prefs.getString("upscale", null) } ?: PlaybackUpscale.OFF,
        PlaybackAiQuality.entries.firstOrNull { it.name == prefs.getString("aiQuality", null) } ?: PlaybackAiQuality.EFFICIENT,
        prefs.getBoolean("autoMarkProgress", true))
    fun save(value: PlaybackSettings) {
        require(value.speed in playbackSpeeds && value.skipSeconds in playbackSkipSeconds)
        prefs.edit {
            putFloat("speed", value.speed); putInt("skip", value.skipSeconds); putBoolean("autoNext", value.autoNext)
            putString("layout", value.layout.name); putBoolean("crop", value.crop); putBoolean("autoLock", value.autoLock)
            putString("upscale", value.upscale.name)
            putString("aiQuality", value.aiQuality.name)
            putBoolean("autoMarkProgress", value.autoMarkProgress)
        }
    }
    fun preferredSource(workKey: String): String? = prefs.getString("source:$workKey", null)
    fun played(workKey: String, source: String) {
        prefs.edit {
            if (!prefs.contains("source:$workKey") && prefs.all.keys.count { it.startsWith("source:") } >= 500) {
                prefs.all.filterKeys { it.startsWith("used:") }.minByOrNull { (it.value as? Long) ?: 0L }?.key?.removePrefix("used:")?.let {
                    remove("source:$it"); remove("used:$it")
                }
            }
            putString("source:$workKey", source); putLong("used:$workKey", System.currentTimeMillis())
        }
    }
}

internal fun nextPlaybackEpisode(current: PlaybackEpisode, lines: List<PlaybackLine>): PlaybackEpisode? {
    val number = current.number ?: return null
    val episodes = lines.firstOrNull { line -> line.episodes.any { it.id == current.id } }?.episodes ?: return null
    return episodes.getOrNull(episodes.indexOfFirst { it.id == current.id } + 1)?.takeIf { it.number == number + 1 }
}

internal fun playbackSeekPosition(position: Long, duration: Long, delta: Long): Long =
    (position.coerceAtLeast(0) + delta).coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
