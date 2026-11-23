package com.animeow.app.data.remote

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.allTitles
import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.animeRatingSortValue
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ln

data class DiscoveryPreferenceProfile(
    val tags: Map<String, Double> = emptyMap(),
    val owned: Set<String> = emptySet(),
    val ownedTitles: Set<String> = emptySet(),
)

private val DISCOVERY_TITLE_PUNCTUATION = Regex("[\\p{Punct}\\p{IsPunctuation}\\p{Space}　·・～~]+")

/** 标题归一化：统一全角/半角与大小写并忽略标点与空白，用于推荐去重和已藏品排除。 */
internal fun normalizedDiscoveryTitle(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val stripped = nfkc.replace(DISCOVERY_TITLE_PUNCTUATION, "")
    return stripped.ifEmpty { nfkc.replace(Regex("[\\s　]+"), "") }
}

internal fun RemoteAnime.discoveryTitleKeys(): List<String> =
    listOfNotNull(title, originalTitle).map(::normalizedDiscoveryTitle).filter(String::isNotEmpty).distinct()

internal fun RemoteAnime.discoverySourceKeys(): List<String> = buildList {
    add("${importSource}:${importId}")
    metadataReferences.forEach { ref ->
        if (ref.source.isNotBlank() && ref.id.isNotBlank()) add("${ref.source}:${ref.id}")
    }
}.distinct()

internal fun discoveryPreferenceProfile(works: List<AnimeEntity>, tags: List<TagEntity>, links: List<AnimeTagEntity>, type: String): DiscoveryPreferenceProfile {
    val eligible = works.filter { it.deletedAt == null && normalizeSubjectType(it.subjectType) == type }
    val byId = eligible.associateBy { it.id }
    val names = tags.filterNot { it.blocked }.associate { it.id to it.name }
    val weights = mutableMapOf<String, Double>()
    links.forEach { link ->
        val work = byId[link.animeId] ?: return@forEach
        val name = names[link.tagId] ?: return@forEach
        if (name.trim() in setOf("日本", "中国", "动画", "动漫", "漫画", "小说", "TV", "OVA", "ONA", "剧场版", "已看", "看完", "在看", "未看", "想看")) return@forEach
        val rating = animeRatingSortValue(work)
        if (rating != null && rating < 50 || work.status in listOf("弃坑", "抛弃")) return@forEach
        weights[name] = (weights[name] ?: 0.0) + when { (rating ?: 0) >= 80 -> 2.0; (rating ?: 0) >= 60 -> 1.25; else -> 0.75 }
    }
    val owned = eligible.flatMap { it.sourceReferences() }.mapNotNull { ref ->
        if (ref.source.isNullOrBlank() || ref.externalId.isNullOrBlank()) null else "${ref.source}:${ref.externalId}"
    }.toSet()
    val ownedTitles = eligible.flatMap { it.allTitles() }.map(::normalizedDiscoveryTitle).filter(String::isNotEmpty).toSet()
    return DiscoveryPreferenceProfile(weights.toList().sortedByDescending { it.second }.take(12).toMap(), owned, ownedTitles)
}

internal fun recommendationTag(value: String): String = (discoveryAniListGenres[value] ?: value).lowercase().replace(" ", "")

/** Rank only the fetched candidate batch; a public rating is never rewritten as a recommendation score. */
internal fun recommendedDiscoveryItems(items: List<RemoteAnime>, profile: DiscoveryPreferenceProfile, today: LocalDate = LocalDate.now()): List<RemoteAnime> {
    val seenIdentities = mutableSetOf<String>()
    val seenTitles = mutableSetOf<String>()
    val candidates = items.filter { item ->
        val sourceKeys = item.discoverySourceKeys()
        val titleKeys = item.discoveryTitleKeys()
        if (sourceKeys.any { it in profile.owned } || titleKeys.any { it in profile.ownedTitles }) return@filter false
        if ((item.ratingCount ?: 0) < 100 || item.score == null || item.score !in 0.0..10.0) return@filter false
        if (runCatching { LocalDate.parse(item.airDate).isAfter(today) }.getOrDefault(false)) return@filter false
        if (sourceKeys.any { it in seenIdentities } || titleKeys.any { it in seenTitles }) return@filter false
        seenIdentities += sourceKeys
        seenTitles += titleKeys
        true
    }
    val weights = profile.tags.entries.groupBy { recommendationTag(it.key) }.mapValues { (_, values) -> values.sumOf { it.value } }
    val maxWeight = weights.values.maxOrNull() ?: 1.0
    fun tags(item: RemoteAnime) = item.tags.map(::recommendationTag).distinct()
    fun score(item: RemoteAnime): Double {
        val count = item.ratingCount!!.toDouble()
        val rating = (count * item.score!! + 200 * 6.8) / (count + 200) / 10
        val affinity = tags(item).map { (weights[it] ?: 0.0) / maxWeight }.sortedDescending().take(3).sum().coerceAtMost(1.0)
        val popularity = (ln(1.0 + (item.popularity ?: item.ratingCount ?: 0)) / ln(100001.0)).coerceIn(0.0, 1.0)
        val date = runCatching { LocalDate.parse(item.airDate) }.getOrNull()
        val recency = date?.let { 1.0 / (1.0 + ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) / 365.0) } ?: 0.0
        return if (weights.isEmpty()) 0.65 * rating + 0.25 * popularity + 0.10 * recency
            else 0.45 * affinity + 0.35 * rating + 0.15 * popularity + 0.05 * recency
    }
    val remaining = candidates.toMutableList()
    val selected = mutableListOf<RemoteAnime>()
    val seenTags = mutableMapOf<String, Int>()
    while (remaining.isNotEmpty()) {
        val next = remaining.maxBy { item -> score(item) - (tags(item).maxOfOrNull { seenTags[it] ?: 0 } ?: 0) * 0.06 }
        selected += next; remaining.remove(next)
        tags(next).forEach { seenTags[it] = (seenTags[it] ?: 0) + 1 }
    }
    return selected
}
