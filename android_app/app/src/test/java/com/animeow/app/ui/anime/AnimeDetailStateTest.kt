package com.animeow.app.ui.anime

import com.animeow.app.data.local.AnimeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AnimeDetailStateTest {
    @Test fun aMissingOrTrashedWorkFinishesLoading() = runBlocking {
        for (anime in listOf(null, AnimeEntity(title = "回收站作品", deletedAt = "2026-11-03"))) {
            assertEquals(listOf(AnimeDetailState.Loading, AnimeDetailState.Missing), flowOf(anime).detailStates().toList())
        }
    }

    @Test fun recordsRemainLiveAndFailuresCanBeRetriedByRecollecting() = runBlocking {
        val anime = AnimeEntity(id = 1, title = "作品", watchedEpisodes = 3)
        var attempts = 0
        val source = flow {
            if (attempts++ == 0) error("private database error")
            emit(anime)
            emit(anime.copy(watchedEpisodes = 4))
            emit(null)
        }.detailStates()
        assertEquals(listOf(AnimeDetailState.Loading, AnimeDetailState.Failed), source.toList())
        assertEquals(listOf(AnimeDetailState.Loading, AnimeDetailState.Loaded(anime),
            AnimeDetailState.Loaded(anime.copy(watchedEpisodes = 4)), AnimeDetailState.Missing), source.toList())
    }

    @Test fun cancellationIsNotDisplayedAsAReadFailure() = runBlocking {
        try {
            flow<AnimeEntity?> { throw CancellationException("leave page") }.detailStates().toList()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
