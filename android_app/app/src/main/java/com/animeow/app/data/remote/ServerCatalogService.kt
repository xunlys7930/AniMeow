package com.animeow.app.data.remote

import com.animeow.app.BuildConfig
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal class ServerCatalogService(
    private val client: JsonHttpClient = JsonHttpClient(),
) {
    val isConfigured: Boolean
        get() = BuildConfig.CLOUD_API_BASE.isNotBlank() && BuildConfig.API_TOKEN.isNotBlank()

    suspend fun search(
        query: String,
        filters: DiscoveryFilters = DiscoveryFilters(),
        limit: Int = 50,
    ): List<RemoteAnime> {
        require(isConfigured) { "当前构建未配置资料库服务器或访问令牌" }
        return discoveryQueryYears(filters.year, filters.seasonMonth)
            .flatMap { queryYear -> searchPage(query, filters.copy(year = queryYear), limit) }
            .distinctBy { "${it.importSource}:${it.importId}" }
            .let { applyDiscoveryFilters(it, filters, RemoteCatalogSource.SERVER) }
    }

    private suspend fun searchPage(
        query: String,
        filters: DiscoveryFilters,
        limit: Int,
    ): List<RemoteAnime> {
        val url = buildString {
            append(baseUrl())
            append("/api/search?limit=${limit.coerceIn(1, 100)}&offset=0")
            query.trim().takeIf(String::isNotEmpty)?.let { appendQuery("keyword", it) }
            filters.tag?.takeIf(String::isNotBlank)?.let { appendQuery("tag", it) }
            filters.year?.let { appendQuery("year", it.toString()) }
            filters.seasonMonth?.let { appendQuery("month", it.toString()) }
            filters.format?.takeIf(String::isNotBlank)?.let { appendQuery("format", it) }
            appendQuery(
                "sort",
                when (filters.sort) {
                    DiscoverySort.SCORE, DiscoverySort.RANK, DiscoverySort.POPULARITY -> "score"
                    DiscoverySort.NEWEST, DiscoverySort.TRENDING -> "air_date"
                },
            )
        }
        val root = JSONObject(client.get(url, authHeaders()))
        checkSuccess(root)
        val data = root.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toRemoteAnime()?.let(::add)
            }
        }
    }

    suspend fun updateCover(title: String, coverUrl: String): Int {
        require(isConfigured) { "当前构建未配置资料库服务器或访问令牌" }
        val root = JSONObject(
            client.post(
                baseUrl() + "/api/update_cover",
                JSONObject().put("title", title).put("cover_url", coverUrl).toString(),
                authHeaders(),
            ),
        )
        val status = root.optString("status")
        if (status != "success" && status != "no_match") {
            error(root.optString("message").ifBlank { "封面同步失败" })
        }
        return root.optInt("updated", 0)
    }

    private fun JSONObject.toRemoteAnime(): RemoteAnime? {
        val databaseId = optLong("id", -1).takeIf { it > 0 }?.toString() ?: return null
        val title = optString("name_cn").takeIf(String::isNotBlank)
            ?: optString("name").takeIf(String::isNotBlank)
            ?: optString("name_original").takeIf(String::isNotBlank)
            ?: return null
        val originSource = optString("source").takeIf(String::isNotBlank) ?: "server"
        val originId = opt("api_id")?.toString()?.takeIf(String::isNotBlank) ?: databaseId
        val totalEpisodes = optInt("total_eps", optInt("eps", 0)).takeIf { it > 0 }
        return RemoteAnime(
            source = RemoteCatalogSource.SERVER,
            id = databaseId,
            title = title,
            originalTitle = optString("name_original").takeIf(String::isNotBlank)?.takeUnless { it == title },
            coverUrl = optString("cover_url").takeIf(String::isNotBlank)
                ?: optString("image").takeIf(String::isNotBlank),
            summary = optString("summary").takeIf(String::isNotBlank),
            score = optDouble("score", 0.0).takeIf { it > 0.0 },
            episodes = totalEpisodes,
            airDate = optString("air_date").takeIf(String::isNotBlank),
            studio = optString("studio").takeIf(String::isNotBlank),
            tags = optString("tags").split(Regex("[,，/、]")).map(String::trim).filter(String::isNotBlank),
            format = optString("format").takeIf(String::isNotBlank)
                ?: optString("eps_breakdown").takeIf(String::isNotBlank),
            siteUrl = when (originSource.lowercase(java.util.Locale.ROOT)) {
                "bangumi" -> "https://bgm.tv/subject/$originId"
                "anilist" -> "https://anilist.co/anime/$originId"
                else -> null
            },
            originSource = originSource,
            originId = originId,
        )
    }

    private fun StringBuilder.appendQuery(key: String, value: String) {
        append('&').append(encode(key)).append('=').append(encode(value))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun authHeaders(): Map<String, String> {
        val token = BuildConfig.API_TOKEN.trim()
        val authValue = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        val rawToken = token.removePrefix("Bearer ").removePrefix("bearer ").trim()
        return mapOf(
            "Authorization" to authValue,
            "X-Api-Key" to rawToken,
            "X-Auth-Token" to rawToken,
            "Accept" to "application/json",
        )
    }

    private fun baseUrl(): String = BuildConfig.CLOUD_API_BASE.trim().trimEnd('/')

    private fun checkSuccess(root: JSONObject) {
        if (root.optString("status") != "success") {
            error(root.optString("message").ifBlank { "资料库服务器请求失败" })
        }
    }
}
