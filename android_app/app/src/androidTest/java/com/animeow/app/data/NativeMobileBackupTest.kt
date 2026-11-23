package com.animeow.app.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NativeMobileBackupTest {
    @Test fun fullArchiveRoundTripKeepsNewColumnsAndReadsOlderMissingColumns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).build()
        val archive = File(context.cacheDir, "mobile-backup-${UUID.randomUUID()}.zip")
        val older = File(context.cacheDir, "mobile-backup-old-${UUID.randomUUID()}.zip")
        val service = NativeBackupService(context, db)
        try {
            val dao = db.libraryDao()
            val keep = dao.insertAnime(AnimeEntity(title = "星海旅人", pinnedAt = 123))
            val source = dao.insertAnime(AnimeEntity(title = "Star Voyager", externalSource = "anilist", externalId = "987"))
            val character = dao.insertCharacter(CharacterEntity(name = "旅人", anilistId = 4321))
            dao.insertAnimeCharacters(listOf(AnimeCharacterEntity(source, character, "主角")))
            LibraryRepository(db).mergeAnimes(keep, setOf(source))
            val summary = service.export(archive)
            assertEquals(1, summary.characterCount)
            val selection = NativeRestoreSelection(libraryAndCharacters = true, analysisHistory = false, appearanceSettings = false)
            db.clearAllTables()
            service.restore(service.prepareRestore(archive), selection)
            val restored = dao.getAnime(keep)!!
            assertEquals(123L, restored.pinnedAt)
            assertTrue(restored.matchesExternalReference("anilist", "987"))
            assertTrue(restored.allTitles().contains("Star Voyager"))
            assertEquals(4321L, dao.getCharacterItems().single().character.anilistId)
            assertEquals(character, dao.observeCharactersForAnime(keep).first().single().character.id)

            ZipFile(archive).use { zip ->
                ZipOutputStream(older.outputStream()).use { output ->
                    zip.entries().asSequence().forEach { entry ->
                        var bytes = zip.getInputStream(entry).use { it.readBytes() }
                        if (entry.name == "database.json") {
                            val data = JSONObject(bytes.toString(Charsets.UTF_8))
                            listOf("animes" to listOf("pinnedAt", "mergedSourcesJson"), "characters" to listOf("anilistId")).forEach { (table, columns) ->
                                data.optJSONArray(table)?.let { rows ->
                                    repeat(rows.length()) { index -> columns.forEach { rows.getJSONObject(index).remove(it) } }
                                }
                            }
                            bytes = data.toString().toByteArray()
                        }
                        if (entry.name == "manifest.json") {
                            val manifest = JSONObject(bytes.toString(Charsets.UTF_8)).put("version", 9)
                            manifest.remove("dataFingerprint")
                            bytes = manifest.toString().toByteArray()
                        }
                        output.putNextEntry(ZipEntry(entry.name)); output.write(bytes); output.closeEntry()
                    }
                }
            }
            db.clearAllTables()
            service.restore(service.prepareRestore(older), selection)
            assertEquals("星海旅人", dao.getAnime(keep)!!.title)
            assertNull(dao.getAnime(keep)!!.pinnedAt)
            assertNull(dao.getAnime(keep)!!.mergedSourcesJson)
            assertNull(dao.getCharacterItems().single().character.anilistId)
        } finally { db.close(); archive.delete(); older.delete() }
    }
}
