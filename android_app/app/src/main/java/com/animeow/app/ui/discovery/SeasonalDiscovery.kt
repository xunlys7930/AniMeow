package com.animeow.app.ui.discovery

import com.animeow.app.data.remote.DiscoveryFilters
import com.animeow.app.data.remote.DiscoverySort
import com.animeow.app.data.remote.SERVER_DISCOVERY_SEASON_MONTHS
import com.animeow.app.data.remote.serverDiscoverySeasonForDate
import java.time.LocalDate

internal fun seasonalDiscoveryFilters(date: LocalDate = LocalDate.now()): DiscoveryFilters {
    val season = serverDiscoverySeasonForDate(date)
    return DiscoveryFilters(year = season.year, seasonMonth = season.month, sort = DiscoverySort.POPULARITY)
}

// Clearing optional filters must keep the overview scoped to its selected season.
internal fun requiredSeasonFilters(filters: DiscoveryFilters, previous: DiscoveryFilters): DiscoveryFilters =
    filters.copy(
        year = filters.year?.takeIf { it in 1..9999 } ?: previous.year,
        seasonMonth = filters.seasonMonth?.takeIf { it in SERVER_DISCOVERY_SEASON_MONTHS } ?: previous.seasonMonth,
    )
