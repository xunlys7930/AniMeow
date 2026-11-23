package com.animeow.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.local.*
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import com.animeow.app.data.remote.RemoteCharacter
import com.animeow.app.data.remote.RemoteCharacterSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MergeLibraryTransactionTest {
    private suspend fun withDatabase(block: suspend (AniMeowDatabase, LibraryRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AniMeowDatabase::class.java).build()
        try { block(db, LibraryRepository(db)) } finally { db.close() }
    }

    @Test fun groupImportKeepsSameNameCharactersFromDifferentSourcesSeparate() = runBlocking {
        withDatabase { db, _ ->
            val dao = db.libraryDao()
            val existing = dao.insertCharacter(CharacterEntity(name = "同名角色", bgmId = 77))
            val repository = CharacterRepository(db)
            val result = repository.importGroupPackage("""{"group":{"name":"来源检查"},"characters":[{"name":"同名角色","anilist_id":77}]}""")
            val imported = dao.getCharacterGroupMembers(result.groupId).single().character
            assertNotEquals(existing, imported.id)
            assertNull(imported.bgmId)
            assertEquals(77L, imported.anilistId)
            assertEquals(2, dao.getCharacterItems().size)
        }
    }

    @Test fun conflictingGroupIdentitiesRollbackEarlierMembersToo() = runBlocking {
        withDatabase { db, _ ->
            val dao = db.libraryDao()
            dao.insertCharacter(CharacterEntity(name = "第一条", bgmId = 123))
            dao.insertCharacter(CharacterEntity(name = "第二条", anilistId = 456))
            val result = runCatching { CharacterRepository(db).importGroupPackage("""{"group":{"name":"冲突包"},"characters":[{"name":"先写入的成员"},{"name":"冲突成员","bgm_id":123,"anilist_id":456}]}""") }
            assertTrue(result.isFailure)
            assertEquals(2, dao.getCharacterItems().size)
            assertTrue(dao.observeCharacterGroupSummaries().first().isEmpty())
        }
    }

    @Test fun groupWorkImportFindsMergedIdentityAndExportsItsReferences() = runBlocking {
        withDatabase { db, library ->
            val dao = db.libraryDao()
            val keep = dao.insertAnime(AnimeEntity(title = "保留条目"))
            val source = dao.insertAnime(AnimeEntity(title = "Other language", externalSource = "anilist", externalId = "99"))
            library.mergeAnimes(keep, setOf(source))
            library.emptyTrash()
            val repository = CharacterRepository(db)
            val result = repository.importGroupPackage("""{"group":{"name":"作品关联"},"works":[{"title":"New title","external_source":"anilist","external_id":"99"}]}""")
            assertEquals(0, result.createdWorkCount)
            assertEquals(keep, dao.getCharacterGroupWorks(result.groupId).single().anime.id)
            val exported = org.json.JSONObject(repository.exportGroupPackage(result.groupId)).getJSONArray("works").getJSONObject(0)
            assertTrue(exported.getString("merged_sources_json").contains("anilist"))
        }
    }

    @Test fun mergeKeepsRelationsReviewsPinsAndAllLanguageIdentitiesAfterTrashIsEmptied() = runBlocking {
        withDatabase { db, repository ->
            val dao = db.libraryDao()
            val target = dao.insertAnime(AnimeEntity(title = "星海旅人", review = "喜欢角色", pinnedAt = 10))
            val first = dao.insertAnime(AnimeEntity(title = "星の旅人", review = "喜欢音乐", externalSource = "bangumi", externalId = "888", watchedEpisodes = 3))
            val second = dao.insertAnime(AnimeEntity(title = "Star Voyager", externalSource = "anilist", externalId = "888", pinnedAt = 20, watchedEpisodes = 6))
            val tag = dao.insertTag(TagEntity(name = "科幻"))
            dao.insertAnimeTags(listOf(AnimeTagEntity(first, tag), AnimeTagEntity(second, tag)))
            val character = dao.insertCharacter(CharacterEntity(name = "旅人", bgmId = 333))
            dao.insertAnimeCharacters(listOf(AnimeCharacterEntity(first, character), AnimeCharacterEntity(second, character, "主角")))
            val group = dao.insertCharacterGroup(CharacterGroupEntity(name = "远行组"))
            dao.insertCharacterGroupWork(CharacterGroupWorkEntity(group, second))
            dao.insertWatchRecords(listOf(WatchRecordEntity(animeId = first, episode = 1, status = "在看", recordDate = "2026-09-01"),
                WatchRecordEntity(animeId = second, episode = 1, status = "在看", recordDate = "2026-09-01"),
                WatchRecordEntity(animeId = second, episode = 1, status = "看完", recordDate = "2026-09-01")))

            assertNotNull(repository.mergeAnimes(target, setOf(first, second)))
            var merged = dao.getAnime(target)!!
            assertNull(merged.externalId)
            assertTrue(merged.matchesExternalReference("bangumi", "888"))
            assertTrue(merged.matchesExternalReference("anilist", "888"))
            assertTrue(merged.allTitles().containsAll(listOf("星海旅人", "星の旅人", "Star Voyager")))
            assertTrue(merged.review!!.contains("喜欢角色")); assertTrue(merged.review!!.contains("喜欢音乐"))
            assertEquals(20L, merged.pinnedAt)
            assertEquals(listOf(tag), repository.getTagIdsForAnime(target))
            assertEquals("主角", dao.observeCharactersForAnime(target).first().single().roleName)
            assertTrue(dao.getCharacterGroupWorks(group).any { it.anime.id == target })
            assertEquals(2, dao.getWatchRecords().count { it.animeId == target })
            assertNotNull(dao.getAnime(first)!!.deletedAt)
            assertEquals("888", dao.getAnime(first)!!.externalId)
            assertNull(repository.findTrashedByExternalId("bangumi", "888"))
            repository.emptyTrash()
            merged = dao.getAnime(target)!!
            assertTrue(merged.matchesExternalReference("bangumi", "888"))
            val import = repository.importRemoteAnime(RemoteAnime(RemoteCatalogSource.ANILIST, "888", "A renamed title"), forceDuplicate = true)
            assertEquals(target, (import as RemoteImportResult.Duplicate).existing.id)
            assertEquals(1, dao.getActiveAnimes().size)
        }
    }

    @Test fun restoringSourceIsIndependentAndKeepsItsOriginalIdentity() = runBlocking {
        withDatabase { db, repository ->
            val dao = db.libraryDao()
            val target = dao.insertAnime(AnimeEntity(title = "主记录"))
            val source = dao.insertAnime(AnimeEntity(title = "Original", externalSource = "bangumi", externalId = "72", review = "原评价"))
            repository.mergeAnimes(target, setOf(source))
            repository.restoreAnime(source)
            assertEquals("原评价", dao.getAnime(source)!!.review)
            assertNull(dao.getAnime(source)!!.deletedAt)
            assertTrue(dao.getAnime(target)!!.matchesExternalReference("bangumi", "72"))
            val imported = repository.importRemoteAnime(RemoteAnime(RemoteCatalogSource.BANGUMI, "72", "Original"))
            assertEquals(source, (imported as RemoteImportResult.Duplicate).existing.id)
        }
    }

    @Test fun failureHalfwayThroughMergeRollsBackAllTables() = runBlocking {
        withDatabase { db, repository ->
            val dao = db.libraryDao()
            val target = dao.insertAnime(AnimeEntity(title = "保留", watchedEpisodes = 1))
            val source = dao.insertAnime(AnimeEntity(title = "来源", watchedEpisodes = 9, pinnedAt = 30))
            val tag = dao.insertTag(TagEntity(name = "测试"))
            dao.insertAnimeTags(listOf(AnimeTagEntity(source, tag)))
            dao.insertWatchRecord(WatchRecordEntity(animeId = source, episode = 4))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_merged_record BEFORE INSERT ON watch_records WHEN NEW.animeId = $target BEGIN SELECT RAISE(ABORT, 'qa rollback'); END")
            assertTrue(runCatching { repository.mergeAnimes(target, setOf(source)) }.isFailure)
            assertEquals(1, dao.getAnime(target)!!.watchedEpisodes)
            assertNull(dao.getAnime(target)!!.pinnedAt)
            assertTrue(repository.getTagIdsForAnime(target).isEmpty())
            assertNull(dao.getAnime(source)!!.deletedAt)
            assertEquals(1, dao.getWatchRecords().size)
        }
    }

    @Test fun staleSelectionsAndMixedMediaCannotPartiallyMerge() = runBlocking {
        withDatabase { db, repository ->
            val dao = db.libraryDao()
            val anime = dao.insertAnime(AnimeEntity(title = "同名作品"))
            val book = dao.insertAnime(AnimeEntity(title = "同名作品", subjectType = "book"))
            assertTrue(runCatching { repository.mergeAnimes(anime, setOf(book)) }.isFailure)
            assertTrue(runCatching { repository.mergeAnimes(anime, setOf(999)) }.isFailure)
            assertEquals(2, dao.getActiveAnimes().size)
        }
    }

    @Test fun remoteMetadataKeepsOwnedKeysAndRollsBackWhenTagWriteFails() = runBlocking {
        withDatabase { db, repository ->
            val dao = db.libraryDao()
            val first = dao.insertAnime(AnimeEntity(title = "主条目"))
            dao.insertAnime(AnimeEntity(title = "已有来源", externalSource = "bangumi", externalId = "90"))
            val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "90", "来源标题", episodes = 12, tags = listOf("新标签"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_metadata_tag BEFORE INSERT ON tags BEGIN SELECT RAISE(ABORT, 'qa metadata rollback'); END")
            assertTrue(runCatching { repository.applyRemoteMetadata(first, remote, RemoteMetadataFields(episodes = true, tags = true, externalLink = true), true) }.isFailure)
            assertEquals(0, dao.getAnime(first)!!.totalEpisodes)
            assertNull(dao.getAnime(first)!!.mergedSourcesJson)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_metadata_tag")
            repository.applyRemoteMetadata(first, remote, RemoteMetadataFields(episodes = true, externalLink = true), true)
            assertNull(dao.getAnime(first)!!.externalId)
            assertTrue(dao.getAnime(first)!!.matchesExternalReference("bangumi", "90"))
        }
    }

    @Test fun equalRemoteNumbersFromDifferentCharacterSourcesNeverCollide() = runBlocking {
        withDatabase { db, _ ->
            val repository = CharacterRepository(db, InstrumentationRegistry.getInstrumentation().targetContext)
            val bangumi = repository.upsertRemoteCharacter(RemoteCharacter(123, "Alice", source = RemoteCharacterSource.BANGUMI))
            val anilist = repository.upsertRemoteCharacter(RemoteCharacter(123, "Alice", source = RemoteCharacterSource.ANILIST))
            assertNotEquals(bangumi, anilist)
            assertEquals(bangumi, repository.upsertRemoteCharacter(RemoteCharacter(123, "爱丽丝", source = RemoteCharacterSource.BANGUMI)))
            assertNull(db.libraryDao().getCharacter(anilist)!!.bgmId)
            assertEquals(123L, db.libraryDao().getCharacter(anilist)!!.anilistId)
        }
    }
}
