package com.animeow.app.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.broadcastWeekdayLabel
import com.animeow.app.data.hasActiveBroadcast
import com.animeow.app.data.matchBroadcastAnime
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.preferences.CalendarContentDensity
import com.animeow.app.data.preferences.CalendarDisplaySettings
import com.animeow.app.data.preferences.CalendarMarkerStyle
import com.animeow.app.data.preferences.CalendarModule
import com.animeow.app.data.preferences.CalendarPagePreset
import com.animeow.app.data.preferences.CalendarPreferences
import com.animeow.app.data.preferences.calendarPresetSettings
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.util.runCatchingCancellable
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CalendarMode(val displayName: String) {
    MONTH("月视图"),
    WEEK("周视图"),
    AGENDA("议程"),
}

enum class CalendarEventType(
    val storageKey: String,
    val displayName: String,
) {
    AIR_DATE("air_date", "放送"),
    WATCH_START("watch_start", "开始观看"),
    WATCH_FINISH("watch_finish", "完成观看"),
    WATCH_RECORD("watch_record", "打卡"),
    REMINDER("reminder", "更新提醒"),
    BROADCAST("broadcast", "追番更新"),
}

enum class MissingDateKind(val displayName: String) {
    AIR_DATE("缺少放送日期"),
    WATCH_START("缺少开始日期"),
    WATCH_FINISH("缺少完成日期"),
}

data class MissingDateItem(
    val anime: AnimeEntity,
    val missing: Set<MissingDateKind>,
)

data class CalendarEvent(
    val id: String,
    val animeId: Long,
    val date: LocalDate,
    val title: String,
    val subtitle: String,
    val type: CalendarEventType,
    val time: String? = null,
    val coverUrl: String? = null,
    val watchRecordId: Long? = null,
)

data class CalendarRepairState(
    val isRunning: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class CalendarUiState(
    val mode: CalendarMode = CalendarMode.MONTH,
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val events: List<CalendarEvent> = emptyList(),
    val missingDateItems: List<MissingDateItem> = emptyList(),
    val displaySettings: CalendarDisplaySettings = CalendarDisplaySettings(),
    val repairState: CalendarRepairState = CalendarRepairState(),
    val libraryAnimes: List<AnimeEntity> = emptyList(),
) {
    val followingAnimes: List<AnimeEntity> = libraryAnimes.filter {
        it.broadcastDay in 1..7 && normalizeSubjectType(it.subjectType) == "anime"
    }.sortedWith(compareBy<AnimeEntity> { it.broadcastDay }.thenBy { it.title })
    val hasReminders: Boolean = libraryAnimes.any { it.reminderDay in 1..7 && !it.reminderTime.isNullOrBlank() }
    val selectedEvents: List<CalendarEvent> = events.filter { it.date == selectedDate }
    val eventsByDate: Map<LocalDate, List<CalendarEvent>> = events.groupBy(CalendarEvent::date)
    val monthEvents: List<CalendarEvent> = events.filter { YearMonth.from(it.date) == visibleMonth }
    val historyEvents: List<CalendarEvent> = events.filter { event ->
        event.type != CalendarEventType.REMINDER &&
            event.type != CalendarEventType.BROADCAST &&
            event.date.year != selectedDate.year &&
            MonthDay.from(event.date) == MonthDay.from(selectedDate)
    }.sortedByDescending(CalendarEvent::date)
    val hiddenEventTypeCount: Int = CalendarEventType.entries.count {
        it.storageKey !in displaySettings.enabledEventTypes
    }
}

data class BroadcastImportState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val isImporting: Boolean = false,
    val items: List<RemoteAnime> = emptyList(),
    val selectedKeys: Set<String> = emptySet(),
    val message: String? = null,
    val isError: Boolean = false,
)

private data class CalendarSourceSnapshot(
    val animes: List<AnimeEntity>,
    val records: List<WatchRecordEntity>,
    val settings: CalendarDisplaySettings,
)

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val repository = app.libraryRepository
    private val discoveryRepository = app.discoveryRepository
    private val preferences = CalendarPreferences(application)
    private val settingsMutex = Mutex()
    private val mode = MutableStateFlow(CalendarMode.MONTH)
    private val visibleMonth = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val repairState = MutableStateFlow(CalendarRepairState())
    private val _broadcastState = MutableStateFlow(BroadcastImportState())
    val broadcastState = _broadcastState.asStateFlow()

    private val source = combine(
        repository.observeAnimes(),
        repository.observeWatchRecords(),
        preferences.settings,
    ) { animes, records, settings ->
        CalendarSourceSnapshot(animes, records, settings)
    }

    val state: StateFlow<CalendarUiState> = combine(
        source,
        mode,
        visibleMonth,
        selectedDate,
        repairState,
    ) { source, currentMode, month, selected, currentRepairState ->
        val events = buildCalendarEvents(source.animes, source.records, month)
            .filter { it.type.storageKey in source.settings.enabledEventTypes }
        CalendarUiState(
            mode = currentMode,
            visibleMonth = month,
            selectedDate = selected,
            events = events,
            missingDateItems = buildMissingDateItems(source.animes),
            displaySettings = source.settings,
            repairState = currentRepairState,
            libraryAnimes = source.animes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CalendarUiState(),
    )

    fun setMode(value: CalendarMode) {
        mode.value = value
    }

    fun loadBroadcastCalendar(forceRefresh: Boolean = false) {
        if (_broadcastState.value.isLoading || _broadcastState.value.isImporting ||
            (_broadcastState.value.hasLoaded && !forceRefresh)) return
        viewModelScope.launch {
            _broadcastState.update { it.copy(isLoading = true, message = null, isError = false) }
            runCatchingCancellable { discoveryRepository.broadcastCalendar() }
                .onSuccess { items ->
                    _broadcastState.update { current ->
                        current.copy(
                            isLoading = false, hasLoaded = true, items = items,
                            selectedKeys = current.selectedKeys.intersect(items.map { it.uniqueKey }.toSet()),
                        )
                    }
                }.onFailure { error ->
                    _broadcastState.update { it.copy(isLoading = false, message = error.message ?: "新番排期暂时无法加载，请重试", isError = true) }
                }
        }
    }

    fun toggleBroadcastSelection(key: String) {
        if (_broadcastState.value.isImporting) return
        _broadcastState.update {
            it.copy(selectedKeys = if (key in it.selectedKeys) it.selectedKeys - key else it.selectedKeys + key)
        }
    }

    fun importSelectedBroadcasts() = importBroadcasts(
        _broadcastState.value.items.filter { it.uniqueKey in _broadcastState.value.selectedKeys },
    )

    fun importWatchingBroadcasts() = importBroadcasts(
        _broadcastState.value.items.filter { remote ->
            matchBroadcastAnime(remote, state.value.libraryAnimes).anime?.let {
                it.status == "在看" && it.broadcastDay !in 1..7
            } == true
        },
    )

    private fun importBroadcasts(items: List<RemoteAnime>) {
        if (items.isEmpty() || _broadcastState.value.isImporting) return
        viewModelScope.launch {
            _broadcastState.update { it.copy(isImporting = true, message = null, isError = false) }
            runCatchingCancellable { repository.importBroadcastSchedules(items) }
                .onSuccess { result ->
                    mutateDisplaySettings { it.copy(enabledEventTypes = it.enabledEventTypes + CalendarEventType.BROADCAST.storageKey) }
                    _broadcastState.update { it.copy(
                        isImporting = false, selectedKeys = emptySet(),
                        message = "已导入 ${result.added + result.updated} 部的更新排期" +
                            if (result.skipped > 0) "，${result.skipped} 部保留原设置或跳过" else "",
                    ) }
                }.onFailure { error ->
                    _broadcastState.update { it.copy(isImporting = false, message = "导入失败：${error.message.orEmpty()}", isError = true) }
                }
        }
    }

    fun updateBroadcastSchedule(animeId: Long, day: Int?, time: String?) {
        if (_broadcastState.value.isImporting) return
        viewModelScope.launch {
            _broadcastState.update { it.copy(isImporting = true, message = null, isError = false) }
            runCatchingCancellable { repository.updateBroadcastSchedule(animeId, day, time) }
                .onSuccess {
                    _broadcastState.update { it.copy(isImporting = false, message = if (day == null) "已取消这部作品的日历排期" else "排期已保存") }
                }.onFailure { error ->
                    _broadcastState.update { it.copy(isImporting = false, message = error.message ?: "排期保存失败", isError = true) }
                }
        }
    }

    fun selectDate(value: LocalDate) {
        selectedDate.value = value
        visibleMonth.value = YearMonth.from(value)
    }

    fun previousPeriod() {
        when (mode.value) {
            CalendarMode.MONTH, CalendarMode.AGENDA -> {
                visibleMonth.value = visibleMonth.value.minusMonths(1)
                selectedDate.value = visibleMonth.value.atDay(1)
            }
            CalendarMode.WEEK -> selectDate(selectedDate.value.minusWeeks(1))
        }
    }

    fun nextPeriod() {
        when (mode.value) {
            CalendarMode.MONTH, CalendarMode.AGENDA -> {
                visibleMonth.value = visibleMonth.value.plusMonths(1)
                selectedDate.value = visibleMonth.value.atDay(1)
            }
            CalendarMode.WEEK -> selectDate(selectedDate.value.plusWeeks(1))
        }
    }

    fun jumpToday() {
        selectDate(LocalDate.now())
    }

    fun applyPreset(preset: CalendarPagePreset) {
        if (preset == CalendarPagePreset.CUSTOM) return
        mutateDisplaySettings { current ->
            calendarPresetSettings(preset, current.enabledEventTypes)
        }
    }

    fun setMarkerStyle(value: CalendarMarkerStyle) = mutateCustomSettings {
        it.copy(markerStyle = value)
    }

    fun setDensity(value: CalendarContentDensity) = mutateCustomSettings {
        it.copy(density = value)
    }

    fun setShowCovers(value: Boolean) = mutateCustomSettings {
        it.copy(showCovers = value)
    }

    fun setShowLegend(value: Boolean) = mutateCustomSettings {
        it.copy(showLegend = value)
    }

    fun setModuleVisible(module: CalendarModule, visible: Boolean) = mutateCustomSettings { current ->
        val hidden = current.hiddenModules.toMutableSet()
        if (visible) {
            hidden.remove(module)
        } else if (CalendarModule.entries.size - hidden.size > 1) {
            hidden.add(module)
        }
        current.copy(hiddenModules = hidden)
    }

    fun moveModule(module: CalendarModule, offset: Int) = mutateCustomSettings { current ->
        val order = current.moduleOrder.toMutableList()
        val from = order.indexOf(module)
        val to = (from + offset).coerceIn(0, order.lastIndex)
        if (from >= 0 && from != to) {
            order.removeAt(from)
            order.add(to, module)
        }
        current.copy(moduleOrder = order)
    }

    fun setEventTypeEnabled(type: CalendarEventType, enabled: Boolean) {
        mutateDisplaySettings { current ->
            val values = current.enabledEventTypes.toMutableSet()
            if (enabled) {
                values.add(type.storageKey)
            } else if (values.size > 1) {
                values.remove(type.storageKey)
            }
            current.copy(enabledEventTypes = values)
        }
    }

    fun resetDisplaySettings() {
        viewModelScope.launch { settingsMutex.withLock { preferences.reset() } }
    }

    fun inferMissingWatchDates(animeIds: Set<Long> = emptySet()) {
        if (repairState.value.isRunning) return
        viewModelScope.launch {
            repairState.value = CalendarRepairState(isRunning = true)
            runCatchingCancellable {
                val animes = repository.getActiveAnimesSnapshot()
                val recordsByAnime = repository.getWatchRecordsSnapshot()
                    .mapNotNull { record -> record.recordDate.toLocalDateOrNull()?.let { record.animeId to it } }
                    .groupBy({ it.first }, { it.second })
                val candidates = buildMissingDateItems(animes).filter {
                    animeIds.isEmpty() || it.anime.id in animeIds
                }
                var changedFields = 0
                var changedWorks = 0
                candidates.forEach { item ->
                    val dates = recordsByAnime[item.anime.id].orEmpty().sorted()
                    val start = dates.firstOrNull().takeIf { MissingDateKind.WATCH_START in item.missing }
                    val finish = dates.lastOrNull().takeIf { MissingDateKind.WATCH_FINISH in item.missing }
                    val changed = repository.fillMissingCalendarDates(
                        animeId = item.anime.id,
                        watchStartDate = start?.toString(),
                        watchFinishDate = finish?.toString(),
                    )
                    if (changed > 0) changedWorks += 1
                    changedFields += changed
                }
                if (changedFields == 0) {
                    "没有可从打卡记录可靠推断的日期，剩余项目可手动编辑"
                } else {
                    "已为 $changedWorks 部作品补全 $changedFields 个观看日期"
                }
            }.onSuccess { message ->
                repairState.value = CalendarRepairState(message = message)
            }.onFailure { error ->
                repairState.value = CalendarRepairState(
                    message = "推断日期失败：${error.message.orEmpty()}",
                    isError = true,
                )
            }
        }
    }

    fun repairMissingAirDatesOnline(animeIds: Set<Long> = emptySet()) {
        if (repairState.value.isRunning) return
        viewModelScope.launch {
            repairState.value = CalendarRepairState(isRunning = true)
            runCatchingCancellable {
                val candidates = buildMissingDateItems(repository.getActiveAnimesSnapshot())
                    .filter { MissingDateKind.AIR_DATE in it.missing }
                    .filter { animeIds.isEmpty() || it.anime.id in animeIds }
                var repaired = 0
                var failed = 0
                candidates.forEach { item ->
                    val anime = item.anime
                    val remote = runCatchingCancellable { findRemoteMatch(anime) }.getOrNull()
                    val airDate = remote?.airDate.toLocalDateOrNull()?.toString()
                    if (airDate == null) {
                        failed += 1
                    } else if (repository.fillMissingCalendarDates(anime.id, airDate = airDate) > 0) {
                        repaired += 1
                    }
                }
                when {
                    candidates.isEmpty() -> "没有需要联网补全放送日期的作品"
                    repaired == 0 -> "未找到可靠匹配；请检查标题或进入编辑页手动补充"
                    failed == 0 -> "已联网补全 $repaired 部作品的放送日期"
                    else -> "已补全 $repaired 部，另有 $failed 部未找到可靠匹配"
                }
            }.onSuccess { message ->
                repairState.value = CalendarRepairState(message = message)
            }.onFailure { error ->
                repairState.value = CalendarRepairState(
                    message = "联网补全失败：${error.message.orEmpty()}",
                    isError = true,
                )
            }
        }
    }

    fun clearRepairMessage() {
        if (!repairState.value.isRunning) repairState.value = CalendarRepairState()
    }

    suspend fun deleteWatchRecord(recordId: Long) {
        repository.deleteWatchRecord(recordId)
    }

    private suspend fun findRemoteMatch(anime: AnimeEntity): RemoteAnime? {
        val source = when (anime.externalSource?.lowercase(java.util.Locale.ROOT)) {
            "bangumi" -> DiscoverySourceFilter.BANGUMI
            "anilist" -> DiscoverySourceFilter.ANILIST
            "server" -> DiscoverySourceFilter.SERVER
            else -> DiscoverySourceFilter.ALL
        }
        val subjectType = normalizeSubjectType(anime.subjectType)
        val primary = discoveryRepository.search(anime.title, source, subjectType).items
        selectRemoteMatch(anime, primary)?.let { return it }
        val original = anime.originalTitle?.trim().orEmpty()
        if (original.isNotEmpty() && normalizeTitle(original) != normalizeTitle(anime.title)) {
            return selectRemoteMatch(
                anime,
                discoveryRepository.search(original, source, subjectType).items,
            )
        }
        return null
    }

    private fun mutateCustomSettings(transform: (CalendarDisplaySettings) -> CalendarDisplaySettings) {
        mutateDisplaySettings { current -> transform(current).copy(preset = CalendarPagePreset.CUSTOM) }
    }

    private fun mutateDisplaySettings(transform: (CalendarDisplaySettings) -> CalendarDisplaySettings) {
        viewModelScope.launch {
            settingsMutex.withLock {
                preferences.save(transform(preferences.snapshot()))
            }
        }
    }
}

internal fun buildCalendarEvents(
    animes: List<AnimeEntity>,
    records: List<WatchRecordEntity>,
    visibleMonth: YearMonth,
): List<CalendarEvent> {
    val activeById = animes.filter { it.deletedAt == null }.associateBy(AnimeEntity::id)
    val completionNumbers = records.asSequence()
        .mapNotNull { record ->
            val anime = activeById[record.animeId] ?: return@mapNotNull null
            if (record.isCompletionRecord(anime)) record else null
        }
        .groupBy(WatchRecordEntity::animeId)
        .values
        .flatMap { animeRecords ->
            animeRecords.sortedWith(compareBy(WatchRecordEntity::recordDate, WatchRecordEntity::id))
                .mapIndexed { index, record -> record.id to index + 1 }
        }
        .toMap()
    val recordedCompletionDates = records.filter { it.id in completionNumbers }
        .mapNotNull { record -> record.recordDate.toLocalDateOrNull()?.let { record.animeId to it } }
        .toSet()
    val rangeStart = visibleMonth.minusMonths(1).atDay(1)
    val rangeEnd = visibleMonth.plusMonths(1).atEndOfMonth()
    return buildList {
        animes.forEach { anime ->
            if (anime.deletedAt != null) return@forEach
            anime.airDate.toLocalDateOrNull()?.let { date ->
                add(CalendarEvent("air-${anime.id}-$date", anime.id, date, anime.title, "首次放送", CalendarEventType.AIR_DATE, coverUrl = anime.coverUrl))
            }
            anime.watchStartDate.toLocalDateOrNull()?.let { date ->
                add(CalendarEvent("start-${anime.id}-$date", anime.id, date, anime.title, "开始观看", CalendarEventType.WATCH_START, coverUrl = anime.coverUrl))
            }
            anime.watchFinishDate.toLocalDateOrNull()?.let { date ->
                if (anime.id to date !in recordedCompletionDates) {
                    add(CalendarEvent("finish-${anime.id}-$date", anime.id, date, anime.title, "完成观看", CalendarEventType.WATCH_FINISH, coverUrl = anime.coverUrl))
                }
            }
            if (anime.hasActiveBroadcast()) {
                val firstAirDate = anime.airDate.toLocalDateOrNull()
                val start = if (firstAirDate != null && firstAirDate.isAfter(rangeStart)) firstAirDate else rangeStart
                var date = start.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(anime.broadcastDay!!)))
                while (!date.isAfter(rangeEnd)) {
                    add(CalendarEvent(
                        id = "broadcast-${anime.id}-$date", animeId = anime.id, date = date, title = anime.title,
                        subtitle = "每${broadcastWeekdayLabel(anime.broadcastDay)}更新" + if (anime.broadcastTime.isNullOrBlank()) " · 时间待定" else "",
                        type = CalendarEventType.BROADCAST, time = anime.broadcastTime, coverUrl = anime.coverUrl,
                    ))
                    date = date.plusWeeks(1)
                }
            }
            val reminderDay = anime.reminderDay
            if (reminderDay != null && !anime.reminderTime.isNullOrBlank()) {
                var date = rangeStart
                while (!date.isAfter(rangeEnd)) {
                    if (date.dayOfWeek.value == reminderDay) {
                        add(
                            CalendarEvent(
                                id = "reminder-${anime.id}-$date",
                                animeId = anime.id,
                                date = date,
                                title = anime.title,
                                subtitle = "每周更新提醒",
                                type = CalendarEventType.REMINDER,
                                time = anime.reminderTime,
                                coverUrl = anime.coverUrl,
                            ),
                        )
                    }
                    date = date.plusDays(1)
                }
            }
        }
        records.forEach { record ->
            val anime = activeById[record.animeId] ?: return@forEach
            val date = record.recordDate.toLocalDateOrNull() ?: return@forEach
            val completionNumber = completionNumbers[record.id]
            add(
                CalendarEvent(
                    id = "record-${record.id}",
                    animeId = anime.id,
                    date = date,
                    title = anime.title,
                    subtitle = if (completionNumber != null) {
                        val action = if (anime.subjectType.isBookSubjectType()) "完成阅读" else "完成观看"
                        if (completionNumber > 1) "$action（第 $completionNumber 次）" else action
                    } else {
                        if (anime.subjectType.isBookSubjectType()) {
                            "阅读第 ${record.episode} 话/页"
                        } else {
                            "打卡第 ${record.episode} 集"
                        }
                    },
                    type = if (completionNumber != null) CalendarEventType.WATCH_FINISH else CalendarEventType.WATCH_RECORD,
                    coverUrl = anime.coverUrl,
                    watchRecordId = record.id,
                ),
            )
        }
    }.distinctBy(CalendarEvent::id)
        .sortedWith(compareBy(CalendarEvent::date, CalendarEvent::time, CalendarEvent::type))
}

private fun WatchRecordEntity.isCompletionRecord(anime: AnimeEntity): Boolean {
    val normalized = status.orEmpty().trim()
    if (normalized.equals("completed", ignoreCase = true)) return true
    return normalized in COMPLETED_STATUSES &&
        (anime.totalEpisodes <= 0 || episode >= anime.totalEpisodes)
}

internal fun buildMissingDateItems(animes: List<AnimeEntity>): List<MissingDateItem> =
    animes.mapNotNull { anime ->
        val missing = buildSet {
            if (anime.airDate.isNullOrBlank()) add(MissingDateKind.AIR_DATE)
            if (anime.status == "在看" && anime.watchStartDate.isNullOrBlank()) {
                add(MissingDateKind.WATCH_START)
            }
            if (anime.status in COMPLETED_STATUSES && anime.watchFinishDate.isNullOrBlank()) {
                add(MissingDateKind.WATCH_FINISH)
            }
        }
        if (missing.isEmpty()) null else MissingDateItem(anime, missing)
    }.sortedWith(
        compareByDescending<MissingDateItem> { it.missing.size }
            .thenBy { it.anime.title.lowercase(java.util.Locale.ROOT) },
    )

internal fun selectRemoteMatch(anime: AnimeEntity, candidates: List<RemoteAnime>): RemoteAnime? {
    val externalId = anime.externalId?.trim().orEmpty()
    if (externalId.isNotEmpty()) {
        candidates.firstOrNull {
            it.importId == externalId &&
                (anime.externalSource.isNullOrBlank() || it.importSource.equals(anime.externalSource, true)) &&
                it.airDate.toLocalDateOrNull() != null
        }?.let { return it }
    }
    val titles = listOfNotNull(anime.title, anime.originalTitle)
        .map(::normalizeTitle)
        .filter(String::isNotEmpty)
        .toSet()
    return candidates.firstOrNull { candidate ->
        candidate.airDate.toLocalDateOrNull() != null &&
            listOfNotNull(candidate.title, candidate.originalTitle).map(::normalizeTitle).any(titles::contains)
    }
}

private fun normalizeTitle(value: String): String =
    value.lowercase(java.util.Locale.ROOT).replace(Regex("[\\s\\p{P}\\p{S}]+"), "")

private fun String?.toLocalDateOrNull(): LocalDate? {
    val normalized = this?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
    return runCatching { LocalDate.parse(normalized) }.getOrNull()
}

private val COMPLETED_STATUSES = setOf("看完", "已完成", "完成")
