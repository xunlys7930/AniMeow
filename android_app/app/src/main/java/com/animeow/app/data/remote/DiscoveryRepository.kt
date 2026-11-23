package com.animeow.app.data.remote

import android.content.Context
import android.net.Uri
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences

class DiscoveryRepository internal constructor(
    context: Context,
    private val bangumi: BangumiService = BangumiService(JsonHttpClient(2_500, 3_500), context),
    private val aniList: AniListService = AniListService(JsonHttpClient(2_500, 3_500), context),
    private val server: ServerCatalogService = ServerCatalogService(JsonHttpClient(2_500, 3_500)),
) {
    private val appContext = context.applicationContext
    private val coverAlternatives = com.animeow.app.data.media.RemoteCoverAlternatives(appContext)
    private val traceMoe = TraceMoeService(context, aniList)
    private val preferences = DiscoveryPreferences(context)
    private val metadataLookup = MetadataLookup(context)
    private val broadcastCache = object : LinkedHashMap<String, BroadcastDay>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BroadcastDay>?) = size > 8
    }

    internal suspend fun dailyBroadcast(date: java.time.LocalDate, zone: java.time.ZoneId, forceRefresh: Boolean = false): BroadcastDay {
        val key = "$date:$zone:${preferences.snapshot().copy(display = com.animeow.app.data.preferences.DiscoveryDisplayConfig())}"
        val cached = synchronized(broadcastCache) { broadcastCache[key] }
        if (!forceRefresh && cached != null && java.time.Duration.between(cached.updatedAt, java.time.Instant.now()).toMillis() < CACHE_DURATION_MS) return cached
        return try {
            val raw = loadBroadcastDay(date, zone) { page -> aniList.airingPage(date, zone, page) }
            val day = localizeBroadcastDay(raw)
            day.also {
                synchronized(broadcastCache) { broadcastCache[key] = it }
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            cached?.copy(warnings = (cached.warnings + "暂时无法更新，显示上次加载的放送安排，请重试").distinct()) ?: throw error
        }
    }

    private suspend fun localizeBroadcastDay(raw: BroadcastDay): BroadcastDay {
        if (raw.entries.isEmpty()) return raw
        val calendarItems = runCatching { broadcastCalendar() }.getOrNull().orEmpty()
        if (calendarItems.isEmpty()) return raw
        fun normalizeKey(s: String?): String = s?.replace(Regex("[\\s·:：\\-_/\\\\~～!！?？\"'“”‘’()]"), "")?.lowercase().orEmpty()
        val mapping = mutableMapOf<String, RemoteAnime>()
        calendarItems.forEach { item ->
            val origKey = normalizeKey(item.originalTitle)
            if (origKey.isNotEmpty()) mapping[origKey] = item
            val titleKey = normalizeKey(item.title)
            if (titleKey.isNotEmpty()) mapping[titleKey] = item
        }
        val localizedEntries = raw.entries.map { entry ->
            val anime = entry.anime
            val matched = mapping[normalizeKey(anime.originalTitle)]
                ?: mapping[normalizeKey(anime.title)]
                ?: mapping[normalizeKey(anime.localizedTitles["native"])]
                ?: mapping[normalizeKey(anime.localizedTitles["romaji"])]
            if (matched != null && matched.title.isNotBlank()) {
                val updatedAnime = anime.copy(
                    title = matched.title,
                    originalTitle = anime.originalTitle ?: matched.originalTitle,
                    coverUrl = anime.coverUrl ?: matched.coverUrl,
                    score = anime.score ?: matched.score,
                    siteUrl = matched.siteUrl ?: anime.siteUrl,
                )
                entry.copy(anime = updatedAnime)
            } else entry
        }
        return raw.copy(entries = localizedEntries)
    }
    private val feedCache = object : LinkedHashMap<String, Pair<Long, RemoteFetchResult>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, RemoteFetchResult>>?) = size > 16
    }

    suspend fun searchMetadata(
        query: String,
        subjectType: String = "anime",
        externalSource: String? = null,
        externalId: String? = null,
        includeIncompleteResults: Boolean = false,
        accepts: (List<RemoteAnime>) -> Boolean = { it.isNotEmpty() },
    ): RemoteFetchResult = metadataLookup.search(query, subjectType, externalSource, externalId,
        includeIncompleteResults = includeIncompleteResults, accepts = accepts)

    suspend fun featured(
        source: DiscoverySourceFilter = DiscoverySourceFilter.ALL,
        subjectType: String = "anime",
        filters: DiscoveryFilters = DiscoveryFilters(),
        page: Int = 1,
        forceRefresh: Boolean = false,
        onProgress: suspend (RemoteFetchResult, String) -> Unit = { _, _ -> },
        preference: DiscoveryPreferenceProfile = DiscoveryPreferenceProfile(),
    ): RemoteFetchResult {
        if (filters.sort != DiscoverySort.TRENDING) return loadFeed(source, subjectType, filters, DiscoveryFeedMode.FEATURED, null, page, forceRefresh, onProgress)
        fun arrange(result: RemoteFetchResult): RemoteFetchResult = result.copy(
            items = recommendedDiscoveryItems(result.items, preference), totalCount = 0, totalCountExact = false, totalPages = null,
            resultScope = (if (preference.tags.isEmpty()) "本批推荐：评分可信度 65%＋人气 25%＋新近度 10%；添加收藏标签后可按偏好推荐" else
                "本批推荐：标签偏好 45%＋评分可信度 35%＋人气 15%＋新近度 5%") + "；至少 100 人评分，减少重复类型，排除已收藏作品")
        val result = loadFeed(source, subjectType, filters, DiscoveryFeedMode.FEATURED, null, page, forceRefresh) { value, message -> onProgress(arrange(value), message) }
        val candidates = result.items.toMutableList()
        if (filters.tag == null) {
            preference.tags.keys.take(2).forEach { tag ->
                com.animeow.app.util.runCatchingCancellable {
                    if (source == DiscoverySourceFilter.ANILIST) aniList.discoverPaged(null, filters.copy(tag = tag), page, limit = 12)
                    else bangumi.discoverPaged(null, subjectType, filters.copy(tag = tag), page, limit = 12, forceServer = source == DiscoverySourceFilter.SERVER)
                }.getOrNull()?.let { candidates += it.items }
            }
        }
        return arrange(result.copy(items = candidates))
    }

    suspend fun ranking(
        source: DiscoverySourceFilter,
        subjectType: String,
        filters: DiscoveryFilters,
        page: Int = 1,
        forceRefresh: Boolean = false,
        onProgress: suspend (RemoteFetchResult, String) -> Unit = { _, _ -> },
    ): RemoteFetchResult = loadFeed(source, subjectType, filters, DiscoveryFeedMode.RANKING, null, page, forceRefresh, onProgress)

    suspend fun seasonal(
        source: DiscoverySourceFilter,
        filters: DiscoveryFilters,
        page: Int = 1,
        forceRefresh: Boolean = false,
        onProgress: suspend (RemoteFetchResult, String) -> Unit = { _, _ -> },
    ): RemoteFetchResult = loadFeed(source, "anime", filters, DiscoveryFeedMode.SEASONAL, null, page, forceRefresh, onProgress)

    suspend fun search(
        query: String,
        source: DiscoverySourceFilter,
        subjectType: String = "anime",
        filters: DiscoveryFilters = DiscoveryFilters(),
        page: Int = 1,
        feedMode: DiscoveryFeedMode = DiscoveryFeedMode.FEATURED,
        onProgress: suspend (RemoteFetchResult, String) -> Unit = { _, _ -> },
    ): RemoteFetchResult = loadFeed(source, subjectType, filters, feedMode, query.trim(), page, false, onProgress)

    suspend fun cachedFeed(
        source: DiscoverySourceFilter,
        subjectType: String,
        filters: DiscoveryFilters,
        mode: DiscoveryFeedMode,
        query: String? = null,
        page: Int = 1,
    ): RemoteFetchResult? {
        val key = cacheKey(source, normalizeSubjectType(subjectType), filters, mode, query, preferences.snapshot()) + ":p$page"
        return synchronized(feedCache) { feedCache[key]?.second }
    }

    private suspend fun loadFeed(
        source: DiscoverySourceFilter,
        subjectType: String,
        filters: DiscoveryFilters,
        mode: DiscoveryFeedMode,
        query: String?,
        page: Int = 1,
        forceRefresh: Boolean,
        onProgress: suspend (RemoteFetchResult, String) -> Unit,
    ): RemoteFetchResult {
        val type = normalizeSubjectType(subjectType)
        val settings = preferences.snapshot()
        val key = cacheKey(source, type, filters, mode, query, settings) + ":p$page"
        val cached = synchronized(feedCache) { feedCache[key] }
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.first < CACHE_DURATION_MS) return cached.second

        if (mode == DiscoveryFeedMode.SEASONAL) {
            return server.seasonal(filters, query.orEmpty(), page).also {
                synchronized(feedCache) { feedCache[key] = System.currentTimeMillis() to it }
            }
        }

        val pages = java.util.concurrent.ConcurrentHashMap<String, RemoteFetchResult>()
        suspend fun serverPage(): RemoteFetchResult {
            if (query.isNullOrBlank() && filters.sort in listOf(DiscoverySort.RANK, DiscoverySort.SCORE, DiscoverySort.POPULARITY, DiscoverySort.TRENDING)) {
                return bangumi.discoverPaged(null, type, filters, page, forceServer = true)
            }
            val paged = server.searchPaged(query.orEmpty(), filters, page = page, pageSize = 24, subjectType = type)
            val items = paged.items.filter { normalizeSubjectType(it.subjectType) == type }
                .also { values -> values.forEach(coverAlternatives::remember) }
            return RemoteFetchResult(items = items, page = paged.page,
                totalPages = paged.totalPages.takeIf { paged.filtersApplied && paged.totalKnown }, hasMore = paged.hasMore,
                totalCount = if (paged.filtersApplied && paged.totalKnown) paged.total else 0,
                totalCountExact = paged.filtersApplied && paged.totalKnown,
                resultScope = if (filters.sort in listOf(DiscoverySort.RANK, DiscoverySort.POPULARITY)) "服务器暂无独立排名与人气数据，当前按评分排序" else
                    if (filters.sort == DiscoverySort.TRENDING) "服务器趋势按最新放送排列；分批加载" else
                    if (paged.filtersApplied && paged.totalKnown) null else "服务器分批加载，筛选当前批次；匹配总数暂不可用")
        }
        if (source == DiscoverySourceFilter.SERVER) {
            return serverPage().also { synchronized(feedCache) { feedCache[key] = System.currentTimeMillis() to it } }
        }
        val serverRequest = DiscoveryRequest("服务器") {
            serverPage().also { pages["服务器"] = it }.items
        }
        val bangumiRequest = DiscoveryRequest("Bangumi") {
            val paged = bangumi.discoverPaged(query, type, filters, page)
            pages["Bangumi"] = paged
            paged.items.also { if (settings.contributePublicMetadata) CatalogContributionWorker.enqueueAll(appContext, it) }
        }
        val aniListRequest = DiscoveryRequest("AniList") {
            val paged = if (type != "anime") RemoteFetchResult(emptyList(), page = page) else aniList.discoverPaged(query, filters, page)
            pages["AniList"] = paged
            paged.items.also { if (settings.contributePublicMetadata) CatalogContributionWorker.enqueueAll(appContext, it) }
        }
        fun withPagination(result: RemoteFetchResult): RemoteFetchResult {
            val available = pages.values.toList()
            return result.copy(page = page, hasMore = available.any { it.hasMore },
                totalPages = available.singleOrNull()?.totalPages.takeIf { source != DiscoverySourceFilter.ALL },
                totalCount = if (source != DiscoverySourceFilter.ALL) available.singleOrNull()?.totalCount ?: 0 else 0,
                totalCountExact = source != DiscoverySourceFilter.ALL && available.singleOrNull()?.totalCountExact == true,
                resultScope = if (source == DiscoverySourceFilter.ALL) {
                    (listOf("各来源按批次合并，保留各来源排序，不代表跨来源总榜") + available.mapNotNull { it.resultScope }).distinct().joinToString("；")
                } else available.singleOrNull()?.resultScope)
        }
        val useServer = source == DiscoverySourceFilter.SERVER ||
            (source == DiscoverySourceFilter.ALL && server.isConfigured && settings.showServerSource && settings.includeServerInAllSources)
        val fallbacks = when (source) {
            DiscoverySourceFilter.SERVER -> emptyList()
            DiscoverySourceFilter.BANGUMI -> listOf(bangumiRequest)
            DiscoverySourceFilter.ANILIST -> listOf(aniListRequest)
            DiscoverySourceFilter.ALL -> listOfNotNull(bangumiRequest, aniListRequest.takeIf { type == "anime" })
        }
        val result = try { loadDiscoverySources(serverRequest.takeIf { useServer }, fallbacks, sourceTimeoutMillis = if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER) 25_000 else 8_000, onProgress = { partial, message ->
            if (partial.items.isNotEmpty()) synchronized(feedCache) { feedCache[key] = 0L to withPagination(partial) }
            onProgress(withPagination(partial), message)
        }) } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (cached?.second?.items?.isNotEmpty() == true) return cached.second.copy(
                warnings = listOf("资料暂时无法更新，显示上次已加载的内容"))
            throw error
        }
        // An offline retry must not erase a previously useful feed with an empty partial response.
        val paginated = withPagination(result)
        val resolved = if (result.items.isEmpty() && result.warnings.isNotEmpty() && cached?.second?.items?.isNotEmpty() == true) {
            cached.second.copy(warnings = result.warnings)
        } else paginated.copy(warnings = (paginated.warnings + listOfNotNull(publicCacheNotice(paginated.items))).distinct())
        synchronized(feedCache) { feedCache[key] = System.currentTimeMillis() to resolved }
        return resolved
    }

    internal fun acceptContribution(record: RemoteAnime) = synchronized(feedCache) {
        feedCache.replaceAll { _, (at, result) -> at to result.copy(items = result.items.map { localizedDiscoveryItem(it, record) }) }
    }

    private fun cacheKey(source: DiscoverySourceFilter, type: String, filters: DiscoveryFilters, mode: DiscoveryFeedMode,
        query: String?, settings: DiscoveryNetworkSettings): String =
        "$mode:$source:$type:$filters:$query:${settings.copy(display = com.animeow.app.data.preferences.DiscoveryDisplayConfig())}"

    suspend fun searchByImage(uri: Uri): List<ImageSearchMatch> = traceMoe.search(uri)

    suspend fun broadcastCalendar(): List<RemoteAnime> = bangumi.calendar().filter { it.broadcastDay in 1..7 }
        .also { CatalogContributionWorker.enqueueAll(appContext, it) }

    /**
     * 为列表中缺少封面的项触发服务器后台封面刷新。
     *
     * 调用后立即返回，封面查找在服务器后台异步进行，
     * 下次刷新发现列表时就能看到新封面。
     *
     * @param items 发现列表中的动画项
     * @param maxRequests 最多触发多少个封面刷新请求（避免一次性请求过多）
     */
    suspend fun refreshMissingCovers(items: List<RemoteAnime>, maxRequests: Int = 10) {
        if (!server.isConfigured) return
        val missingCovers = items.filter { it.coverUrl.isNullOrBlank() }.take(maxRequests)
        if (missingCovers.isEmpty()) return
        missingCovers.forEach { item ->
            runCatching {
                server.refreshCover(
                    source = item.importSource,
                    id = item.importId,
                    title = item.title,
                )
            }
        }
    }

    private companion object { const val CACHE_DURATION_MS = 5 * 60 * 1_000L }
}
