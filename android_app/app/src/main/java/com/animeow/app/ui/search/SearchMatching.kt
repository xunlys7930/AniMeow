package com.animeow.app.ui.search

import java.util.Locale

private val searchSeparators = Regex("[\\s　·・:：—_\\-]+")
private val searchWordBoundary = Regex("[\\s　]+")

internal fun normalizeSearchText(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace(searchSeparators, "")

internal class SearchTerms(query: String) {
    private val phrase = normalizeSearchText(query)
    private val words = query.trim().split(searchWordBoundary)
        .map(::normalizeSearchText).filter(String::isNotEmpty)
    val isEmpty: Boolean get() = phrase.isEmpty()

    fun rank(title: String, vararg fields: String?): Int? {
        if (isEmpty) return null
        val name = normalizeSearchText(title)
        if (name == phrase) return 0
        if (name.startsWith(phrase)) return 1
        if (phrase in name) return 2
        val values = fields.mapNotNull { it?.let(::normalizeSearchText) }
        if (values.any { phrase in it }) return 3
        if (words.size > 1 && words.all { word -> word in name || values.any { word in it } }) return 4
        return null
    }
}

internal fun <T> List<T>.rankedSearch(rank: (T) -> Int?): List<T> =
    mapNotNull { item -> rank(item)?.let { score -> item to score } }
        .sortedBy { it.second }
        .map { it.first }
