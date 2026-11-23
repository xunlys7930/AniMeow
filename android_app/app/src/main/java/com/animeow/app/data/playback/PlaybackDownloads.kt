@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.animeow.app.data.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.animeow.app.R
import com.animeow.app.data.remote.*
import com.animeow.app.ui.playback.PlaybackActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

data class PlaybackDownloadInfo(val animeId: Long, val workKey: String, val title: String, val source: String,
    val work: PlaybackWork, val episode: PlaybackEpisode, val line: String, val lineName: String, val headers: Map<String, String>) {
    fun encode(): ByteArray = JSONObject().put("animeId", animeId).put("key", workKey).put("title", title).put("source", source)
        .put("work", work.id).put("workTitle", work.title).put("episode", episode.id).put("label", episode.label)
        .put("number", episode.number).put("line", line).put("lineName", lineName).put("headers", JSONObject(headers)).toString().toByteArray()
    companion object {
        fun decode(data: ByteArray): PlaybackDownloadInfo? = runCatching {
            val value = JSONObject(data.toString(Charsets.UTF_8)); val headers = value.optJSONObject("headers")
            PlaybackDownloadInfo(value.getLong("animeId"), value.getString("key"), value.getString("title"), value.getString("source"),
                PlaybackWork(value.getString("work"), value.getString("workTitle"), ""),
                PlaybackEpisode(value.getString("episode"), value.getString("label"), value.optInt("number").takeIf { it > 0 }),
                value.getString("line"), value.getString("lineName"),
                listOf("Referer", "User-Agent").mapNotNull { name -> headers?.optString(name)?.takeIf { it.isNotBlank() }?.let { name to it } }.toMap())
        }.getOrNull()
    }
}

internal fun playbackDownloadId(workKey: String, source: String, work: String, episode: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(listOf(workKey, source, work, episode).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

data class PlaybackDownloadRow(val id: String, val info: PlaybackDownloadInfo, val state: Int, val bytes: Long,
    val total: Long, val percent: Float, val status: String, val bytesPerSecond: Long = 0)

internal class PlaybackDownloads private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("playback_download_preferences", Context.MODE_PRIVATE)
    private val database = StandaloneDatabaseProvider(context)
    private val directory = File(context.noBackupFilesDir, "playback_downloads")
    val cache = SimpleCache(directory, NoOpCacheEvictor(), database)
    private val buffer = SimpleCache(File(context.cacheDir, "playback_buffer"), LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024), database)
    private val executor = Executors.newFixedThreadPool(2)
    val manager = DownloadManager(context, DefaultDownloadIndex(database), DownloaderFactory { request ->
        val headers = PlaybackDownloadInfo.decode(request.data)?.headers.orEmpty()
        val factory = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory { spec -> "${request.id}:${CacheKeyFactory.DEFAULT.buildCacheKey(spec)}" }
            .setUpstreamDataSourceFactory(spaceCheckedHttp(headers))
        val delegate = DefaultDownloaderFactory(factory, executor).createDownloader(request)
        if (request.mimeType in listOf("application/x-mpegURL", "application/vnd.apple.mpegurl"))
            VerifiedHlsDownloader(delegate, http(headers), request.uri) else delegate
    }).apply {
        maxParallelDownloads = 2
        requirements = Requirements(Requirements.NETWORK or if (wifiOnly) Requirements.NETWORK_UNMETERED else 0)
        addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                if (finalException != null) prefs.edit { putString("error:${download.request.id}",
                    when {
                        generateSequence<Throwable>(finalException) { it.cause }.any { it.message?.contains("ANIMEOW_SPACE") == true } -> "空间不足，请释放空间后重试"
                        generateSequence<Throwable>(finalException) { it.cause }.any { it.message?.contains("ANIMEOW_HLS") == true } -> "此来源为直播、加密或不支持的播放列表，暂不支持离线缓存"
                        else -> "下载失败，可重新解析后重试"
                    }) }
                else if (download.state == Download.STATE_COMPLETED) prefs.edit { remove("error:${download.request.id}") }
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                prefs.edit { remove("error:${download.request.id}") }
            }
        })
    }
    val wifiOnly: Boolean get() = prefs.getBoolean("wifiOnly", true)
    val availableBytes: Long get() = StatFs(context.noBackupFilesDir.absolutePath).availableBytes
    val bufferedBytes: Long get() = buffer.cacheSpace
    fun failure(id: String): String = prefs.getString("error:$id", null) ?: "下载失败，可重试或更换线路"
    private fun http(headers: Map<String, String>) = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000)
        .setReadTimeoutMs(20000).setDefaultRequestProperties(headers)
    private fun spaceCheckedHttp(headers: Map<String, String>) = DataSource.Factory {
        val upstream = http(headers).createDataSource()
        object : DataSource by upstream {
            private var checked = 0L
            override fun open(dataSpec: DataSpec): Long {
                if (availableBytes < 64L * 1024 * 1024) throw IOException("ANIMEOW_SPACE")
                return upstream.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (checked >= 4L * 1024 * 1024) {
                    checked = 0
                    if (availableBytes < 64L * 1024 * 1024) throw IOException("ANIMEOW_SPACE")
                }
                return upstream.read(buffer, offset, length).also { if (it > 0) checked += it }
            }
        }
    }
    fun playbackFactory(headers: Map<String, String>, offline: Boolean, downloadId: String? = null): DataSource.Factory {
        val streaming = CacheDataSource.Factory().setCache(buffer).setUpstreamDataSourceFactory(http(headers))
        return CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory { spec -> "${downloadId.orEmpty()}:${CacheKeyFactory.DEFAULT.buildCacheKey(spec)}" }
            .setUpstreamDataSourceFactory(if (offline) null else streaming)
    }
    suspend fun list(): List<Download> = withContext(Dispatchers.IO) {
        manager.downloadIndex.getDownloads().use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.download) } }
    }
    suspend fun find(id: String): Download? = withContext(Dispatchers.IO) { manager.downloadIndex.getDownload(id) }
    suspend fun enqueue(info: PlaybackDownloadInfo, stream: PlaybackStream) {
        check(stream.mimeType in listOf("video/mp4", "application/x-mpegURL", "application/vnd.apple.mpegurl")) { "此视频格式暂不支持缓存" }
        check(availableBytes >= 100L * 1024 * 1024) { "可用空间不足 100 MB，请释放空间后再缓存" }
        val id = playbackDownloadId(info.workKey, info.source, info.work.id, info.episode.id)
        val old = find(id)
        if (old?.state == Download.STATE_COMPLETED) return
        // Expiring URLs can identify different bytes. Remove an old failed request before replacing its URL.
        if (old != null && old.request.uri.toString() != stream.url) {
            remove(id)
            var remaining = 100
            while (find(id) != null && remaining-- > 0) delay(50)
            check(find(id) == null) { "旧任务正在清理，请稍后重试" }
        }
        val request = DownloadRequest.Builder(id, Uri.parse(stream.url)).setMimeType(stream.mimeType).setData(info.encode()).build()
        DownloadService.sendAddDownload(context, PlaybackDownloadService::class.java, request, false)
        DownloadService.sendResumeDownloads(context, PlaybackDownloadService::class.java, false)
    }
    fun pause(id: String) = DownloadService.sendSetStopReason(context, PlaybackDownloadService::class.java, id, 1, false)
    fun resume(id: String) {
        DownloadService.sendSetStopReason(context, PlaybackDownloadService::class.java, id, 0, false)
        DownloadService.sendResumeDownloads(context, PlaybackDownloadService::class.java, false)
    }
    fun remove(id: String) = DownloadService.sendRemoveDownload(context, PlaybackDownloadService::class.java, id, false)
    fun wifiOnly(value: Boolean) {
        prefs.edit { putBoolean("wifiOnly", value) }
        DownloadService.sendSetRequirements(context, PlaybackDownloadService::class.java,
            Requirements(Requirements.NETWORK or if (value) Requirements.NETWORK_UNMETERED else 0), false)
    }
    suspend fun clearBuffer() = withContext(Dispatchers.IO) { buffer.keys.toList().forEach { buffer.removeResource(it) } }
    companion object {
        @Volatile private var instance: PlaybackDownloads? = null
        fun get(context: Context): PlaybackDownloads = instance ?: synchronized(this) {
            instance ?: PlaybackDownloads(context.applicationContext).also { instance = it }
        }
    }
}

class PlaybackDownloadService : DownloadService(220045, 1000L, "playback_downloads", R.string.playback_download_channel, 0) {
    override fun getDownloadManager(): DownloadManager = PlaybackDownloads.get(this).manager
    override fun getScheduler(): Scheduler? = PlatformScheduler(this, 220046)
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val info = downloads.firstNotNullOfOrNull { PlaybackDownloadInfo.decode(it.request.data) }
        val intent = info?.let { PendingIntent.getActivity(this, 0, PlaybackActivity.intent(this, it.animeId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return DownloadNotificationHelper(this, "playback_downloads").buildProgressNotification(this,
            android.R.drawable.stat_sys_download, intent, "动画缓存 · ${if (notMetRequirements != 0) "等待符合设置的网络" else "下载中"}", downloads, notMetRequirements)
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        // A persisted stop reason prevents a replacement service from immediately resuming timed-out work.
        downloadManager.setStopReason(null, 2)
        downloadManager.pauseDownloads()
        super.onTimeout(startId, fgsType)
    }
}
