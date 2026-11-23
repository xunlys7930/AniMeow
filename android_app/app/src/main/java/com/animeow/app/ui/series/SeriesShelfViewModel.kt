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
import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.preferences.SeriesDisplaySettings
import com.animeow.app.data.preferences.SeriesPreferences
import com.animeow.app.data.preferences.SeriesSort
import com.animeow.app.ui.tracker.BatchMetadataOptions
import com.animeow.app.ui.tracker.BatchMetadataState
import com.animeow.app.ui.tracker.selectBestRemoteMetadataMatch
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CancellationException
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

data class SeriesShelfItem(
    val series: SeriesEntity,
    val animes: List<AnimeEntity>,
    val linkedCrossMedia: List<com.animeow.app.data.local.SeriesLinkedCrossMediaEntry> = emptyList(),
) {
    val coverUrl: String? = series.customCoverUrl
        ?: animes.sortedByDescending(AnimeEntity::id).firstNotNullOfOrNull(AnimeEntity::coverUrl)
        ?: linkedCrossMedia.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl
    val watchedEpisodes: Int = animes.sumOf(AnimeEntity::watchedEpisodes)
    val totalEpisodes: Int = animes.sumOf(AnimeEntity::totalEpisodes)
    val totalMemberCount: Int = animes.size + linkedCrossMedia.size
}

data class SeriesShelfState(
    val series: List<SeriesShelfItem> = emptyList(),
    val standalone: List<AnimeEntity> = emptyList(),
)

class SeriesShelfViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val database = (application as AniMeowApplication).database

    val state: StateFlow<SeriesShelfState> = combine(
        repository.observeSeries(),
        repository.observeAnimes(),
        database.gameDao().observe(),
        database.audioWorkDao().observe(),
    ) { allSeries, animes, games, audioWorks ->
        SeriesShelfState(
            series = allSeries.map { series ->
                val crossMedia = com.animeow.app.data.local.resolveSeriesLinkedCrossMedia(series.linkedMediaJson, games, audioWorks)
                SeriesShelfItem(series, animes.filter { it.seriesId == series.id }, crossMedia)
            }.filter { it.totalMemberCount > 0 },
            standalone = animes.filter { it.seriesId == null },
        )
    }.flowOn(Dispatchers.Default).stateIn(
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
    val linkedCrossMedia: List<com.animeow.app.data.local.SeriesLinkedCrossMediaEntry> = emptyList(),
    val availableGames: List<com.animeow.app.data.games.GameEntity> = emptyList(),
    val availableAudioWorks: List<com.animeow.app.data.audio.AudioWork> = emptyList(),
    val seriesNames: Map<Long, String> = emptyMap(),
    val tags: List<TagEntity> = emptyList(),
    val statuses: List<WatchStatusEntity> = emptyList(),
    val displaySettings: SeriesDisplaySettings = SeriesDisplaySettings(),
)

class SeriesDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val database = (application as AniMeowApplication).database
    private val discoveryRepository = (application as AniMeowApplication).discoveryRepository
    private val reminderScheduler = (application as AniMeowApplication).reminderScheduler
    private val seriesId = checkNotNull(savedStateHandle.get<Long>("seriesId"))
    private val preferences = SeriesPreferences(application)
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val batchMetadata = com.animeow.app.ui.tracker.BatchMetadataController(viewModelScope, repository, search = { anime, query ->
        discoveryRepository.searchMetadata(query, normalizeSubjectType(anime.subjectType), anime.externalSource, anime.externalId) { selectBestRemoteMetadataMatch(anime, it) != null }
    },
        onError = { getApplication<AniMeowApplication>().operationLog.recordError(it, "系列批量补全") },
        onFinished = { succeeded -> _selectedIds.value = _selectedIds.value - succeeded })
    val batchMetadataState: StateFlow<BatchMetadataState> = batchMetadata.state

    val state: StateFlow<SeriesDetailState> = combine(
        combine(
            repository.observeSeries(),
            repository.observeAnimes(),
            database.gameDao().observe(),
            database.audioWorkDao().observe(),
        ) { allSeries, animes, games, audioWorks ->
            val currentSeries = allSeries.firstOrNull { it.id == seriesId }
            val crossMedia = com.animeow.app.data.local.resolveSeriesLinkedCrossMedia(currentSeries?.linkedMediaJson, games, audioWorks)
            val linkedKeys = crossMedia.mapTo(hashSetOf()) { it.key }
            SeriesDetailState(
                isLoaded = true,
                series = currentSeries,
                animes = animes.filter { it.seriesId == seriesId }
                    .sortedWith(compareBy<AnimeEntity> { it.airDate }.thenBy { it.id }),
                availableAnimes = animes.filter { it.seriesId != seriesId },
                linkedCrossMedia = crossMedia,
                availableGames = games.filter { "GAME:${it.syncId}" !in linkedKeys },
                availableAudioWorks = audioWorks.filter { "AUDIO:${it.syncId}" !in linkedKeys },
                seriesNames = allSeries.associate { it.id to it.name },
            )
        },
        repository.observeTags(),
        combine(repository.observeWatchStatuses(), com.animeow.app.data.preferences.TrackerPreferences(application).settings) { statuses, tracker ->
            com.animeow.app.data.preferences.visibleWatchStatuses(statuses, tracker.hiddenActionStatusIds)
        },
        preferences.observe(seriesId),
    ) { base, tags, statuses, displaySettings ->
        base.copy(
            tags = tags,
            statuses = statuses,
            displaySettings = displaySettings,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SeriesDetailState(),
    )

    internal val sourceWorks = MutableStateFlow(SeriesSourceState())
    private var sourceJob: kotlinx.coroutines.Job? = null
    private var sourceGeneration = 0

    internal fun closeSourceWorks() {
        if (sourceWorks.value.saving) return
        sourceGeneration++
        sourceJob?.cancel()
        sourceWorks.value = SeriesSourceState()
    }

    internal fun searchSeriesWorks(query: String) = loadSourceWorks("搜索：${query.trim()}（动画与书籍各首批最多 24 项）") {
        val clean = query.trim()
        require(clean.isNotEmpty() && clean.length <= 200) { "请输入 1～200 字的作品名称或 Bangumi 编号" }
        val api = com.animeow.app.data.remote.BangumiService(context = getApplication())
        val items = if (clean.matches(Regex("[1-9][0-9]{0,9}"))) {
            listOfNotNull(api.seriesSubject(clean.toLong(), "anime"), api.seriesSubject(clean.toLong(), "book"))
        } else {
            api.search(clean, "anime") + api.search(clean, "book")
        }
        items.map { com.animeow.app.data.remote.SeriesSourceWork(it.importId, it.title,
            if (it.subjectType == "book") 1 else 2, "", it.cacheStale) }
    }

    internal fun findSeriesRelations(anime: AnimeEntity) = loadSourceWorks("来源：${anime.title} 的 Bangumi 直接关联") {
        val ids = anime.sourceReferences().filter { it.source == "bangumi" }.mapNotNull { it.externalId?.toLongOrNull() }.distinct()
        require(ids.size == 1) { "这部作品没有唯一的 Bangumi 关联，请先搜索添加或在作品编辑中核对来源" }
        com.animeow.app.data.remote.BangumiService(context = getApplication()).seriesRelations(ids.single())
    }

    private fun loadSourceWorks(origin: String, fetch: suspend () -> List<com.animeow.app.data.remote.SeriesSourceWork>) {
        if (sourceWorks.value.saving) return
        sourceJob?.cancel()
        val generation = ++sourceGeneration
        sourceWorks.value = SeriesSourceState(loading = true)
        sourceJob = viewModelScope.launch {
            try {
                val rows = fetch()
                if (generation == sourceGeneration) sourceWorks.value = SeriesSourceState(items = rows, loaded = true, origin = origin)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == sourceGeneration) sourceWorks.value = SeriesSourceState(error =
                    if (error is IllegalArgumentException) error.message else "读取失败，请检查网络后重试")
            }
        }
    }

    internal fun toggleSourceWork(id: String) {
        sourceWorks.update { current ->
            if (current.loading || current.saving || current.items.none { it.id == id && it.subjectType != null }) current
            else current.copy(selected = if (id in current.selected) current.selected - id else current.selected + id, error = null)
        }
    }

    internal fun importSourceWorks() {
        val current = sourceWorks.value
        if (current.saving || current.loading || current.selected.isEmpty()) return
        val items = current.items.filter { it.id in current.selected && it.subjectType != null }
        sourceWorks.value = current.copy(saving = true, error = null, message = null)
        sourceJob = viewModelScope.launch {
            val succeeded = mutableSetOf<String>()
            val failures = mutableListOf<String>()
            try {
                val api = com.animeow.app.data.remote.BangumiService(context = getApplication())
                items.forEach { item ->
                    try {
                        val remote = requireNotNull(api.seriesSubject(item.id.toLong(), requireNotNull(item.subjectType))) { "作品类型或来源资料已变化，请重新查找" }
                        repository.importBatchItem(remote, com.animeow.app.data.BatchSeriesDestination(existingSeriesId = seriesId), com.animeow.app.data.BatchMetadataMode.KEEP)
                        succeeded += item.id
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        failures += "${item.title}：" + (if (error is IllegalArgumentException) error.message else "保存失败，请重试")
                    }
                }
            } finally {
                sourceWorks.update { it.copy(saving = false, selected = it.selected - succeeded,
                    message = "已保存 ${succeeded.size} 部作品；已有资料与个人记录保留。",
                    error = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")) }
            }
        }
    }

    fun setSelectedSeriesRole(role: String?) = withSelection("系列关系已保存") { selected ->
        repository.setSeriesRole(selected, seriesId, role)
    }

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

    suspend fun addAnimes(expectedSeries: Map<Long, Long?>) {
        repository.groupDraggedAnimes(expectedSeries, seriesId, "")
        _messages.emit("已添加 ${expectedSeries.size} 部作品")
    }

    suspend fun addCrossMedia(items: List<com.animeow.app.data.local.SeriesLinkedMediaItem>) {
        if (items.isEmpty()) return
        repository.addSeriesLinkedMedia(seriesId, items)
        _messages.emit("已关联 ${items.size} 部跨媒体作品")
    }

    fun removeCrossMedia(kind: String, workSyncId: String) = runOperation("已移出当前系列") {
        repository.removeSeriesLinkedMedia(seriesId, setOf("$kind:$workSyncId"))
    }

    fun setCrossMediaRole(kind: String, workSyncId: String, role: String?) = runOperation("系列关系已保存") {
        repository.setSeriesLinkedMediaRole(seriesId, setOf("$kind:$workSyncId"), role)
    }

    fun setSelectedStatus(status: String) = withSelection("已批量更新状态") { selected ->
        repository.updateStatuses(selected, status)
    }

    suspend fun updateTagsForSelected(
        tagIds: Set<Long>,
        remove: Boolean,
        newTags: List<com.animeow.app.data.BatchTagDraft>,
    ) {
        repository.updateTagsForAnimes(_selectedIds.value, tagIds, remove, newTags)
        clearSelection()
    }

    fun removeSelectedFromSeries() = withSelection("已移出系列") { selected ->
        repository.removeSeriesFromAnimes(selected)
    }

    fun moveSelectedToTrash() = withSelection("已移入回收站，可随时恢复") { selected ->
        repository.deleteAnimes(selected).forEach(::cancelReminderSafely)
    }

    fun startBatchMetadataMatch(options: BatchMetadataOptions) = batchMetadata.startBatchMetadataMatch(options, _selectedIds.value)
    fun retryBatchMetadataMatch() = batchMetadata.retryBatchMetadataMatch()
    fun skipCurrentMetadataMatch() = batchMetadata.skipCurrentMetadataMatch()
    fun cancelBatchMetadataMatch() = batchMetadata.cancelBatchMetadataMatch()
    fun clearBatchMetadataMessage() = batchMetadata.clearBatchMetadataMessage()

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


}
