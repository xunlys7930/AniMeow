package com.animeow.app.data.analysis

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.cloud.CloudAccountService
import com.animeow.app.data.importer.LegacyBackupImporter
import com.animeow.app.data.local.AniMeowDatabase
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
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
class CustomModelConnectionTest {
    @Test fun fetchesModelsAndTestsActualChatWithoutCreatingAnalysisRecord() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        val service = AnimeAnalysisService(database, CloudAccountService(context, NativeBackupService(context, database), LegacyBackupImporter(context, database)))
        val requests = mutableListOf<Pair<String, String>>()
        ServerSocket(0, 3, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val responder = thread(isDaemon = true) {
                listOf(
                    200 to """{"data":[{"id":"z"},{"id":"a"},{"id":"z"},{}]}""",
                    200 to """{"choices":[{"message":{"content":"OK"}}]}""",
                    401 to """{"error":{"message":"invalid key"}}""",
                ).forEach { (status, json) ->
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream().buffered()
                        fun readLine(): String = buildString {
                            while (true) {
                                val next = input.read()
                                if (next == -1 || next == 10) break
                                if (next != 13) append(next.toChar())
                            }
                        }
                        val line = readLine()
                        var length = 0
                        var authorization = ""
                        while (true) {
                            val header = readLine().orEmpty()
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            if (header.startsWith("Authorization:", true)) authorization = header.substringAfter(':').trim()
                        }
                        val body = input.readNBytes(length)
                        requests += line to String(body, Charsets.UTF_8)
                        assertEquals("Bearer test-key", authorization)
                        val bytes = json.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
            try {
                val base = "http://127.0.0.1:${server.localPort}/v1"
                assertEquals(listOf("a", "z"), service.fetchCustomModels("$base/chat/completions", "test-key"))
                assertTrue(service.testCustomConnection(AnimeAnalysisSettings(endpoint = base, model = "a"), "test-key") >= 0)
                val failure = runCatching { service.fetchCustomModels(base, "test-key") }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("401"))
                assertFalse(failure?.message.orEmpty().contains("test-key"))
                responder.join(10_000)
                assertEquals("GET /v1/models HTTP/1.1", requests[0].first)
                assertEquals("POST /v1/chat/completions HTTP/1.1", requests[1].first)
                val chat = JSONObject(requests[1].second)
                assertEquals("a", chat.getString("model"))
                assertEquals("请只回复 OK", chat.getJSONArray("messages").getJSONObject(0).getString("content"))
                assertFalse(chat.toString().contains("stats"))
                assertTrue(database.libraryDao().observeAnimeAnalysisRecords().first().isEmpty())
            } finally {
                database.close()
            }
        }
    }

    @Test fun forgettingKeyOnlyRemovesMatchingServiceAndClearRemovesAll() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("custom_model_secure", Context.MODE_PRIVATE)
        preferences.edit().putString("https://one.test/v1", "encrypted").putString("https://two.test/v1", "encrypted").commit()
        val store = CustomModelKeyStore(context)
        store.save("https://one.test/v1/chat/completions", "temporary", false)
        assertEquals("", store.load("https://one.test/v1"))
        assertTrue(preferences.contains("https://two.test/v1"))
        assertThrows(IllegalArgumentException::class.java) { store.save("https://two.test/v1", "", true) }
        assertTrue(preferences.contains("https://two.test/v1"))
        store.clear()
        assertTrue(preferences.all.isEmpty())
    }
}
