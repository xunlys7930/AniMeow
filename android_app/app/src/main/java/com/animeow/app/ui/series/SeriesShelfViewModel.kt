package com.animeow.app.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.RemoteMetadataFields
import com.animeow.app.data.preferences.SeriesDisplaySettings
import com.animeow.app.data.preferences.SeriesPreferences
import com.animeow.app.data.preferences.SeriesSort
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.ui.tracker.BatchMetadataOptions
import com.animeow.app.ui.tracker.BatchMetadataState
import com.animeow.app.ui.tracker.selectBestRemoteMetadataMatch
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

data class SeriesShelfItem(
    val series: SeriesEntity,
    val animes: List<AnimeEntity>,
) {
    val coverUrl: String? = series.customCoverUrl
        ?: animes.sortedByDescending(AnimeEntity::id).firstNotNullOfOrNull(AnimeEntity::coverUrl)
    val watchedEpisodes: Int = animes.sumOf(AnimeEntity::watchedEpisodes)
    val totalEpisodes: Int = animes.sumOf(AnimeEntity::totalEpisodes)
}

data class SeriesShelfState(
    val series: List<SeriesShelfItem> = emptyList(),
    val standalone: List<AnimeEntity> = emptyList(),
)

class SeriesShelfViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository

    val state: StateFlow<SeriesShelfState> = combine(
        repository.observeSeries(),
        repository.observeAnimes(),
    ) { allSeries, animes ->
        SeriesShelfState(
            series = allSeries.map { series ->
                SeriesShelfItem(series, animes.filter { it.seriesId == series.id })
            }.filter { it.animes.isNotEmpty() },
            standalone = animes.filter { it.seriesId == null },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SeriesShelfState(),
    )
}

data class SeriesDetailState(
    val isLoaded: Boolean = false,
    val series: SeriesEntity? = null,
    val animes: List<AnimeEntity> = emptyList(),
    val availableAnimes: List<AnimeEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val statuses: List<WatchStatusEntity> = emptyList(),
    val displaySettings: SeriesDisplaySettings = SeriesDisplaySettings(),
)

class SeriesDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val discoveryRepository = (application as AniMeowApplication).discoveryRepository
    private val reminderScheduler = (application as AniMeowApplication).reminderScheduler
    private val seriesId = checkNotNull(savedStateHandle.get<Long>("seriesId"))
    private val preferences = SeriesPreferences(application)
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _batchMetadataState = MutableStateFlow(BatchMetadataState())
    val batchMetadataState: StateFlow<BatchMetadataState> = _batchMetadataState.asStateFlow()
    private var batchMetadataJob: Job? = null
    private var currentMetadataRequest: Deferred<RemoteAnime?>? = null

    val state: StateFlow<SeriesDetailState> = combine(
        repository.observeSeries(),
        repository.observeAnimes(),
        repository.observeTags(),
        repository.observeWatchStatuses(),
        preferences.observe(seriesId),
    ) { allSeries, animes, tags, statuses, displaySettings ->
        SeriesDetailState(
            isLoaded = true,
            series = allSeries.firstOrNull { it.id == seriesId },
            animes = animes.filter { it.seriesId == seriesId }
                .sortedWith(compareBy<AnimeEntity> { it.airDate }.thenBy { it.id }),
            availableAnimes = animes.filter { it.seriesId != seriesId },
            tags = tags,
            statuses = statuses,
            displaySettings = displaySettings,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SeriesDetailState(),
    )

    fun toggleSelection(animeId: Long) {
        _selectedIds.update { selected ->
            if (animeId in selected) selected - animeId else selected + animeId
        }
    }

    fun selectAll(visibleIds: Set<Long>) {
        _selectedIds.value = if (_selectedIds.value == visibleIds) emptySet() else visibleIds
    }

    fun setSort(value: SeriesSort) = updateDisplay { it.copy(sort = value) }
    fun setAscending(value: Boolean) = updateDisplay { it.copy(ascending = value) }
    fun setColumns(value: Int?) = updateDisplay { it.copy(columnsOverride = value) }
    fun setStatusFilter(value: String?) {
        clearSelection()
        updateDisplay { it.copy(statusFilter = value) }
    }

    private fun updateDisplay(transform: (SeriesDisplaySettings) -> SeriesDisplaySettings) {
        viewModelScope.launch {
            runCatchingCancellable { preferences.update(seriesId, transform) }
                .onFailure { _messages.emit("显示设置未能保存，请重试") }
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun saveSeriesInfo(name: String, description: String) = runOperation("系列信息已保存") {
        val current = state.value.series ?: error("系列已不存在")
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "系列名称不能为空" }
        repository.saveSeries(
            current.copy(
                name = normalized,
                description = description.trim().takeIf(String::isNotBlank),
            ),
        )
    }

    fun setSeriesCover(coverUrl: String?) = runOperation("系列封面已更新") {
        repository.updateSeriesCover(seriesId, coverUrl)
    }

    fun addAnimes(animeIds: Set<Long>) = runOperation("已添加 ${animeIds.size} 部作品") {
        repository.assignSeriesToAnimes(animeIds, seriesId)
    }

    fun setSelectedStatus(status: String) = withSelection("已批量更新状态") { selected ->
        repository.updateStatuses(selected, status)
    }

    fun addTagToSelected(tagId: Long) = withSelection("已批量添加标签") { selected ->
        repository.addTagToAnimes(selected, tagId)
    }

    fun removeTagFromSelected(tagId: Long) = withSelection("已批量移除标签") { selected ->
        repository.removeTagFromAnimes(selected, tagId)
    }

    fun createTagAndAddToSelected(name: String, color: Long) = withSelection("已新建标签并批量添加") { selected ->
        repository.createTagAndAddToAnimes(selected, name, color)
    }

    fun removeSelectedFromSeries() = withSelection("已移出系列") { selected ->
        repository.removeSeriesFromAnimes(selected)
    }

    fun moveSelectedToTrash() = withSelection("已移入回收站，可随时恢复") { selected ->
        repository.deleteAnimes(selected).forEach(::cancelReminderSafely)
    }

    fun startBatchMetadataMatch(options: BatchMetadataOptions) {
        if (_batchMetadataState.value.isRunning || !options.fields.hasSelection) return
        val selected = _selectedIds.value
        if (selected.isEmpty()) return
        batchMetadataJob = viewModelScope.launch {
            val candidates = state.value.animes.filter { it.id in selected }
            _batchMetadataState.value = BatchMetadataState(isRunning = true, total = candidates.size)
            var updated = 0
            var unmatched = 0
            var failed = 0
            try {
                candidates.forEachIndexed { index, anime ->
                    if (!isActive) return@launch
                    _batchMetadataState.update {
                        it.copy(completed = index, currentTitle = anime.title, message = null)
                    }
                    val outcome = runCatching {
                        val remote = findRemoteMetadataMatch(anime)
                        when {
                            remote == null -> SeriesBatchMatchOutcome.UNMATCHED
                            repository.applyRemoteMetadata(
                                animeId = anime.id,
                                remote = remote,
                                fields = options.fields,
                                overwriteExisting = options.overwriteExisting,
                            ) -> SeriesBatchMatchOutcome.UPDATED
                            else -> SeriesBatchMatchOutcome.UNCHANGED
                        }
                    }.getOrElse { error ->
                        if (error is CancellationException && !isActive) throw error
                        SeriesBatchMatchOutcome.FAILED
                    }
                    when (outcome) {
                        SeriesBatchMatchOutcome.UPDATED -> updated += 1
                        SeriesBatchMatchOutcome.UNMATCHED -> unmatched += 1
                        SeriesBatchMatchOutcome.FAILED -> failed += 1
                        SeriesBatchMatchOutcome.UNCHANGED -> Unit
                    }
                    _batchMetadataState.update {
                        it.copy(
                            completed = index + 1,
                            updated = updated,
                            unmatched = unmatched,
                            failed = failed,
                        )
                    }
                }
                clearSelection()
                _batchMetadataState.update {
                    it.copy(
                        isRunning = false,
                        currentTitle = null,
                        message = "已更新 $updated 部，未匹配 $unmatched 部，失败 $failed 部",
                    )
                }
            } catch (_: CancellationException) {
                _batchMetadataState.update {
                    it.copy(
                        isRunning = false,
                        currentTitle = null,
                        message = "已停止：完成 ${it.completed} / ${it.total} 部",
                    )
                }
            } finally {
                currentMetadataRequest = null
                batchMetadataJob = null
            }
        }
    }

    fun skipCurrentMetadataMatch() {
        currentMetadataRequest?.cancel()
    }

    fun cancelBatchMetadataMatch() {
        batchMetadataJob?.cancel()
    }

    fun clearBatchMetadataMessage() {
        if (!_batchMetadataState.value.isRunning) _batchMetadataState.value = BatchMetadataState()
    }

    private fun runOperation(success: String, action: suspend () -> Unit) = viewModelScope.launch {
        try {
            action()
            _messages.emit(success)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            _messages.emit(error.message?.takeIf(String::isNotBlank) ?: "操作失败")
        } catch (error: IllegalStateException) {
            _messages.emit(error.message?.takeIf(String::isNotBlank) ?: "操作失败")
        } catch (_: Exception) {
            _messages.emit("操作失败，请稍后重试")
        }
    }

    private fun withSelection(
        success: String,
        action: suspend (Set<Long>) -> Unit,
    ) = viewModelScope.launch {
        val selected = _selectedIds.value
        if (selected.isEmpty()) return@launch
        try {
            action(selected)
            clearSelection()
            _messages.emit(success)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            _messages.emit(error.message?.takeIf(String::isNotBlank) ?: "操作失败")
        } catch (error: IllegalStateException) {
            _messages.emit(error.message?.takeIf(String::isNotBlank) ?: "操作失败")
        } catch (_: Exception) {
            _messages.emit("操作失败，请稍后重试")
        }
    }

    private fun cancelReminderSafely(animeId: Long) {
        try {
            reminderScheduler.cancel(animeId)
        } catch (error: Exception) {
            getApplication<AniMeowApplication>().operationLog.recordError(error, "系列详情 · 取消提醒")
        }
    }

    private suspend fun findRemoteMetadataMatch(anime: AnimeEntity): RemoteAnime? = supervisorScope {
        val source = when (anime.externalSource?.lowercase(java.util.Locale.ROOT)) {
            "bangumi" -> DiscoverySourceFilter.BANGUMI
            "anilist" -> DiscoverySourceFilter.ANILIST
            "server" -> DiscoverySourceFilter.SERVER
            else -> DiscoverySourceFilter.ALL
        }
        suspend fun search(query: String): RemoteAnime? {
            val request = async {
                val result = discoveryRepository.search(query, source, normalizeSubjectType(anime.subjectType))
                selectBestRemoteMetadataMatch(anime, result.items)
            }
            currentMetadataRequest = request
            return try {
                withTimeoutOrNull(25_000) { request.await() }
            } catch (error: CancellationException) {
                if (!isActive) throw error
                null
            } finally {
                if (currentMetadataRequest === request) currentMetadataRequest = null
            }
        }
        search(anime.title)?.let { return@supervisorScope it }
        anime.originalTitle?.trim()?.takeIf { it.isNotEmpty() && !it.equals(anime.title, true) }
            ?.let { original -> search(original)?.let { return@supervisorScope it } }
        null
    }
}

private enum class SeriesBatchMatchOutcome {
    UPDATED,
    UNCHANGED,
    UNMATCHED,
    FAILED,
}
