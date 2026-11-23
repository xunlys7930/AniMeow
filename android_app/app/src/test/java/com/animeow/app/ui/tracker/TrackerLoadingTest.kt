package com.animeow.app.ui.tracker

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.preferences.TrackerSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TrackerLoadingTest {
    private val rules = LibraryDisplayRules(BaseLibraryFilters("", "全部", LibrarySort.RECENT, false), AdvancedLibraryFilters(), TrackerSettings())

    @Test fun startupWaitsForDatabaseAndSavedFiltersWithoutPublishingAnEmptyLibrary() = runBlocking {
        val animes = MutableSharedFlow<List<AnimeEntity>>(replay = 1)
        val tags = MutableSharedFlow<List<AnimeTagEntity>>(replay = 1)
        val series = MutableSharedFlow<List<SeriesEntity>>(replay = 1)
        val filters = MutableSharedFlow<LibraryDisplayRules>(replay = 1)
        val first = async { trackerLibraryState(animes, tags, series, filters).first() }
        animes.emit(listOf(AnimeEntity(id = 1, title = "已保存的作品", status = "在看")))
        tags.emit(emptyList())
        yield()
        assertFalse(first.isCompleted)
        series.emit(emptyList())
        yield()
        assertFalse(first.isCompleted)
        filters.emit(rules)
        val loaded = withTimeout(2_000) { first.await() }
        assertFalse(loaded.isLoading)
        assertEquals("已保存的作品", loaded.items.single().title)
    }

    @Test fun anActuallyEmptyDatabaseCanFinishLoading() = runBlocking {
        val loaded = trackerLibraryState(flowOf(emptyList()), flowOf(emptyList()), flowOf(emptyList()), flowOf(rules)).first()
        assertFalse(loaded.isLoading)
        assertTrue(loaded.items.isEmpty())
    }

    @Test fun firstDisplayUsesSavedFiltersAndSeriesTogether() = runBlocking {
        val watching = AnimeEntity(id = 1, title = "第一季", status = "在看", seriesId = 2)
        val completed = watching.copy(id = 3, title = "第二季", status = "看完")
        val loaded = trackerLibraryState(flowOf(listOf(watching, completed)), flowOf(emptyList()),
            flowOf(listOf(SeriesEntity(id = 2, name = "已保存系列"))),
            flowOf(rules.copy(filters = rules.filters.copy(status = "在看")))).first()
        assertFalse(loaded.isLoading)
        assertEquals(setOf(1L), loaded.items.flatMap { it.memberAnimeIds }.toSet())
    }
}
