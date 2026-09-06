package com.animeow.app.data.remote

import android.content.Context
import android.net.Uri
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

class DiscoveryRepository internal constructor(
    context: Context,
    private val bangumi: BangumiService = BangumiService(context = context),
    private val aniList: AniListService = AniListService(),
    private val server: ServerCatalogService = ServerCatalogService(),
) {
    private val traceMoe = TraceMoeService(context, aniList)
    private val preferences = DiscoveryPreferences(context)
    private val featuredCache = mutableMapOf<String, Pair<Long, RemoteFetchResult>>()

    suspend fun featured(
        source: DiscoverySourceFilter = DiscoverySourceFilter.ALL,
        subjectType: String = "anime",
        filters: DiscoveryFilters = DiscoveryFilters(),
        forceRefresh: Boolean = false,
    ): RemoteFetchResult {
        val normalizedSubjectType = normalizeSubjectType(subjectType)
        val cacheKey = "featured:$source:$normalizedSubjectType:$filters"
        val now = System.currentTimeMillis()
        featuredCache[cacheKey]?.takeIf { !forceRefresh && now - it.first < CACHE_DURATION_MS }
            ?.let { return it.second }
        val bangumiFeatured: suspend () -> List<RemoteAnime> = {
            val items = if (normalizedSubjectType == "anime" && filters.activeCount == 0) {
                bangumi.calendar()
            } else {
                bangumi.ranking(filters, normalizedSubjectType, 50)
            }
            applyDiscoveryFilters(items, filters, RemoteCatalogSource.BANGUMI)
        }
        val aniListFeatured: suspend () -> List<RemoteAnime> = {
            if (normalizedSubjectType != "anime") {
                emptyList()
            } else {
                val items = if (filters.activeCount == 0) aniList.trending() else aniList.ranking(filters, 50)
                applyDiscoveryFilters(items, filters, RemoteCatalogSource.ANILIST)
            }
        }
        val result = when (source) {
            DiscoverySourceFilter.BANGUMI -> singleSource("Bangumi", bangumiFeatured)
            DiscoverySourceFilter.ANILIST -> singleSource("AniList", aniListFeatured)
            DiscoverySourceFilter.SERVER -> singleSource("资料库服务器") {
                applyDiscoveryFilters(server.search("", filters), filters, RemoteCatalogSource.SERVER)
            }
            DiscoverySourceFilter.ALL -> fetchAll(
                bangumiRequest = bangumiFeatured,
                aniListRequest = aniListFeatured,
                serverRequest = if (preferences.snapshot().let { it.showServerSource && it.includeServerInAllSources }) {
                    suspend {
                        applyDiscoveryFilters(server.search("", filters), filters, RemoteCatalogSource.SERVER)
                    }
                } else null,
            )
        }
        featuredCache[cacheKey] = now to result
        return result
    }

    suspend fun ranking(
        source: DiscoverySourceFilter,
        subjectType: String,
        filters: DiscoveryFilters,
    ): RemoteFetchResult {
        val normalizedSubjectType = normalizeSubjectType(subjectType)
        return when (source) {
            DiscoverySourceFilter.BANGUMI -> singleSource("Bangumi") {
                applyDiscoveryFilters(bangumi.ranking(filters, normalizedSubjectType), filters, RemoteCatalogSource.BANGUMI)
            }
            DiscoverySourceFilter.ANILIST -> singleSource("AniList") {
                if (normalizedSubjectType == "anime") {
                    applyDiscoveryFilters(aniList.ranking(filters), filters, RemoteCatalogSource.ANILIST)
                } else emptyList()
            }
            DiscoverySourceFilter.SERVER -> singleSource("资料库服务器") {
                applyDiscoveryFilters(server.search("", filters), filters, RemoteCatalogSource.SERVER)
            }
            DiscoverySourceFilter.ALL -> fetchAll(
                bangumiRequest = {
                    applyDiscoveryFilters(
                        bangumi.ranking(filters, normalizedSubjectType),
                        filters,
                        RemoteCatalogSource.BANGUMI,
                    )
                },
                aniListRequest = {
                    if (normalizedSubjectType == "anime") {
                        applyDiscoveryFilters(aniList.ranking(filters), filters, RemoteCatalogSource.ANILIST)
                    } else emptyList()
                },
                serverRequest = if (preferences.snapshot().let { it.showServerSource && it.includeServerInAllSources }) {
                    suspend {
                        applyDiscoveryFilters(server.search("", filters), filters, RemoteCatalogSource.SERVER)
                    }
                } else null,
            )
        }
    }

    suspend fun search(
        query: String,
        source: DiscoverySourceFilter,
        subjectType: String = "anime",
        filters: DiscoveryFilters = DiscoveryFilters(),
    ): RemoteFetchResult {
        val normalizedSubjectType = normalizeSubjectType(subjectType)
        return when (source) {
            DiscoverySourceFilter.BANGUMI -> singleSource("Bangumi") {
                applyDiscoveryFilters(
                    bangumi.search(query, normalizedSubjectType, 50),
                    filters,
                    RemoteCatalogSource.BANGUMI,
                )
            }
            DiscoverySourceFilter.ANILIST -> singleSource("AniList") {
                if (normalizedSubjectType == "anime") {
                    applyDiscoveryFilters(aniList.search(query, 50, filters), filters, RemoteCatalogSource.ANILIST)
                } else emptyList()
            }
            DiscoverySourceFilter.SERVER -> singleSource("资料库服务器") {
                applyDiscoveryFilters(server.search(query, filters), filters, RemoteCatalogSource.SERVER)
            }
            DiscoverySourceFilter.ALL -> fetchAll(
                bangumiRequest = {
                    applyDiscoveryFilters(
                        bangumi.search(query, normalizedSubjectType, 50),
                        filters,
                        RemoteCatalogSource.BANGUMI,
                    )
                },
                aniListRequest = {
                    if (normalizedSubjectType == "anime") {
                        applyDiscoveryFilters(aniList.search(query, 50, filters), filters, RemoteCatalogSource.ANILIST)
                    } else emptyList()
                },
                serverRequest = if (preferences.snapshot().let { it.showServerSource && it.includeServerInAllSources }) {
                    suspend {
                        applyDiscoveryFilters(server.search(query, filters), filters, RemoteCatalogSource.SERVER)
                    }
                } else null,
            )
        }
    }

    suspend fun searchByImage(uri: Uri): List<ImageSearchMatch> = traceMoe.search(uri)

    suspend fun broadcastCalendar(): List<RemoteAnime> =
        singleSource("Bangumi") { bangumi.calendar() }.items.filter { it.broadcastDay in 1..7 }

    private suspend fun singleSource(
        name: String,
        request: suspend () -> List<RemoteAnime>,
    ): RemoteFetchResult = try {
        RemoteFetchResult(request())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw IllegalStateException(userFriendlyRemoteFailure(name, error), error)
    }

    private suspend fun fetchAll(
        bangumiRequest: suspend () -> List<RemoteAnime>,
        aniListRequest: suspend () -> List<RemoteAnime>,
        serverRequest: (suspend () -> List<RemoteAnime>)? = null,
    ): RemoteFetchResult = supervisorScope {
        val requests = buildList<Pair<String, suspend () -> List<RemoteAnime>>> {
            add("Bangumi" to bangumiRequest)
            add("AniList" to aniListRequest)
            serverRequest?.let { add("服务器" to it) }
        }
        val results = requests.map { (name, request) -> name to async { runCatchingCancellable { request() } } }
            .map { (name, task) -> name to task.await() }
        if (results.all { it.second.isFailure }) {
            throw IllegalStateException("在线资料来源暂时不可用，请稍后重试")
        }
        RemoteFetchResult(
            items = results.flatMap { it.second.getOrDefault(emptyList()) }
                .distinctBy { "${it.importSource}:${it.importId}" },
            warnings = results.mapNotNull { (name, result) ->
                result.exceptionOrNull()?.let { userFriendlyRemoteFailure(name, it) }
            },
        )
    }

    private companion object {
        const val CACHE_DURATION_MS = 5 * 60 * 1_000L
    }
}
