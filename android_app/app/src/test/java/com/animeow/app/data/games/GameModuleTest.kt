package com.animeow.app.data.games

import androidx.room.Room
import androidx.room.RoomDatabase
import com.animeow.app.data.backup.NativeBackupMerger
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.preferences.AppearanceSettingsCodec
import com.animeow.app.ui.theme.AppearanceSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class GameModuleTest {
    @Test fun missingGameFieldsAreFilledWithoutReplacingExistingMetadataOrRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = GameRepository(RuntimeEnvironment.getApplication(), db)
            val old = GameEntity(bangumiId = 10, title = "个人名称", originalTitle = "人工原名", coverUrl = "file:///custom.png",
                synopsis = "已有简介", developer = "", releaseDate = "", status = "在玩", playMinutes = 720, rating = 9, review = "保留感想")
            assertTrue(old.needsMetadata)
            val id = db.gameDao().insert(old)
            val incoming = GameEntity(bangumiId = 10, title = "来源名称", originalTitle = "来源原名", coverUrl = "https://example.invalid/image.png",
                developer = "示例开发商", releaseDate = "2026-10-23", synopsis = "", score = 8.0)
            val next = repository.enrich(id, incoming)!!
            assertEquals(old.copy(id = id, developer = incoming.developer, releaseDate = incoming.releaseDate, score = 8.0), next)
            assertFalse(next.needsMetadata)
            assertEquals(next, repository.enrich(id, incoming.copy(developer = "", releaseDate = "", score = 0.0)))
            assertEquals(0, db.gameDao().pendingCount())
            assertNull(repository.enrich(id, incoming.copy(bangumiId = 11)))
        } finally { db.close() }
    }

    @Test fun collectingTheSameVerifiedGameFromAnotherSourcePreservesTheExistingRecord() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val old = GameEntity(bangumiId = 256658, title = "夏娃年代记2", originalTitle = "Evenicle -イブニクル- 2",
                releaseDate = "2019-02-22", playMinutes = 720, review = "保留感想", status = "已玩")
            val id = db.gameDao().insert(old)
            val candidate = old.copy(id = 0, bangumiId = null, vndbId = "v23742", originalTitle = "イブニクル２", playMinutes = 0, review = "")
            assertEquals(id, GameRepository(RuntimeEnvironment.getApplication(), db).save(candidate))
            assertEquals(720L, db.gameDao().get(id)?.playMinutes)
            assertEquals("保留感想", db.gameDao().get(id)?.review)
            assertEquals(0, db.gameDao().pendingCount())
        } finally { db.close() }
    }
    @Test fun vndbIdentitySurvivesParsingStorageAndCloudMergeWithoutBecomingABangumiId() = runBlocking {
        val game = parseGameMetadata(JSONObject().put("id", "vndb:v16640").put("vndbId", "v16640").put("title", "夏娃年代记"))
        assertNull(game.bangumiId)
        assertEquals("vndb:v16640", game.catalogKey)
        assertNotEquals(game.catalogKey, GameEntity(bangumiId = 16640).catalogKey)
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = GameRepository(RuntimeEnvironment.getApplication(), db)
            val id = repository.save(game.copy(playMinutes = 90, tags = "剧情, 治愈，剧情"))
            assertEquals(id, repository.save(game))
            assertEquals(90L, db.gameDao().byVndbId("v16640")?.playMinutes)
            assertEquals("剧情，治愈", db.gameDao().byVndbId("v16640")?.tags)
            assertEquals(0, db.gameDao().pendingCount())
            assertNull(repository.enrich(id, GameEntity(bangumiId = 16640, title = "不同来源")))
        } finally { db.close() }
        fun snapshot(sync: String, minutes: Int) = JSONObject().put("games", JSONArray().put(JSONObject()
            .put("id", 1).put("syncId", sync).put("vndbId", "v16640").put("playMinutes", minutes)
            .put("updatedAt", if (minutes == 0) "2026-10-18T00:00:00Z" else "2026-10-17T00:00:00Z")))
        val merged = NativeBackupMerger.merge(snapshot("local", 90), snapshot("remote", 0), true, false).getJSONArray("games")
        assertEquals(1, merged.length())
        assertEquals("local", merged.getJSONObject(0).getString("syncId"))
        assertEquals(0, merged.getJSONObject(0).getInt("playMinutes"))
        assertEquals("v16640", merged.getJSONObject(0).getString("vndbId"))
    }

    @Test fun migrationFrom17PreservesExistingGamesAndAddsIndependentVndbIdentity() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "game-vndb-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, AniMeowDatabase::class.java, name).allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).build()
        var db = open()
        try {
            val id = db.gameDao().insert(GameEntity(title = "保留记录", bangumiId = 16640, playMinutes = 123))
            db.openHelper.writableDatabase.apply {
                execSQL("DROP INDEX index_games_vndbId")
                execSQL("ALTER TABLE animes DROP COLUMN updatedAt")
                execSQL("ALTER TABLE games DROP COLUMN vndbId")
                execSQL("ALTER TABLE games DROP COLUMN tags")
                execSQL("ALTER TABLE audio_works DROP COLUMN originalTitle")
                execSQL("ALTER TABLE series DROP COLUMN linkedMediaJson"); execSQL("ALTER TABLE animes DROP COLUMN memo"); execSQL("ALTER TABLE animes DROP COLUMN seriesRole"); execSQL("ALTER TABLE animes DROP COLUMN bookReadVolumes"); execSQL("ALTER TABLE animes DROP COLUMN bookReadChapters"); execSQL("ALTER TABLE animes DROP COLUMN sourceRatingJson"); version = 17
            }
            db.close(); db = open()
            assertEquals(123L, db.gameDao().get(id)?.playMinutes)
            val vn = db.gameDao().insert(GameEntity(title = "独立编号", vndbId = "v16640"))
            assertEquals(vn, db.gameDao().byVndbId("v16640")?.id)
            assertEquals(id, db.gameDao().byBangumiId(16640)?.id)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun serverMetadataParserRejectsMissingIdentityAndDoesNotInventPersonalProgress() {
        val game = parseGameMetadata(JSONObject().put("id", 284100).put("title", "艾尔登法环").put("platforms", "PC / PS5").put("score", 8.8))
        assertEquals(284100L, game.bangumiId)
        assertEquals("PC / PS5", game.platforms)
        assertEquals(0L, game.playMinutes)
        assertEquals(0, game.rating)
        assertEquals("想玩", game.status)
        try { parseGameMetadata(JSONObject().put("title", "缺少 ID")); fail("missing identity") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun playtimeAndReviewChangesDoNotOverwriteBangumiButKeepPendingStatus() {
        val old = GameEntity(title = "游戏", status = "在玩", rating = 8)
        val newer = old.copy(playMinutes = 60, review = "感想")
        assertEquals(null to null, gameSyncChanges(old, newer, null, true, true))
        assertEquals(3 to null, gameSyncChanges(null, old.copy(rating = 0), null, true, true))
        val pending = GamePendingSync(1, old.syncId, 10, 2, 3, 3, null, "old")
        assertEquals(3 to null, gameSyncChanges(old, newer, pending, true, true))
        assertEquals(2 to 0, gameSyncChanges(old, newer.copy(status = "已玩", rating = 0), pending, true, true))
        assertEquals(null to null, gameSyncChanges(old, newer.copy(status = "已玩"), null, false, false))
    }
    @Test fun upgradeFrom14AddsGamesWithoutChangingAnimationRows() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "game_migration_${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AniMeowDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            db.libraryDao().insertAnimes(listOf(com.animeow.app.data.local.AnimeEntity(title = "旧动画", watchedEpisodes = 7)))
            db.openHelper.writableDatabase.apply {
                execSQL("DROP TABLE games")
                execSQL("DROP TABLE game_pending_sync")
                execSQL("DROP TABLE audio_works")
                execSQL("ALTER TABLE animes DROP COLUMN statusChangedAt")
                execSQL("ALTER TABLE animes DROP COLUMN updatedAt")
                execSQL("DROP TABLE tags")
                execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, color INTEGER, blocked INTEGER NOT NULL)")
                execSQL("CREATE UNIQUE INDEX index_tags_name ON tags (name)")
                execSQL("ALTER TABLE series DROP COLUMN linkedMediaJson"); execSQL("ALTER TABLE animes DROP COLUMN memo"); execSQL("ALTER TABLE animes DROP COLUMN seriesRole"); execSQL("ALTER TABLE animes DROP COLUMN bookReadVolumes"); execSQL("ALTER TABLE animes DROP COLUMN bookReadChapters"); execSQL("ALTER TABLE animes DROP COLUMN sourceRatingJson"); version = 14
            }
            db.close()
            db = Room.databaseBuilder(context, AniMeowDatabase::class.java, name).allowMainThreadQueries()
                .addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).build()
            assertEquals(7, db.libraryDao().getActiveAnimes().single().watchedEpisodes)
            assertTrue(db.gameDao().all().isEmpty())
            assertTrue(db.gameDao().insert(GameEntity(title = "新游戏")) > 0)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun columnsRespectScreenAndFontWithoutChangingSavedSelection() {
        assertEquals(3, effectiveGameColumns(3, 328f, 1f))
        assertEquals(4, effectiveGameColumns(4, 328f, 1f))
        assertEquals(4, effectiveGameColumns(6, 328f, 1f))
        assertEquals(2, effectiveGameColumns(2, 328f, 2f))
        assertEquals(6, effectiveGameColumns(6, 1000f, 1f))
        assertEquals("2小时 5分", gamePlayTime(125))
        gameStatuses.forEach { assertEquals(it, gameStatusFromCollection(gameCollectionType(it))) }
    }
    @Test fun appearanceRoundTripAndOldSettingsKeepIndependentDefaults() {
        val old = AppearanceSettingsCodec.fromJson(JSONObject().put("gridColumns", 5))
        assertEquals(2, old.gameGridColumns)
        assertEquals(GameLayout.GRID, old.gameLayout)
        val settings = AppearanceSettings(gameLayout = GameLayout.LIST, gameGridColumns = 6, gridColumns = 3)
        assertEquals(settings, AppearanceSettingsCodec.fromJson(AppearanceSettingsCodec.toJson(settings)))
        val changes = com.animeow.app.data.preferences.appearanceChanges(
            com.animeow.app.data.preferences.ConfigurationBundle(AppearanceSettings()),
            com.animeow.app.data.preferences.ConfigurationBundle(settings))
        assertEquals(setOf("游戏布局", "游戏网格列数"), changes.map { it.label }.toSet())
    }
    @Test fun mergeRetainsNewerDeletionAndCorrectedZeroAndRemapsCollidingIds() {
        val local = JSONObject().put("games", JSONArray().put(JSONObject().put("id", 1).put("syncId", "a").put("bangumiId", 10).put("playMinutes", 100).put("rating", 8).put("updatedAt", "2026-10-15T00:00:00Z")))
        val remote = JSONObject().put("games", JSONArray()
            .put(JSONObject().put("id", 7).put("syncId", "other-device").put("bangumiId", 10).put("playMinutes", 0).put("rating", 0).put("deletedAt", "2026-10-16").put("updatedAt", "2026-10-16T00:00:00Z"))
            .put(JSONObject().put("id", 1).put("syncId", "b").put("bangumiId", 20).put("updatedAt", "2026-10-16T00:00:00Z")))
        val rows = NativeBackupMerger.merge(local, remote, true, false).getJSONArray("games")
        assertEquals(2, rows.length())
        assertEquals(0, rows.getJSONObject(0).getInt("playMinutes"))
        assertEquals(0, rows.getJSONObject(0).getInt("rating"))
        assertEquals("2026-10-16", rows.getJSONObject(0).getString("deletedAt"))
        assertEquals("a", rows.getJSONObject(0).getString("syncId"))
        assertNotEquals(rows.getJSONObject(0).getLong("id"), rows.getJSONObject(1).getLong("id"))
    }
    @Test fun roomPersistsGamesAndAcknowledgesOnlyMatchingAccountAndVersion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.gameDao()
            val game = GameEntity(title = "游戏", bangumiId = 284100, playMinutes = 90)
            val id = dao.insert(game)
            assertEquals(90L, dao.get(id)?.playMinutes)
            dao.enqueue(GamePendingSync(id, game.syncId, 284100, 1, 2, 3, 8, "v1"))
            dao.enqueue(GamePendingSync(id, game.syncId, 284100, 1, 2, 2, 0, "v2"))
            assertTrue(dao.pending(1, 3).isEmpty())
            dao.acknowledge(id, 1, 2, "v1")
            assertEquals("v2", dao.pending(1, 2).single().updatedAt)
            dao.acknowledge(id, 1, 2, "v2")
            assertTrue(dao.pending(1, 2).isEmpty())
        } finally { db.close() }
    }
}
