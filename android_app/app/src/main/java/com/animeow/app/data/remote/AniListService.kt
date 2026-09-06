package com.animeow.app.data.remote

import org.json.JSONArray
import org.json.JSONObject

internal class AniListService(
    private val client: JsonHttpClient = JsonHttpClient(),
) {
    suspend fun search(
        query: String,
        limit: Int = 24,
        filters: DiscoveryFilters = DiscoveryFilters(),
    ): List<RemoteAnime> {
        val variables = filterVariables(filters, limit, searchMode = true).put("search", query)
        return execute(DISCOVERY_QUERY, variables)
    }

    suspend fun trending(limit: Int = 24): List<RemoteAnime> {
        val variables = JSONObject().put("perPage", limit.coerceIn(1, 50))
        return execute(TRENDING_QUERY, variables)
    }

    suspend fun ranking(filters: DiscoveryFilters, limit: Int = 40): List<RemoteAnime> =
        execute(DISCOVERY_QUERY, filterVariables(filters, limit))

    suspend fun getAnime(id: Int): RemoteAnime? {
        val body = JSONObject()
            .put("query", DETAIL_QUERY)
            .put("variables", JSONObject().put("id", id))
            .toString()
        val response = client.post(API_URL, body)
        val root = JSONObject(response)
        val errors = root.optJSONArray("errors")
        if (errors != null && errors.length() > 0) return null
        return root.optJSONObject("data")?.optJSONObject("Media")?.toRemoteAnime()
    }

    private suspend fun execute(query: String, variables: JSONObject): List<RemoteAnime> {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val response = client.post(API_URL, body)
        return parseResponse(response)
    }

    private fun filterVariables(
        filters: DiscoveryFilters,
        limit: Int,
        searchMode: Boolean = false,
    ): JSONObject = JSONObject()
        .put("perPage", limit.coerceIn(1, 50))
        .put(
            "sort",
            JSONArray().apply {
                when (filters.sort) {
                    DiscoverySort.SCORE -> put("SCORE_DESC")
                    DiscoverySort.RANK -> put("SCORE_DESC").put("POPULARITY_DESC")
                    DiscoverySort.NEWEST -> put("START_DATE_DESC")
                    DiscoverySort.POPULARITY -> put("POPULARITY_DESC")
                    DiscoverySort.TRENDING -> if (searchMode) put("SEARCH_MATCH")
                    else put("TRENDING_DESC").put("POPULARITY_DESC")
                }
            },
        )
        .apply {
            filters.year?.let { put("seasonYear", it) }
            filters.seasonMonth?.let { month ->
                put(
                    "season",
                    when (month) {
                        1 -> "WINTER"
                        4 -> "SPRING"
                        7 -> "SUMMER"
                        10 -> "FALL"
                        else -> JSONObject.NULL
                    },
                )
            }
            filters.format?.takeIf(String::isNotBlank)?.let { put("format", normalizeFormat(it)) }
            filters.tag?.takeIf(String::isNotBlank)?.let { put("genre", it) }
            filters.minimumScore?.let { put("minimumScore", it.coerceIn(0, 100)) }
        }

    private fun normalizeFormat(value: String): String = when (value.uppercase(java.util.Locale.ROOT)) {
        "剧场版", "MOVIE" -> "MOVIE"
        "WEB", "ONA" -> "ONA"
        "SP", "SPECIAL" -> "SPECIAL"
        else -> value.uppercase(java.util.Locale.ROOT)
    }

    internal fun parseResponse(json: String): List<RemoteAnime> {
        val root = JSONObject(json)
        val errors = root.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            error(errors.optJSONObject(0)?.optString("message").orEmpty().ifBlank { "AniList 查询失败" })
        }
        val media = root.optJSONObject("data")
            ?.optJSONObject("Page")
            ?.optJSONArray("media") ?: JSONArray()
        return buildList {
            for (index in 0 until media.length()) {
                media.optJSONObject(index)?.toRemoteAnime()?.let(::add)
            }
        }
    }

    private fun JSONObject.toRemoteAnime(): RemoteAnime? {
        val id = optLong("id", -1).takeIf { it > 0 }?.toString() ?: return null
        val titles = optJSONObject("title")
        val title = titles?.optString("english")?.takeIf(String::isNotBlank)
            ?: titles?.optString("romaji")?.takeIf(String::isNotBlank)
            ?: titles?.optString("native")?.takeIf(String::isNotBlank)
            ?: return null
        val original = titles?.optString("native")?.takeIf(String::isNotBlank)
        val start = optJSONObject("startDate")
        val year = start?.optInt("year", 0) ?: 0
        val month = start?.optInt("month", 0) ?: 0
        val day = start?.optInt("day", 0) ?: 0
        val airDate = when {
            year <= 0 -> null
            month <= 0 -> "%04d".format(year)
            day <= 0 -> "%04d-%02d".format(year, month)
            else -> "%04d-%02d-%02d".format(year, month, day)
        }
        val genres = optJSONArray("genres")
        val tags = buildList {
            if (genres != null) {
                for (index in 0 until genres.length()) {
                    genres.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        val studios = optJSONObject("studios")?.optJSONArray("nodes")
        val studio = studios?.optJSONObject(0)?.optString("name")?.takeIf(String::isNotBlank)
        val cover = optJSONObject("coverImage")
        return RemoteAnime(
            source = RemoteCatalogSource.ANILIST,
            id = id,
            title = title,
            originalTitle = original?.takeUnless { it == title },
            coverUrl = cover?.optString("extraLarge")?.takeIf(String::isNotBlank)
                ?: cover?.optString("large")?.takeIf(String::isNotBlank),
            summary = cleanDescription(optString("description")),
            score = optDouble("averageScore", 0.0).takeIf { it > 0.0 },
            episodes = optInt("episodes", 0).takeIf { it > 0 },
            airDate = airDate,
            studio = studio,
            tags = tags,
            format = optString("format").takeIf(String::isNotBlank),
            subjectType = "anime",
            siteUrl = optString("siteUrl").takeIf(String::isNotBlank),
        )
    }

    private fun cleanDescription(value: String): String? = value
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .trim()
        .takeIf(String::isNotBlank)

    private companion object {
        const val API_URL = "https://graphql.anilist.co"
        const val MEDIA_FIELDS = """
            id title { romaji english native }
            coverImage { extraLarge large }
            episodes format averageScore description(asHtml: false)
            startDate { year month day }
            genres siteUrl
            studios(isMain: true) { nodes { name } }
        """
        val DISCOVERY_QUERY = """
            query DiscoverAnime(
              ${'$'}search: String,
              ${'$'}perPage: Int,
              ${'$'}sort: [MediaSort],
              ${'$'}seasonYear: Int,
              ${'$'}season: MediaSeason,
              ${'$'}format: MediaFormat,
              ${'$'}genre: String,
              ${'$'}minimumScore: Int
            ) {
              Page(page: 1, perPage: ${'$'}perPage) {
                media(
                  search: ${'$'}search,
                  type: ANIME,
                  sort: ${'$'}sort,
                  seasonYear: ${'$'}seasonYear,
                  season: ${'$'}season,
                  format: ${'$'}format,
                  genre: ${'$'}genre,
                  averageScore_greater: ${'$'}minimumScore
                ) { $MEDIA_FIELDS }
              }
            }
        """
        val TRENDING_QUERY = """
            query TrendingAnime(${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: [TRENDING_DESC, POPULARITY_DESC]) { $MEDIA_FIELDS }
              }
            }
        """
        val DETAIL_QUERY = """
            query AnimeDetail(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { $MEDIA_FIELDS }
            }
        """
    }
}
