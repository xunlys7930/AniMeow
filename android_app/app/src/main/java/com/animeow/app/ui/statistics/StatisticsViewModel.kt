package com.animeow.app.ui.statistics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.data.local.EPISODE_DURATION_MAX_MINUTES
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.animeRatingSortValue
import com.animeow.app.data.local.normalizeSubjectType
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

data class NamedCount(
    val name: String,
    val count: Int,
    val id: Long? = null,
    val filterValue: String? = null,
    val color: Long? = null,
)

data class StatisticsUiState(
    val totalAnime: Int = 0,
    val animeCount: Int = 0,
    val bookCount: Int = 0,
    val watchedEpisodes: Int = 0,
    val watchHours: Int = 0,
    val averageRating: Double? = null,
    val currentStreakDays: Int = 0,
    val statusCounts: List<NamedCount> = emptyList(),
    val ratingCounts: List<NamedCount> = emptyList(),
    val tagCounts: List<NamedCount> = emptyList(),
    val monthlyActivity: List<NamedCount> = emptyList(),
    val adaptationTypeCounts: List<NamedCount> = emptyList(),
    val genreCounts: List<NamedCount> = emptyList(),
    val yearCounts: List<NamedCount> = emptyList(),
    val watchMinutes: Long = watchHours.toLong() * 60,
)

class StatisticsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val dateRefresh = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun refreshDate() { dateRefresh.tryEmit(Unit) }

    private val statistics = combine(
        combine(repository.observeAnimes(), repository.observeEpisodeDetailsWithDuration()) { animes, details ->
            animes to details
        },
        repository.observeTags(),
        repository.observeAnimeTags(),
        repository.observeWatchStatuses(),
        repository.observeWatchRecords(),
    ) { library, tags, links, statuses, records ->
        { today: LocalDate -> calculateStatistics(library.first, tags, links, records, statuses, today, library.second) }
    }
    val state: StateFlow<StatisticsUiState> = combine(statistics, com.animeow.app.ui.tracker.homeDateUpdates(dateRefresh)) { calculate, today ->
        calculate(today)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatisticsUiState(),
    )
}

internal fun calculateStatistics(
    animes: List<AnimeEntity>,
    tags: List<TagEntity>,
    links: List<AnimeTagEntity>,
    records: List<WatchRecordEntity>,
    statuses: List<WatchStatusEntity> = emptyList(),
    today: LocalDate = LocalDate.now(),
    episodeDetails: List<EpisodeDetailEntity> = emptyList(),
): StatisticsUiState {
    val ratings = animes.mapNotNull(::animeRatingSortValue)
    val tagsById = tags.associateBy(TagEntity::id)
    val activeAnimeIds = animes.mapTo(hashSetOf(), AnimeEntity::id)
    val tagCounts = links.asSequence()
        .filter { it.animeId in activeAnimeIds }
        .groupingBy(AnimeTagEntity::tagId)
        .eachCount()
        .mapNotNull { (tagId, count) ->
            tagsById[tagId]?.let { NamedCount(it.name, count, id = tagId, color = it.color) }
        }
        .sortedByDescending(NamedCount::count)
    val rawStatusCounts = animes.groupingBy(AnimeEntity::status).eachCount()
    val configuredStatusNames = hashSetOf<String>()
    val statusCounts = buildList {
        statuses.sortedBy(WatchStatusEntity::sortOrder).forEach { status ->
            if (configuredStatusNames.add(status.name)) {
                add(
                    NamedCount(
                        name = status.name.ifBlank { "未分类" },
                        count = rawStatusCounts[status.name] ?: 0,
                        filterValue = status.name,
                        color = status.color,
                    ),
                )
            }
        }
        rawStatusCounts.entries
            .filterNot { it.key in configuredStatusNames }
            .sortedByDescending(Map.Entry<String, Int>::value)
            .forEach { (name, count) ->
                add(NamedCount(name.ifBlank { "未分类" }, count, filterValue = name))
            }
    }
    val ratingCounts = listOf(
        "9.0–10.0" to (90..100),
        "8.0–8.9" to (80..89),
        "7.0–7.9" to (70..79),
        "6.0–6.9" to (60..69),
        "0.1–5.9" to (1..59),
    ).map { (label, range) -> NamedCount(label, ratings.count(range::contains)) }

    val parsedRecordDates = records.filter { it.animeId in activeAnimeIds }.mapNotNull { com.animeow.app.data.local.watchRecordDate(it.recordDate) }
    val months = (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
    val monthly = months.map { month ->
        NamedCount(
            name = "${month.monthValue}月",
            count = parsedRecordDates.count { YearMonth.from(it) == month },
        )
    }
    val streakDates = parsedRecordDates.toSet()
    var cursor = if (today in streakDates) today else today.minusDays(1)
    var streak = 0
    while (cursor in streakDates) {
        streak += 1
        cursor = cursor.minusDays(1)
    }
    val watchedEpisodes = animes.filter { it.deletedAt == null && normalizeSubjectType(it.subjectType) == "anime" }
        .sumOf { it.watchedEpisodes.coerceAtLeast(0).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val watchMinutes = estimateWatchMinutes(animes, episodeDetails)

    // —— 分类饼图数据计算 ——
    val animeIdToTagNames: Map<Long, Set<String>> = links.asSequence()
        .filter { it.animeId in activeAnimeIds }
        .groupBy(AnimeTagEntity::animeId)
        .mapValues { (_, tagLinks) ->
            tagLinks.mapNotNull { link -> tagsById[link.tagId]?.name }.toSet()
        }

    val adaptationTypeCounts = calculateAdaptationTypeCounts(animeIdToTagNames)
    val genreCounts = calculateGenreCounts(animeIdToTagNames)
    val yearCounts = calculateYearCounts(animes)

    return StatisticsUiState(
        totalAnime = animes.size,
        animeCount = animes.count { normalizeSubjectType(it.subjectType) == "anime" },
        bookCount = animes.count { normalizeSubjectType(it.subjectType) == "book" },
        watchedEpisodes = watchedEpisodes,
        watchHours = (watchMinutes / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        watchMinutes = watchMinutes,
        averageRating = ratings.filter { it > 0 }.takeIf(List<Int>::isNotEmpty)?.average()?.div(10.0),
        currentStreakDays = streak,
        statusCounts = statusCounts,
        ratingCounts = ratingCounts,
        tagCounts = tagCounts,
        monthlyActivity = monthly,
        adaptationTypeCounts = adaptationTypeCounts,
        genreCounts = genreCounts,
        yearCounts = yearCounts,
    )
}

/** 按本季已看集号累计；缺失时长按 24 分钟估算，不枚举未填写的集号。 */
internal fun estimateWatchMinutes(animes: List<AnimeEntity>, details: List<EpisodeDetailEntity>): Long {
    val activeAnimes = animes.filter { it.deletedAt == null && normalizeSubjectType(it.subjectType) == "anime" }.associateBy(AnimeEntity::id)
    var minutes = activeAnimes.values.sumOf { it.watchedEpisodes.coerceAtLeast(0).toLong() * 24 }
    details.forEach { detail ->
        val anime = activeAnimes[detail.animeId] ?: return@forEach
        val duration = detail.durationMinutes?.takeIf { it in 1..EPISODE_DURATION_MAX_MINUTES } ?: return@forEach
        if (detail.episode in 1..anime.watchedEpisodes) minutes += duration - 24L
    }
    return minutes
}

internal fun formatEstimatedWatchTime(minutes: Long): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    val hours = safeMinutes / 60
    val remainder = safeMinutes % 60
    return when {
        hours == 0L -> "$remainder 分钟"
        remainder == 0L -> "$hours 小时"
        else -> "$hours 小时 $remainder 分钟"
    }
}

// 预定义标签分组：改编类型
private val ADAPTATION_TYPE_RULES = listOf(
    "漫改" to listOf("漫改", "漫画改"),
    "轻改" to listOf("轻改", "轻小说改"),
    "游戏改" to listOf("游戏改"),
    "原创" to listOf("原创"),
)

// 预定义标签分组：题材类型
private val GENRE_RULES = listOf(
    "恋爱" to listOf("恋爱", "爱情"),
    "冒险" to listOf("冒险"),
    "奇幻" to listOf("奇幻", "幻想"),
    "喜剧" to listOf("喜剧", "搞笑"),
    "科幻" to listOf("科幻"),
    "悬疑" to listOf("悬疑", "推理"),
    "日常" to listOf("日常"),
    "热血" to listOf("热血", "战斗"),
)

private fun calculateAdaptationTypeCounts(
    animeTagNames: Map<Long, Set<String>>,
): List<NamedCount> {
    val counts = ADAPTATION_TYPE_RULES.associate { (label, keywords) ->
        val count = animeTagNames.count { (_, tagNames) ->
            tagNames.any { tag -> keywords.any { keyword -> tag.contains(keyword) } }
        }
        label to count
    }.toMutableMap()
    val matched = counts.values.sum()
    counts["其他"] = animeTagNames.size - matched
    return counts.map { (name, count) -> NamedCount(name, count) }
        .filter { it.count > 0 }
        .sortedByDescending(NamedCount::count)
}

private fun calculateGenreCounts(
    animeTagNames: Map<Long, Set<String>>,
): List<NamedCount> {
    val counts = GENRE_RULES.associate { (label, keywords) ->
        val count = animeTagNames.count { (_, tagNames) ->
            tagNames.any { tag -> keywords.any { keyword -> tag.contains(keyword) } }
        }
        label to count
    }.toMutableMap()
    val matched = animeTagNames.count { (_, tagNames) ->
        GENRE_RULES.any { (_, keywords) ->
            tagNames.any { tag -> keywords.any { keyword -> tag.contains(keyword) } }
        }
    }
    counts["其他"] = animeTagNames.size - matched
    return counts.map { (name, count) -> NamedCount(name, count) }
        .filter { it.count > 0 }
        .sortedByDescending(NamedCount::count)
}

private fun calculateYearCounts(animes: List<AnimeEntity>): List<NamedCount> {
    return animes.mapNotNull { anime ->
        anime.airDate?.take(4)?.toIntOrNull()
    }.groupingBy { it }.eachCount()
        .map { (year, count) -> NamedCount(year.toString(), count) }
        .sortedByDescending { it.name }
        .take(15)
}
