package com.animeow.app.data.cloud

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.importer.LegacyBackupImporter
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.playback.PlaybackCheckpoint
import com.animeow.app.data.playback.PlaybackHistory
import com.animeow.app.data.playback.PlaybackIdentities
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class CloudPlaybackSyncTest {
    @Test fun playbackOnlyChangeUploadsAndRestoresWithZipChecksumsInCloudMetadata() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).build()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val archives = ConcurrentHashMap<Long, Archive>()
        val responder = thread(isDaemon = true) { serveBackups(server, archives) }
        try {
            val native = NativeBackupService(context, database)
            val service = CloudAccountService(context, native, LegacyBackupImporter(context, database),
                apiBase = "http://127.0.0.1:${server.localPort}")
            val session = CloudSession(1, "local-test", "local-only-token")
            val preferences = CloudSyncPreferences(context)
            val coordinator = CloudSyncCoordinator(service, preferences)
            val history = PlaybackHistory(context)
            val anime = AnimeEntity(id = 651, title = "Cloud playback fixture", createdAt = "2026-11-20T10:00:00Z")
            database.libraryDao().insertAnime(anime)

            val initial = coordinator.synchronize(session)
            assertEquals(CloudSyncResultType.UPLOADED, initial.type)
            val initialBackup = requireNotNull(initial.remoteBackup)
            val zipHash = requireNotNull(initialBackup.contentHash)
            val logicalFingerprint = initial.localSummary.dataFingerprint
            assertNotEquals(logicalFingerprint, zipHash)
            assertEquals(CloudSyncResultType.NO_CHANGE, coordinator.synchronize(session).type)

            // Simulate a previous client having stored the ZIP checksum as its sync baseline.
            preferences.markSynced(zipHash, initialBackup.id, "old baseline", Instant.now().toString())
            val workKey = PlaybackIdentities(context).key(anime)
            val checkpoint = PlaybackCheckpoint("test", "work", "ep2", 120_000, 900_000)
            history.describe(workKey, anime, "第 2 集")
            history.save(workKey, checkpoint)
            val uploaded = coordinator.synchronize(session)
            assertEquals(CloudSyncResultType.UPLOADED, uploaded.type)
            assertNotEquals(logicalFingerprint, uploaded.localSummary.dataFingerprint)
            assertEquals(2, archives.size)

            // The receiving device has the original collection, with the same older sync baseline.
            for (name in listOf("playback_history_v1", "playback_history_index_v1")) {
                assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
            }
            preferences.markSynced(zipHash, initialBackup.id, "old baseline", Instant.now().toString())
            assertEquals(CloudSyncResultType.RESTORED, coordinator.synchronize(session).type)
            val restored = history.entries().single()
            assertEquals(anime.id, restored.animeId)
            assertEquals("第 2 集", restored.episode)
            assertEquals(checkpoint, restored.checkpoint)
            assertEquals(CloudSyncResultType.NO_CHANGE, coordinator.synchronize(session).type)
            assertEquals(2, archives.size)
        } finally {
            server.close()
            responder.join(5_000)
            database.close()
        }
    }

    private fun serveBackups(server: ServerSocket, archives: MutableMap<Long, Archive>) {
        while (!server.isClosed) {
            val connection = try {
                server.accept()
            } catch (error: SocketException) {
                if (server.isClosed) return
                throw error
            }
            connection.use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val (method, path) = reader.readLine().split(' ')
                val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }
                val length = headers.toList().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toInt() ?: 0
                val response = when {
                    method == "POST" -> {
                        // The fixture request contains only ASCII metadata and Base64 ZIP bytes.
                        val request = CharArray(length)
                        var offset = 0
                        while (offset < length) {
                            val read = reader.read(request, offset, length - offset)
                            check(read > 0)
                            offset += read
                        }
                        val body = JSONObject(String(request))
                        val zip = Base64.decode(body.getString("backup_base64"), Base64.DEFAULT)
                        val archive = Archive(archives.size.toLong() + 1, body.getString("file_name"), zip)
                        archives[archive.id] = archive
                        jsonResponse(archive.metadata())
                    }
                    path.endsWith("/download") -> {
                        val id = path.substringBeforeLast('/').substringAfterLast('/').toLong()
                        archives.getValue(id).zip
                    }
                    else -> jsonResponse(JSONArray(archives.values.map(Archive::metadata)))
                }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(response)
                    flush()
                }
            }
        }
    }

    private fun jsonResponse(data: Any): ByteArray =
        JSONObject().put("status", "success").put("data", data).toString().toByteArray()

    private class Archive(val id: Long, private val fileName: String, val zip: ByteArray) {
        private val checksum = MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }

        fun metadata(): JSONObject = JSONObject().put("id", id).put("file_name", fileName)
            .put("payload_size", zip.size).put("content_hash", checksum)
    }
}
