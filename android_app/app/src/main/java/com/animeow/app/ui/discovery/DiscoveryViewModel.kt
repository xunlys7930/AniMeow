package com.animeow.app.ui.discovery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.RemoteImportResult
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.allTitles
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.DiscoveryFeedMode
import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoverySort
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.discoverySourceKeys
import com.animeow.app.data.remote.discoveryTitleKeys
import com.animeow.app.data.remote.normalizedDiscoveryTitle
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class RemoteDuplicateRequest(
    val remote: RemoteAnime,
    val existing: AnimeEntity,
)

data class DiscoveryUiState(
    val query: String = "",
    val localIdsByIdentity: Map<String, Long> = emptyMap(),
    val sourceFilter: DiscoverySourceFilter = DiscoverySourceFilter.BANGUMI,
    val feedMode: DiscoveryFeedMode = DiscoveryFeedMode.FEATURED,
    val filters: DiscoveryFilters = DiscoveryFilters(),
    val networkSettings: DiscoveryNetworkSettings = DiscoveryNetworkSettings(),
    val subjectType: String = "anime",
    val featured: List<RemoteAnime> = emptyList(),
    val results: List<RemoteAnime> = emptyList(),
    val selected: RemoteAnime? = null,
    val batchMode: Boolean = false,
    val batchSelected: Map<String, RemoteAnime> = emptyMap(),
    val batchCandidates: List<RemoteAnime>? = null,
    val batchAnchorTitle: String? = null,
    val batchSeriesName: String = "",
    val batchInitialStatus: String = "想看",
    val existingSeriesNames: List<String> = emptyList(),
    val batchBusy: Boolean = false,
    val batchLoading: Boolean = false,
    val batchMessage: String? = null,
    val isLoading: Boolean = true,
    val loadingMessage: String = "正在连接资料库服务器",
    val importingKey: String? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val duplicate: RemoteDuplicateRequest? = null,
    val currentPage: Int = 1,
    val totalPages: Int? = null,
    val hasNextPage: Boolean = false,
    val totalCount: Int = 0,
    val totalCountExact: Boolean = false,
    val resultScope: String? = null,
)

class DiscoveryViewModel(application: Application, private val savedState: SavedStateHandle = SavedStateHandle()) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val discoveryRepository = app.discoveryRepository
    private val libraryRepository = app.libraryRepository
    private val discoveryPreferences = DiscoveryPreferences(application)
    private val _state = MutableStateFlow(DiscoveryUiState(subjectType = DiscoveryModule.fromKey(savedState["discovery_module"]).key))
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()
    private val _openAnime = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openAnime = _openAnime.asSharedFlow()
    private var searchJob: Job? = null
    private var loadedFeedKey: String? = null
    private var browsingBeforeSeason: Pair<String, DiscoveryFilters>? = null
    private var seasonalFilters = seasonalDiscoveryFilters()
    private var batchJob: Job? = null
    private var seriesJob: Job? = null
    private suspend fun recommendationProfile(type: String) = kotlinx.coroutines.flow.combine(
        libraryRepository.observeAnimes(), libraryRepository.observeTags(), libraryRepository.observeAnimeTags(),
    ) { works, tags, links -> com.animeow.app.data.remote.discoveryPreferenceProfile(works, tags, links, type) }.first()

    fun beginBatch(item: RemoteAnime? = null) {
        val initialSelected = if (item != null) mapOf("${item.importSource}:${item.importId}" to item) else emptyMap()
        _state.update { it.copy(batchMode = true, batchMessage = null, batchSelected = if (item != null) initialSelected else it.batchSelected) }
    }
    fun selectAllCurrent(items: List<RemoteAnime>) {
        if (state.value.batchBusy) return
        _state.update { current ->
            val updated = current.batchSelected.toMutableMap()
            items.take(60).forEach { item ->
                val key = "${item.importSource}:${item.importId}"
                updated[key] = item
            }
            current.copy(batchSelected = updated)
        }
    }
    fun clearSelection() {
        if (state.value.batchBusy) return
        _state.update { it.copy(batchSelected = emptyMap()) }
    }
    fun endBatch() {
        if (state.value.batchBusy) return
        seriesJob?.cancel()
        _state.update {
            it.copy(
                batchMode = false,
                batchSelected = emptyMap(),
                batchCandidates = null,
                batchAnchorTitle = null,
                batchSeriesName = "",
                batchInitialStatus = "想看",
                batchLoading = false,
            )
        }
    }
    fun exitRelatedCandidates() {
        if (state.value.batchBusy) return
        seriesJob?.cancel()
        _state.update { it.copy(batchCandidates = null, batchAnchorTitle = null, batchLoading = false, batchMessage = null) }
    }
    fun setBatchSeriesName(value: String) { if (!state.value.batchBusy) _state.update { it.copy(batchSeriesName = value.take(100)) } }
    fun setBatchInitialStatus(value: String) {
        if (!state.value.batchBusy && value in setOf("想看", "在看", "看完")) {
            _state.update { it.copy(batchInitialStatus = value) }
        }
    }
    fun toggleBatch(item: RemoteAnime) {
        if (state.value.batchBusy) return
        val key = "${item.importSource}:${item.importId}"
        _state.update { current ->
            if (key in current.batchSelected) current.copy(batchSelected = current.batchSelected - key)
            else if (current.batchSelected.size < 60) current.copy(batchSelected = current.batchSelected + (key to item))
            else current.copy(batchMessage = "每次最多选择 60 部，请先保存当前选择")
        }
    }
    fun prepareRelated(item: RemoteAnime) {
        if (state.value.batchBusy) return
        val bgmId = if (item.importSource == "bangumi") item.importId.toLongOrNull()
            else item.metadataReferences.firstOrNull { it.source == "bangumi" }?.id?.toLongOrNull()
        val seed = if (item.importSource == "bangumi" || bgmId == null) item
            else item.copy(
                source = com.animeow.app.data.remote.RemoteCatalogSource.BANGUMI,
                id = bgmId.toString(),
                originSource = "bangumi",
                originId = bgmId.toString(),
            )
        val suggestedSeries = com.animeow.app.ui.anime.seriesBaseTitle(item.title).take(100)
        seriesJob?.cancel()
        _state.update {
            it.copy(
                selected = null,
                batchMode = true,
                batchLoading = true,
                batchSelected = emptyMap(),
                batchCandidates = null,
                batchAnchorTitle = item.title,
                batchSeriesName = it.batchSeriesName.ifBlank { suggestedSeries },
                batchMessage = null,
            )
        }
        seriesJob = viewModelScope.launch {
            runCatchingCancellable { com.animeow.app.data.remote.collectSeriesCandidates(seed) { id, type ->
                com.animeow.app.data.remote.BangumiService(context = getApplication()).relatedSubjects(id, type)
            } }.onSuccess { result ->
                _state.update { it.copy(batchLoading = false, batchCandidates = result.items,
                    batchMessage = if (result.incomplete) "关联作品较多，已展示前 60 部候选，请核对后选择。" else "请勾选需要添加的正篇、剧场版或特别篇。") }
            }.onFailure { error -> _state.update { it.copy(batchLoading = false, batchMessage = friendlyError(error)) } }
        }
    }
    fun cancelBatch() { batchJob?.cancel() }
    fun importBatch() {
        val snapshot = state.value
        if (snapshot.batchBusy || snapshot.batchLoading || snapshot.batchSelected.isEmpty()) return
        _state.update { it.copy(batchBusy = true, batchMessage = "正在逐项保存，已保存项目不会因后续失败而撤销") }
        batchJob = viewModelScope.launch {
            var added = 0; var reused = 0
            val failures = mutableListOf<String>()
            try {
                for ((key, item) in snapshot.batchSelected) {
                    try {
                        val detailed = if (item.importSource == "bangumi" && (item.episodes == null || item.summary.isNullOrBlank()))
                            com.animeow.app.data.remote.BangumiService(context = getApplication()).subject(item.importId.toLong(), item.subjectType)
                                ?: error("未取得完整资料") else item
                        val result = libraryRepository.importBatchItem(detailed, snapshot.batchSeriesName, snapshot.batchInitialStatus)
                        if (result.second) added++ else reused++
                        _state.update { it.copy(batchSelected = it.batchSelected - key) }
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) { failures.add("${item.title}：${friendlyError(error)}") }
                }
            } finally {
                _state.update { it.copy(batchBusy = false, batchMessage = "新增 $added 部，复用 $reused 部。" +
                    if (failures.isNotEmpty()) "未完成的选择已保留：\n" + failures.joinToString("\n") else
                    if (it.batchSelected.isNotEmpty()) "已停止，未保存的选择已保留。" else "全部选择已处理，已有个人记录保持原样。") }
            }
        }
    }

    init {
        viewModelScope.launch {
            libraryRepository.observeSeries().collect { seriesList ->
                val names = seriesList.map { it.name.trim() }.filter(String::isNotEmpty).distinct()
                _state.update { it.copy(existingSeriesNames = names) }
            }
        }
        viewModelScope.launch {
            com.animeow.app.data.remote.CatalogContributionWorker.localizedResults.collect { record ->
                discoveryRepository.acceptContribution(record)
                _state.update { current -> current.copy(
                    featured = current.featured.map { com.animeow.app.data.remote.localizedDiscoveryItem(it, record) },
                    results = current.results.map { com.animeow.app.data.remote.localizedDiscoveryItem(it, record) },
                    selected = current.selected?.let { com.animeow.app.data.remote.localizedDiscoveryItem(it, record) },
                ) }
            }
        }
        viewModelScope.launch {
            libraryRepository.observeAnimes().collect { animes ->
                val keys = buildMap {
                    animes.forEach { anime -> anime.sourceReferences().forEach { reference ->
                        if (!reference.source.isNullOrBlank() && !reference.externalId.isNullOrBlank()) put("${reference.source}:${reference.externalId}", anime.id)
                    } }
                }
                val titlesByType = animes
                    .filter { it.deletedAt == null }
                    .groupBy { normalizeSubjectType(it.subjectType) }
                    .mapValues { (_, list) ->
                        list.flatMap { it.allTitles() }.map(::normalizedDiscoveryTitle).filter(String::isNotEmpty).toSet()
                    }
                _state.update { current ->
                    val filteredFeatured = if (current.feedMode == DiscoveryFeedMode.FEATURED && current.filters.sort == DiscoverySort.TRENDING) {
                        val ownedTitles = titlesByType[normalizeSubjectType(current.subjectType)].orEmpty()
                        current.featured.filterNot { item ->
                            item.discoverySourceKeys().any { it in keys } ||
                                item.discoveryTitleKeys().any { it in ownedTitles }
                        }
                    } else {
                        current.featured
                    }
                    current.copy(localIdsByIdentity = keys, featured = filteredFeatured)
                }
            }
        }
        viewModelScope.launch {
            var initialized = false
            discoveryPreferences.settings.collect { settings ->
                _state.update { current ->
                    current.copy(
                        networkSettings = settings,
                        sourceFilter = current.sourceFilter.takeUnless {
                            it == DiscoverySourceFilter.SERVER && !settings.showServerSource
                        } ?: DiscoverySourceFilter.BANGUMI,
                    )
                }
                if (!initialized) {
                    initialized = true
                    loadFeatured(forceRefresh = false)
                }
            }
        }
    }

    fun updateQuery(value: String) {
        if (value == state.value.query) return
        seriesJob?.cancel()
        _state.update { it.copy(batchCandidates = null, batchLoading = false) }
        _state.update { it.copy(query = value, results = emptyList(), warnings = emptyList(), isLoading = true, currentPage = 1, totalPages = null, hasNextPage = false, totalCount = 0, totalCountExact = false, resultScope = null) }
        scheduleSearch(page = 1)
    }

    fun selectSource(source: DiscoverySourceFilter) {
        if (state.value.feedMode == DiscoveryFeedMode.SEASONAL) return
        if (source == state.value.sourceFilter) return
        seriesJob?.cancel()
        _state.update { it.copy(batchCandidates = null, batchLoading = false) }
        _state.update { it.copy(sourceFilter = source, currentPage = 1, totalPages = null, hasNextPage = false) }
        if (state.value.query.isBlank()) loadFeatured(false, page = 1) else scheduleSearch(immediate = true, page = 1)
    }

    fun selectFeedMode(mode: DiscoveryFeedMode) {
        if (mode !in discoveryFeeds(state.value.subjectType)) return
        if (mode == state.value.feedMode) return
        seriesJob?.cancel()
        _state.update { it.copy(batchCandidates = null, batchLoading = false) }
        val current = state.value
        if (mode == DiscoveryFeedMode.SEASONAL) {
            browsingBeforeSeason = current.subjectType to current.filters
        } else if (current.feedMode == DiscoveryFeedMode.SEASONAL) {
            seasonalFilters = current.filters
        }
        val (type, filters) = if (mode == DiscoveryFeedMode.SEASONAL) "anime" to seasonalFilters
            else browsingBeforeSeason?.takeIf { current.feedMode == DiscoveryFeedMode.SEASONAL }
                ?: (current.subjectType to current.filters)
        val switchesSeason = mode == DiscoveryFeedMode.SEASONAL || current.feedMode == DiscoveryFeedMode.SEASONAL
        _state.update { it.copy(feedMode = mode, subjectType = type,
            query = if (switchesSeason) "" else it.query,
            results = if (switchesSeason) emptyList() else it.results,
            selected = if (switchesSeason) null else it.selected,
            filters = if (mode == DiscoveryFeedMode.RANKING && filters.sort == DiscoverySort.TRENDING) filters.copy(sort = DiscoverySort.RANK)
                else if (mode == DiscoveryFeedMode.FEATURED && current.feedMode == DiscoveryFeedMode.RANKING) filters.copy(sort = DiscoverySort.TRENDING) else filters,
            currentPage = 1, totalPages = null, hasNextPage = false) }
        if (state.value.query.isBlank()) loadFeatured(false, page = 1)
    }

    fun updateFilters(filters: DiscoveryFilters) = applyFilters(filters, state.value.sourceFilter)

    fun applyFilters(filters: DiscoveryFilters, source: DiscoverySourceFilter) {
        val appliedSource = if (state.value.feedMode == DiscoveryFeedMode.SEASONAL) state.value.sourceFilter
            else if (state.value.subjectType == "book" && source == DiscoverySourceFilter.ANILIST) DiscoverySourceFilter.ALL else source
        val next = if (state.value.feedMode == DiscoveryFeedMode.SEASONAL) requiredSeasonFilters(filters, state.value.filters) else filters
        if (next == state.value.filters && appliedSource == state.value.sourceFilter) return
        seriesJob?.cancel()
        _state.update { it.copy(batchCandidates = null, batchLoading = false) }
        _state.update { it.copy(filters = next, sourceFilter = appliedSource, currentPage = 1, totalPages = null, hasNextPage = false) }
        if (state.value.query.isBlank()) loadFeatured(false, page = 1) else scheduleSearch(immediate = true, page = 1)
    }

    fun changePage(page: Int) {
        if (page < 1 || (state.value.totalPages != null && page > state.value.totalPages!!)) return
        if (page == state.value.currentPage) return
        _state.update { it.copy(currentPage = page) }
        if (state.value.query.isBlank()) {
            loadFeatured(forceRefresh = false, page = page)
        } else {
            scheduleSearch(immediate = true, page = page)
        }
    }

    fun saveNetworkSettings(settings: DiscoveryNetworkSettings) = viewModelScope.launch {
        runCatchingCancellable { discoveryPreferences.save(settings) }
            .onSuccess {
                _state.update { current ->
                    current.copy(
                        networkSettings = settings,
                        sourceFilter = current.sourceFilter.takeUnless {
                            it == DiscoverySourceFilter.SERVER && !settings.showServerSource
                        } ?: DiscoverySourceFilter.BANGUMI,
                    )
                }
                if (state.value.query.isBlank()) refreshFeatured() else scheduleSearch(immediate = true, page = 1)
            }
            .onFailure { error -> _state.update { it.copy(error = friendlyError(error)) } }
    }

    fun resetNetworkSettings() = viewModelScope.launch {
        discoveryPreferences.resetNetwork()
        if (state.value.query.isBlank()) refreshFeatured() else scheduleSearch(immediate = true, page = 1)
    }

    fun saveDisplaySettings(display: DiscoveryDisplayConfig) = viewModelScope.launch {
        runCatchingCancellable { discoveryPreferences.saveDisplay(display) }
            .onFailure { error -> _state.update { it.copy(error = friendlyError(error)) } }
    }

    fun resetDisplaySettings() = viewModelScope.launch {
        runCatchingCancellable { discoveryPreferences.resetDisplay() }
            .onFailure { error -> _state.update { it.copy(error = friendlyError(error)) } }
    }

    fun selectSubjectType(subjectType: String) {
        if (state.value.batchBusy) return
        val type = DiscoveryModule.fromKey(subjectType).key
        if (type == state.value.subjectType) return
        endBatch()
        searchJob?.cancel()
        savedState["discovery_module"] = type
        loadedFeedKey = null
        _state.update { it.copy(subjectType = type, feedMode = DiscoveryFeedMode.FEATURED,
            sourceFilter = if (type == "book" && it.sourceFilter == DiscoverySourceFilter.ANILIST) DiscoverySourceFilter.BANGUMI else it.sourceFilter,
            filters = DiscoveryFilters(), query = "", featured = emptyList(), results = emptyList(), selected = null,
            duplicate = null, error = null, warnings = emptyList(), totalCount = 0, totalCountExact = false, resultScope = null,
            isLoading = type != "game", currentPage = 1, totalPages = null, hasNextPage = false) }
        if (type != "game") loadFeatured(false, page = 1)
    }

    fun select(item: RemoteAnime?) {
        _state.update { it.copy(selected = item, error = if (item != null) null else it.error) }
    }

    fun refreshFeatured() = loadFeatured(forceRefresh = true, page = state.value.currentPage)

    private fun loadFeatured(forceRefresh: Boolean, page: Int = state.value.currentPage) {
        if (state.value.subjectType == "game") return
        searchJob?.cancel()
        val snapshot = state.value
        val key = "${snapshot.sourceFilter}:${snapshot.subjectType}:${snapshot.filters}:${snapshot.feedMode}:p$page"
        _state.update { it.copy(isLoading = true, error = null, warnings = emptyList(),
            totalCount = 0, totalCountExact = false, resultScope = null,
            loadingMessage = loadingMessage(snapshot), featured = if (loadedFeedKey == key) it.featured else emptyList()) }
        loadedFeedKey = key
        searchJob = viewModelScope.launch {
            runCatchingCancellable {
                discoveryRepository.cachedFeed(snapshot.sourceFilter, snapshot.subjectType, snapshot.filters, snapshot.feedMode, page = page)?.takeUnless {
                    snapshot.feedMode == DiscoveryFeedMode.FEATURED && snapshot.filters.sort == DiscoverySort.TRENDING
                }?.let { cached ->
                    _state.update { it.copy(featured = cached.items, currentPage = cached.page, totalPages = cached.totalPages, hasNextPage = cached.hasMore, totalCount = cached.totalCount, totalCountExact = cached.totalCountExact, resultScope = cached.resultScope) }
                }
                val progress: suspend (com.animeow.app.data.remote.RemoteFetchResult, String) -> Unit = { result, message ->
                    _state.update { it.copy(featured = result.items.ifEmpty { it.featured }, warnings = result.warnings, loadingMessage = message, resultScope = result.resultScope) }
                }
                if (snapshot.feedMode == DiscoveryFeedMode.SEASONAL) {
                    discoveryRepository.seasonal(snapshot.sourceFilter, snapshot.filters, page, forceRefresh, progress)
                } else if (snapshot.feedMode == DiscoveryFeedMode.RANKING) {
                    discoveryRepository.ranking(snapshot.sourceFilter, snapshot.subjectType, snapshot.filters, page = page, forceRefresh = forceRefresh, onProgress = progress)
                } else {
                    discoveryRepository.featured(
                        source = snapshot.sourceFilter,
                        subjectType = snapshot.subjectType,
                        filters = snapshot.filters,
                        page = page,
                        forceRefresh = forceRefresh,
                        onProgress = progress,
                        preference = recommendationProfile(snapshot.subjectType),
                    )
                }
            }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            featured = result.items,
                            isLoading = false,
                            warnings = result.warnings,
                            currentPage = result.page,
                            totalPages = result.totalPages,
                            hasNextPage = result.hasMore,
                            totalCount = result.totalCount,
                            totalCountExact = result.totalCountExact,
                            resultScope = result.resultScope,
                        )
                    }
                    // 后台触发缺失封面的刷新（不阻塞UI）
                    viewModelScope.launch {
                        runCatching { discoveryRepository.refreshMissingCovers(result.items) }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = friendlyError(error)) }
                }
        }
    }

    private fun loadingMessage(snapshot: DiscoveryUiState): String = if (snapshot.feedMode == DiscoveryFeedMode.SEASONAL || snapshot.sourceFilter == DiscoverySourceFilter.SERVER ||
        (snapshot.sourceFilter == DiscoverySourceFilter.ALL && snapshot.networkSettings.showServerSource && snapshot.networkSettings.includeServerInAllSources)) {
        "正在连接资料库服务器"
    } else "正在查询${snapshot.sourceFilter.displayName}"

    fun retry() {
        if (state.value.query.isBlank()) refreshFeatured() else scheduleSearch(immediate = true, page = state.value.currentPage)
    }

    fun import(item: RemoteAnime) {
        state.value.localIdsByIdentity["${item.importSource}:${item.importId}"]?.let { id ->
            _state.update { it.copy(selected = null) }
            _openAnime.tryEmit(id)
            return
        }
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

    private fun scheduleSearch(immediate: Boolean = false, page: Int = state.value.currentPage) {
        searchJob?.cancel()
        val snapshot = state.value
        if (snapshot.query.isBlank()) {
            _state.update { it.copy(results = emptyList(), error = null) }
            loadFeatured(forceRefresh = false, page = 1)
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(450)
            val current = state.value
            _state.update { it.copy(isLoading = true, error = null, warnings = emptyList(), results = emptyList(), totalCount = 0, totalCountExact = false, resultScope = null, loadingMessage = loadingMessage(current)) }
            runCatchingCancellable {
                discoveryRepository.search(
                    query = current.query.trim(),
                    source = current.sourceFilter,
                    subjectType = current.subjectType,
                    filters = current.filters,
                    page = page,
                    feedMode = if (current.feedMode == DiscoveryFeedMode.SEASONAL) DiscoveryFeedMode.SEASONAL else DiscoveryFeedMode.FEATURED,
                    onProgress = { result, message ->
                        _state.update { it.copy(results = result.items, warnings = result.warnings, loadingMessage = message, resultScope = result.resultScope) }
                    },
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        results = result.items,
                        isLoading = false,
                        warnings = result.warnings,
                        currentPage = result.page,
                        totalPages = result.totalPages,
                        hasNextPage = result.hasMore,
                        totalCount = result.totalCount,
                        totalCountExact = result.totalCountExact,
                        resultScope = result.resultScope,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false, error = friendlyError(error), results = emptyList()) }
            }
        }
    }

    private fun importInternal(item: RemoteAnime, force: Boolean) {
        if (state.value.importingKey != null) return
        _state.update { it.copy(importingKey = item.uniqueKey, error = null) }
        viewModelScope.launch {
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
