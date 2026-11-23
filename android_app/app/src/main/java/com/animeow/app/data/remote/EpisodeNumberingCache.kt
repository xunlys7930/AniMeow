package com.animeow.app.data.remote

import android.os.SystemClock
import com.animeow.app.data.bangumi.EpisodeNumbering

/** Bounded process cache only: does not add backup fields or migrate the user database. */
internal object EpisodeNumberingCache {
    internal data class Entry(val numbering: EpisodeNumbering?, val createdAt: Long)
    private val entries = LinkedHashMap<Pair<Long, Int>, Entry>(64, 0.75f, true)

    @Synchronized
    fun get(key: Pair<Long, Int>): Entry? {
        val entry = entries[key] ?: return null
        val ttl = if (entry.numbering == null) 10 * 60_000L else 6 * 60 * 60_000L
        if (SystemClock.elapsedRealtime() - entry.createdAt >= ttl) {
            entries.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: Pair<Long, Int>, numbering: EpisodeNumbering?): EpisodeNumbering? {
        entries[key] = Entry(numbering, SystemClock.elapsedRealtime())
        while (entries.size > 64) entries.remove(entries.keys.first())
        return numbering
    }
}
