package com.animeow.app.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which collection categories the user keeps: anime/book/game/audio.
 * Audio is opt-in; the other three stay visible until switched off.
 * The same set also hides the matching entries on the discovery page.
 */
internal object LibraryCategories {
    val all = listOf("anime", "book", "game", "audio")
    private val defaults = setOf("anime", "book", "game")
    private var prefs: android.content.SharedPreferences? = null
    private val _visible = MutableStateFlow(defaults)
    val visible: StateFlow<Set<String>> = _visible

    fun init(context: Context) {
        val store = context.getSharedPreferences("library_categories", Context.MODE_PRIVATE)
        prefs = store
        _visible.value = store.getStringSet("visible", null)?.filterTo(mutableSetOf()) { it in all } ?: defaults
    }

    fun setVisible(value: Set<String>) {
        _visible.value = value.filterTo(mutableSetOf()) { it in all }
        prefs?.edit()?.putStringSet("visible", _visible.value)?.apply()
    }
}
