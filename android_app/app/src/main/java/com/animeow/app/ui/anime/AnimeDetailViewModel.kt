package com.animeow.app.ui.anime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.ProgressChange
import com.animeow.app.data.bangumi.EpisodeNumbering
import com.animeow.app.data.bangumi.bangumiSubjectIdOf
import com.animeow.app.data.remote.BangumiService
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.remote.RemoteCharacter
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.WorkCharacterCatalog
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WorkCharactersUiState(
    val catalog: WorkCharacterCatalog? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class AnimeDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val characterRepository = (application as AniMeowApplication).characterRepository
    val animeId: Long = checkNotNull(savedStateHandle.get<Long>("animeId"))
    val episodeDetails = repository.observeEpisodeDetails(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val reload = MutableStateFlow(0)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val detailState = reload.flatMapLatest {
        repository.observeAnime(animeId).detailStates { error ->
            runCatchingCancellable { getApplication<AniMeowApplication>().operationLog.recordError(error, "作品详情 · 加载") }
        }
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AnimeDetailState.Loading,
    )

    fun retryLoading() { reload.update { it + 1 } }

    val anime: StateFlow<AnimeEntity?> = detailState.map { (it as? AnimeDetailState.Loaded)?.anime }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )
    // Read-only metadata for this detail page, never a migration of saved progress.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val episodeNumbering: StateFlow<Pair<Long, EpisodeNumbering>?> = anime
        .map { current -> current?.takeIf { it.subjectType == "anime" && it.totalEpisodes > 0 }
            ?.let { value -> bangumiSubjectIdOf(value)?.let { it to value.totalEpisodes } } }
        .distinctUntilChanged()
        .transformLatest { key ->
            emit(null)
            if (key != null) {
                val numbering = runCatchingCancellable {
                    withTimeoutOrNull(10_000) {
                        BangumiService(context = application).fetchEpisodeNumbering(key.first, key.second)
                    }
                }.getOrNull()
                if (numbering?.usesContinuousNumbering == true) emit(key.first to numbering)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val allTags: StateFlow<List<TagEntity>> = repository.observeTags().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val statuses: StateFlow<List<WatchStatusEntity>> = combine(
        repository.observeWatchStatuses(), com.animeow.app.data.preferences.TrackerPreferences(application).settings,
    ) { statuses, settings -> com.animeow.app.data.preferences.visibleWatchStatuses(statuses, settings.hiddenActionStatusIds) }.stateIn(
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
    private val seriesId = anime.map { it?.seriesId }.distinctUntilChanged()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val series: StateFlow<SeriesEntity?> = seriesId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.observeSeries(id)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val siblings: StateFlow<List<AnimeEntity>> = seriesId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeSeriesMembers(id, animeId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val database = (application as AniMeowApplication).database
    val linkedCrossMedia: StateFlow<List<com.animeow.app.data.local.SeriesLinkedCrossMediaEntry>> = combine(
        series,
        database.gameDao().observe(),
        database.audioWorkDao().observe(),
    ) { currentSeries, games, audioWorks ->
        com.animeow.app.data.local.resolveSeriesLinkedCrossMedia(currentSeries?.linkedMediaJson, games, audioWorks)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val _tagIds = MutableStateFlow<Set<Long>>(emptySet())
    val tagIds: StateFlow<Set<Long>> = _tagIds.asStateFlow()
    private val _workCharacters = MutableStateFlow(WorkCharactersUiState())
    val workCharacters = _workCharacters.asStateFlow()
    private var workCharactersJob: Job? = null
    private var workCharactersIdentity: List<String?>? = null
    private var chosenCastWork: RemoteAnime? = null
    private var castWorkQuery: String? = null

    init {
        viewModelScope.launch {
            anime.filterNotNull().first()
            runCatchingCancellable { repository.getTagIdsForAnime(animeId).toSet() }
                .onSuccess { _tagIds.value = it }
                .onFailure { error ->
                    runCatchingCancellable { getApplication<AniMeowApplication>().operationLog.recordError(error, "作品详情 · 标签") }
                }
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

    fun updateMemo(memo: String?) {
        viewModelScope.launch {
            runCatchingCancellable { repository.updateAnimeMemo(animeId, memo) }
                .onFailure { error ->
                    runCatchingCancellable { getApplication<AniMeowApplication>().operationLog.recordError(error, "作品详情 · 私密备注") }
                }
        }
    }

    suspend fun setStatus(status: String) {
        repository.updateStatuses(setOf(animeId), status)
        val app = getApplication<AniMeowApplication>()
        if (status.trim() in com.animeow.app.data.REMINDER_STOP_STATUSES) {
            val settings = com.animeow.app.data.preferences.ReminderPreferences(app).snapshot()
            if (settings.autoStopWhenCompleted) {
                // 只停调度：作品状态可能改回"在看"，用户设定的次数与截止日期要保留。
                app.reminderScheduler.stopScheduling(animeId)
            }
        }
    }

    suspend fun saveReminder(day: Int?, time: String?, remainingCount: Int? = null, untilDate: String? = null) {
        val updated = repository.setReminder(animeId, day, time)
        val scheduler = getApplication<AniMeowApplication>().reminderScheduler
        if (day == null) {
            scheduler.cancel(animeId)
        } else {
            scheduler.setReminderLimit(animeId, remainingCount, untilDate)
            scheduler.schedule(updated)
        }
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

    suspend fun setCharacterVoiceActor(characterId: Long, value: String) {
        characterRepository.setVoiceActor(characterId, animeId, value)
    }

    suspend fun deleteCharacterPermanently(characterId: Long) {
        characterRepository.deleteCharacter(characterId)
    }

    fun loadWorkCharacters(selected: RemoteAnime? = null, force: Boolean = false, workQuery: String? = null) {
        val current = anime.value ?: return
        val identity = listOf(current.title, current.subjectType, current.externalSource, current.externalId, current.externalUrl)
        if (identity != workCharactersIdentity) {
            workCharactersJob?.cancel()
            workCharactersIdentity = identity
            chosenCastWork = null
            castWorkQuery = null
            _workCharacters.value = WorkCharactersUiState()
        }
        if (!force && selected == null && workQuery == null && (_workCharacters.value.catalog != null || workCharactersJob?.isActive == true)) return
        if (workQuery != null) { castWorkQuery = workQuery.trim(); chosenCastWork = null }
        if (selected != null) chosenCastWork = selected
        val query = castWorkQuery
        val chosen = chosenCastWork
        workCharactersJob?.cancel()
        _workCharacters.value = _workCharacters.value.copy(loading = true, error = null)
        workCharactersJob = viewModelScope.launch {
            runCatchingCancellable {
                val work = if (query.isNullOrBlank()) current else current.copy(title = query, externalSource = null, externalId = null, externalUrl = null)
                withTimeoutOrNull(12_000) { characterRepository.workCharacters(work, chosen, force) } ?: error("角色列表加载超时，请重试")
            }.onSuccess { _workCharacters.value = WorkCharactersUiState(catalog = it) }
                .onFailure { _workCharacters.value = _workCharacters.value.copy(loading = false, error = it.message ?: "角色列表加载失败") }
        }
    }

    suspend fun importAndLinkCharacter(character: RemoteCharacter, roleName: String?) {
        val characterId = characterRepository.importBangumi(character)
        characterRepository.linkWork(characterId, animeId, roleName)
    }
}
