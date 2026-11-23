@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.animeow.app.data.playback

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import com.animeow.app.data.remote.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class PlaybackDownloadsTest {
    @Test fun explicitDownloadsSurviveReopenAndAreReadableWithoutServerWhileMissingCacheNeverFallsBackToNetwork() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowStatFs.registerStats(context.noBackupFilesDir.absolutePath, 1_000_000, 800_000, 800_000)
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val count = AtomicInteger()
        val mp4 = ByteArray(32 * 1024) { (it % 251).toByte() }
        val segment = ByteArray(4096) { (it % 127).toByte() }
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader(); val path = reader.readLine().split(' ')[1]
                while (!reader.readLine().isNullOrBlank()) { }
                count.incrementAndGet()
                val bytes = when (path) { "/video.mp4" -> mp4; "/playlist.m3u8" -> manifest
                    "/live.m3u8" -> manifest.toString(Charsets.UTF_8).replace("#EXT-X-ENDLIST\n", "").toByteArray()
                    else -> segment }
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            } }
        }
        fun create(): PlaybackDownloads = PlaybackDownloads::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        var store = create()
        fun close() {
            store.manager.release(); store.cache.release()
            PlaybackDownloads::class.java.getDeclaredField("buffer").apply { isAccessible = true }.get(store).let { (it as androidx.media3.datasource.cache.SimpleCache).release() }
            PlaybackDownloads::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(store).let { (it as java.util.concurrent.ExecutorService).shutdownNow() }
            PlaybackDownloads::class.java.getDeclaredField("database").apply { isAccessible = true }.get(store).let { (it as androidx.media3.database.StandaloneDatabaseProvider).close() }
        }
        val base = "http://127.0.0.1:${server.localPort}"
        val info = PlaybackDownloadInfo(7, "stable-work-key", "合成测试", "source", PlaybackWork("work", "季度", ""), PlaybackEpisode("1", "第 1 集", 1), "line", "线路 1", mapOf("Referer" to "https://example.test/"))
        try {
            assertEquals(info, PlaybackDownloadInfo.decode(info.encode()))
            store.manager.requirements = Requirements(0); store.manager.resumeDownloads()
            val requests = listOf("mp4" to "video/mp4", "hls" to "application/x-mpegURL").map { (id, mime) ->
                DownloadRequest.Builder(id, android.net.Uri.parse("$base/${if (id == "mp4") "video.mp4" else "playlist.m3u8"}"))
                    .setMimeType(mime).setData(info.copy(episode = info.episode.copy(id = id)).encode()).build()
            }
            requests.forEach { store.manager.addDownload(it, 1) }
            waitUntil { runBlocking { store.list().count { it.state == Download.STATE_STOPPED } == 2 } }
            assertEquals(0, count.get())
            store.manager.setStopReason("mp4", 0)
            waitUntil { runBlocking { store.find("mp4")?.state == Download.STATE_COMPLETED } }
            assertEquals(Download.STATE_STOPPED, store.find("hls")?.state)
            store.manager.setStopReason("hls", 0)
            waitUntil { runBlocking {
                val values = store.list()
                assertFalse(values.joinToString { "${it.request.id}:${it.state}:${store.failure(it.request.id)}" }, values.any { it.state == Download.STATE_FAILED })
                values.count { it.state == Download.STATE_COMPLETED } == 2
            } }
            assertTrue(count.get() >= 3)
            store.manager.minRetryCount = 0
            store.manager.addDownload(DownloadRequest.Builder("live", android.net.Uri.parse("$base/live.m3u8"))
                .setMimeType("application/x-mpegURL").setData(info.encode()).build())
            // The index becomes FAILED before the main-thread listener saves the explanation.
            waitUntil { runBlocking { store.find("live")?.state == Download.STATE_FAILED } && store.failure("live").contains("暂不支持") }
            store.manager.removeDownload("live")
            waitUntil { runBlocking { store.find("live") == null } }
            close()
            server.close(); worker.join(2000)
            store = create()
            waitUntil { store.manager.isInitialized }
            assertEquals(2, store.list().count { it.state == Download.STATE_COMPLETED })
            val before = count.get()
            assertArrayEquals(mp4, read(store.playbackFactory(emptyMap(), true, "mp4"), "$base/video.mp4"))
            assertArrayEquals(segment.copyOfRange(50, 200), read(store.playbackFactory(emptyMap(), true, "hls"), "$base/segment.ts", 50, 150))
            assertArrayEquals(manifest, read(store.playbackFactory(emptyMap(), true, "hls"), "$base/playlist.m3u8"))
            assertEquals(before, count.get())
            try { read(store.playbackFactory(emptyMap(), true), "$base/missing.mp4"); fail("Offline reads must not reach HTTP") }
            catch (_: java.io.IOException) { }
            assertEquals(before, count.get())
            store.clearBuffer()
            assertArrayEquals(mp4, read(store.playbackFactory(emptyMap(), true, "mp4"), "$base/video.mp4"))
            assertEquals(2, store.list().size)
            store.manager.removeDownload("mp4")
            waitUntil { runBlocking { store.find("mp4") == null } }
            assertArrayEquals(segment, read(store.playbackFactory(emptyMap(), true, "hls"), "$base/segment.ts"))
        } finally {
            server.close(); worker.join(2000); close()
        }
    }
    @Test fun downloadIdentitySeparatesWorksSourcesAndEpisodesAndAssociationPreservesSpecials() {
        assertNotEquals(playbackDownloadId("a", "bc", "d", "e"), playbackDownloadId("ab", "c", "d", "e"))
        assertNotEquals(playbackDownloadId("a", "s", "w", "1"), playbackDownloadId("a", "s", "w", "2"))
        val context = RuntimeEnvironment.getApplication()
        val store = PlaybackAssociations(context)
        val association = PlaybackAssociation("giri", PlaybackWork("3", "标题", "https://ani.girigirilove.com/cover.webp"), "second",
            listOf(PlaybackLine("second", "线路", listOf(PlaybackEpisode("sp", "SP01", null), PlaybackEpisode("1", "第 1 集", 1)))))
        store.save("key", association)
        assertEquals(association, PlaybackAssociations(context).read("key"))
        val anime = com.animeow.app.data.local.AnimeEntity(id = 71, title = "旧标题", createdAt = "test-created")
        val identities = PlaybackIdentities(context)
        val old = identities.key(anime)
        assertEquals(playbackHistoryKey(anime), old)
        assertEquals(old, PlaybackIdentities(context).key(anime.copy(title = "更新后的标题", externalId = "new-metadata-id")))
    }
    private fun read(factory: DataSource.Factory, url: String, position: Long = 0, length: Long = -1): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(url).setPosition(position).setLength(length).build())
            val output = ByteArrayOutputStream(); val buffer = ByteArray(1024)
            while (true) { val count = source.read(buffer, 0, buffer.size); if (count == -1) break; output.write(buffer, 0, count) }
            return output.toByteArray()
        } finally { source.close() }
    }
    private fun waitUntil(condition: () -> Boolean) {
        repeat(1000) { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(10) }
        fail("Download transport did not settle")
    }
}
