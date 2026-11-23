package com.animeow.app.data.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable public identities only: no account, private notes, titles, or reading history. */
internal class CatalogContributionQueue(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val preferences = context.applicationContext.getSharedPreferences("catalog_contribution_queue", Context.MODE_PRIVATE)

    fun add(references: List<MetadataReference>): Boolean = synchronized(lock) {
        val pending = pending().toMutableList()
        val cooldowns = cooldowns()
        val fresh = references.distinct().filter { valid(it) && it !in pending && (cooldowns.optLong(key(it)) <= now()) }
            .take((400 - pending.size).coerceAtLeast(0))
        if (fresh.isEmpty()) return false
        pending += fresh
        savePending(pending)
        true
    }

    fun first(): MetadataReference? = synchronized(lock) { pending().firstOrNull() }

    fun complete(reference: MetadataReference, cooldownMillis: Long) = synchronized(lock) {
        val cooldowns = cooldowns()
        cooldowns.put(key(reference), now() + cooldownMillis)
        val pruned = JSONObject()
        cooldowns.keys().asSequence().filter { cooldowns.optLong(it) > now() }.toList().takeLast(1600)
            .forEach { pruned.put(it, cooldowns.optLong(it)) }
        preferences.edit().putString("pending", encode(pending().filter { it != reference }))
            .putString("cooldowns", pruned.toString()).commit()
    }

    fun clearPending() = synchronized(lock) { preferences.edit().remove("pending").commit() }

    private fun pending(): List<MetadataReference> = runCatching {
        val array = JSONArray(preferences.getString("pending", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val parts = array.optString(index).split(':')
            if (parts.size == 2) MetadataReference(parts[0], parts[1]).takeIf(::valid) else null
        }.distinct().take(400)
    }.getOrDefault(emptyList())

    private fun cooldowns() = runCatching { JSONObject(preferences.getString("cooldowns", "{}")) }.getOrDefault(JSONObject())
    private fun savePending(items: List<MetadataReference>) = preferences.edit().putString("pending", encode(items)).commit()
    private fun encode(items: List<MetadataReference>) = JSONArray(items.map(::key)).toString()
    private fun key(item: MetadataReference) = "${item.source}:${item.id}"
    private fun valid(item: MetadataReference) = item.source in listOf("bangumi", "anilist", "kitsu", "tvmaze") &&
        item.id.toIntOrNull()?.let { it > 0 && it.toString() == item.id } == true

    private companion object { val lock = Any() }
}
