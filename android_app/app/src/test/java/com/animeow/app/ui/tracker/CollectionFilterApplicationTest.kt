package com.animeow.app.ui.tracker

import android.app.Application
import android.app.Instrumentation
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.*
import com.animeow.app.data.preferences.CollectionFilterConditions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class CollectionFilterApplicationTest {
    @Test fun failedApplicationsKeepTheCurrentSelectionAndResetStaysInsideTheBookCategory() = runBlocking {
        val singleton = AniMeowDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        val app = Instrumentation.newApplication(AniMeowApplication::class.java, RuntimeEnvironment.getApplication()) as AniMeowApplication
        val store = ViewModelStore()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val dao = app.database.libraryDao()
            val book = dao.insertAnime(AnimeEntity(title = "书籍一", subjectType = "book", status = "在看"))
            val otherBook = dao.insertAnime(AnimeEntity(title = "书籍二", subjectType = "book", status = "在看"))
            dao.insertAnime(AnimeEntity(title = "动画", subjectType = "anime", status = "在看"))
            val tag = dao.insertTag(TagEntity(name = "日常"))
            dao.insertAnimeTags(listOf(AnimeTagEntity(book, tag)))
            try { androidx.work.WorkManager.getInstance(app) }
            catch (_: IllegalStateException) { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
            val model = TrackerViewModel(app)
            store.put("tracker", model)
            scope.launch { combine(model.libraryState, model.tags, model.statusOptions) { _, _, _ -> Unit }.collect() }
            waitUntil { !model.libraryState.value.isLoading && model.tags.value.isNotEmpty() && "在看" in model.statusOptions.value }
            val saved = CollectionFilterConditions(subjectType = "book", status = "在看", tags = listOf("日常"))
            assertNull(model.applyCollectionFilter(saved))
            waitUntil { model.libraryState.value.items.flatMap { it.memberAnimeIds } == listOf(book) }
            val before = model.libraryState.value
            assertNotNull(model.applyCollectionFilter(saved.copy(tags = listOf("已删除标签"))))
            assertNotNull(model.applyCollectionFilter(saved.copy(status = "不存在的状态")))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, model.libraryState.value)
            model.clearAdvancedFilters("book")
            waitUntil { model.libraryState.value.items.flatMap { it.memberAnimeIds }.toSet() == setOf(book, otherBook) }
            assertEquals("book", model.advanced.value.subjectType)
        } finally {
            scope.cancel(); store.clear(); app.database.close(); singleton.set(null, null)
        }
    }
    private fun waitUntil(condition: () -> Boolean) {
        repeat(400) { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(10) }
        fail("收藏筛选状态未按预期完成")
    }
}
