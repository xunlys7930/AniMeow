package com.animeow.app.ui.search

import com.animeow.app.data.local.allTitles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.RemoteImportResult
import com.animeow.app.data.audio.AudioWork
import com.animeow.app.data.games.GameEntity
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.CharacterGroupSummary
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.EpisodeSearchTitle
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.episodeUnitLabel
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.preferences.GlobalSearchPreferences
import com.animeow.app.data.remote.DiscoverySourceFilter
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.util.userSafeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GlobalSearchScope(val storageKey: String, val label: String) {
    ALL("all", "全部"),
    SETTINGS("settings", "设置"),
    LOCAL("local", "本地"),
    ONLINE("online", "在线");

    val includesLocal: Boolean get() = this == ALL || this == LOCAL
    val includesSettings: Boolean get() = this == ALL || this == SETTINGS
    val includesOnline: Boolean get() = this == ALL || this == ONLINE

    companion object {
        fun fromStorage(value: String?): GlobalSearchScope = entries.firstOrNull { it.storageKey == value } ?: ALL
    }
}

internal fun shouldSearchOnline(scope: GlobalSearchScope, query: String): Boolean =
    scope.includesOnline && query.trim().length >= 2 && normalizeSearchText(query).isNotEmpty()

internal val RemoteAnime.globalSearchKey: String get() = "${normalizeSubjectType(subjectType)}:$uniqueKey"

private val onlineWorkTypes = mapOf("anime" to "动画", "book" to "书籍")

/** A related work appears once, under the first matching work in its series. */
internal fun searchSeriesSuggestions(
    results: List<AnimeEntity>,
    membersBySeries: Map<Long, List<AnimeEntity>>,
): Map<Long, List<AnimeEntity>> {
    val visibleIds = results.mapTo(mutableSetOf()) { it.id }
    val shownSeries = mutableSetOf<Long>()
    return buildMap {
        results.forEach { anime ->
            anime.seriesId?.takeIf(shownSeries::add)?.let { seriesId ->
                put(anime.id, membersBySeries[seriesId].orEmpty()
                    .filterNot { it.id in visibleIds }.distinctBy { it.id }.take(6))
            }
        }
    }
}

private data class SearchRequest(val query: String, val scope: GlobalSearchScope)

data class GlobalSearchUiState(
    val query: String = "",
    val scope: GlobalSearchScope = GlobalSearchScope.ALL,
    val recentQueries: List<String> = emptyList(),
    val animes: List<AnimeEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val characters: List<CharacterListItem> = emptyList(),
    val groups: List<CharacterGroupSummary> = emptyList(),
    val statuses: List<WatchStatusEntity> = emptyList(),
    val games: List<GameEntity> = emptyList(),
    val audioWorks: List<AudioWork> = emptyList(),
    val episodes: List<EpisodeSearchResult> = emptyList(),
    val remoteItems: List<RemoteAnime> = emptyList(),
    val existingExternalKeys: Set<String> = emptySet(),
    val remoteLoadingTypes: Set<String> = emptySet(),
    val remoteHasMore: Set<String> = emptySet(),
    val remoteWarnings: List<String> = emptyList(),
    val remoteError: String? = null,
    val importingRemoteKey: String? = null,
    val seriesById: Map<Long, SeriesEntity> = emptyMap(),
    val membersBySeriesId: Map<Long, List<AnimeEntity>> = emptyMap(),
) {
    val remoteLoading: Boolean get() = remoteLoadingTypes.isNotEmpty()

    val hasLocalResults: Boolean
        get() = animes.isNotEmpty() || series.isNotEmpty() || tags.isNotEmpty() ||
            characters.isNotEmpty() || groups.isNotEmpty() || statuses.isNotEmpty() ||
            games.isNotEmpty() || audioWorks.isNotEmpty() || episodes.isNotEmpty()

    fun isAlreadyLocal(remote: RemoteAnime): Boolean =
        "${remote.importSource}:${remote.importId}" in existingExternalKeys
}

data class EpisodeSearchResult(val episode: EpisodeSearchTitle, val workTitle: String, val unit: String)

sealed interface GlobalSearchEvent {
    data class OpenAnime(val animeId: Long) : GlobalSearchEvent
    data class Message(val value: String) : GlobalSearchEvent
}

private data class LibrarySearchData(
    val animes: List<AnimeEntity>,
    val series: List<SeriesEntity>,
    val tags: List<TagEntity>,
    val statuses: List<WatchStatusEntity>,
    val media: MediaSearchData,
)

private data class MediaSearchData(
    val games: List<GameEntity>,
    val audioWorks: List<AudioWork>,
    val episodes: List<EpisodeSearchTitle>,
)

private data class CharacterSearchData(
    val characters: List<CharacterListItem>,
    val groups: List<CharacterGroupSummary>,
)

private data class RemoteSearchData(
    val query: String = "",
    val loadingTypes: Set<String> = emptySet(),
    val nextPages: Map<String, Int> = emptyMap(),
    val items: List<RemoteAnime> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val importingKey: String? = null,
)

class GlobalSearchViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val library = app.libraryRepository
    private val characters = app.characterRepository
    private val discovery = app.discoveryRepository
    private val preferences = GlobalSearchPreferences(application)
    internal val inputQuery = savedStateHandle.getStateFlow("query", "")
    private val scope = savedStateHandle.getStateFlow("scope", GlobalSearchScope.ALL.storageKey)
    private val searchRequest = combine(inputQuery, scope) { text, scopeKey -> SearchRequest(text, GlobalSearchScope.fromStorage(scopeKey)) }
    private val onlineQuery = searchRequest.map { request ->
        request.query.trim().takeIf { shouldSearchOnline(request.scope, it) }
    }.distinctUntilChanged()
    private val retry = MutableStateFlow(0)
    private val moreRequests = MutableSharedFlow<String>(extraBufferCapacity = 2)
    private val remote = MutableStateFlow(RemoteSearchData())
    private val eventsFlow = MutableSharedFlow<GlobalSearchEvent>(extraBufferCapacity = 4)
    val events = eventsFlow.asSharedFlow()

    private val libraryData = combine(
        library.observeAnimes(),
        library.observeSeries(),
        library.observeTags(),
        library.observeWatchStatuses(),
        combine(
            app.database.gameDao().observe(),
            app.database.audioWorkDao().observe(),
            app.database.libraryDao().observeEpisodeSearchTitles(),
            ::MediaSearchData,
        ),
        ::LibrarySearchData,
    )

    private val characterData = combine(
        characters.observeCharacters(),
        characters.observeGroups(),
    ) { characterItems, groups -> CharacterSearchData(characterItems, groups) }

    private val localSearch = combine(searchRequest, libraryData, characterData) { request, libraryData, characterData ->
        val rawQuery = request.query
        val clean = rawQuery.trim()
        val terms = SearchTerms(clean)
        val hasQuery = request.scope.includesLocal && !terms.isEmpty
        val seriesById = libraryData.series.associateBy { it.id }
        val membersBySeriesId = libraryData.animes.filter { it.seriesId != null }.groupBy { it.seriesId!! }
        GlobalSearchUiState(
            query = rawQuery,
            scope = request.scope,
            animes = if (hasQuery) libraryData.animes.rankedSearch { anime ->
                terms.rank(anime.title, *anime.allTitles().toTypedArray(), anime.studio, anime.synopsis,
                    anime.status, "未看".takeIf { anime.status == "想看" }, anime.voiceActors,
                    anime.review, anime.memo, anime.mediaFormat, anime.externalId)
            } else emptyList(),
            series = if (hasQuery) libraryData.series.rankedSearch { series ->
                terms.rank(series.name, series.description)
            } else emptyList(),
            tags = if (hasQuery) libraryData.tags.rankedSearch { terms.rank(it.name) } else emptyList(),
            characters = if (hasQuery) characterData.characters.rankedSearch { item ->
                terms.rank(
                    item.character.name,
                    item.character.nameCn,
                    item.character.summary,
                    item.character.review,
                )
            } else emptyList(),
            groups = if (hasQuery) characterData.groups.rankedSearch { item ->
                terms.rank(item.group.name, item.group.description, item.group.shareCode)
            } else emptyList(),
            statuses = if (hasQuery) libraryData.statuses.rankedSearch { status ->
                terms.rank(status.name, "未看".takeIf { status.name == "想看" })
            } else emptyList(),
            games = if (hasQuery) libraryData.media.games.rankedSearch { game ->
                terms.rank(game.title, game.originalTitle, game.developer, game.publisher, game.tags,
                    game.platforms, game.genre, game.status, game.review, game.vndbId, game.bangumiId?.toString())
            } else emptyList(),
            audioWorks = if (hasQuery) libraryData.media.audioWorks.rankedSearch { work ->
                terms.rank(work.title, work.originalTitle, work.workCode, work.voiceActors, work.circle,
                    work.category, work.tags, work.status, work.review)
            } else emptyList(),
            episodes = if (hasQuery) {
                val worksById = libraryData.animes.associateBy(AnimeEntity::id)
                libraryData.media.episodes.rankedSearch { terms.rank(it.title) }.mapNotNull { episode ->
                    worksById[episode.animeId]?.let { work ->
                        EpisodeSearchResult(episode, work.title, episodeUnitLabel(work.subjectType))
                    }
                }
            } else emptyList(),
            existingExternalKeys = libraryData.animes.mapNotNull { anime ->
                val source = anime.externalSource?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val id = anime.externalId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                "$source:$id"
            }.toSet(),
            seriesById = seriesById,
            membersBySeriesId = membersBySeriesId,
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<GlobalSearchUiState> = combine(localSearch, preferences.recentQueries, remote) { local, recent, remoteData ->
        val clean = local.query.trim()
        val visibleRemote = if (local.scope.includesOnline && remoteData.query == clean) remoteData else RemoteSearchData()
        local.copy(
            recentQueries = recent,
            remoteItems = visibleRemote.items,
            remoteLoadingTypes = visibleRemote.loadingTypes,
            remoteHasMore = visibleRemote.nextPages.keys,
            remoteWarnings = visibleRemote.warnings,
            remoteError = visibleRemote.errors.values.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            importingRemoteKey = remoteData.importingKey,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GlobalSearchUiState(query = inputQuery.value, scope = GlobalSearchScope.fromStorage(scope.value)),
    )

    init {
        viewModelScope.launch {
            combine(onlineQuery, retry) { text, _ -> text }.collectLatest { clean ->
                if (clean == null) {
                    updateRemote(RemoteSearchData())
                    return@collectLatest
                }
                updateRemote(RemoteSearchData(query = clean, loadingTypes = onlineWorkTypes.keys))
                delay(450)
                coroutineScope {
                    onlineWorkTypes.keys.forEach { type -> launch { fetchOnlinePage(clean, type, 1) } }
                    moreRequests.collect { type ->
                        val current = remote.value
                        val page = current.nextPages[type] ?: return@collect
                        if (type in current.loadingTypes) return@collect
                        remote.update { it.copy(loadingTypes = it.loadingTypes + type, errors = it.errors - type) }
                        launch { fetchOnlinePage(clean, type, page) }
                    }
                }
            }
        }
    }

    private suspend fun fetchOnlinePage(query: String, type: String, pageNumber: Int) {
        val result = runCatchingCancellable { discovery.search(query, DiscoverySourceFilter.ALL, subjectType = type, page = pageNumber) }
        val page = result.getOrNull()
        val label = onlineWorkTypes.getValue(type)
        val error = result.exceptionOrNull()?.let { "$label：${it.userSafeMessage("搜索失败")}" }
        remote.update { current ->
            if (current.query != query) return@update current
            val nextPages = when {
                page == null -> current.nextPages
                page.hasMore -> current.nextPages + (type to (pageNumber + 1))
                else -> current.nextPages - type
            }
            current.copy(
                loadingTypes = current.loadingTypes - type,
                nextPages = nextPages,
                items = (current.items + page?.items.orEmpty()).distinctBy(RemoteAnime::globalSearchKey),
                warnings = (current.warnings + page?.warnings.orEmpty().map { "$label：$it" }).distinct(),
                errors = if (error == null) current.errors - type else current.errors + (type to error),
            )
        }
    }

    private fun updateRemote(value: RemoteSearchData) {
        remote.update { value.copy(importingKey = it.importingKey) }
    }

    fun setQuery(value: String) {
        savedStateHandle["query"] = value.take(80)
    }

    fun setScope(value: GlobalSearchScope) {
        savedStateHandle["scope"] = value.storageKey
    }

    fun retryOnlineSearch() {
        retry.update { it + 1 }
    }

    fun loadMoreOnline(type: String) {
        moreRequests.tryEmit(type)
    }

    fun useRecentQuery(value: String) = setQuery(value)

    fun rememberCurrentQuery() {
        val clean = inputQuery.value.trim()
        if (clean.length >= 2) viewModelScope.launch { preferences.remember(clean) }
    }

    fun clearRecentQueries() {
        viewModelScope.launch { preferences.clear() }
    }

    fun importRemote(remoteAnime: RemoteAnime) {
        if (remote.value.importingKey != null) return
        rememberCurrentQuery()
        remote.update { it.copy(importingKey = remoteAnime.globalSearchKey) }
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
