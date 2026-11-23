package com.animeow.app.data.cloud

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.importer.LegacyBackupImporter
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.preferences.ConfigurationProfileRepository
import com.animeow.app.testing.ControlledHttpServer
import com.animeow.app.testing.TestHttpResponse
import com.animeow.app.ui.theme.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CloudConfigurationBackupTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val id = UUID.randomUUID().toString()
    private val files = File(base.filesDir, "configuration-cloud-$id").apply { mkdirs() }
    private val cache = File(base.cacheDir, "configuration-cloud-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$id-$name", mode)
    }
    private val database = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).build()
    private val native = NativeBackupService(context, database)
    private val legacy = LegacyBackupImporter(context, database)
    private val session = CloudSession(990013, "configuration_fixture", "local-configuration-fixture")
    private val sessions = SecureCloudSessionStore(context)

    @After fun clean() {
        sessions.clear()
        database.close()
        files.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test fun configurationUsesTheSameDeviceBackupAndSelectiveRestoreKeepsLibraryChanges() = runBlocking {
        sessions.save(session)
        val preferences = AppearancePreferences(context)
        val configuration = AccentPalettePreset.NIGHT_FLOW.apply(AppearanceSettings()).copy(accentColor = 0xFF0D1329L,
            detailProgressMode = DetailProgressMode.COMBINED, themeColor = 0xFF73814CL)
        preferences.restore(configuration)
        val profiles = ConfigurationProfileRepository(context)
        profiles.saveCurrent("合成配置档案")
        database.libraryDao().insertAnime(AnimeEntity(title = "云端快照合成作品"))
        val archive = File(cache, "captured.animeow.zip")
        ControlledHttpServer { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/user/backups", request.path)
            val body = JSONObject(request.body)
            archive.writeBytes(Base64.decode(body.getString("backup_base64"), Base64.DEFAULT))
            assertTrue(body.getString("device_id").isNotBlank())
            TestHttpResponse(body = """{"status":"success","data":{"id":9913,"file_name":"fixture.animeow.zip","file_size":${archive.length()}}}""")
        }.use { server ->
            val service = CloudAccountService(context, native, legacy, apiBase = server.baseUrl)
            val sync = CloudSyncPreferences(context)
            assertEquals(9913L, uploadDeviceBackup(session, service, sync).id)
            assertEquals(9913L, sync.snapshot().lastRemoteBackupId)
        }
        val prepared = native.prepareRestore(archive)
        try {
            assertFalse(prepared.fontsIncluded)
            assertEquals(configuration.accentColor, prepared.settings.accentColor)
            assertEquals(configuration.accentGradient, prepared.settings.accentGradient)
            assertEquals(configuration.themeColor, prepared.settings.themeColor)
            assertEquals(DetailProgressMode.COMBINED, prepared.settings.detailProgressMode)
            assertTrue(prepared.configurationProfilesJson!!.contains("合成配置档案"))
            assertEquals(1, prepared.summary.animeCount)
            preferences.restore(AppearanceSettings())
            database.libraryDao().insertAnime(AnimeEntity(title = "备份后本机新增"))
            native.restore(prepared, NativeRestoreSelection(libraryAndCharacters = false, analysisHistory = false, appearanceSettings = true))
            assertEquals(configuration.accentGradient, preferences.snapshot().accentGradient)
            assertEquals(configuration.themeColor, preferences.snapshot().themeColor)
            assertEquals(DetailProgressMode.COMBINED, preferences.snapshot().detailProgressMode)
            assertEquals(2, native.summarizeCurrent(includeFonts = false).animeCount)
            assertEquals(session.token, sessions.load()?.token)
        } finally { native.discard(prepared) }
    }

    @Test fun failedUploadKeepsLocalConfigurationSessionAndSyncBaseline() = runBlocking {
        sessions.save(session)
        val preferences = AppearancePreferences(context)
        val configuration = AccentPalettePreset.MINT_SKY.apply(AppearanceSettings())
        preferences.restore(configuration)
        val sync = CloudSyncPreferences(context)
        sync.markSynced("previous", 123L, "fixture", "2026-09-13T00:00:00Z")
        ControlledHttpServer { TestHttpResponse(503, """{"message":"服务暂忙"}""") }.use { server ->
            val service = CloudAccountService(context, native, legacy, apiBase = server.baseUrl)
            assertTrue(runCatching { uploadDeviceBackup(session, service, sync) }.isFailure)
        }
        assertEquals(configuration.accentGradient, preferences.snapshot().accentGradient)
        assertEquals(123L, sync.snapshot().lastRemoteBackupId)
        assertEquals(session.token, sessions.load()?.token)
        assertTrue(File(cache, "shared/cloud_upload").listFiles().orEmpty().isEmpty())
    }
}
