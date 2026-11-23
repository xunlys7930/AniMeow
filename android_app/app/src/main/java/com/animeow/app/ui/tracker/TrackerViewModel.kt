package com.animeow.app.ui.tracker

import com.animeow.app.data.local.allTitles
import com.animeow.app.data.preferences.withModuleVisible

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.ProgressChange
import com.animeow.app.data.lastModifiedTime
import com.animeow.app.data.statusOrderTime
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
import com.animeow.app.data.remote.RemoteAnime
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch

enum class LibrarySort(
    val storageKey: String,
    val displayName: String,
    val defaultAscending: Boolean,
) {
    RECENT("recent", "最近添加", false),
    UPDATED("updated", "最后修改", false),
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

internal data class TrackerFilterSelection(
    val base: BaseLibraryFilters = BaseLibraryFilters("", "全部", LibrarySort.RECENT, false),
    val advanced: AdvancedLibraryFilters = AdvancedLibraryFilters(),
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
    val reading: List<AnimeEntity> = emptyList(),
    val recentAdded: List<AnimeEntity> = emptyList(),
    val books: List<AnimeEntity> = emptyList(),
    val incomplete: List<AnimeEntity> = emptyList(),
    val todaySchedule: List<AnimeEntity> = emptyList(),
    val recap: WeeklyRecap = WeeklyRecap(),
    val date: java.time.LocalDate = java.time.LocalDate.now(),
)

data class TrackerLibraryState(
    val items: List<LibraryDisplayItem> = emptyList(),
    val isLoading: Boolean = true,
)

/** Room flows have no synthetic initial value. Publish readiness and content in the same emission. */
internal fun trackerLibraryState(
    animes: kotlinx.coroutines.flow.Flow<List<AnimeEntity>>,
    tags: kotlinx.coroutines.flow.Flow<List<AnimeTagEntity>>,
    series: kotlinx.coroutines.flow.Flow<List<SeriesEntity>>,
    rules: kotlinx.coroutines.flow.Flow<LibraryDisplayRules>,
): kotlinx.coroutines.flow.Flow<TrackerLibraryState> = combine(animes, tags, series, rules) { source, tagLinks, allSeries, display ->
    val filtered = filterAndSortAnimes(
        source = source, query = display.filters.query, status = display.filters.status,
        sort = display.filters.sort, subjectType = display.advanced.subjectType,
        selectedTagIds = display.advanced.tagIds, matchAllTags = display.advanced.matchAllTags,
        animeTagLinks = tagLinks, years = display.advanced.years, ascending = display.filters.sortAscending,
    )
    TrackerLibraryState(buildLibraryDisplayItems(filtered, source, allSeries, display), isLoading = false)
}

data class BatchMetadataOptions(
    val fields: RemoteMetadataFields = RemoteMetadataFields(),
    val overwriteExisting: Boolean = false,
)

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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun kotlinx.coroutines.flow.Flow<LibraryDisplayRules>.settleSearch() = kotlinx.coroutines.flow.flow {
    var previousQuery: String? = null
    emitAll(transformLatest { rules ->
        val changed = previousQuery != null && previousQuery != rules.filters.query
        previousQuery = rules.filters.query
        if (changed && rules.filters.query.isNotBlank()) kotlinx.coroutines.delay(150)
        emit(rules)
    })
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrackerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val discoveryRepository = (application as AniMeowApplication).discoveryRepository
    private val reminderScheduler = (application as AniMeowApplication).reminderScheduler
    private val trackerPreferences = TrackerPreferences(application)
    private val appearancePreferences = com.animeow.app.data.preferences.AppearancePreferences(application)
    private val cloudSyncPreferences = com.animeow.app.data.cloud.CloudSyncPreferences(application)
    val bangumiSyncEnabled: StateFlow<Boolean> = cloudSyncPreferences.settings
        .map { it.bangumiProgressSync || it.bangumiRatingSync }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val bangumiShowHomeStatus: StateFlow<Boolean> = cloudSyncPreferences.settings
        .map { it.bangumiShowHomeStatus }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val bangumiSyncStatus = combine(
        (application as AniMeowApplication).database.libraryDao().observeBangumiPendingSyncCount(),
        cloudSyncPreferences.settings,
    ) { count, cloud ->
        if ((cloud.bangumiProgressSync || cloud.bangumiRatingSync) && cloud.bangumiShowHomeStatus)
            com.animeow.app.data.bangumi.bangumiQueueStatusText(count, cloud.bangumiLastReport)
        else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val filters = MutableStateFlow(TrackerFilterSelection())
    private val _selectedAnimeIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _trackerSettings = MutableStateFlow(TrackerSettings())
    private val batchMetadata = BatchMetadataController(viewModelScope, repository, search = { anime, query ->
        discoveryRepository.searchMetadata(query, normalizeSubjectType(anime.subjectType), anime.externalSource, anime.externalId) { selectBestRemoteMetadataMatch(anime, it) != null }
    },
        onError = { getApplication<AniMeowApplication>().operationLog.recordError(it, "批量补全") },
        onFinished = { succeeded -> _selectedAnimeIds.value = _selectedAnimeIds.value - succeeded })
    private val settingsLoaded = MutableStateFlow(false)
    private val sourceAnimes = repository.observeAnimes().shareIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        replay = 1,
    )

    private val displayRules = combine(filters, _trackerSettings) { selected, settings ->
        LibraryDisplayRules(selected.base, selected.advanced, settings)
    }

    val libraryState: StateFlow<TrackerLibraryState> = trackerLibraryState(
        sourceAnimes,
        repository.observeAnimeTags(),
        repository.observeSeries(),
        combine(displayRules, settingsLoaded) { rules, loaded -> rules.takeIf { loaded } }.filterNotNull().settleSearch(),
    ).flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackerLibraryState(),
    )

    val isDefaultMode: StateFlow<Boolean> = displayRules.map { it.isDefaultMode }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    val showHomeModules = filters.map { it.base.query.isBlank() && it.base.status == "全部" && it.advanced.tagIds.isEmpty() && it.advanced.years.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val homeDateRefresh = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun refreshHomeDate() { homeDateRefresh.tryEmit(Unit) }

    private val homeActivity = homeDateUpdates(homeDateRefresh).flatMapLatest { today ->
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        // Offset timestamps can cross two calendar dates at extreme time zones.
        combine(repository.observeLatestWatchRecords(), repository.observeWatchRecordsSince(monday.minusDays(2).toString())) { latest, recent ->
            today to (recent + latest.map { WatchRecordEntity(it.id, it.animeId, 0, recordDate = it.recordDate) })
        }
    }
    val homeSections: StateFlow<TrackerHomeSections> = combine(
        sourceAnimes,
        homeActivity,
        displayRules.map { it.advanced.subjectType }.distinctUntilChanged(),
        showHomeModules,
    ) { source, activity, subjectType, show ->
        val (today, records) = activity
        if (!show) {
            TrackerHomeSections()
        } else {
            buildTrackerHomeSections(source.filter { subjectType == "all" || normalizeSubjectType(it.subjectType) == normalizeSubjectType(subjectType) }, records, today = today)
        }
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackerHomeSections(),
    )

    val statusOptions: StateFlow<List<String>> = combine(
        repository.observeWatchStatuses(),
        sourceAnimes,
        _trackerSettings,
    ) { configured, source, settings ->
        listOf("全部") + com.animeow.app.data.preferences.statusOptionNames(configured, source.map { it.status }, settings.hiddenHomeStatusIds)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = listOf("全部"),
    )

    val actionStatusOptions = combine(repository.observeWatchStatuses(), sourceAnimes, _trackerSettings) { configured, source, settings ->
        com.animeow.app.data.preferences.statusOptionNames(configured, source.map { it.status }, settings.hiddenActionStatusIds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setProgressPlacement(value: com.animeow.app.data.preferences.CardProgressPlacement) = updateTrackerSettings { it.copy(progressPlacement = value) }
    fun setCoverProgressAlpha(value: Float) = updateTrackerSettings { it.copy(coverProgressAlpha = value.coerceIn(0.15f, 0.95f)) }

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
        }.flowOn(Dispatchers.Default).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = mapOf("全部" to 0),
        )

    val query = filters.map { it.base.query }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val status = filters.map { it.base.status }.stateIn(viewModelScope, SharingStarted.Eagerly, "全部")
    val sort = filters.map { it.base.sort }.stateIn(viewModelScope, SharingStarted.Eagerly, LibrarySort.RECENT)
    val sortAscending = filters.map { it.base.sortAscending }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val selectedAnimeIds: StateFlow<Set<Long>> = _selectedAnimeIds
    val advanced = filters.map { it.advanced }.stateIn(viewModelScope, SharingStarted.Eagerly, AdvancedLibraryFilters())
    val trackerSettings: StateFlow<TrackerSettings> = _trackerSettings
    val batchMetadataState: StateFlow<BatchMetadataState> = batchMetadata.state
    suspend fun groupDraggedAnime(source: AnimeEntity, target: LibraryDisplayItem, name: String) {
        require(canGroupDrop(source, target)) { "请拖到其他作品或不同系列" }
        val targetSeries = when (target) {
            is LibraryDisplayItem.SeriesItem -> target.series.id
            is LibraryDisplayItem.AnimeItem -> target.anime.seriesId
        }
        val expected = if (target is LibraryDisplayItem.AnimeItem) mapOf(source.id to source.seriesId,
            target.anime.id to target.anime.seriesId) else mapOf(source.id to source.seriesId)
        repository.groupDraggedAnimes(expected, targetSeries, name)
    }
    val availableYears: StateFlow<List<String>> = sourceAnimes.map { source ->
        source.mapNotNull { it.airDate?.take(4)?.takeIf { year -> year.all(Char::isDigit) } }
            .distinct()
            .sortedDescending()
    }.flowOn(Dispatchers.Default).stateIn(
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
            combine(repository.observeWatchStatuses(), _trackerSettings, status) { statuses, settings, selected ->
                statuses.any { it.id in settings.hiddenHomeStatusIds && it.name == selected }
            }.collect { hidden -> if (hidden) setStatus("全部") }
        }
        viewModelScope.launch {
            var initialized = false
            trackerPreferences.settings.collect { persisted ->
                val saved = persisted.normalized()
                _trackerSettings.value = saved
                if (!initialized) {
                    val initialStatus = when (saved.defaultStartStatus) {
                        TRACKER_START_ALL_STATUS -> "全部"
                        TRACKER_START_LAST_STATUS -> saved.lastSelectedStatus
                        else -> saved.defaultStartStatus
                    }
                    filters.value = TrackerFilterSelection(BaseLibraryFilters("", initialStatus,
                        LibrarySort.fromStorage(saved.lastSortKey), saved.sortAscending), AdvancedLibraryFilters(subjectType = saved.lastSubjectType))
                    initialized = true
                    settingsLoaded.value = true
                }
            }
        }
    }

    fun applyCollectionFilter(value: com.animeow.app.data.preferences.CollectionFilterConditions): String? {
        if (_selectedAnimeIds.value.isNotEmpty()) return "请先结束当前多选，再应用筛选方案"
        val matches = value.tags.map { name -> tags.value.singleOrNull { it.name == name } ?: return "标签已变化，请调整方案后重试" }
        if (value.status != "全部" && value.status !in statusOptions.value) return "状态已变化或被隐藏，请调整方案后重试"
        filters.value = TrackerFilterSelection(BaseLibraryFilters(value.query, value.status, LibrarySort.fromStorage(value.sort), value.ascending),
            AdvancedLibraryFilters(normalizeSubjectTypeFilter(value.subjectType), matches.map { it.id }.toSet(), value.matchAllTags, value.years.toSet()))
        viewModelScope.launch { trackerPreferences.update { it.copy(lastSelectedStatus = value.status,
            lastSubjectType = value.subjectType, lastSortKey = value.sort, sortAscending = value.ascending) } }
        return null
    }

    fun setSearchQuery(value: String) { filters.update { it.copy(base = it.base.copy(query = value)) } }

    fun setStatus(value: String) {
        filters.update { it.copy(base = it.base.copy(status = value)) }
        _trackerSettings.update { it.copy(lastSelectedStatus = value) }
        viewModelScope.launch { trackerPreferences.setLastSelectedStatus(value) }
    }

    fun setSort(value: LibrarySort) {
        filters.update { it.copy(base = it.base.copy(sort = value, sortAscending = value.defaultAscending)) }
        persistSort()
    }

    fun toggleSortDirection() {
        filters.update { it.copy(base = it.base.copy(sortAscending = !it.base.sortAscending)) }
        persistSort()
    }

    fun resetSort() { setSort(LibrarySort.RECENT) }

    fun setSubjectType(value: String) {
        val normalized = normalizeSubjectTypeFilter(value)
        filters.update { it.copy(advanced = it.advanced.copy(subjectType = normalized)) }
        _trackerSettings.update { it.copy(lastSubjectType = normalized) }
        viewModelScope.launch { trackerPreferences.setLastSubjectType(normalized) }
    }

    fun toggleTagFilter(tagId: Long) {
        filters.update { it.copy(advanced = it.advanced.copy(tagIds = if (tagId in it.advanced.tagIds) it.advanced.tagIds - tagId else it.advanced.tagIds + tagId)) }
    }

    fun setTagMatchAll(value: Boolean) { filters.update { it.copy(advanced = it.advanced.copy(matchAllTags = value)) } }

    fun toggleYear(value: String) {
        filters.update { it.copy(advanced = it.advanced.copy(years = if (value in it.advanced.years) it.advanced.years - value else it.advanced.years + value)) }
    }

    fun clearYears() { filters.update { it.copy(advanced = it.advanced.copy(years = emptySet())) } }

    fun clearAdvancedFilters(subjectType: String = "all") {
        val normalized = normalizeSubjectTypeFilter(subjectType)
        filters.update { it.copy(advanced = AdvancedLibraryFilters(subjectType = normalized)) }
        _trackerSettings.update { it.copy(lastSubjectType = normalized) }
        viewModelScope.launch { trackerPreferences.setLastSubjectType(normalized) }
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
    fun setHomeModuleVisible(module: com.animeow.app.data.preferences.HomeModule, visible: Boolean) = updateTrackerSettings { it.withModuleVisible(module, visible) }
    fun moveHomeModule(module: com.animeow.app.data.preferences.HomeModule, offset: Int) = updateTrackerSettings { current ->
        val order = current.homeModuleOrder.toMutableList()
        val index = order.indexOf(module)
        if (index >= 0) order.add((index + offset).coerceIn(0, order.lastIndex), order.removeAt(index))
        current.copy(homeModuleOrder = order)
    }
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

    fun setBangumiShowHomeStatus(visible: Boolean) {
        viewModelScope.launch { cloudSyncPreferences.setBangumiShowHomeStatus(visible) }
    }

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

    suspend fun updateSelectedEpisodeProgress(ids: Set<Long>, progress: Int): Int =
        repository.updateSelectedEpisodeProgress(ids, progress)

    suspend fun resetUnwatchedEpisodeProgress(): Int = repository.resetUnwatchedEpisodeProgress()

    suspend fun previewBatchEpisodeProgress(ids: Set<Long>?, progress: Int) = repository.previewBatchEpisodeProgress(ids, progress)

    suspend fun applyBatchEpisodeProgress(preview: com.animeow.app.data.BatchEpisodeProgress) = repository.applyBatchEpisodeProgress(preview)

    suspend fun undoBatchEpisodeProgress(change: com.animeow.app.data.BatchEpisodeProgress, appliedAt: Long) =
        repository.undoBatchEpisodeProgress(change, appliedAt)

    suspend fun applyCollectionPosterPreset(preset: com.animeow.app.data.preferences.CollectionPosterPreset) {
        trackerPreferences.update(preset::apply)
        appearancePreferences.applyCollectionPosterStyle()
    }

    suspend fun restoreHomeCustomization(appearance: com.animeow.app.ui.theme.AppearanceSettings, tracker: TrackerSettings) {
        trackerPreferences.update { current -> tracker.copy(
            hiddenHomeStatusIds = current.hiddenHomeStatusIds, hiddenActionStatusIds = current.hiddenActionStatusIds,
            lastSelectedStatus = current.lastSelectedStatus, lastSubjectType = current.lastSubjectType,
            lastSortKey = current.lastSortKey, sortAscending = current.sortAscending,
            showStatusCount = current.showStatusCount, timelineGroupField = current.timelineGroupField, infoOpacity = current.infoOpacity,
        ) }
        appearancePreferences.restoreHomeCustomization(appearance)
    }

    suspend fun setPinned(ids: Set<Long>, pinned: Boolean): com.animeow.app.data.PinChange =
        repository.setPinned(ids, pinned)

    suspend fun restorePins(change: com.animeow.app.data.PinChange) = repository.restorePins(change)

    suspend fun deleteSelected() {
        val movedIds = repository.deleteAnimes(_selectedAnimeIds.value)
        movedIds.forEach(::cancelReminderSafely)
        clearSelection()
    }

    suspend fun updateTagsForSelected(
        tagIds: Set<Long>,
        remove: Boolean,
        newTags: List<com.animeow.app.data.BatchTagDraft>,
    ) {
        repository.updateTagsForAnimes(_selectedAnimeIds.value, tagIds, remove, newTags)
        clearSelection()
    }

    suspend fun syncSelectedCovers(): CoverSyncSummary {
        val result = repository.syncCoverUrlsToServer(_selectedAnimeIds.value)
        clearSelection()
        return result
    }

    fun startBatchMetadataMatch(options: BatchMetadataOptions) = batchMetadata.startBatchMetadataMatch(options, _selectedAnimeIds.value)
    fun retryBatchMetadataMatch() = batchMetadata.retryBatchMetadataMatch()
    fun skipCurrentMetadataMatch() = batchMetadata.skipCurrentMetadataMatch()
    fun cancelBatchMetadataMatch() = batchMetadata.cancelBatchMetadataMatch()
    fun clearBatchMetadataMessage() = batchMetadata.clearBatchMetadataMessage()

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

    private suspend fun scheduleReminderSafely(anime: AnimeEntity) {
        try {
            reminderScheduler.schedule(anime)
        } catch (error: Exception) {
            getApplication<AniMeowApplication>().operationLog.recordError(error, "首页 · 恢复提醒")
        }
    }

    private fun persistSort() {
        val sort = filters.value.base.sort
        val ascending = filters.value.base.sortAscending
        _trackerSettings.update { it.copy(lastSortKey = sort.storageKey, sortAscending = ascending) }
        viewModelScope.launch { trackerPreferences.setLastSort(sort.storageKey, ascending) }
    }

    private fun updateTrackerSettings(transform: (TrackerSettings) -> TrackerSettings) {
        viewModelScope.launch { trackerPreferences.update(transform) }
    }


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
    // Index once instead of scanning the entire library for every series.
    val membersBySeries = (if (isFilteredView) filtered else allAnimes).groupBy(AnimeEntity::seriesId)
    val seriesItems = allSeries.asSequence()
        .filter { it.id in targetSeriesIds && (!searching || it.name.contains(normalizedQuery, true) || it.description.orEmpty().contains(normalizedQuery, true)) }
        .map { series ->
            val members = membersBySeries[series.id].orEmpty()
            LibraryDisplayItem.SeriesItem(
                series = series,
                members = members,
            )
        }
        .filter { searching || it.members.isNotEmpty() }
        .toList()
    val animeItems = filtered.asSequence()
        .filter { searching || it.seriesId == null || it.seriesId !in knownSeriesIds }
        .map(LibraryDisplayItem::AnimeItem)
        .toList()
    return sortLibraryDisplayItems(
        items = seriesItems + animeItems,
        sort = rules.filters.sort,
        ascending = rules.filters.sortAscending,
        statusRecency = rules.filters.status != "全部",
    )
}

private fun sortLibraryDisplayItems(
    items: List<LibraryDisplayItem>,
    sort: LibrarySort,
    ascending: Boolean,
    statusRecency: Boolean,
): List<LibraryDisplayItem> {
    val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    val statusTimes = if (statusRecency && sort == LibrarySort.RECENT) items.associate { item ->
        item.stableKey to (item.memberAnimes().maxOfOrNull { it.statusOrderTime() } ?: 0L)
    } else emptyMap()
    val updatedTimes = if (sort == LibrarySort.UPDATED) items.associate { item ->
        item.stableKey to (item.memberAnimes().maxOfOrNull { it.lastModifiedTime() } ?: 0L)
    } else emptyMap()
    return items.sortedWith { first, second ->
        val pin = comparePinned(first.memberAnimes().mapNotNull(AnimeEntity::pinnedAt).maxOrNull(),
            second.memberAnimes().mapNotNull(AnimeEntity::pinnedAt).maxOrNull())
        if (pin != 0) return@sortedWith pin
        val primary = when (sort) {
            LibrarySort.RECENT -> if (statusRecency) compareValues(statusTimes.getValue(first.stableKey), statusTimes.getValue(second.stableKey), ascending)
                else compareValues(first.latestAnimeId(), second.latestAnimeId(), ascending)
            LibrarySort.UPDATED -> compareValues(
                updatedTimes.getValue(first.stableKey),
                updatedTimes.getValue(second.stableKey),
                ascending,
            )
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
    fun sourceKey(value: String?) = value?.trim()?.lowercase(Locale.ROOT)?.let { if (it == "bgm") "bangumi" else it }
    val externalSource = sourceKey(anime.externalSource)
    val compatible = candidates.filter { normalizeSubjectType(it.subjectType) == normalizeSubjectType(anime.subjectType) }
    if (externalId.isNotEmpty()) {
        compatible.firstOrNull { candidate ->
            candidate.importId == externalId &&
                (externalSource.isNullOrBlank() || sourceKey(candidate.importSource) == externalSource)
        }?.let { return it }
    }
    val localTitles = listOfNotNull(anime.title, anime.originalTitle)
        .map(::normalizeMetadataTitle)
        .filter(String::isNotEmpty)
        .toSet()
    // Automatic batch completion must not substitute another season or a same-name remake.
    // Ambiguous/partial matches remain available through the editor's manual search.
    val localYear = anime.airDate?.take(4)?.toIntOrNull()
    return compatible.filter { candidate ->
        if (externalId.isNotEmpty() && !externalSource.isNullOrBlank() && sourceKey(candidate.importSource) == externalSource) return@filter false
        val remoteYear = candidate.airDate?.take(4)?.toIntOrNull()
        if (localYear != null && remoteYear != null && localYear != remoteYear) return@filter false
        val remoteTitles = listOfNotNull(candidate.title, candidate.originalTitle)
            .map(::normalizeMetadataTitle)
            .filter(String::isNotEmpty)
        remoteTitles.any { it in localTitles }
    }.distinctBy { "${sourceKey(it.importSource)}:${it.importId}" }.singleOrNull()
}

internal fun normalizeMetadataTitle(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")

internal fun buildTrackerHomeSections(
    source: List<AnimeEntity>,
    watchRecords: List<WatchRecordEntity>,
    recentLimit: Int = 12,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): TrackerHomeSections {
    val recordOrder = compareBy<WatchRecordEntity> { it.recordDate.orEmpty() }.thenBy { it.id }
    val latestRecords = buildMap<Long, WatchRecordEntity> {
        watchRecords.forEach { record ->
            val previous = get(record.animeId)
            if (previous == null || recordOrder.compare(record, previous) > 0) put(record.animeId, record)
        }
    }
    val fallbackRecency = compareByDescending<AnimeEntity> { it.createdAt.orEmpty() }
        .thenByDescending(AnimeEntity::id)
    val reading = source
        .filter { it.subjectType.isBookSubjectType() && it.status == "在看" }
        .sortedWith { first, second ->
            val firstRank = latestRecords[first.id]
            val secondRank = latestRecords[second.id]
            when {
                firstRank != null && secondRank != null -> recordOrder.compare(secondRank, firstRank)
                firstRank != null -> -1
                secondRank != null -> 1
                else -> fallbackRecency.compare(first, second)
            }
        }
    val recent = source.sortedWith(fallbackRecency).take(recentLimit.coerceAtLeast(0))
    val books = source.filter { it.subjectType.isBookSubjectType() }.sortedWith(fallbackRecency)
    return TrackerHomeSections(
        reading = reading,
        recentAdded = recent,
        books = books,
        incomplete = source.filter { it.coverUrl.isNullOrBlank() || it.synopsis.isNullOrBlank() }.sortedWith(fallbackRecency),
        todaySchedule = source.filter { it.status.trim() !in com.animeow.app.data.REMINDER_STOP_STATUSES + "搁置" && (it.airDate == today.toString() || it.reminderDay == today.dayOfWeek.value) }.sortedBy { it.reminderTime },
        recap = buildWeeklyRecap(source, watchRecords, today),
        date = today,
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
): List<AnimeEntity> {
    val normalizedFilter = normalizeSubjectTypeFilter(subjectType)
    // Only selected tags matter; build their membership index once per filter update.
    val selectedTagsByAnime = if (selectedTagIds.isEmpty()) emptyMap() else {
        val index = HashMap<Long, MutableSet<Long>>()
        animeTagLinks.forEach { link ->
            if (link.tagId in selectedTagIds) index.getOrPut(link.animeId) { hashSetOf() }.add(link.tagId)
        }
        index
    }
    return source.asSequence()
    .filter { anime ->
        query.isBlank() ||
            anime.allTitles().any { it.contains(query, ignoreCase = true) } ||
            anime.studio.orEmpty().contains(query, ignoreCase = true) ||
            anime.review.orEmpty().contains(query, ignoreCase = true)
    }
    .filter { anime -> status == "全部" || anime.status == status }
    .filter { anime ->
        normalizedFilter == "all" || normalizeSubjectType(anime.subjectType) == normalizedFilter
    }
    .filter { anime -> years.isEmpty() || years.any { anime.airDate?.startsWith(it) == true } }
    .filter { anime ->
        if (selectedTagIds.isEmpty()) {
            true
        } else {
            val animeTags = selectedTagsByAnime[anime.id].orEmpty()
            if (matchAllTags) animeTags.containsAll(selectedTagIds) else animeTags.any(selectedTagIds::contains)
        }
    }
    .sortedWith(libraryComparator(sort, ascending, status != "全部"))
    .toList()
}

private fun libraryComparator(sort: LibrarySort, ascending: Boolean, statusRecency: Boolean): Comparator<AnimeEntity> {
    val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    val statusTimes = mutableMapOf<Long, Long>()
    val updatedTimes = mutableMapOf<Long, Long>()
    return Comparator { first, second ->
        val pin = comparePinned(first.pinnedAt, second.pinnedAt)
        if (pin != 0) return@Comparator pin
        val primary = when (sort) {
            LibrarySort.RECENT -> if (statusRecency) compareValues(
                statusTimes.getOrPut(first.id) { first.statusOrderTime() },
                statusTimes.getOrPut(second.id) { second.statusOrderTime() }, ascending,
            ) else compareValues(first.id, second.id, ascending)
            LibrarySort.UPDATED -> compareValues(
                updatedTimes.getOrPut(first.id) { first.lastModifiedTime() },
                updatedTimes.getOrPut(second.id) { second.lastModifiedTime() },
                ascending,
            )
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

/** Pins stay ahead in either sort direction; filtering still determines membership. */
private fun comparePinned(first: Long?, second: Long?): Int = when {
    first == null && second == null -> 0
    first == null -> 1
    second == null -> -1
    else -> second.compareTo(first)
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
