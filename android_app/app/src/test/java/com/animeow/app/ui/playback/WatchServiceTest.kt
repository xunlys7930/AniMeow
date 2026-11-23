package com.animeow.app.ui.playback

import android.app.Application
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class WatchServiceTest {
    @Test fun publicationKeepsTheRequestIdAndOnlySendsExplicitFieldsToTheSameServer() = runBlocking {
        fixture { service, exchange ->
            val request = WatchSubmission(watchTestProgram, "comment", "合成公开评论", UUID.randomUUID().toString())
            val row = service.create(request, CloudSession(1, "synthetic", "synthetic-token"))
            assertEquals(request.clientId, exchange.body?.getString("client_id"))
            assertFalse(exchange.body!!.has("time_ms")); assertFalse(exchange.body!!.has("parent_id"))
            assertEquals("Bearer synthetic-token", exchange.authorization)
            assertNull(row.timeMs); assertEquals("1", row.id)
        }
    }
    @Test fun capabilitiesAndExternalZeroTimestampAreParsedWithoutInventingOwnDeletePermissions() = runBlocking {
        fixture { service, _ ->
            assertTrue(service.capabilities(null).admin)
            val external = service.externalComments(55, 0, null)
            assertTrue(external.truncated); assertEquals(0L, external.items.single().timeMs)
            assertTrue(external.items.single().external); assertFalse(external.items.single().canDelete)
        }
    }
    @Test fun aModerationRefusalUsesTheRoleErrorWithoutTreatingItAsExpiredLogin() = runBlocking {
        fixture { service, _ ->
            try { service.reviews(CloudSession(1, "synthetic", "synthetic-token")); fail("expected role refusal") }
            catch (expected: CommunityManagementForbiddenException) { assertNull(service.session()) }
        }
    }
    private data class Captured(@Volatile var body: JSONObject? = null, @Volatile var authorization: String? = null)
    private suspend fun fixture(run: suspend (WatchService, Captured) -> Unit) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val captured = Captured()
        val worker = thread(isDaemon = true, name = "synthetic-watch-http") {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun line(): String = buildString {
                        while (true) {
                            val next = input.read()
                            if (next < 0 || next == 10) break
                            if (next != 13) append(next.toChar())
                        }
                    }
                    val path = line().split(' ')[1]
                    val headers = generateSequence { line().takeIf(String::isNotEmpty) }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    captured.authorization = headers["authorization"]
                    val request = input.readNBytes(headers["content-length"]?.toInt() ?: 0).toString(Charsets.UTF_8)
                    if (request.isNotEmpty()) captured.body = JSONObject(request)
                    val status = if (path.endsWith("/reviews")) "403 Forbidden" else "200 OK"
                    val body = when {
                        path.endsWith("/reviews") -> """{"status":"error","message":"需要社区管理员权限"}"""
                        path.endsWith("/capabilities") -> """{"status":"success","data":{"interaction":1,"share":1,"external":true,"is_admin":true}}"""
                        path.contains("/external/comments/") -> """{"status":"success","data":{"items":[{"id":"dandan:77","content":"合成外部弹幕","time_ms":0}],"truncated":true}}"""
                        else -> """{"status":"success","data":{"id":1,"subject_id":123,"episode_id":456,"kind":"comment","content":"合成公开评论","time_ms":null}}"""
                    }.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }
        try {
            val context = RuntimeEnvironment.getApplication()
            val base = "http://127.0.0.1:${server.localPort}"
            run(WatchService(context, CommunityService(context, apiBase = base)), captured)
        } finally { server.close(); worker.join(2000) }
    }
}
