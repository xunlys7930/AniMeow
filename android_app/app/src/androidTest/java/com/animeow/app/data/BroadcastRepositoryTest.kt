package com.animeow.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BroadcastRepositoryTest {
    private lateinit var database: AniMeowDatabase
    private lateinit var repository: LibraryRepository
    private val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "42", "排期测试作品", airDate = "2026-09-07", broadcastDay = 1)

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AniMeowDatabase::class.java).build()
        repository = LibraryRepository(database)
    }

    @After fun closeDatabase() { database.close() }

    @Test
    fun importingTwiceDoesNotDuplicateWorksOrEnableNotifications() = runBlocking {
        assertEquals(BroadcastImportSummary(added = 1), repository.importBroadcastSchedules(listOf(remote)))
        assertEquals(BroadcastImportSummary(skipped = 1), repository.importBroadcastSchedules(listOf(remote)))
        val anime = repository.getActiveAnimesSnapshot().single()
        assertEquals(1, anime.broadcastDay)
        assertEquals("想看", anime.status)
        assertNull(anime.broadcastTime)
        assertNull(anime.reminderDay)
        assertNull(anime.reminderTime)
        assertNull(anime.watchStartDate)
    }

    @Test
    fun importingPreservesExistingProgressDatesAndManualSchedule() = runBlocking {
        val local = AnimeEntity(title = remote.title, status = "在看", watchedEpisodes = 7, watchStartDate = "2026-08-01", broadcastDay = 3, broadcastTime = "22:00", reminderDay = 5, reminderTime = "18:00")
        val id = repository.saveAnime(local, emptySet())
        repository.importBroadcastSchedules(listOf(remote))
        assertEquals(local.copy(id = id, createdAt = repository.getActiveAnimesSnapshot().single().createdAt), repository.getActiveAnimesSnapshot().single())
    }

    @Test
    fun ambiguousTitlesAndRecycledWorksAreSkipped() = runBlocking {
        repository.saveAnime(AnimeEntity(title = remote.title), emptySet())
        repository.saveAnime(AnimeEntity(title = remote.title), emptySet())
        assertEquals(BroadcastImportSummary(skipped = 1), repository.importBroadcastSchedules(listOf(remote)))
        assertEquals(2, repository.getActiveAnimesSnapshot().size)
        val other = remote.copy(id = "100", title = "已删除作品")
        repository.importBroadcastSchedules(listOf(other))
        val id = repository.getActiveAnimesSnapshot().single { it.externalId == "100" }.id
        repository.deleteAnime(id)
        assertEquals(BroadcastImportSummary(skipped = 1), repository.importBroadcastSchedules(listOf(other)))
        assertEquals(2, repository.getActiveAnimesSnapshot().size)
    }

    @Test
    fun firstAndFinalCheckInUndoRestoresDatesAndRemovesItsRecord() = runBlocking {
        val id = repository.saveAnime(AnimeEntity(title = "单集作品", totalEpisodes = 1), emptySet())
        val change = repository.incrementProgress(id, autoCompleteStatus = true)!!
        val after = repository.getActiveAnimesSnapshot().single()
        assertEquals(LocalDate.now().toString(), after.watchStartDate)
        assertEquals(LocalDate.now().toString(), after.watchFinishDate)
        repository.restoreProgress(change)
        val restored = repository.getActiveAnimesSnapshot().single()
        assertEquals(0, restored.watchedEpisodes)
        assertNull(restored.watchStartDate)
        assertNull(restored.watchFinishDate)
        assertTrue(repository.getWatchRecordsSnapshot().isEmpty())
    }

    @Test
    fun editingOrRemovingScheduleLeavesRemindersAndOtherMetadataUntouched() = runBlocking {
        val id = repository.saveAnime(AnimeEntity(title = "作品", reminderDay = 2, reminderTime = "20:00"), emptySet())
        repository.updateBroadcastSchedule(id, 7, "23:30")
        assertEquals("23:30", repository.getActiveAnimesSnapshot().single().broadcastTime)
        repository.updateBroadcastSchedule(id, null, null)
        val anime = repository.getActiveAnimesSnapshot().single()
        assertNull(anime.broadcastDay)
        assertNull(anime.broadcastTime)
        assertEquals(2, anime.reminderDay)
        assertEquals("20:00", anime.reminderTime)
    }
}
