package com.animeow.app.data.remote

import java.time.DateTimeException
import java.time.LocalDate

internal val SERVER_DISCOVERY_SEASON_MONTHS = setOf(1, 4, 7, 10)
internal const val SERVER_DISCOVERY_SEASON_START_DAY = 25

internal data class ServerDiscoverySeason(
    val year: Int,
    val month: Int,
)

internal fun isAirDateInServerDiscoverySeason(
    airDate: String?,
    seasonMonth: Int,
    seasonYear: Int? = null,
): Boolean {
    if (seasonMonth !in SERVER_DISCOVERY_SEASON_MONTHS) return false
    val date = parseServerDiscoveryAirDate(airDate) ?: return false
    if (seasonYear == null) return serverDiscoverySeasonForDate(date).month == seasonMonth

    val start = serverDiscoverySeasonStart(seasonYear, seasonMonth)
    val end = serverDiscoveryNextSeasonStart(seasonYear, seasonMonth)
    return !date.isBefore(start) && date.isBefore(end)
}

internal fun parseServerDiscoveryAirDate(airDate: String?): LocalDate? {
    val match = AIR_DATE_PATTERN.find(airDate?.trim().orEmpty()) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 1
    return try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }
}

internal fun serverDiscoverySeasonForDate(date: LocalDate): ServerDiscoverySeason {
    if (date.dayOfMonth >= SERVER_DISCOVERY_SEASON_START_DAY) {
        when (date.monthValue) {
            3 -> return ServerDiscoverySeason(date.year, 4)
            6 -> return ServerDiscoverySeason(date.year, 7)
            9 -> return ServerDiscoverySeason(date.year, 10)
            12 -> return ServerDiscoverySeason(date.year + 1, 1)
        }
    }
    return when (date.monthValue) {
        in 1..3 -> ServerDiscoverySeason(date.year, 1)
        in 4..6 -> ServerDiscoverySeason(date.year, 4)
        in 7..9 -> ServerDiscoverySeason(date.year, 7)
        else -> ServerDiscoverySeason(date.year, 10)
    }
}

internal fun serverDiscoverySeasonStart(seasonYear: Int, seasonMonth: Int): LocalDate = when (seasonMonth) {
    1 -> LocalDate.of(seasonYear - 1, 12, SERVER_DISCOVERY_SEASON_START_DAY)
    4, 7, 10 -> LocalDate.of(seasonYear, seasonMonth - 1, SERVER_DISCOVERY_SEASON_START_DAY)
    else -> throw IllegalArgumentException("seasonMonth 必须是 1、4、7 或 10")
}

internal fun serverDiscoveryNextSeasonStart(seasonYear: Int, seasonMonth: Int): LocalDate = when (seasonMonth) {
    1 -> serverDiscoverySeasonStart(seasonYear, 4)
    4 -> serverDiscoverySeasonStart(seasonYear, 7)
    7 -> serverDiscoverySeasonStart(seasonYear, 10)
    10 -> serverDiscoverySeasonStart(seasonYear + 1, 1)
    else -> throw IllegalArgumentException("seasonMonth 必须是 1、4、7 或 10")
}

internal fun discoveryQueryYears(year: Int?, seasonMonth: Int?): List<Int?> =
    if (year != null && seasonMonth == 1) listOf(year, year - 1) else listOf(year)

private val AIR_DATE_PATTERN = Regex("""^(\d{4})-(\d{1,2})(?:-(\d{1,2}))?""")
