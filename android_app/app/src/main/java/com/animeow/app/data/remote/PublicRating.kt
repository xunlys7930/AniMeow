package com.animeow.app.data.remote

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.sourceReferences
import org.json.JSONObject
import java.time.Instant

internal fun RemoteAnime.sourceRatingSnapshot(): String? {
    if (score == null && ratingCount == null) return null
    return JSONObject().put("source", importSource).put("id", importId)
        .put("score", score?.let { if (source == RemoteCatalogSource.ANILIST) it / 10 else if (it > 10) it / 10 else it })
        .put("count", ratingCount).put("rank", sourceRank)
        .put("updatedAt", cacheUpdatedAt ?: catalogUpdatedAt ?: Instant.now().toString()).put("stale", cacheStale).toString()
}

internal fun AnimeEntity.publicRating(): JSONObject? = runCatching {
    if ((sourceRatingJson?.length ?: 0) > 4096) return null
    val value = JSONObject(sourceRatingJson ?: return null)
    val source = value.getString("source")
    val id = value.getString("id")
    value.takeIf { (externalSource == source && externalId == id) || sourceReferences().any { it.source == source && it.externalId == id } }
}.getOrNull()

internal fun RemoteAnime.ratingCountLabel(): String = ratingCount?.let { "$it 人评分" } ?: "评分人数暂无"

internal fun mergeSourceRatingSnapshots(local: String?, incoming: String?): String? {
    fun parse(value: String?) = value?.takeIf { it.length <= 4096 }?.let { runCatching { JSONObject(it) }.getOrNull() }
    val remote = parse(incoming) ?: return local
    val current = parse(local) ?: return incoming
    if (current.optString("source") != remote.optString("source") || current.optString("id") != remote.optString("id")) return local
    val remoteTime = runCatching { Instant.parse(remote.getString("updatedAt")) }.getOrNull() ?: return local
    val localTime = runCatching { Instant.parse(current.getString("updatedAt")) }.getOrNull()
    return if (localTime == null || remoteTime > localTime) incoming else local
}
