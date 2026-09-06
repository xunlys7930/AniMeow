package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.globalSearchDataStore by preferencesDataStore(name = "global_search_preferences")

class GlobalSearchPreferences(context: Context) {
    private val dataStore = context.applicationContext.globalSearchDataStore

    val recentQueries: Flow<List<String>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values -> decode(values[Keys.RECENT_QUERIES]) }

    suspend fun remember(query: String) {
        val clean = query.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY_LENGTH)
        if (clean.length < 2) return
        val next = buildList {
            add(clean)
            addAll(recentQueries.first().filterNot { it.equals(clean, ignoreCase = true) })
        }.take(MAX_RECENT_QUERIES)
        dataStore.edit { it[Keys.RECENT_QUERIES] = JSONArray(next).toString() }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(Keys.RECENT_QUERIES) }
    }

    private fun decode(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw.orEmpty())
        List(array.length()) { index -> array.optString(index).trim() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
            .take(MAX_RECENT_QUERIES)
    }.getOrDefault(emptyList())

    private object Keys {
        val RECENT_QUERIES = stringPreferencesKey("recent_queries")
    }

    private companion object {
        const val MAX_RECENT_QUERIES = 8
        const val MAX_QUERY_LENGTH = 80
    }
}
