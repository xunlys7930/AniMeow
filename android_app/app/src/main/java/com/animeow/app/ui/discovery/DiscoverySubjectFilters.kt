package com.animeow.app.ui.discovery

import com.animeow.app.data.remote.DiscoveryFilters

/** Book results must never be hidden by an animation format or broadcast-season selection. */
internal fun discoveryFiltersForSubjectType(filters: DiscoveryFilters, subjectType: String): DiscoveryFilters =
    if (subjectType == "anime") filters else filters.copy(seasonMonth = null, format = null)
