package com.animeow.app.data.remote

import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class DiscoveryRequest(val name: String, val load: suspend () -> List<RemoteAnime>)

/** Publish the server before starting fallbacks; every fallback publishes as soon as it finishes. */
internal suspend fun loadDiscoverySources(
    server: DiscoveryRequest?,
    fallbacks: List<DiscoveryRequest>,
    serverTimeoutMillis: Long = 4_000,
    sourceTimeoutMillis: Long = 8_000,
    onProgress: suspend (RemoteFetchResult, String) -> Unit = { _, _ -> },
): RemoteFetchResult = supervisorScope {
    val completed = linkedMapOf<String, Result<List<RemoteAnime>>>()
    fun snapshot() = RemoteFetchResult(
        items = mergeDiscoveryItems(completed.values.flatMap { it.getOrDefault(emptyList()) }),
        warnings = completed.mapNotNull { (name, result) -> result.exceptionOrNull()?.let { userFriendlyRemoteFailure(name, it) } },
    )
    suspend fun load(request: DiscoveryRequest, timeout: Long) = runCatchingCancellable {
        withTimeoutOrNull(timeout) { request.load() } ?: error("连接超时")
    }
    if (server != null) {
        completed[server.name] = load(server, serverTimeoutMillis)
        onProgress(snapshot(), if (fallbacks.isEmpty()) "正在整理服务器作品" else
            if (snapshot().items.isNotEmpty()) "服务器已就绪，正在补充其他来源" else "服务器暂无可用结果，正在查询其他来源")
    }
    val channel = Channel<Pair<String, Result<List<RemoteAnime>>>>(fallbacks.size.coerceAtLeast(1))
    try {
        fallbacks.forEach { request -> launch { channel.send(request.name to load(request, sourceTimeoutMillis)) } }
        repeat(fallbacks.size) { index ->
            val (name, result) = channel.receive()
            completed[name] = result
            onProgress(snapshot(), if (index == fallbacks.lastIndex) "资料已就绪" else "$name 已完成，正在补充其他来源")
        }
    } finally { channel.close() }
    if (completed.isNotEmpty() && completed.values.all { it.isFailure }) {
        error("在线资料来源暂时不可用，请检查网络后重试")
    }
    snapshot()
}

internal fun mergeDiscoveryItems(items: List<RemoteAnime>): List<RemoteAnime> {
    val merged = linkedMapOf<String, RemoteAnime>()
    items.forEach { incoming ->
        val key = "${incoming.importSource}:${incoming.importId}:${incoming.subjectType}"
        val current = merged[key]
        merged[key] = if (current == null) incoming else localizedDiscoveryItem(current, incoming).copy(
            coverUrl = current.coverUrl?.takeIf { it.isNotBlank() && it != "null" } ?: incoming.coverUrl,
            episodes = current.episodes?.takeIf { it > 0 } ?: incoming.episodes,
            score = current.score ?: incoming.score, broadcastDay = current.broadcastDay ?: incoming.broadcastDay,
            coverUrls = incoming.coverUrls + current.coverUrls,
        )
    }
    return merged.values.toList()
}
