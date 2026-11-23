package com.animeow.app.data.local

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.animeow.app.data.CharacterRepository
import com.animeow.app.data.LibraryRepository
import com.animeow.app.data.media.CoverImageStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MetadataFieldsStorageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun versionTwelveFileUpgradesThroughRoomValidationWithoutLosingData() = runBlocking {
        // Reconstruct the previous schema, excluding the additive columns introduced after v12.
        val template = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).build()
        val statements = try {
            template.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') AND tbl_name != 'audio_works' AND name != 'index_games_vndbId'",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val sql = cursor.getString(0)
                        add((if (sql.startsWith("CREATE TABLE") && sql.contains("`tags` (")) sql.replace(
                            Regex(",\\s*`sortOrder` INTEGER"), "",
                        ) else sql).replace(Regex(",\\s*`(?:statusChangedAt|bookReadVolumes|bookReadChapters|updatedAt)` INTEGER"), "").replace(
                            Regex(",\\s*\\x60(?:voiceActors|coverOrientation|voiceActor|imageDisplayMode|sourceRole|targetRole|vndbId|sourceRatingJson|seriesRole|memo|linkedMediaJson)\\x60 TEXT"), "",
                        ).replace(Regex(",\\s*`tags` TEXT NOT NULL DEFAULT ''"), ""))
                    }
                }
            }
        } finally { template.close() }
        val name = "metadata-migration-" + UUID.randomUUID() + ".db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        statements.forEach { db.execSQL(it) }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val old = helper.writableDatabase
            old.execSQL("INSERT INTO animes(id,title,status,watchedEpisodes,totalEpisodes,tvEpisodes,spEpisodes,subjectType) VALUES(1,'旧作品','在看',7,12,12,0,'anime')")
            old.execSQL("INSERT INTO characters(id,name) VALUES(1,'角色')")
            old.execSQL("INSERT INTO anime_characters(animeId,characterId,roleName,sortOrder) VALUES(1,1,'主角',2)")
            old.execSQL("INSERT INTO watch_records(id,animeId,episode,status) VALUES(1,1,7,'watched')")
            old.execSQL("INSERT INTO episode_details(animeId,episode,review) VALUES(1,7,'保留感想')")
        } finally { helper.close() }
        val upgraded = Room.databaseBuilder(context, AniMeowDatabase::class.java, name)
            // Robolectric's Android SQLite cannot reopen this Windows file in WAL mode.
            // Keep a real file and Room schema validation; only the host test journal differs.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = upgraded.libraryDao()
            val anime = requireNotNull(dao.getAnime(1))
            assertEquals("旧作品", anime.title)
            assertEquals(7, anime.watchedEpisodes)
            assertNull(anime.voiceActors)
            assertNull(anime.coverOrientation)
            assertEquals("主角", dao.getAnimeCharacter(1, 1)?.roleName)
            assertNull(dao.getAnimeCharacter(1, 1)?.voiceActor)
            assertEquals("保留感想", dao.getEpisodeDetail(1, 7)?.review)
            upgraded.openHelper.readableDatabase.query("SELECT COUNT(*) FROM watch_records").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            dao.updateAnime(anime.copy(voiceActors = "名单", coverOrientation = "landscape"))
            dao.updateCharacterVoiceActor(1, 1, "对应声优")
            assertEquals("名单", dao.getAnime(1)?.voiceActors)
            assertEquals("landscape", dao.getAnime(1)?.coverOrientation)
            assertEquals("对应声优", dao.getAnimeCharacter(1, 1)?.voiceActor)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun actorsRemainIndependentPerWorkAndRelinkingPreservesThem() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val a = dao.insertAnime(AnimeEntity(title = "甲", voiceActors = "作品名单", coverOrientation = "portrait"))
            val b = dao.insertAnime(AnimeEntity(title = "乙"))
            val c = dao.insertCharacter(CharacterEntity(name = "角色"))
            val repo = CharacterRepository(db)
            repo.linkWork(c, a, "主角")
            repo.linkWork(c, b, "配角")
            repo.setVoiceActor(c, a, "声优甲")
            repo.setVoiceActor(c, b, "声优乙")
            repo.linkWork(c, a, "配角")
            assertEquals("声优甲", dao.getAnimeCharacter(a, c)?.voiceActor)
            assertEquals("声优乙", dao.getAnimeCharacter(b, c)?.voiceActor)
            repo.setVoiceActor(c, a, " ")
            assertNull(dao.getAnimeCharacter(a, c)?.voiceActor)
            assertEquals("配角", dao.getAnimeCharacter(a, c)?.roleName)
            assertEquals("作品名单", dao.getAnime(a)?.voiceActors)
        } finally { db.close() }
    }

    @Test
    fun conflictingCharacterMergeLeavesBothCharactersAndActorsIntact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val a = dao.insertAnime(AnimeEntity(title = "作品"))
            val first = dao.insertCharacter(CharacterEntity(name = "一"))
            val second = dao.insertCharacter(CharacterEntity(name = "二"))
            dao.insertAnimeCharacter(AnimeCharacterEntity(a, first, voiceActor = "声优甲"))
            dao.insertAnimeCharacter(AnimeCharacterEntity(a, second, voiceActor = "声优乙"))
            val failure = runCatching { CharacterRepository(db).mergeDuplicates(first, listOf(second)) }.exceptionOrNull()
            assertTrue(failure?.message?.contains("声优资料有分歧") == true)
            assertNotNull(dao.getCharacter(first))
            assertNotNull(dao.getCharacter(second))
            assertEquals("声优甲", dao.getAnimeCharacter(a, first)?.voiceActor)
            assertEquals("声优乙", dao.getAnimeCharacter(a, second)?.voiceActor)
        } finally { db.close() }
    }

    @Test
    fun oneWorkKeepsSeparateCreditsForEachCharacter() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val work = dao.insertAnime(AnimeEntity(title = "合成作品"))
            val first = dao.insertCharacter(CharacterEntity(name = "角色甲"))
            val second = dao.insertCharacter(CharacterEntity(name = "角色乙"))
            val repo = CharacterRepository(db)
            repo.linkWork(first, work, "主角")
            repo.linkWork(second, work, "配角")
            repo.setVoiceActor(first, work, "声优甲")
            repo.setVoiceActor(second, work, "声优乙")
            assertEquals("声优甲", dao.getAnimeCharacter(work, first)?.voiceActor)
            assertEquals("声优乙", dao.getAnimeCharacter(work, second)?.voiceActor)
            repo.setVoiceActor(first, work, "声优丙")
            assertEquals("声优丙", dao.getAnimeCharacter(work, first)?.voiceActor)
            assertEquals("声优乙", dao.getAnimeCharacter(work, second)?.voiceActor)
            repo.setVoiceActor(first, work, "")
            assertNull(dao.getAnimeCharacter(work, first)?.voiceActor)
            assertEquals("声优乙", dao.getAnimeCharacter(work, second)?.voiceActor)
        } finally { db.close() }
    }

    @Test
    fun workMergeFillsMissingActorsAndKeepsSourceAssociations() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val target = dao.insertAnime(AnimeEntity(title = "目标"))
            val source = dao.insertAnime(AnimeEntity(title = "来源", voiceActors = "名单", coverOrientation = "landscape"))
            val c = dao.insertCharacter(CharacterEntity(name = "角色"))
            dao.insertAnimeCharacter(AnimeCharacterEntity(target, c))
            dao.insertAnimeCharacter(AnimeCharacterEntity(source, c, voiceActor = "声优"))
            LibraryRepository(db).mergeAnimes(target, setOf(source))
            assertEquals("名单", dao.getAnime(target)?.voiceActors)
            assertEquals("landscape", dao.getAnime(target)?.coverOrientation)
            assertEquals("声优", dao.getAnimeCharacter(target, c)?.voiceActor)
            assertEquals("声优", dao.getAnimeCharacter(source, c)?.voiceActor)
            assertNotNull(dao.getAnime(source))
        } finally { db.close() }
    }

    @Test
    fun coverImportUsesChosenRatioAndPreservesPreviousFiles() = runBlocking {
        val source = File(context.cacheDir, "metadata-cover-" + UUID.randomUUID() + ".png")
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val original = source.readBytes()
        val outputs = mutableListOf<File>()
        try {
            val store = CoverImageStore(context)
            for (orientation in listOf("portrait", "landscape")) {
                val url = store.importCroppedCover(Uri.fromFile(source), 0.5f, 0.5f, 1f, coverAspectRatio(orientation, 1f))
                val file = File(requireNotNull(Uri.parse(url).path))
                outputs += file
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                assertEquals(coverAspectRatio(orientation, 1f), bounds.outWidth.toFloat() / bounds.outHeight, 0.005f)
            }
            assertNotEquals(outputs[0], outputs[1])
            assertTrue(outputs.all { it.exists() })
            assertArrayEquals(original, source.readBytes())
            assertEquals(0.7f, coverAspectRatio(null, 0.7f))
            assertEquals(0.7f, coverAspectRatio("unknown", 0.7f))
        } finally {
            outputs.forEach { it.delete() }
            source.delete()
        }
    }
}
