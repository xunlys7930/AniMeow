package com.animeow.app.ui.management

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ManagedTag(
    val tag: TagEntity,
    val animeCount: Int,
)

data class ManagedStatus(
    val status: WatchStatusEntity,
    val animeCount: Int,
)

data class ManagedSeries(
    val series: SeriesEntity,
    val animes: List<AnimeEntity>,
) {
    val coverUrl: String? = series.customCoverUrl
        ?: animes.sortedByDescending(AnimeEntity::id).firstNotNullOfOrNull(AnimeEntity::coverUrl)
}

class LibraryManagementViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: Flow<String> = _events.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val statuses: StateFlow<List<WatchStatusEntity>> = repository.observeWatchStatuses().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val statusItems: StateFlow<List<ManagedStatus>> = combine(
        repository.observeWatchStatuses(),
        repository.observeAnimes(),
        repository.observeDeletedAnimes(),
    ) { statuses, activeAnimes, deletedAnimes ->
        val counts = (activeAnimes.asSequence() + deletedAnimes.asSequence())
            .groupingBy(AnimeEntity::status)
            .eachCount()
        statuses.map { ManagedStatus(it, counts[it.name] ?: 0) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val tags: StateFlow<List<TagEntity>> = repository.observeTags().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val tagItems: StateFlow<List<ManagedTag>> = combine(
        repository.observeAnimes(),
        repository.observeTags(),
        repository.observeAnimeTags(),
    ) { animes, tags, links ->
        val activeIds = animes.mapTo(hashSetOf(), AnimeEntity::id)
        val counts = links.asSequence()
            .filter { it.animeId in activeIds }
            .groupingBy(AnimeTagEntity::tagId)
            .eachCount()
        tags.map { ManagedTag(it, counts[it.id] ?: 0) }
            .sortedWith(
                compareByDescending<ManagedTag>(ManagedTag::animeCount)
                    .thenBy { it.tag.name.lowercase(java.util.Locale.ROOT) },
            )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val series: StateFlow<List<SeriesEntity>> = repository.observeSeries().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val seriesItems: StateFlow<List<ManagedSeries>> = combine(
        repository.observeSeries(),
        repository.observeAnimes(),
    ) { allSeries, animes ->
        allSeries.map { item -> ManagedSeries(item, animes.filter { it.seriesId == item.id }) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun saveStatus(id: Long, name: String, color: Long? = null, onSuccess: () -> Unit = {}) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            _events.tryEmit("状态名称不能为空")
            return
        }
        val existing = statuses.value.firstOrNull { it.id == id }
        launchOperation("状态已保存", "状态保存失败", onSuccess) {
            repository.saveWatchStatus(
                WatchStatusEntity(
                    id = id,
                    name = normalized,
                    color = color ?: existing?.color ?: 0xFF3482FF,
                    sortOrder = existing?.sortOrder ?: (statuses.value.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                ),
            )
        }
    }

    fun deleteStatus(
        id: Long,
        replacementStatusId: Long? = null,
        onSuccess: () -> Unit = {},
    ) = launchOperation(
        success = if (replacementStatusId == null) "状态已删除" else "作品已迁移，状态已删除",
        fallback = "状态删除失败",
        onSuccess = onSuccess,
    ) {
        repository.deleteWatchStatus(id, replacementStatusId)
    }

    fun reorderStatuses(statusIds: List<Long>, onSuccess: () -> Unit = {}) = launchOperation(
        success = "状态顺序已保存",
        fallback = "状态排序保存失败",
        onSuccess = onSuccess,
    ) {
        repository.reorderWatchStatuses(statusIds)
    }

    fun saveTag(id: Long, name: String, color: Long? = null, onSuccess: () -> Unit = {}) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            _events.tryEmit("标签名称不能为空")
            return
        }
        val existing = tags.value.firstOrNull { it.id == id }
        launchOperation("标签已保存", "标签保存失败", onSuccess) {
            repository.saveTag(
                TagEntity(
                    id = id,
                    name = normalized,
                    color = color ?: existing?.color ?: 0xFF8A4FD0,
                ),
            )
        }
    }

    fun deleteTag(id: Long, onSuccess: () -> Unit = {}) = launchOperation(
        "标签已删除",
        "标签删除失败",
        onSuccess,
    ) {
        repository.deleteTag(id)
    }

    fun saveSeries(id: Long, name: String, description: String, onSuccess: () -> Unit = {}) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            _events.tryEmit("系列名称不能为空")
            return
        }
        val existing = series.value.firstOrNull { it.id == id }
        launchOperation("系列已保存", "系列保存失败", onSuccess) {
            repository.saveSeries(
                SeriesEntity(
                    id = id,
                    name = normalized,
                    description = description.trim().takeIf(String::isNotBlank),
                    customCoverUrl = existing?.customCoverUrl,
                    createdAt = existing?.createdAt ?: java.time.Instant.now().toString(),
                ),
            )
        }
    }

    fun deleteSeries(id: Long, onSuccess: () -> Unit = {}) = launchOperation(
        "系列已删除，作品已转为无系列",
        "系列删除失败",
        onSuccess,
    ) {
        repository.deleteSeries(id)
    }

    fun saveSeriesCover(seriesId: Long, coverUrl: String?, onSuccess: () -> Unit = {}) = launchOperation(
        success = if (coverUrl.isNullOrBlank()) "已恢复自动系列封面" else "系列封面已更新",
        fallback = "系列封面保存失败",
        onSuccess = onSuccess,
    ) {
        repository.updateSeriesCover(seriesId, coverUrl)
    }

    private fun launchOperation(
        success: String?,
        fallback: String,
        onSuccess: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (_busy.value) {
            _events.tryEmit("请等待当前操作完成")
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                onSuccess()
                success?.let { _events.emit(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                _events.emit(error.message?.takeIf(String::isNotBlank) ?: fallback)
            } catch (error: IllegalStateException) {
                _events.emit(error.message?.takeIf(String::isNotBlank) ?: fallback)
            } catch (_: Exception) {
                _events.emit(fallback)
            } finally {
                _busy.value = false
            }
        }
    }
}
