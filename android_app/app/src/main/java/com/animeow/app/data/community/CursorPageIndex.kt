package com.animeow.app.data.community

import java.util.TreeMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CursorBatch<T>(val items: List<T>, val nextCursor: Long?)
internal data class IndexedCursorPage<T>(val number: Int, val items: List<T>, val hasNext: Boolean, val lastPage: Int?)

/** Stores page boundaries only. Large lists never accumulate all user/image records. */
internal class CursorPageIndex {
    private val starts = TreeMap<Int, Long?>().apply { put(1, null) }
    private var lastPage: Int? = null

    suspend fun <T> load(
        requestedPage: Int,
        onLocating: (Int) -> Unit = {},
        fetch: suspend (Long?) -> CursorBatch<T>,
    ): IndexedCursorPage<T> {
        var target = requestedPage.coerceAtLeast(1).let { minOf(it, lastPage ?: it) }
        var number = starts.floorKey(target) ?: 1
        while (true) {
            currentCoroutineContext().ensureActive()
            onLocating(number)
            val cursor = starts[number]
            val batch = fetch(cursor)
            currentCoroutineContext().ensureActive()
            check(batch.nextCursor == null || (batch.nextCursor != cursor && batch.nextCursor !in starts.headMap(number, true).values)) {
                "列表暂时无法继续翻页，请刷新后重试"
            }
            if (batch.items.isEmpty() && number > 1) {
                lastPage = number - 1
                starts.tailMap(number, true).clear()
                number = starts.floorKey(number - 1) ?: 1
                target = minOf(target, number)
                continue
            }
            val next = batch.nextCursor
            if (next == null) {
                lastPage = number
                starts.tailMap(number + 1, true).clear()
            } else {
                if (starts[number + 1] != next) starts.tailMap(number + 1, true).clear()
                starts[number + 1] = next
                if ((lastPage ?: Int.MAX_VALUE) <= number) lastPage = null
            }
            if (number == target || next == null) return IndexedCursorPage(number, batch.items, next != null, lastPage)
            number++
        }
    }
}
