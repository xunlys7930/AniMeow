package com.animeow.app.ui.anime

import com.animeow.app.data.local.AnimeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

sealed interface AnimeDetailState {
    data object Loading : AnimeDetailState
    data class Loaded(val anime: AnimeEntity) : AnimeDetailState
    data object Missing : AnimeDetailState
    data object Failed : AnimeDetailState
}

internal fun Flow<AnimeEntity?>.detailStates(onFailure: suspend (Throwable) -> Unit = {}): Flow<AnimeDetailState> =
    map<AnimeEntity?, AnimeDetailState> { anime ->
        if (anime == null || anime.deletedAt != null) AnimeDetailState.Missing else AnimeDetailState.Loaded(anime)
    }.onStart { emit(AnimeDetailState.Loading) }
        .catch {
            if (it is CancellationException) throw it
            onFailure(it)
            emit(AnimeDetailState.Failed)
        }
