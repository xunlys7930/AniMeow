package com.animeow.app.data.remote

import android.app.Application
import com.animeow.app.data.audio.AudioWork
import com.animeow.app.data.audio.applyAudioMetadata
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class MediaCatalogServiceTest {
    @Test fun completionMetadataPreservesPersonalRecordsAndUserEnteredFieldsByDefault() {
        val personal = AudioWork(title = "我的标题", review = "我的感想", rating = 8, status = "在听", listenedSeconds = 600)
        val metadata = AudioMetadata("来源标题", "https://cdn.test/a.jpg", "声优", "社团", "2026-10-18", "RJ01570739", "https://www.dlsite.com/", "ASMR", "ASMR")
        val filled = applyAudioMetadata(personal, metadata, false)
        assertEquals("我的标题", filled.title); assertEquals("声优", filled.voiceActors); assertEquals(personal.review, filled.review)
        val replaced = applyAudioMetadata(personal, metadata, true)
        assertEquals("来源标题", replaced.title); assertEquals(8, replaced.rating); assertEquals(600L, replaced.listenedSeconds); assertEquals("在听", replaced.status)
        assertEquals(personal.syncId, replaced.syncId)
    }
    @Test fun rejectsPrivateCleartextAndCredentialedVideoAddresses() {
        assertTrue(validPlaybackUrl("https://cdn.test/video.mp4"))
        for (url in listOf("http://cdn.test/a.mp4", "https://127.0.0.1/a", "https://localhost/a", "https://[::1]/a", "https://secret.internal/a", "https://name:password@cdn.test/a", "https://cdn.test:8443/a")) assertFalse(url, validPlaybackUrl(url))
    }
    @Test fun sourcesRequestUsesThePublicEndpointWithoutAccountCredentials() = fixture(200,
        """{"status":"success","data":[{"id":"giri","name":"giriGiriLove"},{"id":"seven","name":"7sefun"}]}""") { service, request ->
        assertEquals(2, service.sources().size)
        assertTrue(request().startsWith("GET /api/playback/sources "))
        assertFalse(request().contains("Authorization:", true))
    }
    @Test fun oldServerAndMalformedErrorsHaveFriendlyMessages() {
        fixture(404, "<html>old server</html>") { service, _ ->
            try { service.sources(); fail() } catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("服务器尚未开通")) }
        }
        fixture(400, "<html>unexpected private error</html>") { service, _ ->
            try { service.audio("RJ01570739"); fail() } catch (failure: IllegalStateException) { assertEquals("请检查作品编号或链接", failure.message) }
        }
    }
    @Test fun videoRequestsOnlyRetainMediaHeaders() = fixture(200,
        """{"status":"success","data":{"url":"https://cdn.test/a.mp4","mimeType":"video/mp4","headers":{"Referer":"https://source.test/","User-Agent":"AniMeow","Authorization":"secret","Cookie":"private"}}}""") { service, request ->
        val stream = service.resolve("giri", "123", "1-1")
        assertEquals(setOf("Referer", "User-Agent"), stream.headers.keys)
        assertTrue(request().contains("episodeId=1-1"))
    }
    private fun fixture(status: Int, response: String, run: suspend (MediaCatalogService, () -> String) -> Unit) = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var request = ""
        val worker = thread(isDaemon = true) {
            runCatching { server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                val rows = mutableListOf<String>()
                while (true) { val row = reader.readLine() ?: break; if (row.isBlank()) break; rows += row }
                request = rows.joinToString("\n")
                val body = response.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply { write("HTTP/1.1 $status Fixture\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
            } }
        }
        try { run(MediaCatalogService(baseUrl = "http://127.0.0.1:${server.localPort}")) { request } }
        finally { server.close(); worker.join(2000) }
    }
}
