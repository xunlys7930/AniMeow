package com.animeow.app.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Public work/cast results only. Explicit retry bypasses the short session cache. */
internal class WorkCharacterCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class CachedWork {
        val mutex = Mutex()
        var result: WorkCharacterCatalog? = null
        var savedAt = 0L
    }
    private val entries = object : LinkedHashMap<List<String?>, CachedWork>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String?>, CachedWork>?) = size > 32
    }

    suspend fun get(key: List<String?>, force: Boolean, load: suspend () -> WorkCharacterCatalog): WorkCharacterCatalog {
        val entry = synchronized(entries) { entries.getOrPut(key) { CachedWork() } }
        return entry.mutex.withLock {
            entry.result?.takeIf { !force && now() - entry.savedAt in 0L until 300_000L }?.let { return@withLock it }
            load().also { entry.result = it; entry.savedAt = now() }
        }
    }
}
