package com.animeow.app.ui.anime

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.SeriesEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSuggestionTest {
    @Test
    fun stripsCommonSeasonSuffixes() {
        assertEquals("无职转生", seriesBaseTitle("无职转生 第2季"))
        assertEquals("Re:Zero", seriesBaseTitle("Re:Zero Season 3"))
        assertEquals("Sound Euphonium", seriesBaseTitle("Sound Euphonium III"))
    }

    @Test
    fun suggestsExistingSeriesBeforeCreatingOne() {
        val suggestion = buildSeriesSuggestion(
            AnimeEditorState(
                isLoading = false,
                title = "无职转生 第2季",
                series = listOf(SeriesEntity(id = 7, name = "无职转生")),
                libraryAnimes = listOf(AnimeEntity(id = 1, title = "无职转生")),
            ),
        )

        assertTrue(suggestion is SeriesSuggestion.Existing)
        assertEquals(7L, (suggestion as SeriesSuggestion.Existing).series.id)
    }
}
