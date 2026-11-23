package com.animeow.app.data.remote

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

internal data class BroadcastEntry(val id: Long, val anime: RemoteAnime, val episode: Int, val airingAt: Instant)
internal data class BroadcastPage(val entries: List<BroadcastEntry>, val hasMore: Boolean, val warnings: List<String> = emptyList(), val updatedAt: Instant? = null)
internal data class BroadcastDay(val entries: List<BroadcastEntry>, val warnings: List<String>, val updatedAt: Instant)

internal fun broadcastBounds(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
    date.atStartOfDay(zone).toEpochSecond() to date.plusDays(1).atStartOfDay(zone).toEpochSecond()

internal suspend fun loadBroadcastDay(date: LocalDate, zone: ZoneId, load: suspend (Int) -> BroadcastPage): BroadcastDay {
    val (start, end) = broadcastBounds(date, zone)
    val entries = linkedMapOf<Long, BroadcastEntry>()
    val warnings = mutableListOf<String>()
    var updatedAt: Instant? = null
    for (page in 1..10) {
        val result = try { load(page) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (page == 1) throw error
            warnings.add("后续放送安排暂时无法取得，当前仅显示已加载部分，请重试")
            break
        }
        warnings.addAll(result.warnings)
        result.updatedAt?.let { updatedAt = listOfNotNull(updatedAt, it).minOrNull() }
        result.entries.filter { it.airingAt.epochSecond in start until end }.forEach { entries[it.id] = it }
        if (!result.hasMore) break
        if (page == 10) warnings.add("已达到本次加载上限，当前放送安排可能不完整")
    }
    return BroadcastDay(entries.values.sortedWith(compareBy<BroadcastEntry> { it.airingAt }.thenBy { it.id }),
        warnings.distinct(), updatedAt ?: Instant.now())
}
