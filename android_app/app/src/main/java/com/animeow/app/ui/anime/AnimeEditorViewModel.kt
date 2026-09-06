package com.animeow.app.ui.anime

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.parseAnimeRatingInput
import com.animeow.app.data.local.sanitizeAnimeRatingInput
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.withNormalizedSubjectType
import com.animeow.app.data.local.normalizeTagKey
import com.animeow.app.data.local.simplifyTagName
import com.animeow.app.data.media.CoverImageStore
import com.animeow.app.data.preferences.AnimeEditorDensity
import com.animeow.app.data.preferences.AnimeEditorModule
import com.animeow.app.data.preferences.AnimeEditorPreferences
import com.animeow.app.data.preferences.AnimeEditorPreset
import com.animeow.app.data.preferences.AnimeEditorSettings
import com.animeow.app.data.preferences.EditorExitBehavior
import com.animeow.app.data.preferences.TrackerPreferences
import com.animeow.app.data.preferences.animeEditorPresetSettings
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class AnimeEditorState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isCoverProcessing: Boolean = false,
    val isSearching: Boolean = false,
    val isEditing: Boolean = false,
    val isDirty: Boolean = false,
    val title: String = "",
    val coverUrl: String = "",
    val status: String = "未看",
    val ratingMode: String = RATING_MODE_SCORE,
    val rating: String = "",
    val ratingGrade: String = "",
    val funRatingTier: String = "",
    val review: String = "",
    val seriesId: Long? = null,
    val createdAt: String? = null,
    val airDate: String = "",
    val studio: String = "",
    val watchStartDate: String = "",
    val watchFinishDate: String = "",
    val watchedEpisodes: String = "0",
    val totalEpisodes: String = "0",
    val tvEpisodes: String = "0",
    val spEpisodes: String = "0",
    val subjectType: String = "anime",
    val reminderDay: String = "",
    val reminderTime: String = "",
    val externalSource: String? = null,
    val externalId: String? = null,
    val externalUrl: String? = null,
    val originalTitle: String = "",
    val synopsis: String = "",
    val mediaFormat: String = "",
    val selectedTagIds: Set<Long> = emptySet(),
    val statuses: List<WatchStatusEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
    val libraryAnimes: List<AnimeEntity> = emptyList(),
    val dismissedSeriesSuggestionKey: String? = null,
    val remoteResults: List<RemoteAnime> = emptyList(),
    val remoteWarnings: List<String> = emptyList(),
    val duplicateCandidates: List<AnimeEntity> = emptyList(),
    val trashedDuplicate: AnimeEntity? = null,
    val editorSettings: AnimeEditorSettings = AnimeEditorSettings(),
    val error: String? = null,
)

data class AnimeEditorSaveResult(
    val animeId: Long,
    val warning: String? = null,
)

class AnimeEditorViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val discoveryRepository = (application as AniMeowApplication).discoveryRepository
    private val coverImageStore = CoverImageStore(application)
    private val editorPreferences = AnimeEditorPreferences(application)
    private val trackerPreferences = TrackerPreferences(application)
    private val animeId = savedStateHandle.get<Long>("animeId")?.takeIf { it > 0 }
    private val initialSubjectType = normalizeSubjectType(savedStateHandle.get<String>("subjectType"))
    private var loadedAnime: AnimeEntity? = null
    private var saveInFlight = false
    private var seriesSuggestionInFlight = false
    private val _state = MutableStateFlow(
        AnimeEditorState(
            isEditing = animeId != null,
            subjectType = initialSubjectType,
        ),
    )
    val state: StateFlow<AnimeEditorState> = _state.asStateFlow()
    private val _saved = MutableSharedFlow<AnimeEditorSaveResult>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    init {
        viewModelScope.launch {
            editorPreferences.settings.collect { settings ->
                _state.update { it.copy(editorSettings = settings) }
            }
        }
        viewModelScope.launch {
            combine(
                repository.observeWatchStatuses(),
                repository.observeTags(),
                repository.observeSeries(),
                repository.observeAnimes(),
            ) { statuses, tags, series, animes ->
                EditorReferenceData(statuses, tags, series, animes)
            }.collect { references ->
                    _state.update {
                        it.copy(
                            statuses = references.statuses,
                            tags = references.tags,
                            series = references.series,
                            libraryAnimes = references.animes,
                            status = if (it.status.isBlank() && references.statuses.isNotEmpty()) {
                                references.statuses.first().name
                            } else {
                                it.status
                            },
                        )
                    }
                }
        }
        if (animeId == null) {
            _state.update { it.copy(isLoading = false) }
        } else {
            viewModelScope.launch {
                repository.observeAnime(animeId).collect { anime ->
                    if (anime != null) {
                        loadedAnime = anime
                        _state.update { current -> current.fromAnime(anime) }
                    }
                    _state.update { it.copy(isLoading = false) }
                }
            }
            viewModelScope.launch {
                _state.update {
                    it.copy(selectedTagIds = repository.getTagIdsForAnime(animeId).toSet())
                }
            }
        }
    }

    fun updateTitle(value: String) = update {
        it.copy(title = value, error = null, dismissedSeriesSuggestionKey = null)
    }
    fun updateCoverUrl(value: String) = update { it.copy(coverUrl = value) }
    fun updateStatus(value: String) = update { it.copy(status = value) }
    fun updateRatingMode(value: String) = update {
        it.copy(ratingMode = value.takeIf { mode -> mode in RATING_MODES } ?: RATING_MODE_SCORE)
    }
    fun updateRating(value: String) = update { it.copy(rating = sanitizeAnimeRatingInput(value)) }
    fun updateRatingGrade(value: String) = update {
        it.copy(ratingGrade = normalizeAnimeRatingGrade(value).orEmpty())
    }
    fun updateFunRatingTier(value: String) = update { it.copy(funRatingTier = value) }
    fun updateReview(value: String) = update { it.copy(review = value) }
    fun updateSeries(value: Long?) = update { it.copy(seriesId = value) }
    fun updateAirDate(value: String) = update { it.copy(airDate = value) }
    fun updateStudio(value: String) = update { it.copy(studio = value) }
    fun updateWatchStartDate(value: String) = update { it.copy(watchStartDate = value) }
    fun updateWatchFinishDate(value: String) = update { it.copy(watchFinishDate = value) }
    fun updateWatchedEpisodes(value: String) = update { it.copy(watchedEpisodes = value.filter(Char::isDigit)) }
    fun updateTotalEpisodes(value: String) = update { it.copy(totalEpisodes = value.filter(Char::isDigit)) }
    fun updateTvEpisodes(value: String) = update { it.copy(tvEpisodes = value.filter(Char::isDigit)) }
    fun updateSpEpisodes(value: String) = update { it.copy(spEpisodes = value.filter(Char::isDigit)) }
    fun updateSubjectType(value: String) = update { it.copy(subjectType = normalizeSubjectType(value)) }
    fun updateReminderDay(value: String) = update { it.copy(reminderDay = value.filter(Char::isDigit)) }
    fun updateReminderTime(value: String) = update { it.copy(reminderTime = value) }
    fun updateOriginalTitle(value: String) = update { it.copy(originalTitle = value) }
    fun updateSynopsis(value: String) = update { it.copy(synopsis = value) }

    fun importCover(uri: Uri, focusX: Float, focusY: Float, zoom: Float) {
        viewModelScope.launch {
            _state.update { it.copy(isCoverProcessing = true, error = null) }
            try {
                val coverUrl = coverImageStore.importCroppedCover(
                    source = uri,
                    focusX = focusX,
                    focusY = focusY,
                    zoom = zoom,
                    previousUrl = state.value.coverUrl,
                )
                _state.update { it.copy(coverUrl = coverUrl, isCoverProcessing = false, isDirty = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        isCoverProcessing = false,
                        error = error.message ?: "封面处理失败",
                    )
                }
            }
        }
    }

    fun toggleTag(tagId: Long) = update {
        it.copy(
            selectedTagIds = if (tagId in it.selectedTagIds) {
                it.selectedTagIds - tagId
            } else {
                it.selectedTagIds + tagId
            },
        )
    }

    fun createAndSelectTag(name: String, color: Long = 0xFF8A4FD0) {
        val normalized = simplifyTagName(name)
        if (normalized.isEmpty()) return
        state.value.tags.firstOrNull { normalizeTagKey(it.name) == normalizeTagKey(normalized) }?.let { existing ->
            update { it.copy(selectedTagIds = it.selectedTagIds + existing.id) }
            return
        }
        viewModelScope.launch {
            runCatchingCancellable { repository.saveTag(TagEntity(name = normalized, color = color)) }
                .onSuccess { tagId ->
                    _state.update { it.copy(selectedTagIds = it.selectedTagIds + tagId, isDirty = true) }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "创建标签失败") }
                }
        }
    }

    fun searchRemote() {
        val query = state.value.title.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(error = "请先输入标题再搜索资料") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSearching = true, error = null, remoteResults = emptyList()) }
            runCatchingCancellable {
                discoveryRepository.search(
                    query = query,
                    source = DiscoverySourceFilter.ALL,
                    subjectType = state.value.subjectType,
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        isSearching = false,
                        remoteResults = result.items,
                        remoteWarnings = result.warnings,
                        error = if (result.items.isEmpty()) "没有找到匹配资料" else null,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isSearching = false, error = error.message ?: "联网搜索失败") }
            }
        }
    }

    fun searchByImage(uri: Uri) {
        if (state.value.subjectType != "anime") {
            _state.update { it.copy(error = "以图搜番目前仅支持动画截图") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSearching = true, error = null, remoteResults = emptyList()) }
            runCatchingCancellable { discoveryRepository.searchByImage(uri) }
                .onSuccess { matches ->
                    _state.update {
                        it.copy(
                            isSearching = false,
                            remoteResults = matches.map { match -> match.anime }.distinctBy(RemoteAnime::uniqueKey),
                            remoteWarnings = emptyList(),
                            error = if (matches.isEmpty()) "没有识别到匹配作品，请换一张更清晰的截图" else null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isSearching = false, error = error.message ?: "识图失败") }
                }
        }
    }

    fun applyRemoteAnime(remote: RemoteAnime, overwrite: Boolean) {
        val current = state.value
        val normalizedScore = remote.score?.let { score ->
            val stored = (if (score <= 10.0) score * 10.0 else score).roundToInt().coerceIn(0, 100)
            formatAnimeRating(stored)
        }.orEmpty()
        val shouldApplyRemoteRating = normalizedScore.isNotBlank() && (
            overwrite || (current.rating.isBlank() && current.ratingGrade.isBlank())
        )
        val matchingTagIds = current.tags.filter { tag ->
            remote.tags.any { normalizeTagKey(it) == normalizeTagKey(tag.name) }
        }.mapTo(mutableSetOf(), TagEntity::id)
        val remoteSubjectType = normalizeSubjectType(remote.subjectType)
        val next = current.copy(
            title = mergeEditorValue(current.title, remote.title, overwrite),
            coverUrl = mergeEditorValue(current.coverUrl, remote.coverUrl.orEmpty(), overwrite),
            ratingMode = if (shouldApplyRemoteRating) RATING_MODE_SCORE else current.ratingMode,
            rating = if (shouldApplyRemoteRating) normalizedScore else current.rating,
            ratingGrade = if (shouldApplyRemoteRating && overwrite) "" else current.ratingGrade,
            totalEpisodes = mergeEditorValue(current.totalEpisodes.takeUnless { it == "0" }.orEmpty(), remote.episodes?.toString().orEmpty(), overwrite).ifBlank { "0" },
            airDate = mergeEditorValue(current.airDate, remote.airDate.orEmpty(), overwrite),
            studio = mergeEditorValue(current.studio, remote.studio.orEmpty(), overwrite),
            subjectType = if (overwrite || current.subjectType.isBlank()) {
                remoteSubjectType
            } else {
                normalizeSubjectType(current.subjectType)
            },
            externalSource = remote.importSource,
            externalId = remote.importId,
            externalUrl = remote.siteUrl,
            originalTitle = mergeEditorValue(current.originalTitle, remote.originalTitle.orEmpty(), overwrite),
            synopsis = mergeEditorValue(current.synopsis, remote.summary.orEmpty(), overwrite),
            mediaFormat = mergeEditorValue(current.mediaFormat, remote.format.orEmpty(), overwrite),
            selectedTagIds = current.selectedTagIds + matchingTagIds,
            remoteResults = emptyList(),
            remoteWarnings = emptyList(),
        )
        _state.value = next.copy(isDirty = true)
        val remoteCover = remote.coverUrl?.trim().orEmpty()
        val coverWasApplied = remoteCover.isNotBlank() && next.coverUrl.trim() == remoteCover &&
            (overwrite || current.coverUrl.isBlank())
        if (coverWasApplied) {
            viewModelScope.launch {
                if (trackerPreferences.snapshot().autoSyncNetworkCovers) {
                    repository.syncCoverUrlToServer(next.title, remoteCover)
                }
            }
        }
    }

    fun dismissRemoteResults() {
        _state.update { it.copy(remoteResults = emptyList(), remoteWarnings = emptyList()) }
    }

    fun dismissSeriesSuggestion() = update {
        it.copy(dismissedSeriesSuggestionKey = seriesGroupingKey(it.title))
    }

    fun seriesSuggestion(): SeriesSuggestion? = buildSeriesSuggestion(state.value, animeId)

    fun acceptSeriesSuggestion() {
        val current = state.value
        val suggestion = buildSeriesSuggestion(current, animeId) ?: return
        when (suggestion) {
            is SeriesSuggestion.Existing -> update {
                it.copy(seriesId = suggestion.series.id, dismissedSeriesSuggestionKey = null)
            }
            is SeriesSuggestion.Create -> {
                if (seriesSuggestionInFlight || saveInFlight) return
                seriesSuggestionInFlight = true
                _state.update { it.copy(error = null) }
                viewModelScope.launch {
                    try {
                        val seriesId = repository.createSeriesAndAssignAnimes(
                            name = suggestion.name,
                            animeIds = suggestion.relatedAnimeIds.toSet(),
                        )
                        _state.update {
                            it.copy(seriesId = seriesId, dismissedSeriesSuggestionKey = null, isDirty = true)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        _state.update { it.copy(error = error.message ?: "系列归纳失败") }
                    } finally {
                        seriesSuggestionInFlight = false
                    }
                }
            }
        }
    }

    fun createSeries(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            _state.update { it.copy(error = "系列名称不能为空") }
            return
        }
        state.value.series.firstOrNull { it.name.equals(normalized, ignoreCase = true) }?.let { existing ->
            update { it.copy(seriesId = existing.id, dismissedSeriesSuggestionKey = null) }
            return
        }
        if (saveInFlight || seriesSuggestionInFlight) return
        viewModelScope.launch {
            runCatchingCancellable { repository.createSeriesAndAssignAnimes(normalized, emptySet()) }
                .onSuccess { seriesId ->
                    _state.update {
                        it.copy(seriesId = seriesId, dismissedSeriesSuggestionKey = null, error = null, isDirty = true)
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "创建系列失败") }
                }
        }
    }

    fun save(forceDuplicate: Boolean = false) {
        if (saveInFlight || seriesSuggestionInFlight) return
        val current = state.value
        val title = current.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(error = "请先填写番剧标题") }
            return
        }
        if ((current.reminderDay.isBlank()) != (current.reminderTime.isBlank())) {
            _state.update { it.copy(error = "提醒星期与时间需要同时填写，或同时留空") }
            return
        }
        if (!forceDuplicate && animeId == null) {
            val duplicates = current.libraryAnimes.filter {
                it.title.trim().equals(title, ignoreCase = true)
            }
            if (duplicates.isNotEmpty()) {
                _state.update { it.copy(duplicateCandidates = duplicates) }
                return
            }
            val extSource = current.externalSource
            val extId = current.externalId
            if (!extSource.isNullOrBlank() && !extId.isNullOrBlank()) {
                val extDuplicates = current.libraryAnimes.filter {
                    it.externalSource == extSource && it.externalId == extId
                }
                if (extDuplicates.isNotEmpty()) {
                    _state.update { it.copy(duplicateCandidates = extDuplicates) }
                    return
                }
            }
        }
        if (!forceDuplicate && animeId == null) {
            val extSource = current.externalSource
            val extId = current.externalId
            if (!extSource.isNullOrBlank() && !extId.isNullOrBlank()) {
                viewModelScope.launch {
                    val trashed = repository.findTrashedByExternalId(extSource, extId)
                    if (trashed != null) {
                        _state.update { it.copy(trashedDuplicate = trashed) }
                    } else {
                        saveInternal(current, clearExternalId = forceDuplicate && animeId == null)
                    }
                }
                return
            }
        }
        saveInternal(current, clearExternalId = forceDuplicate && animeId == null)
    }

    fun dismissDuplicateWarning() {
        _state.update { it.copy(duplicateCandidates = emptyList()) }
    }

    fun dismissTrashedDuplicate() {
        _state.update { it.copy(trashedDuplicate = null) }
    }

    fun restoreTrashedDuplicate() {
        val trashed = state.value.trashedDuplicate ?: return
        viewModelScope.launch {
            repository.restoreAnime(trashed.id)
            _state.update { it.copy(trashedDuplicate = null) }
            _saved.emit(AnimeEditorSaveResult(trashed.id, null))
        }
    }

    fun saveAsStandaloneDuplicate() {
        _state.update { it.copy(duplicateCandidates = emptyList()) }
        save(forceDuplicate = true)
    }

    fun groupDuplicatesAndSave() {
        if (saveInFlight || seriesSuggestionInFlight) return
        val current = state.value
        val duplicates = current.duplicateCandidates
        if (duplicates.isEmpty()) return
        val existingSeriesId = duplicates.firstNotNullOfOrNull(AnimeEntity::seriesId)
        launchSave(current, clearExternalId = animeId == null) { draft ->
            val result = repository.saveAnimeAndGroupDuplicates(
                anime = draft,
                tagIds = current.selectedTagIds,
                duplicateAnimeIds = duplicates.mapTo(mutableSetOf(), AnimeEntity::id),
                existingSeriesId = existingSeriesId,
                seriesName = seriesBaseTitle(current.title),
            )
            draft.copy(id = result.animeId, seriesId = result.seriesId)
        }
    }

    fun selectEditorPreset(preset: AnimeEditorPreset) {
        val next = animeEditorPresetSettings(preset).copy(
            exitBehavior = state.value.editorSettings.exitBehavior,
        )
        saveEditorSettings(next)
    }

    fun setEditorDensity(density: AnimeEditorDensity) {
        saveEditorSettings(state.value.editorSettings.copy(preset = AnimeEditorPreset.CUSTOM, density = density))
    }

    fun setExitBehavior(behavior: EditorExitBehavior) {
        saveEditorSettings(state.value.editorSettings.copy(exitBehavior = behavior))
    }

    fun toggleEditorModule(module: AnimeEditorModule) {
        if (module == AnimeEditorModule.BASIC) return
        val current = state.value.editorSettings
        val hidden = if (module in current.hiddenModules) current.hiddenModules - module else current.hiddenModules + module
        saveEditorSettings(current.copy(preset = AnimeEditorPreset.CUSTOM, hiddenModules = hidden))
    }

    fun moveEditorModule(module: AnimeEditorModule, offset: Int) {
        val current = state.value.editorSettings
        val from = current.moduleOrder.indexOf(module)
        val to = (from + offset).coerceIn(0, current.moduleOrder.lastIndex)
        if (from < 0 || from == to) return
        val order = current.moduleOrder.toMutableList().apply { add(to, removeAt(from)) }
        saveEditorSettings(current.copy(preset = AnimeEditorPreset.CUSTOM, moduleOrder = order))
    }

    private fun saveInternal(current: AnimeEditorState, clearExternalId: Boolean = false) {
        launchSave(current, clearExternalId) { draft ->
            val id = repository.saveAnime(
                anime = draft,
                tagIds = current.selectedTagIds,
            )
            draft.copy(id = id)
        }
    }

    private fun launchSave(
        current: AnimeEditorState,
        clearExternalId: Boolean = false,
        persist: suspend (AnimeEntity) -> AnimeEntity,
    ) {
        if (saveInFlight) return
        saveInFlight = true
        val rawDraft = current.toAnime(animeId, loadedAnime)
        val draft = if (clearExternalId) {
            rawDraft.copy(externalSource = null, externalId = null, externalUrl = null)
        } else {
            rawDraft
        }
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val savedAnime = persist(draft)
                val app = getApplication<AniMeowApplication>()
                val reminderWarning = try {
                    app.reminderScheduler.schedule(savedAnime)
                    null
                } catch (error: Exception) {
                    app.operationLog.recordError(error, "作品编辑 · 提醒调度")
                    "作品已保存，但提醒任务未能更新。请到提醒管理页点击“重新同步”。"
                }
                _state.update {
                    it.copy(
                        isSaving = false,
                        isDirty = false,
                        seriesId = savedAnime.seriesId,
                        duplicateCandidates = emptyList(),
                    )
                }
                _saved.emit(AnimeEditorSaveResult(savedAnime.id, reminderWarning))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val msg = error.message.orEmpty()
                val friendly = when {
                    msg.contains("UNIQUE constraint", ignoreCase = true) ||
                        msg.contains("SQLITE_CONSTRAINT", ignoreCase = true) ->
                        "该作品可能已存在于资料库中（来源 ID 重复），请检查后重试"
                    msg.contains("NOT NULL", ignoreCase = true) ->
                        "缺少必填字段，请检查后重试"
                    else -> error.message ?: "保存失败"
                }
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = friendly,
                    )
                }
            } finally {
                saveInFlight = false
                _state.update { if (it.isSaving) it.copy(isSaving = false) else it }
            }
        }
    }

    private fun update(transform: (AnimeEditorState) -> AnimeEditorState) {
        _state.update { transform(it).copy(isDirty = true) }
    }

    private fun saveEditorSettings(value: AnimeEditorSettings) {
        val normalized = value.normalized()
        _state.update { it.copy(editorSettings = normalized) }
        viewModelScope.launch { editorPreferences.save(normalized) }
    }
}

private data class EditorReferenceData(
    val statuses: List<WatchStatusEntity>,
    val tags: List<TagEntity>,
    val series: List<SeriesEntity>,
    val animes: List<AnimeEntity>,
)

sealed interface SeriesSuggestion {
    val label: String

    data class Existing(val series: SeriesEntity) : SeriesSuggestion {
        override val label: String = "加入系列「${series.name}」"
    }

    data class Create(
        val name: String,
        val relatedAnimeIds: List<Long>,
    ) : SeriesSuggestion {
        override val label: String = "创建系列「$name」并归纳 ${relatedAnimeIds.size + 1} 部作品"
    }
}

internal fun buildSeriesSuggestion(
    state: AnimeEditorState,
    editingAnimeId: Long? = null,
): SeriesSuggestion? {
    if (state.title.isBlank() || state.seriesId != null) return null
    val key = seriesGroupingKey(state.title)
    if (key.isBlank() || state.dismissedSeriesSuggestionKey == key) return null

    state.series.firstOrNull { seriesGroupingKey(it.name) == key }?.let {
        return SeriesSuggestion.Existing(it)
    }
    val related = state.libraryAnimes.filter { anime ->
        anime.id != editingAnimeId && anime.seriesId == null && seriesGroupingKey(anime.title) == key
    }
    if (related.isEmpty()) return null
    return SeriesSuggestion.Create(
        name = seriesBaseTitle(state.title),
        relatedAnimeIds = related.map(AnimeEntity::id),
    )
}

internal fun seriesBaseTitle(title: String): String = title
    .trim()
    .replace(Regex("(?i)\\s*(?:第\\s*[一二三四五六七八九十百0-9]+\\s*季|season\\s*[0-9ivx]+|[0-9]+(?:st|nd|rd|th)\\s+season)\\s*$"), "")
    .replace(Regex("(?i)\\s+[ivx]{1,4}\\s*$"), "")
    .trim()
    .ifBlank { title.trim() }

internal fun seriesGroupingKey(title: String): String = seriesBaseTitle(title)
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("[\\s　·:：—_\\-]+"), "")

private fun AnimeEditorState.fromAnime(anime: AnimeEntity): AnimeEditorState = copy(
    isLoading = false,
    isDirty = false,
    title = anime.title,
    coverUrl = anime.coverUrl.orEmpty(),
    status = anime.status,
    ratingMode = if (normalizeAnimeRatingGrade(anime.ratingGrade) != null) RATING_MODE_GRADE else RATING_MODE_SCORE,
    rating = formatAnimeRating(anime.rating),
    ratingGrade = normalizeAnimeRatingGrade(anime.ratingGrade).orEmpty(),
    funRatingTier = anime.funRatingTier.orEmpty(),
    review = anime.review.orEmpty(),
    seriesId = anime.seriesId,
    createdAt = anime.createdAt,
    airDate = anime.airDate.orEmpty(),
    studio = anime.studio.orEmpty(),
    watchStartDate = anime.watchStartDate.orEmpty(),
    watchFinishDate = anime.watchFinishDate.orEmpty(),
    watchedEpisodes = anime.watchedEpisodes.toString(),
    totalEpisodes = anime.totalEpisodes.toString(),
    tvEpisodes = anime.tvEpisodes.toString(),
    spEpisodes = anime.spEpisodes.toString(),
    subjectType = normalizeSubjectType(anime.subjectType),
    reminderDay = anime.reminderDay?.toString().orEmpty(),
    reminderTime = anime.reminderTime.orEmpty(),
    externalSource = anime.externalSource,
    externalId = anime.externalId,
    externalUrl = anime.externalUrl,
    originalTitle = anime.originalTitle.orEmpty(),
    synopsis = anime.synopsis.orEmpty(),
    mediaFormat = anime.mediaFormat.orEmpty(),
)

private fun AnimeEditorState.toAnime(existingId: Long?, original: AnimeEntity?): AnimeEntity = AnimeEntity(
    id = existingId ?: 0,
    title = title.trim(),
    coverUrl = coverUrl.trim().takeIf(String::isNotBlank),
    status = status.ifBlank { "未看" },
    rating = if (ratingMode == RATING_MODE_SCORE) parseAnimeRatingInput(rating) else null,
    ratingGrade = if (ratingMode == RATING_MODE_GRADE) normalizeAnimeRatingGrade(ratingGrade) else null,
    funRatingTier = funRatingTier.trim().takeIf(String::isNotBlank),
    review = review.trim().takeIf(String::isNotBlank),
    seriesId = seriesId,
    createdAt = createdAt ?: java.time.Instant.now().toString(),
    airDate = airDate.trim().takeIf(String::isNotBlank),
    studio = studio.trim().takeIf(String::isNotBlank),
    watchStartDate = watchStartDate.trim().takeIf(String::isNotBlank),
    watchFinishDate = watchFinishDate.trim().takeIf(String::isNotBlank),
    watchedEpisodes = watchedEpisodes.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    totalEpisodes = totalEpisodes.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    tvEpisodes = tvEpisodes.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    spEpisodes = spEpisodes.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    subjectType = normalizeSubjectType(subjectType),
    reminderDay = reminderDay.toIntOrNull()?.coerceIn(1, 7),
    reminderTime = reminderTime.trim().takeIf(String::isNotBlank),
    externalSource = externalSource ?: original?.externalSource,
    externalId = externalId ?: original?.externalId,
    externalUrl = externalUrl ?: original?.externalUrl,
    originalTitle = originalTitle.trim().takeIf(String::isNotBlank) ?: original?.originalTitle,
    synopsis = synopsis.trim().takeIf(String::isNotBlank) ?: original?.synopsis,
    mediaFormat = mediaFormat.trim().takeIf(String::isNotBlank) ?: original?.mediaFormat,
    broadcastDay = original?.broadcastDay,
    broadcastTime = original?.broadcastTime,
).withNormalizedSubjectType()

private const val RATING_MODE_SCORE = "score"
private const val RATING_MODE_GRADE = "grade"
private val RATING_MODES = setOf(RATING_MODE_SCORE, RATING_MODE_GRADE)

private fun mergeEditorValue(current: String, incoming: String, overwrite: Boolean): String =
    if (overwrite || current.isBlank()) incoming.trim() else current
