package com.animeow.app.data

import androidx.room.Room
import com.animeow.app.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class BatchTagsTest {
    @Test fun addsAndRemovesSeveralTagsWithoutReplacingOtherLinksOrRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val repository = LibraryRepository(db)
            val works = (1..3).map { dao.insertAnime(AnimeEntity(title = "作品$it", watchedEpisodes = it, subjectType = if (it == 3) "book" else "anime")) }.toSet()
            val original = works.map { dao.getAnime(it) }
            val keep = dao.insertTag(TagEntity(name = "保留"))
            val first = dao.insertTag(TagEntity(name = "第一标签"))
            val second = dao.insertTag(TagEntity(name = "第二标签"))
            dao.insertAnimeTags(works.map { AnimeTagEntity(it, keep) } + AnimeTagEntity(works.first(), first))
            repeat(2) { repository.updateTagsForAnimes(works, setOf(first, second), remove = false) }
            works.forEach { assertEquals(setOf(keep, first, second), dao.getTagIdsForAnime(it).toSet()) }
            repeat(2) { repository.updateTagsForAnimes(works, setOf(first, second), remove = true) }
            works.forEach { assertEquals(listOf(keep), dao.getTagIdsForAnime(it)) }
            assertEquals(original, works.map { dao.getAnime(it) })
        } finally { db.close() }
    }

    @Test fun validatesWholeSelectionAndRollsBackNewTagsAndLinksOnFailure() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val repository = LibraryRepository(db)
            val first = dao.insertAnime(AnimeEntity(title = "作品一"))
            val second = dao.insertAnime(AnimeEntity(title = "已删除", deletedAt = "2026-10-22"))
            val tag = dao.insertTag(TagEntity(name = "原标签"))
            suspend fun rejects(ids: Set<Long>, tags: Set<Long>, drafts: List<BatchTagDraft> = emptyList()) {
                try { repository.updateTagsForAnimes(ids, tags, false, drafts); fail("stale or invalid batch must fail") }
                catch (_: IllegalArgumentException) { }
                assertTrue(dao.getAnimeTags().isEmpty())
                assertEquals(listOf(tag), dao.getAllTags().map { it.id })
            }
            rejects(setOf(first, second), setOf(tag))
            rejects(setOf(first, Long.MAX_VALUE), setOf(tag))
            rejects(setOf(first), setOf(tag, Long.MAX_VALUE))
            rejects(setOf(first), setOf(tag), listOf(BatchTagDraft("先新建", 1), BatchTagDraft("...", 2)))
            dao.setTagBlocked(tag, true)
            rejects(setOf(first), setOf(tag))
        } finally { db.close() }
    }

    @Test fun draftTagsReuseNormalizedNamesAndOnlyExplicitlyUnblockTheirOwnTag() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val repository = LibraryRepository(db)
            val work = dao.insertAnime(AnimeEntity(title = "作品"))
            val blocked = dao.insertTag(TagEntity(name = "愛情", blocked = true, sortOrder = 7))
            val untouched = dao.insertTag(TagEntity(name = "不恢复", blocked = true))
            repository.updateTagsForAnimes(setOf(work), emptySet(), false, listOf(
                BatchTagDraft("爱情", 10), BatchTagDraft(" 愛情 ", 20), BatchTagDraft("新标签", 30),
            ))
            assertEquals(3, dao.getAllTags().size)
            assertFalse(requireNotNull(dao.getTag(blocked)).blocked)
            assertEquals(7, dao.getTag(blocked)?.sortOrder)
            assertTrue(requireNotNull(dao.getTag(untouched)).blocked)
            assertEquals(setOf(blocked, requireNotNull(dao.getTagByName("新标签")).id), dao.getTagIdsForAnime(work).toSet())
        } finally { db.close() }
    }
}
