package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.seriesDataStore by preferencesDataStore(name = "series_preferences")

enum class SeriesSort(val storageKey: String, val displayName: String) {
    DEFAULT("default", "添加顺序"),
    AIR_DATE("air_date", "放送日期"),
    RATING("rating", "评分"),
    PROGRESS("progress", "观看进度"),
    TITLE("title", "标题");

    companion object {
        fun fromStorage(value: String?): SeriesSort =
            entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}

data class SeriesDisplaySettings(
    val sort: SeriesSort = SeriesSort.DEFAULT,
    val ascending: Boolean = true,
    val columnsOverride: Int? = null,
    val statusFilter: String? = null,
) {
    fun normalized() = copy(
        columnsOverride = columnsOverride?.coerceIn(2, 5),
        statusFilter = statusFilter?.trim()?.takeIf(String::isNotEmpty),
    )
}

/** Each collection remembers its own view, independently of the home screen. */
class SeriesPreferences internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.seriesDataStore)

    fun observe(seriesId: Long): Flow<SeriesDisplaySettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { decode(it, seriesId) }

    suspend fun update(seriesId: Long, transform: (SeriesDisplaySettings) -> SeriesDisplaySettings) {
        dataStore.edit { values ->
            val next = transform(decode(values, seriesId)).normalized()
            values[stringPreferencesKey("${seriesId}_sort")] = next.sort.storageKey
            values[booleanPreferencesKey("${seriesId}_ascending")] = next.ascending
            values[intPreferencesKey("${seriesId}_columns")] = next.columnsOverride ?: 0
            values[stringPreferencesKey("${seriesId}_status")] = next.statusFilter.orEmpty()
        }
    }

    private fun decode(values: Preferences, seriesId: Long) = SeriesDisplaySettings(
        sort = SeriesSort.fromStorage(values[stringPreferencesKey("${seriesId}_sort")]),
        ascending = values[booleanPreferencesKey("${seriesId}_ascending")] ?: true,
        columnsOverride = values[intPreferencesKey("${seriesId}_columns")]?.takeIf { it > 0 },
        statusFilter = values[stringPreferencesKey("${seriesId}_status")],
    ).normalized()
}
