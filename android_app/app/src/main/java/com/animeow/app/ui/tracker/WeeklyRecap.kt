package com.animeow.app.ui.tracker

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.WatchRecordEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class WeeklyRecap(val startDate: String = "", val endDate: String = "", val episodeCount: Int = 0,
    val workCount: Int = 0, val activeDays: Int = 0, val titles: List<String> = emptyList())

internal fun buildWeeklyRecap(animes: List<AnimeEntity>, records: List<WatchRecordEntity>, today: LocalDate = LocalDate.now()): WeeklyRecap {
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val active = animes.filter { it.deletedAt == null }.associateBy { it.id }
    val dated = records.mapNotNull { record ->
        if (record.animeId !in active || record.episode <= 0) return@mapNotNull null
        val date = com.animeow.app.data.local.watchRecordDate(record.recordDate) ?: return@mapNotNull null
        if (date.isBefore(start) || date.isAfter(today)) null else record to date
    }
    val works = dated.map { it.first.animeId }.distinct()
    return WeeklyRecap(start.toString(), today.toString(), dated.distinctBy { it.first.animeId to it.first.episode }.size,
        works.size, dated.map { it.second }.distinct().size, works.mapNotNull { active[it]?.title }.take(3))
}
