package com.animeow.app.data.media

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import com.animeow.app.util.runCatchingCancellable

/** A fast failed response must never beat a slower, decodable cover. */
internal suspend fun <T : Any> firstValidCover(candidates: List<String>, download: suspend (String) -> T?): T? = supervisorScope {
    if (candidates.isEmpty()) return@supervisorScope null
    val results = Channel<T?>(candidates.size)
    val jobs = candidates.map { candidate -> launch { results.send(runCatchingCancellable { download(candidate) }.getOrNull()) } }
    try {
        repeat(candidates.size) { results.receive()?.let { return@supervisorScope it } }
        null
    } finally {
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        results.cancel()
    }
}
