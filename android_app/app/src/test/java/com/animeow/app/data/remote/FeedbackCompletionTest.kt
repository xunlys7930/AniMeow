package com.animeow.app.data.remote

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import com.animeow.app.data.LibraryRepository
import com.animeow.app.data.local.*
import com.animeow.app.ui.tags.matchingTagAnimes
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class FeedbackCompletionTest {
    @Test fun ratingCountsAreIndependentFromPopularityAndPersonalRatings() {
        val result = BangumiService().parseSearchResponse("""{"data":[{"id":253,"name":"作品","rating":{"score":8.5,"total":2,"rank":0},"collection":{"wish":100,"collect":200}}]}""").single()
        assertEquals(2, result.ratingCount); assertNull(result.sourceRank); assertEquals(300, result.popularity)
        val saved = AnimeEntity(title = "作品", externalSource = "bangumi", externalId = "253", rating = 60, sourceRatingJson = result.sourceRatingSnapshot())
        assertEquals(2, saved.publicRating()!!.getInt("count")); assertEquals(60, saved.rating)
        assertNull(saved.copy(externalId = "254").publicRating())
        val ani = result.copy(source = RemoteCatalogSource.ANILIST, id = "1", score = 85.0)
        assertEquals(8.5, JSONObject(ani.sourceRatingSnapshot()!!).getDouble("score"), 0.001)
        val none = BangumiService().parseSearchResponse("""{"data":[{"id":254,"name":"未评分","rating":{"score":0,"total":0,"rank":0}}]}""").single()
        assertEquals(0, none.ratingCount); assertNull(none.score); assertNull(none.sourceRank)
    }

    @Test fun staffKeepsPeopleIdentitiesDifferentJobsAndCacheState() {
        val result = parseBangumiStaff("""{"_animeow_data":[{"id":1,"name":"同名","relation":"导演"},{"id":1,"name":"同名","relation":"脚本","eps":"1-3"},{"id":2,"name":"同名","relation":"导演"},{"id":1,"name":"同名","relation":"导演"}],"_animeow_cache":{"stale":true,"updatedAt":"2026-10-24T00:00:00Z"}}""")
        assertEquals(3, result.people.size); assertTrue(result.stale)
        assertEquals("1-3", result.people[1].episodes)
    }

    @Test fun snapshotMergesUseSourceIdentityAndLatestObservationWithoutLosingKnownCounts() {
        val old = """{"source":"bangumi","id":"253","count":100,"updatedAt":"2026-10-23T00:00:00Z"}"""
        val recent = """{"source":"bangumi","id":"253","count":200,"updatedAt":"2026-10-24T00:00:00Z"}"""
        assertEquals(recent, mergeSourceRatingSnapshots(old, recent))
        assertEquals(recent, mergeSourceRatingSnapshots(recent, old))
        assertEquals(recent, mergeSourceRatingSnapshots(recent, null))
        assertEquals(recent, mergeSourceRatingSnapshots(recent, recent.replace("253", "999")))
    }

    @Test fun shortCommentsAndLongReviewsKeepPaginationAndSafeLinks() {
        val direct = parseBangumiDiscussion("""{"total":41,"data":[{"id":1,"user":{"nickname":"读者"},"comment":"评论","rate":9,"updatedAt":100}]}""", 253, "comments", 2)
        assertTrue(direct.hasMore); assertEquals(2, direct.page); assertEquals(9, direct.comments.single().rate)
        assertEquals("https://bgm.tv/subject/253/comments", direct.comments.single().url)
        val server = parseBangumiDiscussion("""{"total":1,"data":[{"id":1,"author":"读者","title":"长评","text":"摘要","url":"https://bgm.tv/blog/2"}],"_animeow_cache":{"stale":true}}""", 253, "reviews", 1)
        assertTrue(server.stale); assertFalse(server.hasMore); assertEquals("摘要", server.comments.single().text)
        val filtered = parseBangumiDiscussion("""{"total":3,"data":[{"id":1,"entry":{"id":2,"public":false}},{"id":2,"url":"javascript:alert(1)"},{"id":3,"entry":{"id":4,"public":true,"title":"公开","summary":"<b>摘要</b>"}}]}""", 253, "reviews", 1)
        assertEquals(listOf(3L), filtered.comments.map { it.id }); assertEquals("摘要", filtered.comments.single().text)
        assertThrows(Exception::class.java) { parseBangumiDiscussion("""{"data":[]}""", 253, "comments", 1) }
    }

    @Test fun tagIntersectionUnionEmptyDeletedAndMissingSelectionsNeverBroadenUnexpectedly() {
        val works = listOf(AnimeEntity(id = 1, title = "甲"), AnimeEntity(id = 2, title = "乙", subjectType = "book"), AnimeEntity(id = 3, title = "回收站", deletedAt = "2026-10-24"))
        val links = listOf(AnimeTagEntity(1, 1), AnimeTagEntity(1, 2), AnimeTagEntity(1, 2), AnimeTagEntity(2, 2), AnimeTagEntity(3, 1))
        assertEquals(listOf(1L), matchingTagAnimes(works, links, setOf(1, 2), true).map { it.id })
        assertEquals(listOf(1L, 2L), matchingTagAnimes(works, links, setOf(1, 2), false).map { it.id })
        assertTrue(matchingTagAnimes(works, links, emptySet(), false).isEmpty())
        assertTrue(matchingTagAnimes(works, links, setOf(1, 99), true).isEmpty())
        assertEquals(listOf(1L), matchingTagAnimes(works, links, setOf(1, 99), false).map { it.id })
    }

    @Test fun refreshingPublicRatingDoesNotChangeProgressReviewRatingOrTheWrongWork() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao(); val repo = LibraryRepository(db)
            val id = dao.insertAnime(AnimeEntity(title = "个人标题", watchedEpisodes = 7, rating = 60, review = "个人感想", externalSource = "bangumi", externalId = "253"))
            val original = dao.getAnime(id)!!
            val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "253", "来源标题", score = 8.5, ratingCount = 100, sourceRank = 1)
            repo.updatePublicRating(id, remote)
            val current = dao.getAnime(id)!!
            assertEquals(original, current.copy(sourceRatingJson = null)); assertEquals(100, current.publicRating()!!.getInt("count"))
            repo.updatePublicRating(id, remote.copy(id = "999")); assertEquals(current, dao.getAnime(id))
            repo.saveAnime(current.copy(title = "编辑后"), emptySet())
            assertEquals(current.sourceRatingJson, dao.getAnime(id)!!.sourceRatingJson)
            dao.updateAnime(current.copy(deletedAt = "2026-10-24"))
            repo.updatePublicRating(id, remote.copy(ratingCount = 200))
            assertEquals(current.sourceRatingJson, dao.getAnime(id)!!.sourceRatingJson)
        } finally { db.close() }
    }

    @Test fun version20UpgradeKeepsRecordsAndStartsWithUnknownSourceStats() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "rating-migration-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AniMeowDatabase::class.java, name).allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).build()
        var db = open()
        try {
            val id = db.libraryDao().insertAnime(AnimeEntity(title = "旧记录", rating = 70, review = "保留", watchedEpisodes = 8))
            db.openHelper.writableDatabase.execSQL("ALTER TABLE animes DROP COLUMN updatedAt")
            db.openHelper.writableDatabase.apply { execSQL("ALTER TABLE series DROP COLUMN linkedMediaJson"); execSQL("ALTER TABLE animes DROP COLUMN memo"); execSQL("ALTER TABLE animes DROP COLUMN seriesRole"); execSQL("ALTER TABLE animes DROP COLUMN bookReadVolumes"); execSQL("ALTER TABLE animes DROP COLUMN bookReadChapters"); execSQL("ALTER TABLE animes DROP COLUMN sourceRatingJson"); version = 20 }
            db.close(); db = open()
            val row = db.libraryDao().getAnime(id)!!
            assertNull(row.sourceRatingJson); assertEquals(70, row.rating); assertEquals(8, row.watchedEpisodes); assertEquals("保留", row.review)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
