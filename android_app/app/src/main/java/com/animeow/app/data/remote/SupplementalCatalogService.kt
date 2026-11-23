package com.animeow.app.data.remote

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Public documented endpoints only. Kitsu requires the JSON:API media type. */
internal class SupplementalCatalogService(private val client: JsonHttpClient = JsonHttpClient(), context: android.content.Context? = null) {
    private val preferences = context?.let { com.animeow.app.data.preferences.DiscoveryPreferences(it) }
    private suspend fun viaServer() = (preferences?.snapshot() ?: com.animeow.app.data.preferences.DiscoveryNetworkSettings()).publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER

    suspend fun kitsu(query: String, type: String, id: String? = null): List<RemoteAnime> {
        if (type != "anime") return emptyList()
        val suffix = id?.takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true }?.let { "/$it" }
            ?: "?filter%5Btext%5D=${encode(query)}&page%5Blimit%5D=12"
        val root = JSONObject(if (viaServer()) PublicMetadataService().supplemental("kitsu", query, id) else client.get("https://kitsu.app/api/edge/anime$suffix", mapOf("Accept" to "application/vnd.api+json")))
        val data = root.optJSONArray("data") ?: JSONArray().apply { root.optJSONObject("data")?.let(::put) }
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val attr = item.optJSONObject("attributes") ?: continue
                val title = attr.optString("canonicalTitle").takeUnless { it.isBlank() || it == "null" } ?: continue
                add(RemoteAnime(RemoteCatalogSource.KITSU, item.optString("id"), title,
                    originalTitle = attr.optJSONObject("titles")?.clean("ja_jp"),
                    coverUrl = attr.optJSONObject("posterImage")?.let { it.clean("large") ?: it.clean("medium") },
                    summary = attr.clean("synopsis"), episodes = attr.optInt("episodeCount").takeIf { it > 0 },
                    airDate = attr.clean("startDate"), score = attr.clean("averageRating")?.toDoubleOrNull()?.div(10),
                    format = attr.clean("subtype"), siteUrl = "https://kitsu.app/anime/${item.optString("id")}"))
            }
        }.withPublicCache(root.toString())
    }

    suspend fun tvmaze(query: String, type: String, id: String? = null): List<RemoteAnime> {
        if (type != "anime") return emptyList()
        val exact = id?.takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true }
        val response = if (viaServer()) PublicMetadataService().supplemental("tvmaze", query, id) else client.get(if (exact != null) "https://api.tvmaze.com/shows/$exact" else "https://api.tvmaze.com/search/shows?q=${encode(query)}")
        val data = if (exact != null) JSONArray().put(JSONObject().put("show", JSONObject(response))) else publicArray(response)
        return buildList {
            for (i in 0 until minOf(data.length(), 12)) {
                val show = data.optJSONObject(i)?.optJSONObject("show") ?: continue
                if (show.optString("type") != "Animation") continue
                val title = show.clean("name") ?: continue
                add(RemoteAnime(RemoteCatalogSource.TVMAZE, show.optLong("id").toString(), title,
                    coverUrl = show.optJSONObject("image")?.let { it.clean("original") ?: it.clean("medium") },
                    summary = show.clean("summary")?.replace(Regex("<[^>]*>"), ""),
                    airDate = show.clean("premiered"), score = show.optJSONObject("rating")?.optDouble("average")?.takeIf { it.isFinite() && it > 0 },
                    tags = show.optJSONArray("genres")?.let { genres -> (0 until genres.length()).map { genres.optString(it) } }.orEmpty(),
                    siteUrl = show.clean("url")))
            }
        }.withPublicCache(response)
    }

    private fun JSONObject.clean(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
