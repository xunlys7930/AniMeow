package com.animeow.app.data.remote

import android.content.Context
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.preferences.BangumiApiMode
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.data.preferences.MetadataLookupStep
import com.animeow.app.data.preferences.MetadataLookupStrategy
import com.animeow.app.util.runCatchingCancellable
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun orderedMetadataLookup(
    order: List<MetadataLookupStep>,
    accepts: (List<RemoteAnime>) -> Boolean,
    request: suspend (MetadataLookupStep, Set<MetadataLookupStep>) -> List<RemoteAnime>,
    now: Instant = Instant.now(),
): RemoteFetchResult = fetchMetadata(order, MetadataLookupStrategy.ORDERED, accepts, request, now = now)

/** Race at most three sources per tier. A proxy is always a separate, explicit tier. */
internal fun metadataRequestGroups(order: List<MetadataLookupStep>, strategy: MetadataLookupStrategy): List<List<MetadataLookupStep>> {
    val unique = order.distinct()
    if (strategy == MetadataLookupStrategy.ORDERED) return unique.map(::listOf)
    val groups = mutableListOf<List<MetadataLookupStep>>()
    var tier = mutableListOf<MetadataLookupStep>()
    for (step in unique) {
        if (step == MetadataLookupStep.PROXY) {
            if (tier.isNotEmpty()) { groups += tier.toList(); tier.clear() }
            groups += listOf(step)
        } else {
            tier += step
            if (tier.size == 3) { groups += tier.toList(); tier.clear() }
        }
    }
    if (tier.isNotEmpty()) groups += tier.toList()
    return groups
}

internal suspend fun fetchMetadata(
    order: List<MetadataLookupStep>,
    strategy: MetadataLookupStrategy,
    accepts: (List<RemoteAnime>) -> Boolean,
    request: suspend (MetadataLookupStep, Set<MetadataLookupStep>) -> List<RemoteAnime>,
    timeoutMillis: Long = 6_000,
    now: Instant = Instant.now(),
    includeIncompleteResults: Boolean = false,
): RemoteFetchResult {
    val completed = mutableSetOf<MetadataLookupStep>()
    val warnings = mutableListOf<String>()
    val attempts = mutableListOf<MetadataAttempt>()
    var fallback = emptyList<RemoteAnime>()
    for (group in metadataRequestGroups(order, strategy)) {
        val winner = supervisorScope {
            val responses = Channel<Triple<MetadataLookupStep, Result<List<RemoteAnime>>, Long>>(Channel.UNLIMITED)
            val previouslyCompleted = completed.toSet()
            val jobs = group.map { step -> launch {
                val start = System.nanoTime()
                val response = try { runCatchingCancellable {
                    withTimeoutOrNull(if (step == MetadataLookupStep.PROXY) timeoutMillis * 2 else timeoutMillis) {
                        request(step, previouslyCompleted)
                    } ?: throw java.net.SocketTimeoutException()
                } } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    responses.trySend(Triple(step, Result.failure(cancelled), (System.nanoTime() - start) / 1_000_000))
                    throw cancelled
                }
                responses.send(Triple(step, response, (System.nanoTime() - start) / 1_000_000))
            } }
            try {
                repeat(group.size) {
                    val (step, result, elapsed) = responses.receive()
                    result.onSuccess { candidates ->
                        publicCacheNotice(candidates)?.let { warnings += it }
                        completed += step
                        fallback = mergeRemoteCandidates(fallback + candidates)
                        attempts += MetadataAttempt(step.label, elapsed, if (candidates.isEmpty()) "无结果" else "${candidates.size} 条")
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        warnings += userFriendlyRemoteFailure(step.label, error)
                        attempts += MetadataAttempt(step.label, elapsed, "暂不可用")
                    }
                    val complete = fallback.filter { it.hasUsefulMetadata() && it.isFreshCatalogEntry(now) }
                    if (accepts(complete)) return@supervisorScope if (includeIncompleteResults) fallback else complete
                }
                null
            } finally {
                jobs.forEach { it.cancel() }
                responses.close()
            }
        }
        if (winner != null) return RemoteFetchResult(winner, warnings.distinct(), attempts)
    }
    return RemoteFetchResult(fallback, warnings.distinct(), attempts)
}

internal fun RemoteAnime.hasUsefulMetadata(): Boolean = !coverUrl.isNullOrBlank() && !summary.isNullOrBlank()

internal fun RemoteAnime.isFreshCatalogEntry(now: Instant): Boolean {
    if (source != RemoteCatalogSource.SERVER || metadataReferences.isNotEmpty()) return true
    val updated = runCatching { Instant.parse(catalogUpdatedAt) }.getOrNull() ?: return false
    return !updated.isBefore(now.minusSeconds(7 * 24 * 60 * 60))
}

/** Cross-source merges require an exact original title and date; different seasons stay separate. */
internal fun samePublicWork(a: RemoteAnime, b: RemoteAnime): Boolean {
    if (normalizeSubjectType(a.subjectType) != normalizeSubjectType(b.subjectType)) return false
    if (a.importSource == b.importSource) return a.importId == b.importId
    if (a.airDate.isNullOrBlank() || a.airDate != b.airDate) return false
    fun normalize(value: String?) = value.orEmpty().lowercase(java.util.Locale.ROOT).replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
    val original = normalize(a.originalTitle)
    return original.isNotBlank() && original == normalize(b.originalTitle) &&
        (a.episodes == null || b.episodes == null || a.episodes <= 0 || b.episodes <= 0 || a.episodes == b.episodes) &&
        (a.format.isNullOrBlank() || b.format.isNullOrBlank() || a.format.equals(b.format, true))
}

internal fun mergeRemoteCandidates(candidates: List<RemoteAnime>): List<RemoteAnime> {
    val merged = mutableListOf<RemoteAnime>()
    candidates.forEach { incoming ->
        val index = merged.indexOfFirst { samePublicWork(it, incoming) }
        if (index < 0) merged += incoming else {
            val current = merged[index]
            // A retained server fallback must not mask freshly fetched official fields.
            val replaceServer = current.source == RemoteCatalogSource.SERVER && incoming.source != RemoteCatalogSource.SERVER
            val primary = if (replaceServer) incoming else current
            val secondary = if (replaceServer) current else incoming
            merged[index] = primary.copy(
                coverUrl = primary.coverUrl?.takeIf(String::isNotBlank) ?: secondary.coverUrl,
                summary = primary.summary?.takeIf(String::isNotBlank) ?: secondary.summary,
                episodes = primary.episodes?.takeIf { it > 0 } ?: secondary.episodes,
                airDate = primary.airDate?.takeIf(String::isNotBlank) ?: secondary.airDate,
                studio = primary.studio?.takeIf(String::isNotBlank) ?: secondary.studio,
                score = primary.score ?: secondary.score,
                tags = (primary.tags + secondary.tags).distinct(),
                originalTitle = primary.originalTitle ?: secondary.originalTitle,
                coverUrls = (secondary.coverUrls + primary.coverUrls).filterKeys { it in setOf("bangumi", "anilist", "proxy") },
                metadataReferences = current.metadataReferences + incoming.metadataReferences,
            )
        }
    }
    return merged
}

internal class MetadataLookup(context: Context) {
    private val preferences = DiscoveryPreferences(context)
    private val server = ServerCatalogService()
    private val bangumiOfficial = BangumiService(context = context, modeOverride = BangumiApiMode.OFFICIAL_ONLY)
    private val bangumiProxy = BangumiService(context = context, modeOverride = BangumiApiMode.PROXY_ONLY)
    private val aniListOfficial = AniListService(context = context, route = RemoteEndpointRoute.OFFICIAL)
    private val aniListProxy = AniListService(context = context, route = RemoteEndpointRoute.PROXY)
    private val supplemental = SupplementalCatalogService(context = context)
    private val cache = java.util.Collections.synchronizedMap(linkedMapOf<String, Pair<Long, List<RemoteAnime>>>())

    suspend fun search(
        query: String,
        subjectType: String,
        externalSource: String? = null,
        externalId: String? = null,
        includeIncompleteResults: Boolean = false,
        accepts: (List<RemoteAnime>) -> Boolean = { it.isNotEmpty() },
    ): RemoteFetchResult {
        val settings = preferences.snapshot()
        val type = normalizeSubjectType(subjectType)
        val source = externalSource?.lowercase(java.util.Locale.ROOT)
        val id = externalId?.toLongOrNull()?.takeIf { it > 0 }
        suspend fun bgm(service: BangumiService): List<RemoteAnime> =
            if (source in listOf("bangumi", "bgm") && id != null) listOfNotNull(service.subject(id, type)) else service.search(query, type)
        suspend fun ani(service: AniListService): List<RemoteAnime> =
            if (type != "anime") emptyList() else if (source == "anilist" && id != null && id <= Int.MAX_VALUE) listOfNotNull(service.getAnime(id.toInt())) else service.search(query)
        fun marked(items: List<RemoteAnime>, step: MetadataLookupStep) = items.map { item ->
            item.copy(retrievalRoute = step.storageKey,
                metadataReferences = if (item.source == RemoteCatalogSource.SERVER) item.metadataReferences else item.metadataReferences + MetadataReference(item.importSource, item.importId),
                coverUrls = item.coverUrls + if (item.importSource in listOf("bangumi", "anilist") && !item.coverUrl.isNullOrBlank()) mapOf(item.importSource to item.coverUrl) else emptyMap())
        }
        return fetchMetadata(
            MetadataLookupStep.normalize(settings.metadataLookupOrder).filter { it in settings.enabledMetadataSources }
                .let { if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER) listOfNotNull(MetadataLookupStep.SERVER.takeIf { step -> step in settings.enabledMetadataSources }) + it.filterNot { step -> step == MetadataLookupStep.SERVER } else it },
            if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER) MetadataLookupStrategy.ORDERED else settings.metadataLookupStrategy,
            accepts, timeoutMillis = if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER) 25_000 else settings.metadataTimeoutSeconds * 1_000L,
            includeIncompleteResults = includeIncompleteResults,
            request = { step, _ ->
                val key = "${settings.publicDataRoute}|$step|${query.trim()}|$type|$source|$id|${settings.bangumiApiProxyBase}|${settings.aniListProxyUrl}|${settings.allowAniListProxy}"
                val cached = cache[key]?.takeIf { System.currentTimeMillis() - it.first < if (it.second.isEmpty()) 20_000 else 300_000 }
                if (cached != null) cached.second else {
                    val response = when (step) {
                        MetadataLookupStep.SERVER -> {
                            val exact = if (id != null && source in listOf("bangumi", "bgm", "anilist", "kitsu", "tvmaze"))
                                runCatchingCancellable { server.lookup(if (source == "bgm") "bangumi" else source!!, id.toString()) }.getOrNull() else null
                            if (exact != null) listOf(exact) else server.metadataSearch(query, type)
                        }
                        MetadataLookupStep.BANGUMI -> bgm(bangumiOfficial)
                        MetadataLookupStep.ANILIST -> ani(aniListOfficial)
                        MetadataLookupStep.KITSU -> supplemental.kitsu(query, type, id?.toString()?.takeIf { source == "kitsu" })
                        MetadataLookupStep.TVMAZE -> supplemental.tvmaze(query, type, id?.toString()?.takeIf { source == "tvmaze" })
                        MetadataLookupStep.PROXY -> {
                            var found = emptyList<RemoteAnime>()
                            val errors = mutableListOf<Throwable>()
                            for (target in settings.metadataLookupOrder.filter { it == MetadataLookupStep.BANGUMI || it == MetadataLookupStep.ANILIST }) {
                                if (target == MetadataLookupStep.ANILIST && (!settings.allowAniListProxy || type != "anime")) continue
                                runCatchingCancellable {
                                    withTimeoutOrNull(settings.metadataTimeoutSeconds * 1_000L) {
                                        if (target == MetadataLookupStep.BANGUMI) bgm(bangumiProxy) else ani(aniListProxy)
                                    } ?: throw java.net.SocketTimeoutException()
                                }.onSuccess { found = mergeRemoteCandidates(found + it) }.onFailure { errors += it }
                                if (accepts(found.filter { it.hasUsefulMetadata() })) break
                            }
                            if (found.isEmpty() && errors.isNotEmpty()) throw errors.last()
                            found
                        }
                    }
                    marked(response, step).also { items ->
                        synchronized(cache) {
                            if (cache.size >= 80) cache.remove(cache.keys.first())
                            cache[key] = System.currentTimeMillis() to items
                        }
                    }
                }
            },
        )
    }
}
