package com.animeow.app.data.fonts

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animeow.app.R
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.local.AniMeowDatabase
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFontStoreTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(base.filesDir, "font-fixture-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }
    private val database = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).build()
    private val store = AppFontStore(context)
    private fun source(): File = File(directory, "selection.ttf").apply {
        context.resources.openRawResource(R.font.animeow_serif).use { input -> outputStream().use { input.copyTo(it) } }
    }

    @After fun cleanup() { database.close(); directory.deleteRecursively() }

    @Test fun importsDeduplicatesAndRestoresFontWithNativeBackup() = runBlocking {
        val uri = Uri.fromFile(source())
        val imported = store.import(uri)
        assertNotNull(store.load(imported.id))
        assertEquals(imported.id, store.import(uri).id)
        assertEquals(1, store.list().size)
        val backup = NativeBackupService(context, database)
        val archive = File(directory, "font-backup.zip")
        backup.export(archive)
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry("branding/fonts/${imported.id}.font"))
            assertNotNull(zip.getEntry("branding/fonts/${imported.id}.json"))
        }
        store.remove(imported.id)
        assertNull(store.load(imported.id))
        val prepared = backup.prepareRestore(archive)
        try { backup.restore(prepared) } finally { backup.discard(prepared) }
        assertNotNull(store.load(imported.id))
        assertEquals(1, store.list().size)
    }

    @Test fun corruptOrMissingFontFallsBackWithoutUsingUnverifiedBytes() = runBlocking {
        val imported = store.import(Uri.fromFile(source()))
        File(directory, "branding/fonts/${imported.id}.font").appendBytes(byteArrayOf(1))
        assertNull(store.load(imported.id))
        assertNull(store.load("../selection.ttf"))
        assertNull(store.load("f".repeat(64)))
    }

    @Test fun cloudBackupOmitsFontsAndPreservesLocalFontsOnRestore() = runBlocking {
        val imported = store.import(Uri.fromFile(source()))
        val backup = NativeBackupService(context, database)
        val archive = File(directory, "cloud-backup.zip")
        backup.export(archive, includeFonts = false)
        ZipFile(archive).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("branding/fonts/") })
            assertTrue(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText().contains("\"fontsIncluded\":false"))
        }
        val prepared = backup.prepareRestore(archive)
        try { backup.restore(prepared) } finally { backup.discard(prepared) }
        assertNotNull(store.load(imported.id))
        assertEquals(1, store.list().size)
    }

    @Test fun failedImportRetainsExistingFontAndCleansTemporaryFiles() = runBlocking {
        val imported = store.import(Uri.fromFile(source()))
        val invalid = File(directory, "invalid.ttf").apply { writeText("not a font") }
        var rejected = false
        try { store.import(Uri.fromFile(invalid)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertNotNull(store.load(imported.id))
        assertEquals(1, store.list().size)
        assertFalse(File(directory, "branding/fonts").listFiles().orEmpty().any { it.extension == "tmp" })
    }
}
