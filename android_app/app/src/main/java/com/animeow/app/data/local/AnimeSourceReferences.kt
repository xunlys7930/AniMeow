package com.animeow.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Alternate titles and public source identities survive even after merged originals leave the trash. */
data class AnimeSourceReference(
    val title: String,
    val originalTitle: String? = null,
    val source: String? = null,
    val externalId: String? = null,
    val url: String? = null,
) {
    fun matches(source: String, id: String): Boolean =
        this.source?.trim()?.lowercase(Locale.ROOT) == source.trim().lowercase(Locale.ROOT) &&
            externalId?.trim() == id.trim()
}

fun parseAnimeSourceReferences(raw: String?): List<AnimeSourceReference> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            fun text(key: String) = if (item.isNull(key)) null else item.optString(key).trim().takeIf(String::isNotBlank)
            AnimeSourceReference(text("title").orEmpty(), text("originalTitle"), text("source"), text("externalId"), text("url"))
        }.distinct()
    }.getOrDefault(emptyList())
}

fun encodeAnimeSourceReferences(references: List<AnimeSourceReference>): String? = references.distinct()
    .takeIf { it.isNotEmpty() }?.let { values ->
        JSONArray().apply {
            values.forEach { value ->
                put(JSONObject().put("title", value.title).put("originalTitle", value.originalTitle)
                    .put("source", value.source).put("externalId", value.externalId).put("url", value.url))
            }
        }.toString()
    }

fun AnimeEntity.sourceReferences(): List<AnimeSourceReference> = listOf(
    AnimeSourceReference(title, originalTitle, externalSource, externalId, externalUrl),
) + parseAnimeSourceReferences(mergedSourcesJson)

fun AnimeEntity.allTitles(): List<String> = sourceReferences()
    .flatMap { listOfNotNull(it.title, it.originalTitle) }.map(String::trim).filter(String::isNotBlank).distinct()

fun AnimeEntity.matchesExternalReference(source: String, id: String): Boolean =
    sourceReferences().any { it.matches(source, id) }
