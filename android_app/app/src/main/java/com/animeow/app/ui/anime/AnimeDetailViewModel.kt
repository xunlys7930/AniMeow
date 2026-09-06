package com.animeow.app.ui.anime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.ProgressChange
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.remote.RemoteCharacter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AnimeDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val characterRepository = (application as AniMeowApplication).characterRepository
    val animeId: Long = checkNotNull(savedStateHandle.get<Long>("animeId"))

    val anime: StateFlow<AnimeEntity?> = repository.observeAnime(animeId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )
    val allTags: StateFlow<List<TagEntity>> = repository.observeTags().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val statuses: StateFlow<List<WatchStatusEntity>> = repository.observeWatchStatuses().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val characters = characterRepository.observeCharactersForAnime(animeId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val allCharacters: StateFlow<List<CharacterListItem>> = characterRepository.observeCharacters().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val series: StateFlow<SeriesEntity?> = combine(
        anime,
        repository.observeSeries(),
    ) { current, allSeries -> allSeries.firstOrNull { it.id == current?.seriesId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
    val siblings: StateFlow<List<AnimeEntity>> = combine(
        anime,
        repository.observeAnimes(),
    ) { current, allAnimes ->
        val seriesId = current?.seriesId ?: return@combine emptyList()
        allAnimes.filter { it.seriesId == seriesId && it.id != animeId }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val _tagIds = MutableStateFlow<Set<Long>>(emptySet())
    val tagIds: StateFlow<Set<Long>> = _tagIds.asStateFlow()

    init {
        viewModelScope.launch {
            _tagIds.value = repository.getTagIdsForAnime(animeId).toSet()
        }
    }

    suspend fun incrementProgress(
        autoCompleteStatus: Boolean,
        completionStatus: String,
    ): ProgressChange? = anime.value?.let {
        repository.incrementProgress(it.id, autoCompleteStatus, completionStatus)
    }

    suspend fun restoreProgress(change: ProgressChange) {
        repository.restoreProgress(change)
    }

    suspend fun setProgress(progress: Int) {
        repository.setProgress(animeId, progress)
    }

    suspend fun swapSynopsisAndReview() {
        repository.swapSynopsisAndReview(animeId)
    }

    suspend fun setStatus(status: String) {
        repository.updateStatuses(setOf(animeId), status)
    }

    suspend fun deleteAnime() {
        repository.deleteAnime(animeId) ?: error("作品已被移动或不存在")
        val app = getApplication<AniMeowApplication>()
        try {
            app.reminderScheduler.cancel(animeId)
        } catch (error: Exception) {
            app.operationLog.recordError(error, "番剧详情 · 取消提醒")
        }
    }

    suspend fun linkCharacter(characterId: Long, roleName: String?) {
        characterRepository.linkWork(characterId, animeId, roleName)
    }

    suspend fun unlinkCharacter(characterId: Long) {
        characterRepository.unlinkWork(characterId, animeId)
    }

    suspend fun deleteCharacterPermanently(characterId: Long) {
        characterRepository.deleteCharacter(characterId)
    }

    suspend fun searchRemoteCharacters(query: String): List<RemoteCharacter> =
        characterRepository.searchBangumi(query)

    suspend fun importAndLinkCharacter(character: RemoteCharacter, roleName: String?) {
        val characterId = characterRepository.importBangumi(character)
        characterRepository.linkWork(characterId, animeId, roleName)
    }
}
