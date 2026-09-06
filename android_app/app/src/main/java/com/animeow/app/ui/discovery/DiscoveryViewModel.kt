package com.animeow.app.ui.discovery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.RemoteImportResult
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.DiscoveryFeedMode
import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryDisplayConfig
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class RemoteDuplicateRequest(
    val remote: RemoteAnime,
    val existing: AnimeEntity,
)

data class DiscoveryUiState(
    val query: String = "",
    val sourceFilter: DiscoverySourceFilter = DiscoverySourceFilter.ALL,
    val feedMode: DiscoveryFeedMode = DiscoveryFeedMode.FEATURED,
    val filters: DiscoveryFilters = DiscoveryFilters(),
    val networkSettings: DiscoveryNetworkSettings = DiscoveryNetworkSettings(),
    val subjectType: String = "anime",
    val featured: List<RemoteAnime> = emptyList(),
    val results: List<RemoteAnime> = emptyList(),
    val selected: RemoteAnime? = null,
    val isLoading: Boolean = true,
    val importingKey: String? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val duplicate: RemoteDuplicateRequest? = null,
)

class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val discoveryRepository = app.discoveryRepository
    private val libraryRepository = app.libraryRepository
    private val discoveryPreferences = DiscoveryPreferences(application)
    private val _state = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()
    private val _openAnime = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openAnime = _openAnime.asSharedFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            discoveryPreferences.settings.collect { settings ->
                _state.update { current ->
                    current.copy(
                        networkSettings = settings,
                        sourceFilter = current.sourceFilter.takeUnless {
                            it == DiscoverySourceFilter.SERVER && !settings.showServerSource
                        } ?: DiscoverySourceFilter.ALL,
                    )
                }
            }
        }
        refreshFeatured()
    }

    fun updateQuery(value: String) {
        _state.update { it.copy(query = value) }
        scheduleSearch()
    }

    fun selectSource(source: DiscoverySourceFilter) {
        _state.update { it.copy(sourceFilter = source) }
        if (state.value.query.isBlank()) refreshFeatured() else scheduleSearch(immediate = true)
    }

    fun selectFeedMode(mode: DiscoveryFeedMode) {
        _state.update { it.copy(feedMode = mode) }
        if (state.value.query.isBlank()) refreshFeatured()
    }

    fun updateFilters(filters: DiscoveryFilters) {
        _state.update { it.copy(filters = filters) }
        if (state.value.query.isBlank()) refreshFeatured() else scheduleSearch(immediate = true)
    }

    fun saveNetworkSettings(settings: DiscoveryNetworkSettings) = viewModelScope.launch {
        runCatchingCancellable { discoveryPreferences.save(settings) }
            .onSuccess {
                _state.update { current ->
                    current.copy(
                        networkSettings = settings,
                        sourceFilter = current.sourceFilter.takeUnless {
                            it == DiscoverySourceFilter.SERVER && !settings.showServerSource
                        } ?: DiscoverySourceFilter.ALL,
                    )
                }
                if (state.value.query.isBlank()) refreshFeatured() else scheduleSearch(immediate = true)
            }
            .onFailure { error -> _state.update { it.copy(error = friendlyError(error)) } }
    }

    fun resetNetworkSettings() = viewModelScope.launch {
        discoveryPreferences.resetNetwork()
        if (state.value.query.isBlank()) refreshFeatured() else scheduleSearch(immediate = true)
    }

    fun saveDisplaySettings(display: DiscoveryDisplayConfig) = viewModelScope.launch {
        runCatchingCancellable { discoveryPreferences.save(state.value.networkSettings.copy(display = display)) }
            .onFailure { error -> _state.update { it.copy(error = friendlyError(error)) } }
    }

    fun resetDisplaySettings() = viewModelScope.launch {
        runCatchingCancellable { discoveryPreferences.resetDisplay() }
            .onFailure { error -> _state.update { it.copy(error = friendlyError(error)) } }
    }

    fun selectSubjectType(subjectType: String) {
        _state.update { it.copy(subjectType = normalizeSubjectType(subjectType)) }
        if (state.value.query.isNotBlank()) scheduleSearch(immediate = true) else refreshFeatured()
    }

    fun select(item: RemoteAnime?) {
        _state.update { it.copy(selected = item) }
    }

    fun refreshFeatured() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val snapshot = state.value
            runCatchingCancellable {
                if (snapshot.feedMode == DiscoveryFeedMode.RANKING) {
                    discoveryRepository.ranking(snapshot.sourceFilter, snapshot.subjectType, snapshot.filters)
                } else {
                    discoveryRepository.featured(
                        source = snapshot.sourceFilter,
                        subjectType = snapshot.subjectType,
                        filters = snapshot.filters,
                        forceRefresh = true,
                    )
                }
            }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            featured = result.items,
                            isLoading = false,
                            warnings = result.warnings,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = friendlyError(error)) }
                }
        }
    }

    fun retry() {
        if (state.value.query.isBlank()) refreshFeatured() else scheduleSearch(immediate = true)
    }

    fun import(item: RemoteAnime) {
        importInternal(item, force = false)
    }

    fun forceImportDuplicate() {
        val request = state.value.duplicate ?: return
        if (
            request.existing.externalSource == request.remote.importSource &&
            request.existing.externalId == request.remote.importId
        ) {
            _state.update { it.copy(duplicate = null, selected = null) }
            _openAnime.tryEmit(request.existing.id)
            return
        }
        _state.update { it.copy(duplicate = null) }
        importInternal(request.remote, force = true)
    }

    fun openExistingDuplicate() {
        val existingId = state.value.duplicate?.existing?.id ?: return
        _state.update { it.copy(duplicate = null, selected = null) }
        _openAnime.tryEmit(existingId)
    }

    fun dismissDuplicate() {
        _state.update { it.copy(duplicate = null) }
    }

    private fun scheduleSearch(immediate: Boolean = false) {
        searchJob?.cancel()
        val snapshot = state.value
        if (snapshot.query.isBlank()) {
            _state.update { it.copy(results = emptyList(), error = null, isLoading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(450)
            val current = state.value
            _state.update { it.copy(isLoading = true, error = null) }
            runCatchingCancellable {
                discoveryRepository.search(
                    query = current.query.trim(),
                    source = current.sourceFilter,
                    subjectType = current.subjectType,
                    filters = current.filters,
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(results = result.items, isLoading = false, warnings = result.warnings)
                }
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false, error = friendlyError(error), results = emptyList()) }
            }
        }
    }

    private fun importInternal(item: RemoteAnime, force: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(importingKey = item.uniqueKey, error = null) }
            runCatchingCancellable { libraryRepository.importRemoteAnime(item, force) }
                .onSuccess { result ->
                    when (result) {
                        is RemoteImportResult.Added -> {
                            _state.update { it.copy(importingKey = null, selected = null) }
                            _openAnime.emit(result.animeId)
                        }
                        is RemoteImportResult.Duplicate -> {
                            _state.update {
                                it.copy(
                                    importingKey = null,
                                    duplicate = RemoteDuplicateRequest(item, result.existing),
                                )
                            }
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(importingKey = null, error = friendlyError(error)) }
                }
        }
    }

    private fun friendlyError(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: "联网请求失败，请检查网络后重试"
}
