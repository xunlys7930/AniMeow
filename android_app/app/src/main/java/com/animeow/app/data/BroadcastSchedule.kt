package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.remote.RemoteAnime
import java.util.Locale

data class BroadcastLibraryMatch(val anime: AnimeEntity? = null, val ambiguous: Boolean = false)

data class BroadcastImportSummary(val added: Int = 0, val updated: Int = 0, val skipped: Int = 0)

fun matchBroadcastAnime(remote: RemoteAnime, library: List<AnimeEntity>): BroadcastLibraryMatch {
    val animes = library.filter { it.deletedAt == null && normalizeSubjectType(it.subjectType) == "anime" }
    fun source(value: String?) = value?.trim()?.lowercase(Locale.ROOT)?.let { if (it == "bgm") "bangumi" else it }
    animes.firstOrNull {
        source(it.externalSource) == source(remote.importSource) && it.externalId == remote.importId
    }?.let { return BroadcastLibraryMatch(it) }
    fun title(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    val remoteTitles = listOfNotNull(remote.title, remote.originalTitle).map(::title).filter(String::isNotEmpty).toSet()
    val matches = animes.filter { anime ->
        // A known, different subject from the same source must not be merged by title.
        !(source(anime.externalSource) == source(remote.importSource) && !anime.externalId.isNullOrBlank()) &&
            listOfNotNull(anime.title, anime.originalTitle).map(::title).any(remoteTitles::contains)
    }
    return BroadcastLibraryMatch(matches.singleOrNull(), ambiguous = matches.size > 1)
}

fun AnimeEntity.hasActiveBroadcast(): Boolean =
    deletedAt == null && normalizeSubjectType(subjectType) == "anime" && broadcastDay in 1..7 &&
        status.trim() !in setOf("看完", "已完成", "完成", "弃坑", "抛弃", "搁置", "暂停")

fun broadcastWeekdayLabel(day: Int?): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrNull((day ?: 0) - 1) ?: "未设置"
