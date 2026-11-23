package com.animeow.app.ui.screens

import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoveryFeedMode
import com.animeow.app.data.remote.DiscoverySourceFilter

internal fun discoveryFilterCount(filters: DiscoveryFilters, source: DiscoverySourceFilter, mode: DiscoveryFeedMode): Int {
    val conditions = if (mode == DiscoveryFeedMode.SEASONAL) filters.copy(year = null, seasonMonth = null) else filters
    return conditions.activeCount + if (mode != DiscoveryFeedMode.SEASONAL && source != DiscoverySourceFilter.ALL) 1 else 0
}

internal data class DiscoveryFilterChip(val label: String, val withoutFilter: DiscoveryFilters)

internal fun isValidDiscoveryYear(text: String): Boolean =
    text.isBlank() || (text.trim().all(Char::isDigit) && text.trim().toIntOrNull()?.let { it in 1..9999 } == true)

internal fun discoveryFilterChips(filters: DiscoveryFilters): List<DiscoveryFilterChip> = buildList {
    filters.year?.let { add(DiscoveryFilterChip("${it} 年", filters.copy(year = null))) }
    filters.seasonMonth?.let {
        val label = when (it) { 1 -> "冬季"; 4 -> "春季"; 7 -> "夏季"; 10 -> "秋季"; else -> "${it} 月" }
        add(DiscoveryFilterChip(label, filters.copy(seasonMonth = null)))
    }
    filters.format?.let { add(DiscoveryFilterChip(it, filters.copy(format = null))) }
    filters.tag?.let { add(DiscoveryFilterChip("标签：$it", filters.copy(tag = null))) }
    filters.minimumScore?.let {
        add(DiscoveryFilterChip("≥ ${it / 10}.${it % 10} 分", filters.copy(minimumScore = null)))
    }
}
