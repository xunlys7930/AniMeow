package com.animeow.app.data.remote

import android.content.Context
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.util.runCatchingCancellable
import org.json.JSONArray
import org.json.JSONObject

internal enum class RemoteEndpointRoute { AUTO, OFFICIAL, PROXY }

internal class AniListService(
    private val client: JsonHttpClient = JsonHttpClient(),
    context: Context? = null,
    private val route: RemoteEndpointRoute = RemoteEndpointRoute.AUTO,
) {
    private val preferences = context?.applicationContext?.let(::DiscoveryPreferences)
    suspend fun search(
        query: String,
        limit: Int = 24,
        filters: DiscoveryFilters = DiscoveryFilters(),
    ): List<RemoteAnime> {
        val variables = filterVariables(filters, limit, searchMode = true).put("search", query)
        return execute(DISCOVERY_QUERY, variables, "search")
    }

    suspend fun trending(limit: Int = 24): List<RemoteAnime> {
        val variables = JSONObject().put("perPage", limit.coerceIn(1, 50))
        return execute(TRENDING_QUERY, variables, "trending")
    }

    suspend fun ranking(filters: DiscoveryFilters, limit: Int = 40): List<RemoteAnime> =
        execute(DISCOVERY_QUERY, filterVariables(filters, limit), "ranking")

    suspend fun discoverPaged(query: String?, filters: DiscoveryFilters, page: Int, limit: Int = 24): RemoteFetchResult {
        val safePage = page.coerceAtLeast(1)
        val variables = filterVariables(filters, limit, searchMode = !query.isNullOrBlank()).put("page", safePage)
        query?.takeIf(String::isNotBlank)?.let { variables.put("search", it) }
        val response = request(DISCOVERY_QUERY, variables, if (query.isNullOrBlank()) "ranking" else "search")
        val result = parseDiscoveryPage(response, filters, safePage)
        return if (query.isNullOrBlank() && filters.sort in listOf(DiscoverySort.TRENDING, DiscoverySort.SCORE, DiscoverySort.RANK))
            result.copy(items = result.items.filter { (it.ratingCount ?: 0) >= 100 && (filters.sort != DiscoverySort.TRENDING || isAirDateReleased(it.airDate)) },
                resultScope = result.resultScope + "；本批次仅展示至少 100 人评分的作品") else result
    }

    internal fun parseDiscoveryPage(response: String, filters: DiscoveryFilters, safePage: Int): RemoteFetchResult {
        val info = JSONObject(response).optJSONObject("data")?.optJSONObject("Page")?.optJSONObject("pageInfo")
        // Older proxies always return page 1. Never silently duplicate that page or invent continuation.
        val currentPage = info?.optInt("currentPage", safePage) ?: 1
        val compatible = info != null && currentPage == safePage
        val raw = if (safePage > 1 && !compatible) emptyList() else parseResponse(response)
        return RemoteFetchResult(
            items = applyDiscoveryFilters(raw, filters.copy(tag = if (compatible) null else filters.tag), RemoteCatalogSource.ANILIST),
            page = safePage, hasMore = compatible && info!!.optBoolean("hasNextPage", false),
            resultScope = if (!compatible) "当前 AniList 线路仅提供首批结果，可切换来源继续查找" else
                if (filters.sort == DiscoverySort.RANK) "AniList 未提供综合排名，按评分与人气排序" else if (filters.year == null && filters.seasonMonth != null) "AniList 分批加载，未选年份时季度筛选当前批次" else "AniList 按所选条件分批加载",
        )
    }

    suspend fun getAnime(id: Int): RemoteAnime? {
        val response = request(DETAIL_QUERY, JSONObject().put("id", id), "detail")
        val root = JSONObject(response)
        val errors = root.optJSONArray("errors")
        if (errors != null && errors.length() > 0) return null
        return listOfNotNull(root.optJSONObject("data")?.optJSONObject("Media")?.toRemoteAnime()).withPublicCache(response).firstOrNull()
    }

    suspend fun airingPage(date: java.time.LocalDate, zone: java.time.ZoneId, page: Int): BroadcastPage {
        val (start, end) = broadcastBounds(date, zone)
        val variables = JSONObject().put("airingAtGreater", start - 1).put("airingAtLesser", end).put("page", page).put("perPage", 50)
        val response = try { request(AIRING_QUERY, variables, "airing") }
        catch (error: RemoteHttpException) {
            if (error.statusCode == 400 && (preferences?.snapshot() ?: DiscoveryNetworkSettings()).publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER)
                error("服务器尚未支持今日放送，请稍后重试，或在高级设置中主动选择手机直连")
            throw error
        }
        return parseAiringPage(response, page)
    }

    internal fun parseAiringPage(response: String, page: Int): BroadcastPage {
        val root = JSONObject(response)
        check(root.optJSONArray("errors")?.length().let { it == null || it == 0 }) { "放送资料暂时无法读取，请稍后重试" }
        val data = root.optJSONObject("data")?.optJSONObject("Page")
        val info = data?.optJSONObject("pageInfo")
        check(info?.optInt("currentPage", -1) == page && info.opt("hasNextPage") is Boolean) { "当前线路未提供完整放送分页，请切换线路或稍后重试" }
        val rows = checkNotNull(data?.optJSONArray("airingSchedules")) { "来源未返回放送安排，请稍后重试" }
        var invalid = false
        val entries = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index)
                val anime = row?.optJSONObject("media")?.toRemoteAnime()
                val id = row?.optLong("id", 0) ?: 0
                val time = row?.optLong("airingAt", 0) ?: 0
                val episode = row?.optInt("episode", 0) ?: 0
                if (anime == null || id <= 0 || time <= 0 || episode <= 0) { invalid = true; continue }
                add(BroadcastEntry(id, listOf(anime).withPublicCache(response).single(), episode, java.time.Instant.ofEpochSecond(time)))
            }
        }
        val cache = root.optJSONObject("_animeow_cache")
        return BroadcastPage(entries, info!!.optBoolean("hasNextPage"), buildList {
            if (invalid) add("部分来源记录缺少作品、集数或时间，当前放送安排可能不完整")
            if (cache?.optBoolean("stale") == true) add("放送资料暂时无法更新，显示服务器保留的安排，请核对实际更新")
        }, cache?.optString("updatedAt")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() })
    }

    private suspend fun execute(query: String, variables: JSONObject, operation: String): List<RemoteAnime> {
        val response = request(query, variables, operation)
        return parseResponse(response)
    }

    private suspend fun request(query: String, variables: JSONObject, operation: String): String {
        val settings = preferences?.snapshot() ?: DiscoveryNetworkSettings()
        if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER) return checkResponse(PublicMetadataService().anilist(operation, variables))
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        suspend fun official() = checkResponse(client.post(API_URL, body))
        suspend fun proxy(): String {
            check(settings.allowAniListProxy) { "AniList 代理已关闭" }
            val custom = settings.aniListProxyUrl.trim()
            return checkResponse(if (custom.isBlank()) {
                ServerCatalogService(client).proxyAniList(operation, variables)
            } else {
                requireValidRemoteBase(custom)
                client.post(custom, body)
            })
        }
        if (route == RemoteEndpointRoute.OFFICIAL) return official()
        if (route == RemoteEndpointRoute.PROXY) return proxy()
        val first = runCatchingCancellable { official() }
        if (first.isSuccess) return first.getOrThrow()
        if (!settings.allowAniListProxy || !shouldTryRemoteFallback(first.exceptionOrNull()!!)) return first.getOrThrow()
        return proxy()
    }

    private fun checkResponse(response: String): String {
        val error = JSONObject(response).optJSONArray("errors")?.optJSONObject(0)
        if (error != null) {
            val status = error.optInt("status", 502)
            // A missing title is a valid miss, so do not spend proxy quota on it.
            if (status != 404) throw RemoteHttpException(status, error.optString("message"))
        }
        return response
    }

    private fun filterVariables(
        filters: DiscoveryFilters,
        limit: Int,
        searchMode: Boolean = false,
    ): JSONObject = JSONObject()
        .put("perPage", limit.coerceIn(1, 50))
        .put("page", 1)
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
            discoveryDateRange(filters)?.let { (start, end) ->
                put("startDateGreater", start.minusDays(1).toString().replace("-", "").toInt())
                put("startDateLesser", end.toString().replace("-", "").toInt())
            }
            // A yearless quarter spans many disjoint ranges. Filter each fetched batch locally
            // instead of narrowing to AniList's different season classification.
            filters.format?.takeIf(String::isNotBlank)?.let { put("format", normalizedDiscoveryFormat(it)) }
            filters.tag?.takeIf(String::isNotBlank)?.let {
                val genre = discoveryAniListGenres[it] ?: discoveryAniListGenres.values.firstOrNull { genre -> genre.equals(it, true) }
                val sources = discoveryAniListSources[it]
                if (sources != null) put("sources", JSONArray(sources))
                else if (genre != null) put("genre", genre) else put("tag", discoveryAniListTags[it] ?: it)
            }
            filters.minimumScore?.let { put("minimumScore", it.coerceIn(0, 100) - 1) }
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
        }.withPublicCache(json)
    }

    private fun JSONObject.toRemoteAnime(): RemoteAnime? {
        val id = optLong("id", -1).takeIf { it > 0 }?.toString() ?: return null
        val titles = optJSONObject("title")
        val nativeLanguage = when (optString("countryOfOrigin")) {
            "CN" -> "zh-Hans"
            "TW", "HK" -> "zh-Hant"
            "JP" -> "ja"
            "KR" -> "ko"
            else -> "und"
        }
        val localizedTitles = listOf("english" to "en", "romaji" to "ja-Latn", "native" to nativeLanguage)
            .mapNotNull { (key, language) -> titles?.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { language to it } }.toMap()
        val titleLanguage = localizedTitles.preferredLanguage()
        val title = localizedTitles[titleLanguage] ?: return null
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
        val allTags = tags + (optJSONArray("tags")?.let { values -> (0 until values.length()).mapNotNull { values.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) } } ?: emptyList())
        val studios = optJSONObject("studios")?.optJSONArray("nodes")
        val studio = studios?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
        val cover = optJSONObject("coverImage")
        return RemoteAnime(
            source = RemoteCatalogSource.ANILIST,
            id = id,
            title = title,
            originalTitle = original?.takeUnless { it == title },
            coverUrl = cover?.optString("extraLarge")?.takeIf { it.isNotBlank() && it != "null" }
                ?: cover?.optString("large")?.takeIf { it.isNotBlank() && it != "null" },
            summary = cleanDescription(optString("description")),
            score = optDouble("averageScore", 0.0).takeIf { it > 0.0 },
            ratingCount = optJSONObject("stats")?.optJSONArray("scoreDistribution")?.let { rows ->
                (0 until rows.length()).sumOf { rows.optJSONObject(it)?.optInt("amount", 0)?.coerceAtLeast(0)?.toLong() ?: 0L }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            },
            popularity = optInt("popularity", -1).takeIf { it >= 0 },
            episodes = optInt("episodes", 0).takeIf { it > 0 },
            airDate = airDate,
            studio = studio,
            tags = allTags,
            format = optString("format").takeIf { it.isNotBlank() && it != "null" },
            subjectType = "anime",
            siteUrl = optString("siteUrl").takeIf { it.isNotBlank() && it != "null" },
            localizedTitles = localizedTitles, titleLanguage = titleLanguage,
            localizedSummaries = cleanDescription(optString("description"))?.let { mapOf("en" to it) } ?: emptyMap(),
            summaryLanguage = "en",
        )
    }

    private fun cleanDescription(value: String): String? = value
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .trim()
        .takeIf { it.isNotBlank() && it != "null" }

    private companion object {
        const val API_URL = "https://graphql.anilist.co"
        const val MEDIA_FIELDS = """
            id countryOfOrigin title { romaji english native }
            coverImage { extraLarge large }
            episodes format averageScore popularity stats { scoreDistribution { amount } } description(asHtml: false)
            startDate { year month day }
            genres tags { name } siteUrl
            studios(isMain: true) { nodes { name } }
        """
        val DISCOVERY_QUERY = """
            query DiscoverAnime(
              ${'$'}search: String,
              ${'$'}perPage: Int,
              ${'$'}page: Int,
              ${'$'}tag: String,
              ${'$'}sources: [MediaSource],
              ${'$'}startDateGreater: FuzzyDateInt,
              ${'$'}startDateLesser: FuzzyDateInt,
              ${'$'}sort: [MediaSort],
              ${'$'}seasonYear: Int,
              ${'$'}season: MediaSeason,
              ${'$'}format: MediaFormat,
              ${'$'}genre: String,
              ${'$'}minimumScore: Int
            ) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage }
                media(
                  search: ${'$'}search,
                  type: ANIME,
                  sort: ${'$'}sort,
                  seasonYear: ${'$'}seasonYear,
                  season: ${'$'}season,
                  format: ${'$'}format,
                  genre: ${'$'}genre,
                  tag: ${'$'}tag,
                  source_in: ${'$'}sources,
                  startDate_greater: ${'$'}startDateGreater,
                  startDate_lesser: ${'$'}startDateLesser,
                  averageScore_greater: ${'$'}minimumScore
                ) { $MEDIA_FIELDS }
              }
            }
        """
        val TRENDING_QUERY = """
            query TrendingAnime(${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage }
                media(type: ANIME, sort: [TRENDING_DESC, POPULARITY_DESC]) { $MEDIA_FIELDS }
              }
            }
        """
        val DETAIL_QUERY = """
            query AnimeDetail(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { $MEDIA_FIELDS }
            }
        """
        val AIRING_QUERY = """
            query DailyAiring(${'$'}airingAtGreater: Int, ${'$'}airingAtLesser: Int, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage }
                airingSchedules(airingAt_greater: ${'$'}airingAtGreater, airingAt_lesser: ${'$'}airingAtLesser, sort: [TIME]) {
                  id airingAt episode media { $MEDIA_FIELDS }
                }
              }
            }
        """
    }
}
