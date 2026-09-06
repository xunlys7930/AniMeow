package com.animeow.app.ui.tracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.ProgressChange
import com.animeow.app.data.RemoteMetadataFields
import com.animeow.app.data.StatusChange
import com.animeow.app.data.CoverSyncSummary
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.animeRatingSortValue
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.normalizeSubjectTypeFilter
import com.animeow.app.data.preferences.HomeBottomScope
import com.animeow.app.data.preferences.BentoCollectionLayout
import com.animeow.app.data.preferences.CoverFitMode
import com.animeow.app.data.preferences.TRACKER_START_ALL_STATUS
import com.animeow.app.data.preferences.TRACKER_START_LAST_STATUS
import com.animeow.app.data.preferences.TrackerPreferences
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.RemoteAnime
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

enum class LibrarySort(
    val storageKey: String,
    val displayName: String,
    val defaultAscending: Boolean,
) {
    RECENT("recent", "最近添加", false),
    AIR_DATE("air_date", "播出日期", false),
    FINISH_DATE("finish_date", "完成时间", false),
    RATING("rating", "评分", false),
    TITLE("title", "拼音 / 标题", true),
    PROGRESS("progress", "观看进度", false);

    companion object {
        fun fromStorage(value: String?): LibrarySort =
            entries.firstOrNull { it.storageKey == value } ?: RECENT
    }
}

data class AdvancedLibraryFilters(
    val subjectType: String = "all",
    val tagIds: Set<Long> = emptySet(),
    val matchAllTags: Boolean = false,
    val years: Set<String> = emptySet(),
)

internal data class BaseLibraryFilters(
    val query: String,
    val status: String,
    val sort: LibrarySort,
    val sortAscending: Boolean,
)

sealed interface LibraryDisplayItem {
    val stableKey: String
    val title: String
    val coverUrl: String?
    val memberAnimeIds: Set<Long>

    data class AnimeItem(val anime: AnimeEntity) : LibraryDisplayItem {
        override val stableKey: String = "anime:${anime.id}"
        override val title: String = anime.title
        override val coverUrl: String? = anime.coverUrl
        override val memberAnimeIds: Set<Long> = setOf(anime.id)
    }

    data class SeriesItem(
        val series: SeriesEntity,
        val members: List<AnimeEntity>,
    ) : LibraryDisplayItem {
        override val stableKey: String = "series:${series.id}"
        override val title: String = series.name
        override val coverUrl: String? = series.customCoverUrl
            ?: members.maxByOrNull(AnimeEntity::id)?.coverUrl
        override val memberAnimeIds: Set<Long> = members.mapTo(linkedSetOf(), AnimeEntity::id)
    }
}

data class TrackerHomeSections(
    val watching: List<AnimeEntity> = emptyList(),
    val recentAdded: List<AnimeEntity> = emptyList(),
    val books: List<AnimeEntity> = emptyList(),
)

data class BatchMetadataOptions(
    val fields: RemoteMetadataFields = RemoteMetadataFields(),
    val overwriteExisting: Boolean = false,
)

data class BatchMetadataState(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val updated: Int = 0,
    val unmatched: Int = 0,
    val failed: Int = 0,
    val currentTitle: String? = null,
    val message: String? = null,
) {
    val progress: Float
        get() = if (total <= 0) 0f else completed.toFloat() / total
}

internal data class LibraryDisplayRules(
    val filters: BaseLibraryFilters,
    val advanced: AdvancedLibraryFilters,
    val settings: TrackerSettings,
) {
    val isDefaultMode: Boolean
        get() = filters.query.isBlank() &&
            filters.status == "全部" &&
            advanced.subjectType == "all" &&
            advanced.tagIds.isEmpty() &&
            advanced.years.isEmpty()
}

class TrackerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val discoveryRepository = (application as AniMeowApplication).discoveryRepository
    private val reminderScheduler = (application as AniMeowApplication).reminderScheduler
    private val trackerPreferences = TrackerPreferences(application)

    private val searchQuery = MutableStateFlow("")
    private val selectedStatus = MutableStateFlow("全部")
    private val selectedSort = MutableStateFlow(LibrarySort.RECENT)
    private val selectedSortAscending = MutableStateFlow(LibrarySort.RECENT.defaultAscending)
    private val _selectedAnimeIds = MutableStateFlow<Set<Long>>(emptySet())
    private val advancedFilters = MutableStateFlow(AdvancedLibraryFilters())
    private val _trackerSettings = MutableStateFlow(TrackerSettings())
    private val _batchMetadataState = MutableStateFlow(BatchMetadataState())
    private var batchMetadataJob: Job? = null
    private var currentMetadataRequest: Deferred<RemoteAnime?>? = null
    private var trackerSettingsSaveJob: Job? = null
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val sourceAnimes = repository.observeAnimes().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val sourceAnimeTags = repository.observeAnimeTags().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val sourceSeries = repository.observeSeries().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val sourceWatchRecords = repository.observeWatchRecords().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val baseFilters = combine(
        searchQuery,
        selectedStatus,
        selectedSort,
        selectedSortAscending,
    ) { query, status, sort, ascending -> BaseLibraryFilters(query, status, sort, ascending) }

    val animes: StateFlow<List<AnimeEntity>> = combine(
        sourceAnimes,
        sourceAnimeTags,
        baseFilters,
        advancedFilters,
    ) { source, tagLinks, base, advanced ->
        filterAndSortAnimes(
            source = source,
            query = base.query,
            status = base.status,
            sort = base.sort,
            subjectType = advanced.subjectType,
            selectedTagIds = advanced.tagIds,
            matchAllTags = advanced.matchAllTags,
            animeTagLinks = tagLinks,
            years = advanced.years,
            ascending = base.sortAscending,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val displayRules = combine(
        baseFilters,
        advancedFilters,
        _trackerSettings,
    ) { base, advanced, settings -> LibraryDisplayRules(base, advanced, settings) }

    val libraryItems: StateFlow<List<LibraryDisplayItem>> = combine(
        animes,
        sourceAnimes,
        sourceSeries,
        displayRules,
    ) { filtered, allAnimes, allSeries, rules ->
        buildLibraryDisplayItems(
            filtered = filtered,
            allAnimes = allAnimes,
            allSeries = allSeries,
            rules = rules,
        )
    }.onEach {
        _isLoading.value = false
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val isDefaultMode: StateFlow<Boolean> = displayRules.map { it.isDefaultMode }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    val homeSections: StateFlow<TrackerHomeSections> = combine(
        sourceAnimes,
        sourceWatchRecords,
        displayRules,
    ) { source, records, rules ->
        if (!rules.isDefaultMode) {
            TrackerHomeSections()
        } else {
            buildTrackerHomeSections(source, records)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackerHomeSections(),
    )

    val statusOptions: StateFlow<List<String>> = combine(
        repository.observeWatchStatuses(),
        sourceAnimes,
    ) { configured, source ->
        val configuredNames = configured.map { it.name }.filter(String::isNotBlank)
        val storedNames = source.map { it.status }.filter(String::isNotBlank)
        listOf("全部") + (configuredNames + storedNames).distinct()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = listOf("全部"),
    )

    val statusColors: StateFlow<Map<String, Long>> = repository.observeWatchStatuses()
        .map { statuses -> statuses.associate { it.name to it.color } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    val statusCounts: StateFlow<Map<String, Int>> = sourceAnimes
        .map { animes ->
            val byStatus = animes.groupBy { it.status }.mapValues { it.value.size }
            mapOf("全部" to animes.size) + byStatus
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = mapOf("全部" to 0),
        )

    val query: StateFlow<String> = searchQuery
    val status: StateFlow<String> = selectedStatus
    val sort: StateFlow<LibrarySort> = selectedSort
    val sortAscending: StateFlow<Boolean> = selectedSortAscending
    val selectedAnimeIds: StateFlow<Set<Long>> = _selectedAnimeIds
    val advanced: StateFlow<AdvancedLibraryFilters> = advancedFilters
    val trackerSettings: StateFlow<TrackerSettings> = _trackerSettings
    val batchMetadataState: StateFlow<BatchMetadataState> = _batchMetadataState
    val availableYears: StateFlow<List<String>> = sourceAnimes.map { source ->
        source.mapNotNull { it.airDate?.take(4)?.takeIf { year -> year.all(Char::isDigit) } }
            .distinct()
            .sortedDescending()
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

    init {
        viewModelScope.launch {
            val saved = trackerPreferences.snapshot().normalized()
            _trackerSettings.value = saved
            selectedStatus.value = when (saved.defaultStartStatus) {
                TRACKER_START_ALL_STATUS -> "全部"
                TRACKER_START_LAST_STATUS -> saved.lastSelectedStatus
                else -> saved.defaultStartStatus
            }
            selectedSort.value = LibrarySort.fromStorage(saved.lastSortKey)
            selectedSortAscending.value = saved.sortAscending
            advancedFilters.update { it.copy(subjectType = saved.lastSubjectType) }
        }
    }

    fun setSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun setStatus(value: String) {
        selectedStatus.value = value
        _trackerSettings.update { it.copy(lastSelectedStatus = value) }
        viewModelScope.launch { trackerPreferences.setLastSelectedStatus(value) }
    }

    fun setSort(value: LibrarySort) {
        selectedSort.value = value
        selectedSortAscending.value = value.defaultAscending
        persistSort()
    }

    fun toggleSortDirection() {
        selectedSortAscending.value = !selectedSortAscending.value
        persistSort()
    }

    fun resetSort() {
        selectedSort.value = LibrarySort.RECENT
        selectedSortAscending.value = LibrarySort.RECENT.defaultAscending
        persistSort()
    }

    fun setSubjectType(value: String) {
        val normalized = normalizeSubjectTypeFilter(value)
        advancedFilters.value = advancedFilters.value.copy(subjectType = normalized)
        _trackerSettings.update { it.copy(lastSubjectType = normalized) }
        viewModelScope.launch { trackerPreferences.setLastSubjectType(normalized) }
    }

    fun toggleTagFilter(tagId: Long) {
        val current = advancedFilters.value
        advancedFilters.value = current.copy(
            tagIds = if (tagId in current.tagIds) current.tagIds - tagId else current.tagIds + tagId,
        )
    }

    fun setTagMatchAll(value: Boolean) {
        advancedFilters.value = advancedFilters.value.copy(matchAllTags = value)
    }

    fun toggleYear(value: String) {
        val current = advancedFilters.value
        advancedFilters.value = current.copy(
            years = if (value in current.years) current.years - value else current.years + value,
        )
    }

    fun clearYears() {
        advancedFilters.value = advancedFilters.value.copy(years = emptySet())
    }

    fun clearAdvancedFilters() {
        advancedFilters.value = AdvancedLibraryFilters()
        _trackerSettings.update { it.copy(lastSubjectType = "all") }
        viewModelScope.launch { trackerPreferences.setLastSubjectType("all") }
    }

    fun setDefaultStartStatus(value: String) {
        _trackerSettings.update { it.copy(defaultStartStatus = value) }
        viewModelScope.launch { trackerPreferences.setDefaultStartStatus(value) }
    }

    fun setGroupSeriesOnHome(value: Boolean) = updateTrackerSettings { it.copy(groupSeriesOnHome = value) }

    fun setShowStandaloneBookshelf(value: Boolean) =
        updateTrackerSettings { it.copy(showStandaloneBookshelf = value) }

    fun setHomeBottomScope(value: HomeBottomScope) = updateTrackerSettings { it.copy(homeBottomScope = value) }

    fun setBentoCollectionLayout(value: BentoCollectionLayout) =
        updateTrackerSettings { it.copy(bentoCollectionLayout = value) }

    fun setShowSubjectType(value: Boolean) = updateTrackerSettings { it.copy(showSubjectType = value) }

    fun setShowSeriesCount(value: Boolean) = updateTrackerSettings { it.copy(showSeriesCount = value) }

    fun setShowUpdateWeekday(value: Boolean) = updateTrackerSettings { it.copy(showUpdateWeekday = value) }

    fun setShowCoverStatus(value: Boolean) = updateTrackerSettings { it.copy(showCoverStatus = value) }
    fun setShowContinueWatching(value: Boolean) = updateTrackerSettings { it.copy(showContinueWatching = value) }
    fun setShowRecentAdded(value: Boolean) = updateTrackerSettings { it.copy(showRecentAdded = value) }
    fun setStatusBadgeBackground(value: com.animeow.app.data.preferences.StatusBadgeBackground) = updateTrackerSettings { it.copy(statusBadgeBackground = value) }
    fun setStatusBadgeCustomColor(value: Long) = updateTrackerSettings { it.copy(statusBadgeCustomColor = value) }

    fun setShowCoverRating(value: Boolean) = updateTrackerSettings { it.copy(showCoverRating = value) }

    fun setShowCoverProgress(value: Boolean) = updateTrackerSettings { it.copy(showCoverProgress = value) }

    fun setShowCoverSubjectType(value: Boolean) = updateTrackerSettings { it.copy(showCoverSubjectType = value) }

    fun setShowCoverSeriesCount(value: Boolean) = updateTrackerSettings { it.copy(showCoverSeriesCount = value) }

    fun setShowCoverUpdateWeekday(value: Boolean) = updateTrackerSettings { it.copy(showCoverUpdateWeekday = value) }

    fun setShowStatusCount(value: Boolean) = updateTrackerSettings { it.copy(showStatusCount = value) }

    fun setTimelineGroupField(value: com.animeow.app.ui.theme.TimelineGroupField) = updateTrackerSettings { it.copy(timelineGroupField = value) }

    fun setAutoSyncNetworkCovers(value: Boolean) = updateTrackerSettings { it.copy(autoSyncNetworkCovers = value) }

    fun setInfoScale(value: Float) = updateTrackerSettings { it.copy(infoScale = value) }

    fun setInfoOpacity(value: Float) = updateTrackerSettings { it.copy(infoOpacity = value) }

    fun setInfoTextOpacity(value: Float) = updateTrackerSettings { it.copy(infoTextOpacity = value) }

    fun setInfoDarkBackgroundOpacity(value: Float) = updateTrackerSettings {
        it.copy(infoDarkBackgroundOpacity = value)
    }

    fun setInfoLightBackgroundOpacity(value: Float) = updateTrackerSettings {
        it.copy(infoLightBackgroundOpacity = value)
    }

    fun setInfoCornerDp(value: Float) = updateTrackerSettings { it.copy(infoCornerDp = value) }

    fun setCoverCornerDp(value: Float) = updateTrackerSettings { it.copy(coverCornerDp = value) }

    fun setCoverImageOpacity(value: Float) = updateTrackerSettings { it.copy(coverImageOpacity = value) }

    fun setCoverSaturation(value: Float) = updateTrackerSettings { it.copy(coverSaturation = value) }

    fun setCoverFitMode(value: CoverFitMode) = updateTrackerSettings { it.copy(coverFitMode = value) }

    fun toggleSelection(animeId: Long) {
        _selectedAnimeIds.value = if (animeId in _selectedAnimeIds.value) {
            _selectedAnimeIds.value - animeId
        } else {
            _selectedAnimeIds.value + animeId
        }
    }

    fun toggleSelection(animeIds: Set<Long>) {
        if (animeIds.isEmpty()) return
        val current = _selectedAnimeIds.value
        _selectedAnimeIds.value = if (animeIds.all(current::contains)) current - animeIds else current + animeIds
    }

    fun setSelection(animeIds: Set<Long>) {
        _selectedAnimeIds.value = animeIds
    }

    fun beginSelection() {
        if (_selectedAnimeIds.value.isEmpty()) {
            _selectedAnimeIds.value = emptySet()
        }
    }

    fun clearSelection() {
        _selectedAnimeIds.value = emptySet()
    }

    suspend fun updateSelectedStatus(status: String) {
        repository.updateStatuses(_selectedAnimeIds.value, status)
        clearSelection()
    }

    suspend fun deleteSelected() {
        val movedIds = repository.deleteAnimes(_selectedAnimeIds.value)
        movedIds.forEach(::cancelReminderSafely)
        clearSelection()
    }

    suspend fun addTagToSelected(tagId: Long) {
        repository.addTagToAnimes(_selectedAnimeIds.value, tagId)
        clearSelection()
    }

    suspend fun removeTagFromSelected(tagId: Long) {
        repository.removeTagFromAnimes(_selectedAnimeIds.value, tagId)
        clearSelection()
    }

    suspend fun createTagAndAddToSelected(name: String, color: Long) {
        repository.createTagAndAddToAnimes(_selectedAnimeIds.value, name, color)
        clearSelection()
    }

    suspend fun syncSelectedCovers(): CoverSyncSummary {
        val result = repository.syncCoverUrlsToServer(_selectedAnimeIds.value)
        clearSelection()
        return result
    }

    fun startBatchMetadataMatch(options: BatchMetadataOptions) {
        if (_batchMetadataState.value.isRunning || !options.fields.hasSelection) return
        val selected = _selectedAnimeIds.value
        if (selected.isEmpty()) return
        batchMetadataJob = viewModelScope.launch {
            val candidates = repository.getActiveAnimesSnapshot().filter { it.id in selected }
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
                            remote == null -> BatchMatchOutcome.UNMATCHED
                            repository.applyRemoteMetadata(
                                animeId = anime.id,
                                remote = remote,
                                fields = options.fields,
                                overwriteExisting = options.overwriteExisting,
                            ) -> BatchMatchOutcome.UPDATED
                            else -> BatchMatchOutcome.UNCHANGED
                        }
                    }.getOrElse { error ->
                        if (error is CancellationException && !isActive) throw error
                        BatchMatchOutcome.FAILED
                    }
                    when (outcome) {
                        BatchMatchOutcome.UPDATED -> updated += 1
                        BatchMatchOutcome.UNMATCHED -> unmatched += 1
                        BatchMatchOutcome.FAILED -> failed += 1
                        BatchMatchOutcome.UNCHANGED -> Unit
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
                _selectedAnimeIds.value = emptySet()
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

    suspend fun incrementProgress(
        animeId: Long,
        autoCompleteStatus: Boolean,
        completionStatus: String,
    ): ProgressChange? = repository.incrementProgress(
        animeId = animeId,
        autoCompleteStatus = autoCompleteStatus,
        completionStatus = completionStatus,
    )

    suspend fun restoreProgress(change: ProgressChange) {
        repository.restoreProgress(change)
    }

    suspend fun cycleStatus(animeId: Long): StatusChange? = repository.cycleStatus(animeId)

    suspend fun restoreStatus(change: StatusChange) {
        repository.restoreStatus(change)
    }

    suspend fun moveToTrash(animeId: Long): Boolean {
        val moved = repository.deleteAnime(animeId) ?: return false
        cancelReminderSafely(moved.id)
        return true
    }

    suspend fun restoreFromTrash(animeId: Long): Boolean {
        val restored = repository.restoreAnime(animeId) ?: return false
        scheduleReminderSafely(restored)
        return true
    }

    private fun cancelReminderSafely(animeId: Long) {
        try {
            reminderScheduler.cancel(animeId)
        } catch (error: Exception) {
            getApplication<AniMeowApplication>().operationLog.recordError(error, "首页 · 取消提醒")
        }
    }

    private fun scheduleReminderSafely(anime: AnimeEntity) {
        try {
            reminderScheduler.schedule(anime)
        } catch (error: Exception) {
            getApplication<AniMeowApplication>().operationLog.recordError(error, "首页 · 恢复提醒")
        }
    }

    private fun persistSort() {
        val sort = selectedSort.value
        val ascending = selectedSortAscending.value
        _trackerSettings.update { it.copy(lastSortKey = sort.storageKey, sortAscending = ascending) }
        viewModelScope.launch { trackerPreferences.setLastSort(sort.storageKey, ascending) }
    }

    private fun updateTrackerSettings(transform: (TrackerSettings) -> TrackerSettings) {
        val next = transform(_trackerSettings.value).normalized()
        _trackerSettings.value = next
        trackerSettingsSaveJob?.cancel()
        trackerSettingsSaveJob = viewModelScope.launch { trackerPreferences.save(next) }
    }

    private suspend fun findRemoteMetadataMatch(anime: AnimeEntity): RemoteAnime? = supervisorScope {
        val source = when (anime.externalSource?.lowercase(Locale.ROOT)) {
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
        val originalTitle = anime.originalTitle?.trim().orEmpty()
        if (originalTitle.isNotEmpty() && normalizeMetadataTitle(originalTitle) != normalizeMetadataTitle(anime.title)) {
            search(originalTitle)?.let { return@supervisorScope it }
        }
        null
    }
}

private enum class BatchMatchOutcome {
    UPDATED,
    UNCHANGED,
    UNMATCHED,
    FAILED,
}

internal fun buildLibraryDisplayItems(
    filtered: List<AnimeEntity>,
    allAnimes: List<AnimeEntity>,
    allSeries: List<SeriesEntity>,
    rules: LibraryDisplayRules,
): List<LibraryDisplayItem> {
    if (!rules.settings.groupSeriesOnHome) {
        return filtered.map(LibraryDisplayItem::AnimeItem)
    }

    val searching = rules.filters.query.isNotBlank()
    val isFilteredView = !rules.isDefaultMode && !searching

    val normalizedQuery = rules.filters.query.trim()
    val knownSeriesIds = allSeries.mapTo(hashSetOf(), SeriesEntity::id)
    val targetSeriesIds = buildSet {
        filtered.mapNotNullTo(this, AnimeEntity::seriesId)
        if (searching) {
            allSeries.asSequence()
                .filter { series ->
                    series.name.contains(normalizedQuery, ignoreCase = true) ||
                        series.description.orEmpty().contains(normalizedQuery, ignoreCase = true)
                }
                .mapTo(this, SeriesEntity::id)
        }
    }
    val seriesItems = allSeries.asSequence()
        .filter { it.id in targetSeriesIds }
        .map { series ->
            val members = if (isFilteredView) {
                filtered.filter { it.seriesId == series.id }
            } else {
                allAnimes.filter { it.seriesId == series.id }
            }
            LibraryDisplayItem.SeriesItem(
                series = series,
                members = members,
            )
        }
        .filter { searching || it.members.isNotEmpty() }
        .toList()
    val animeItems = filtered.asSequence()
        .filter { it.seriesId == null || it.seriesId !in knownSeriesIds }
        .map(LibraryDisplayItem::AnimeItem)
        .toList()
    return sortLibraryDisplayItems(
        items = seriesItems + animeItems,
        sort = rules.filters.sort,
        ascending = rules.filters.sortAscending,
    )
}

private fun sortLibraryDisplayItems(
    items: List<LibraryDisplayItem>,
    sort: LibrarySort,
    ascending: Boolean,
): List<LibraryDisplayItem> {
    val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    return items.sortedWith { first, second ->
        val primary = when (sort) {
            LibrarySort.RECENT -> compareValues(first.latestAnimeId(), second.latestAnimeId(), ascending)
            LibrarySort.AIR_DATE -> compareNullableText(
                first.aggregateAirDate(ascending),
                second.aggregateAirDate(ascending),
                ascending,
            )
            LibrarySort.FINISH_DATE -> compareNullableText(
                first.memberAnimes().mapNotNull(AnimeEntity::watchFinishDate).maxOrNull(),
                second.memberAnimes().mapNotNull(AnimeEntity::watchFinishDate).maxOrNull(),
                ascending,
            )
            LibrarySort.RATING -> compareNullableNumber(
                first.memberAnimes().mapNotNull(::animeRatingSortValue).maxOrNull(),
                second.memberAnimes().mapNotNull(::animeRatingSortValue).maxOrNull(),
                ascending,
            )
            LibrarySort.TITLE -> compareText(first.title, second.title, ascending, collator)
            LibrarySort.PROGRESS -> compareValues(
                first.memberAnimes().maxOfOrNull(AnimeEntity::watchedEpisodes) ?: 0,
                second.memberAnimes().maxOfOrNull(AnimeEntity::watchedEpisodes) ?: 0,
                ascending,
            )
        }
        if (primary != 0) primary else compareValues(first.latestAnimeId(), second.latestAnimeId(), ascending)
    }
}

private fun LibraryDisplayItem.memberAnimes(): List<AnimeEntity> = when (this) {
    is LibraryDisplayItem.AnimeItem -> listOf(anime)
    is LibraryDisplayItem.SeriesItem -> members
}

private fun LibraryDisplayItem.latestAnimeId(): Long = memberAnimes().maxOfOrNull(AnimeEntity::id) ?: 0L

private fun LibraryDisplayItem.aggregateAirDate(ascending: Boolean): String? {
    val dates = memberAnimes().mapNotNull(AnimeEntity::airDate)
    return if (ascending) dates.minOrNull() else dates.maxOrNull()
}

internal fun selectBestRemoteMetadataMatch(
    anime: AnimeEntity,
    candidates: List<RemoteAnime>,
): RemoteAnime? {
    val externalId = anime.externalId?.trim().orEmpty()
    if (externalId.isNotEmpty()) {
        candidates.firstOrNull { candidate ->
            candidate.importId == externalId &&
                (anime.externalSource.isNullOrBlank() || candidate.importSource.equals(anime.externalSource, true))
        }?.let { return it }
    }
    val localTitles = listOfNotNull(anime.title, anime.originalTitle)
        .map(::normalizeMetadataTitle)
        .filter(String::isNotEmpty)
        .toSet()
    return candidates.mapNotNull { candidate ->
        val remoteTitles = listOfNotNull(candidate.title, candidate.originalTitle)
            .map(::normalizeMetadataTitle)
            .filter(String::isNotEmpty)
        val titleScore = remoteTitles.maxOfOrNull { remote ->
            localTitles.maxOfOrNull { local ->
                when {
                    local == remote -> 200
                    local.length >= 4 && remote.length >= 4 && (local in remote || remote in local) -> 90
                    else -> 0
                }
            } ?: 0
        } ?: 0
        val score = titleScore + if (
            normalizeSubjectType(candidate.subjectType) == normalizeSubjectType(anime.subjectType)
        ) 10 else 0
        candidate to score
    }.filter { it.second >= 90 }
        .maxByOrNull { it.second }
        ?.first
}

internal fun normalizeMetadataTitle(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")

internal fun buildTrackerHomeSections(
    source: List<AnimeEntity>,
    watchRecords: List<WatchRecordEntity>,
    recentLimit: Int = 12,
): TrackerHomeSections {
    val recordRank = buildMap<Long, Int> {
        watchRecords
            .sortedWith(compareByDescending<WatchRecordEntity> { it.recordDate.orEmpty() }.thenByDescending { it.id })
            .forEachIndexed { index, record -> putIfAbsent(record.animeId, index) }
    }
    val fallbackRecency = compareByDescending<AnimeEntity> { it.createdAt.orEmpty() }
        .thenByDescending(AnimeEntity::id)
    val watching = source
        .filter { it.status == "在看" }
        .sortedWith { first, second ->
            val firstRank = recordRank[first.id]
            val secondRank = recordRank[second.id]
            when {
                firstRank != null && secondRank != null -> firstRank.compareTo(secondRank)
                firstRank != null -> -1
                secondRank != null -> 1
                else -> fallbackRecency.compare(first, second)
            }
        }
    val recent = source.sortedWith(fallbackRecency).take(recentLimit.coerceAtLeast(0))
    val books = source.filter { it.subjectType.isBookSubjectType() }.sortedWith(fallbackRecency)
    return TrackerHomeSections(
        watching = watching,
        recentAdded = recent,
        books = books,
    )
}

internal fun filterAndSortAnimes(
    source: List<AnimeEntity>,
    query: String,
    status: String,
    sort: LibrarySort,
    subjectType: String = "all",
    selectedTagIds: Set<Long> = emptySet(),
    matchAllTags: Boolean = false,
    animeTagLinks: List<AnimeTagEntity> = emptyList(),
    years: Set<String> = emptySet(),
    ascending: Boolean = sort.defaultAscending,
): List<AnimeEntity> = source.asSequence()
    .filter { anime ->
        query.isBlank() ||
            anime.title.contains(query, ignoreCase = true) ||
            anime.originalTitle.orEmpty().contains(query, ignoreCase = true) ||
            anime.studio.orEmpty().contains(query, ignoreCase = true) ||
            anime.review.orEmpty().contains(query, ignoreCase = true)
    }
    .filter { anime -> status == "全部" || anime.status == status }
    .filter { anime ->
        val normalizedFilter = normalizeSubjectTypeFilter(subjectType)
        normalizedFilter == "all" || normalizeSubjectType(anime.subjectType) == normalizedFilter
    }
    .filter { anime -> years.isEmpty() || years.any { anime.airDate?.startsWith(it) == true } }
    .filter { anime ->
        if (selectedTagIds.isEmpty()) {
            true
        } else {
            val animeTags = animeTagLinks.asSequence()
                .filter { it.animeId == anime.id }
                .map(AnimeTagEntity::tagId)
                .toSet()
            if (matchAllTags) animeTags.containsAll(selectedTagIds) else animeTags.any(selectedTagIds::contains)
        }
    }
    .sortedWith(libraryComparator(sort, ascending))
    .toList()

private fun libraryComparator(sort: LibrarySort, ascending: Boolean): Comparator<AnimeEntity> {
    val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    return Comparator { first, second ->
        val primary = when (sort) {
            LibrarySort.RECENT -> compareValues(first.id, second.id, ascending)
            LibrarySort.AIR_DATE -> compareNullableText(first.airDate, second.airDate, ascending)
            LibrarySort.FINISH_DATE -> compareNullableText(first.watchFinishDate, second.watchFinishDate, ascending)
            LibrarySort.RATING -> compareNullableNumber(
                animeRatingSortValue(first),
                animeRatingSortValue(second),
                ascending,
            )
            LibrarySort.TITLE -> compareText(first.title, second.title, ascending, collator)
            LibrarySort.PROGRESS -> compareValues(first.watchedEpisodes, second.watchedEpisodes, ascending)
        }
        if (primary != 0) primary else compareValues(first.id, second.id, ascending)
    }
}

private fun compareNullableText(first: String?, second: String?, ascending: Boolean): Int {
    val left = first?.trim()?.takeIf(String::isNotEmpty)
    val right = second?.trim()?.takeIf(String::isNotEmpty)
    if (left == null || right == null) return when {
        left == null && right == null -> 0
        left == null -> 1
        else -> -1
    }
    return compareValues(left, right, ascending)
}

private fun compareNullableNumber(first: Int?, second: Int?, ascending: Boolean): Int {
    if (first == null || second == null) return when {
        first == null && second == null -> 0
        first == null -> 1
        else -> -1
    }
    return compareValues(first, second, ascending)
}

private fun compareText(first: String, second: String, ascending: Boolean, collator: Collator): Int {
    val compared = collator.compare(first, second)
    return if (ascending) compared else -compared
}

private fun <T : Comparable<T>> compareValues(first: T, second: T, ascending: Boolean): Int =
    if (ascending) first.compareTo(second) else second.compareTo(first)
