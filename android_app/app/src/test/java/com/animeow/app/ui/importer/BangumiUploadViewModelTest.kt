package com.animeow.app.ui.importer

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.animeow.app.data.bangumi.BangumiUploadChoice
import com.animeow.app.data.bangumi.BangumiUploadJournal
import com.animeow.app.data.bangumi.BangumiUploadService
import com.animeow.app.data.bangumi.FakeBangumiUploadRemote
import com.animeow.app.data.bangumi.uploadFixtureRow
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class BangumiUploadViewModelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AniMeowDatabase
    private lateinit var directory: File
    private lateinit var remote: FakeBangumiUploadRemote
    private lateinit var model: BangumiUploadViewModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "upload-vm-${UUID.randomUUID()}")
        remote = FakeBangumiUploadRemote()
        val service = BangumiUploadService(context, db, remote, BangumiUploadJournal(directory), pageDelayMs = 0, rowDelayMs = 0)
        store = ViewModelStore()
        model = BangumiUploadViewModel(context, service).also { store.put("vm", it) }
    }

    @After
    fun tearDown() {
        store.clear()
        db.close()
        directory.deleteRecursively()
    }

    @Test
    fun newRemoteSelectionEnforcesStatusInvariantBothWays() = runBlocking {
        db.libraryDao().insertAnime(
            AnimeEntity(title = "新作品", externalSource = "bangumi", externalId = "1", status = "在看", rating = 90, watchedEpisodes = 4),
        )
        model.read()
        waitIdle()
        assertNotNull(model.state.value.preview)

        // Selecting rating without status on remote==null must auto-include status.
        model.select(1L, BangumiUploadChoice(rating = true))
        val first = model.state.value.choices[1L]!!
        assertTrue(first.status)
        assertTrue(first.rating)

        // Unchecking status on remote==null must clear dependent upload fields so fields(choice) cannot throw.
        model.select(1L, first.copy(status = false))
        assertNull(model.state.value.choices[1L])
    }

    @Test
    fun batchDownloadAndPartialClearRespectSafetyRules() = runBlocking {
        // Item 1: remote has higher progress & integer rating 8 while local has fractional 8.5 & ep 2 -> safe download includes status/rating/progress
        db.libraryDao().insertAnime(
            AnimeEntity(title = "作品一", externalSource = "bangumi", externalId = "1", status = "在看", rating = 85, watchedEpisodes = 2),
        )
        // Item 2: remote has rate=0 (clearing!) and lower ep=1 while local has rate=90 and ep 5 -> safe download excludes clearing rating & regressing ep
        db.libraryDao().insertAnime(
            AnimeEntity(title = "作品二", externalSource = "bangumi", externalId = "2", status = "想看", rating = 90, watchedEpisodes = 5),
        )
        remote.rows[1] = uploadFixtureRow(1, type = 2, rate = 8, progress = 6)
        remote.rows[2] = uploadFixtureRow(2, type = 3, rate = 0, progress = 1)

        model.read()
        waitIdle()
        val ids = setOf(1L, 2L)

        model.selectDownloadRows(ids)
        val c1 = model.state.value.choices[1L]!!
        val c2 = model.state.value.choices[2L]!!
        assertTrue(c1.downloadStatus && c1.downloadRating && c1.downloadProgress)
        assertTrue(c2.downloadStatus)
        assertFalse(c2.downloadRating)
        assertFalse(c2.downloadProgress)

        // Toggle UPLOAD status on item 2 only -> turns off its downloadStatus due to mutual exclusivity
        model.toggleBatchField(setOf(2L), BangumiBatchDirection.UPLOAD, BangumiBatchField.STATUS)
        val updatedC2 = model.state.value.choices[2L]!!
        assertTrue(updatedC2.status)
        assertFalse(updatedC2.downloadStatus)

        // Clear only item 1 keeps item 2 intact
        model.clearRows(setOf(1L))
        assertNull(model.state.value.choices[1L])
        assertEquals(1, model.state.value.selectedCount)
    }

    private fun waitIdle() {
        val deadline = System.currentTimeMillis() + 5000
        while (model.state.value.reading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}
