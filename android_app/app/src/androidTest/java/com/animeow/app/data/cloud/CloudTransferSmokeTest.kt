package com.animeow.app.data.cloud

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.backup.NativeBackupSummary
import com.animeow.app.data.importer.LegacyBackupImporter
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.testing.LocalHttpServer
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class CloudTransferSmokeTest {
    private val baseContext = ApplicationProvider.getApplicationContext<Context>()
    private val privateFiles = File(baseContext.filesDir, "cloud-fixture-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = privateFiles
    }
    private val directory = File(context.cacheDir, "cloud-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val database = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).build()
    private val native = NativeBackupService(context, database)
    private val legacy = LegacyBackupImporter(context, database)
    private val session = CloudSession(1, "local-test", "local-only-token")

    @Before
    fun requireLocalHttpFixture() {
        assumeTrue("Debug networking must allow loopback HTTP", NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
    }

    @After
    fun cleanup() {
        database.close()
        directory.deleteRecursively()
        privateFiles.deleteRecursively()
    }

    @Test
    fun eighteenMegabyteUploadAndRestorePreviewKeepMainResponsive() = runBlocking {
        val cover = File(privateFiles, "covers/fixture-cover.jpg").apply { parentFile!!.mkdirs() }
        cover.outputStream().use { output ->
            val random = Random(7)
            val block = ByteArray(64 * 1024)
            repeat(18 * 16) { random.nextBytes(block); output.write(block) }
        }
        database.libraryDao().insertAnime(AnimeEntity(title = "本地备份回归", coverUrl = Uri.fromFile(cover).toString()))
        val archive = File(directory, "fixture.animeow.zip")
        LocalHttpServer {
            """{"status":"success","data":{"id":987654321,"file_name":"fixture.animeow.zip"}}""".toByteArray()
        }.use { server ->
            val service = CloudAccountService(context, native, legacy, apiBase = server.baseUrl)
            val prepared = onResponsiveMain("prepare-upload") { service.prepareUpload() }
            assertEquals(1, prepared.summary.coverCount)
            assertTrue(prepared.file.length() >= 18L * 1024 * 1024)
            prepared.file.copyTo(archive)
            val expectedHash = archive.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val block = ByteArray(64 * 1024)
                while (true) { val read = input.read(block); if (read < 0) break; digest.update(block, 0, read) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            val result = onResponsiveMain("upload") { service.uploadPrepared(session, prepared) }
            assertEquals(987654321L, result.id)
            assertFalse(prepared.file.exists())
            val request = server.request.get(10, TimeUnit.SECONDS)
            assertEquals("/api/user/backups", request.path)
            val body = JSONObject(request.body.toString(Charsets.UTF_8))
            assertEquals(expectedHash, body.getString("content_hash"))
            assertEquals(archive.length(), Base64.decode(body.getString("backup_base64"), Base64.DEFAULT).size.toLong())
        }
        LocalHttpServer { archive.readBytes() }.use { server ->
            val service = CloudAccountService(context, native, legacy, apiBase = server.baseUrl)
            val backup = CloudBackupInfo(987654321, archive.name, archive.length(), "", "")
            val restored = onResponsiveMain("restore-preview") { service.prepareRestore(session, backup) }
            try {
                assertEquals(CloudBackupFormat.NATIVE, restored.format)
                assertEquals(1, restored.preview.animeCount)
                assertEquals(1, restored.preview.coverCount)
                assertTrue(File(context.cacheDir, "shared/cloud_restore").listFiles().orEmpty()
                    .none { it.name.startsWith("restore_987654321_") })
            } finally {
                withContext(Dispatchers.IO) { service.discard(restored) }
            }
        }
    }

    @Test
    fun cancellationClosesWaitingRequestAndRemovesPreparedUpload() = runBlocking {
        val upload = File(directory, "cancel.animeow.zip").apply { writeBytes(ByteArray(32 * 1024)) }
        val release = CountDownLatch(1)
        LocalHttpServer {
            release.await(15, TimeUnit.SECONDS)
            """{"status":"success","data":{"id":1}}""".toByteArray()
        }.use { server ->
            val service = CloudAccountService(context, native, legacy, apiBase = server.baseUrl)
            val summary = NativeBackupSummary(0, 0, 0, 0, 0, "2026-09-08T00:00:00Z")
            val job = launch(Dispatchers.Main) { service.uploadPrepared(session, PreparedCloudUpload(upload, summary)) }
            try {
                withContext(Dispatchers.IO) { server.request.get(10, TimeUnit.SECONDS) }
                withTimeout(5_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertFalse(upload.exists())
            } finally {
                release.countDown()
                job.cancelAndJoin()
            }
        }
    }

    private suspend fun <T> onResponsiveMain(label: String, block: suspend () -> T): T = coroutineScope {
        val violations = CopyOnWriteArrayList<String>()
        val ticks = AtomicInteger()
        val maxGap = AtomicLong()
        val heartbeat = launch(Dispatchers.Main) {
            var previous = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(20)
                val now = SystemClock.elapsedRealtime()
                maxGap.set(maxOf(maxGap.get(), now - previous))
                previous = now
                ticks.incrementAndGet()
            }
        }
        try {
            val result = withContext(Dispatchers.Main) {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .penaltyListener({ it.run() }) { violations += it.stackTraceToString() }.build())
                try { block() } finally { StrictMode.setThreadPolicy(previous) }
            }
            assertTrue("Main-thread disk IO during $label: $violations", violations.isEmpty())
            assertTrue("Main heartbeat must keep running", ticks.get() > 1)
            println("MOBILE_QA $label: ticks=${ticks.get()}, maxGapMs=${maxGap.get()}")
            result
        } finally {
            heartbeat.cancelAndJoin()
        }
    }
}
