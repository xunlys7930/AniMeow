package com.animeow.app.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.RemoteImportResult
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.CharacterGroupSummary
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.preferences.GlobalSearchPreferences
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GlobalSearchUiState(
    val query: String = "",
    val recentQueries: List<String> = emptyList(),
    val animes: List<AnimeEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val characters: List<CharacterListItem> = emptyList(),
    val groups: List<CharacterGroupSummary> = emptyList(),
    val remoteItems: List<RemoteAnime> = emptyList(),
    val existingExternalKeys: Set<String> = emptySet(),
    val remoteLoading: Boolean = false,
    val remoteWarnings: List<String> = emptyList(),
    val remoteError: String? = null,
    val importingRemoteKey: String? = null,
    val seriesById: Map<Long, SeriesEntity> = emptyMap(),
    val membersBySeriesId: Map<Long, List<AnimeEntity>> = emptyMap(),
) {
    val hasLocalResults: Boolean
        get() = animes.isNotEmpty() || series.isNotEmpty() || tags.isNotEmpty() ||
            characters.isNotEmpty() || groups.isNotEmpty()

    fun isAlreadyLocal(remote: RemoteAnime): Boolean =
        "${remote.importSource}:${remote.importId}" in existingExternalKeys
}

sealed interface GlobalSearchEvent {
    data class OpenAnime(val animeId: Long) : GlobalSearchEvent
    data class Message(val value: String) : GlobalSearchEvent
}

private data class LibrarySearchData(
    val animes: List<AnimeEntity>,
    val series: List<SeriesEntity>,
    val tags: List<TagEntity>,
)

private data class CharacterSearchData(
    val characters: List<CharacterListItem>,
    val groups: List<CharacterGroupSummary>,
)

private data class RemoteSearchData(
    val query: String = "",
    val loading: Boolean = false,
    val items: List<RemoteAnime> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val importingKey: String? = null,
)

class GlobalSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val library = app.libraryRepository
    private val characters = app.characterRepository
    private val discovery = app.discoveryRepository
    private val preferences = GlobalSearchPreferences(application)
    private val query = MutableStateFlow("")
    private val remote = MutableStateFlow(RemoteSearchData())
    private val eventsFlow = MutableSharedFlow<GlobalSearchEvent>(extraBufferCapacity = 4)
    val events = eventsFlow.asSharedFlow()
    private var searchJob: Job? = null

    private val libraryData = combine(
        library.observeAnimes(),
        library.observeSeries(),
        library.observeTags(),
    ) { animes, series, tags -> LibrarySearchData(animes, series, tags) }

    private val characterData = combine(
        characters.observeCharacters(),
        characters.observeGroups(),
    ) { characterItems, groups -> CharacterSearchData(characterItems, groups) }

    val state: StateFlow<GlobalSearchUiState> = combine(
        query,
        preferences.recentQueries,
        libraryData,
        characterData,
        remote,
    ) { rawQuery, recent, libraryData, characterData, remoteData ->
        val clean = rawQuery.trim()
        val normalized = normalizeSearchText(clean)
        val hasQuery = normalized.isNotEmpty()
        val seriesById = libraryData.series.associateBy { it.id }
        val membersBySeriesId = libraryData.animes.filter { it.seriesId != null }.groupBy { it.seriesId!! }
        GlobalSearchUiState(
            query = rawQuery,
            recentQueries = recent,
            animes = if (hasQuery) libraryData.animes.filter { anime ->
                matches(normalized, anime.title, anime.originalTitle, anime.studio, anime.synopsis, anime.status)
            }.take(12) else emptyList(),
            series = if (hasQuery) libraryData.series.filter { series ->
                matches(normalized, series.name, series.description)
            }.take(8) else emptyList(),
            tags = if (hasQuery) libraryData.tags.filter { tag -> matches(normalized, tag.name) }.take(12) else emptyList(),
            characters = if (hasQuery) characterData.characters.filter { item ->
                matches(
                    normalized,
                    item.character.name,
                    item.character.nameCn,
                    item.character.summary,
                    item.character.review,
                )
            }.take(10) else emptyList(),
            groups = if (hasQuery) characterData.groups.filter { item ->
                matches(normalized, item.group.name, item.group.description, item.group.shareCode)
            }.take(8) else emptyList(),
            remoteItems = remoteData.items,
            existingExternalKeys = libraryData.animes.mapNotNull { anime ->
                val source = anime.externalSource?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val id = anime.externalId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                "$source:$id"
            }.toSet(),
            remoteLoading = remoteData.loading,
            remoteWarnings = remoteData.warnings,
            remoteError = remoteData.error,
            importingRemoteKey = remoteData.importingKey,
            seriesById = seriesById,
            membersBySeriesId = membersBySeriesId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GlobalSearchUiState(),
    )

    fun setQuery(value: String) {
        val limited = value.take(80)
        query.value = limited
        searchJob?.cancel()
        val clean = limited.trim()
        if (clean.length < 2) {
            remote.value = RemoteSearchData()
            return
        }
        remote.value = RemoteSearchData(query = clean, loading = true)
        searchJob = viewModelScope.launch {
            delay(450)
            runCatchingCancellable {
                discovery.search(clean, DiscoverySourceFilter.ALL).let { result ->
                    result.copy(items = result.items.take(12))
                }
            }.onSuccess { result ->
                if (query.value.trim() == clean) {
                    remote.value = RemoteSearchData(
                        query = clean,
                        items = result.items,
                        warnings = result.warnings,
                    )
                }
            }.onFailure { error ->
                if (query.value.trim() == clean) {
                    remote.value = RemoteSearchData(query = clean, error = error.message ?: "在线搜索失败")
                }
            }
        }
    }

    fun useRecentQuery(value: String) = setQuery(value)

    fun rememberCurrentQuery() {
        val clean = query.value.trim()
        if (clean.length >= 2) viewModelScope.launch { preferences.remember(clean) }
    }

    fun clearRecentQueries() {
        viewModelScope.launch { preferences.clear() }
    }

    fun importRemote(remoteAnime: RemoteAnime) {
        if (remote.value.importingKey != null) return
        rememberCurrentQuery()
        remote.update { it.copy(importingKey = remoteAnime.uniqueKey) }
        viewModelScope.launch {
            runCatchingCancellable { library.importRemoteAnime(remoteAnime) }
                .onSuccess { result ->
                    val animeId = when (result) {
                        is RemoteImportResult.Added -> result.animeId
                        is RemoteImportResult.Duplicate -> result.existing.id
                    }
                    eventsFlow.emit(GlobalSearchEvent.OpenAnime(animeId))
                }
                .onFailure { error ->
                    eventsFlow.emit(GlobalSearchEvent.Message(error.message ?: "加入资料库失败"))
                }
            remote.update { it.copy(importingKey = null) }
        }
    }
}

internal fun normalizeSearchText(value: String): String = value
    .trim()
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("[\\s　·:：—_\\-]+"), "")

internal fun matches(normalizedQuery: String, vararg values: String?): Boolean =
    normalizedQuery.isNotEmpty() && values.any { value ->
        value?.let(::normalizeSearchText)?.contains(normalizedQuery) == true
    }
