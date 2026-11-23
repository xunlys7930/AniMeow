package com.animeow.app.data.remote

import android.content.Context
import com.animeow.app.data.bangumi.EpisodeNumbering
import com.animeow.app.data.bangumi.EpisodeNumberMetadata
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.preferences.BangumiApiMode
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.util.runCatchingCancellable
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class BangumiService(
    private val client: JsonHttpClient = JsonHttpClient(),
    context: Context? = null,
    private val modeOverride: BangumiApiMode? = null,
) {
    private val preferences = context?.applicationContext?.let(::DiscoveryPreferences)

    suspend fun search(
        query: String,
        subjectType: String = "anime",
        limit: Int = 24,
    ): List<RemoteAnime> {
        val normalizedSubjectType = normalizeSubjectType(subjectType)
        val type = if (normalizedSubjectType == "book") 1 else 2
        val body = JSONObject()
            .put("keyword", query)
            .put("sort", "match")
            .put("filter", JSONObject().put("type", JSONArray().put(type)))
            .toString()
        val settings = networkSettings()
        val response = post(settings, "/v0/search/subjects?limit=${limit.coerceIn(1, 50)}&offset=0", body)
        return parseSearchResponse(response, normalizedSubjectType).normalizeImageUrls()
    }

    suspend fun calendar(): List<RemoteAnime> {
        val settings = networkSettings()
        val response = get(settings, "/calendar")
        return parseCalendarResponse(response).normalizeImageUrls()
    }

    suspend fun ranking(
        filters: DiscoveryFilters = DiscoveryFilters(sort = DiscoverySort.RANK),
        subjectType: String = "anime",
        limit: Int = 40,
    ): List<RemoteAnime> {
        val settings = networkSettings()
        val normalizedSubjectType = normalizeSubjectType(subjectType)
        val type = if (normalizedSubjectType == "book") 1 else 2
        val sort = when (filters.sort) {
            DiscoverySort.SCORE -> "score"
            DiscoverySort.NEWEST -> "date"
            DiscoverySort.POPULARITY, DiscoverySort.TRENDING -> "heat"
            DiscoverySort.RANK -> "rank"
        }
        val path = buildString {
            append("/v0/subjects?type=$type&sort=$sort&limit=${limit.coerceIn(1, 50)}&offset=0")
            filters.year?.let { append("&year=$it") }
            filters.seasonMonth?.let { append("&month=$it") }
        }
        val response = get(settings, path)
        return applyClientFilters(parseSearchResponse(response, normalizedSubjectType), filters)
            .normalizeImageUrls()
    }

    /** Search endpoint supports date/tag predicates before pagination; format remains a local predicate. */
    suspend fun discoverPaged(query: String?, subjectType: String, filters: DiscoveryFilters, page: Int, limit: Int = 24, forceServer: Boolean = false): RemoteFetchResult {
        val type = normalizeSubjectType(subjectType)
        val size = limit.coerceIn(1, 50)
        val safePage = page.coerceAtLeast(1)
        val filter = JSONObject().put("type", JSONArray().put(if (type == "book") 1 else 2))
        if (query.isNullOrBlank()) when (filters.sort) {
            DiscoverySort.RANK -> filter.put("rank", JSONArray().put(">0"))
            DiscoverySort.SCORE, DiscoverySort.TRENDING -> filter.put("rating_count", JSONArray().put(">=100"))
            else -> Unit
        }
        filters.tag?.takeIf(String::isNotBlank)?.let { filter.put("tag", JSONArray().put(it)) }
        discoveryDateRange(filters)?.let { (start, end) ->
            filter.put("air_date", JSONArray().put(">=$start").put("<$end"))
        }
        if (query.isNullOrBlank() && filters.sort == DiscoverySort.TRENDING && !filter.has("air_date"))
            filter.put("air_date", JSONArray().put("<${java.time.LocalDate.now().plusDays(1)}"))
        val body = JSONObject().put("keyword", query.orEmpty()).put("sort", when (filters.sort) {
            DiscoverySort.RANK -> "rank"
            DiscoverySort.SCORE -> "score"
            DiscoverySort.NEWEST -> "match" // Search API has no date sort; label the current-page ordering below.
            DiscoverySort.POPULARITY -> "heat"
            DiscoverySort.TRENDING -> if (query.isNullOrBlank()) "heat" else "match"
        }).put("filter", filter)
        val path = "/v0/search/subjects?limit=$size&offset=${(safePage - 1) * size}"
        val response = if (forceServer) PublicMetadataService().bangumi("POST", path, body.toString()) else post(networkSettings(), path, body.toString())
        val raw = parseSearchResponse(response, type).normalizeImageUrls()
        val root = JSONObject(response)
        val total = root.optInt("total", -1)
        val rawCount = root.optJSONArray("data")?.length() ?: 0
        // Tags have already been matched remotely; response tags may only include a popular subset.
        val eligible = if (query.isNullOrBlank()) when (filters.sort) {
            DiscoverySort.RANK -> raw.filter { (it.sourceRank ?: 0) > 0 }
            DiscoverySort.SCORE -> raw.filter { (it.ratingCount ?: 0) >= 100 }
            DiscoverySort.TRENDING -> raw.filter { (it.ratingCount ?: 0) >= 100 && isAirDateReleased(it.airDate) }
            else -> raw
        } else raw
        val items = applyDiscoveryFilters(eligible, filters.copy(tag = null), RemoteCatalogSource.BANGUMI)
        return RemoteFetchResult(items, page = safePage,
            hasMore = if (total >= 0) safePage.toLong() * size < total else rawCount == size,
            resultScope = if (filters.sort == DiscoverySort.NEWEST) "Bangumi 分批加载，最新放送按本页结果排序" else
                "Bangumi ${if (filters.sort == DiscoverySort.RANK) "有效综合排名" else if (filters.sort == DiscoverySort.SCORE && query.isNullOrBlank()) "评分榜（至少 100 人评分）" else if (filters.sort == DiscoverySort.TRENDING && query.isNullOrBlank()) "趋势按来源热度（已放送、至少 100 人评分）" else "来源排序"}；格式、季度和最低评分进一步筛选当前批次${if (forceServer) "；经服务器缓存" else ""}")
    }

    suspend fun seriesSubject(subjectId: Long, subjectType: String): RemoteAnime? {
        require(subjectId in 1..Int.MAX_VALUE.toLong()) { "请输入有效的 Bangumi 作品编号" }
        val response = get(networkSettings(), "/v0/subjects/$subjectId")
        val row = JSONObject(response)
        if (row.optInt("type") != if (subjectType == "book") 1 else 2) return null
        return row.toRemoteAnime(subjectType)?.withNormalizedImageUrl()
    }

    suspend fun seriesRelations(subjectId: Long): List<SeriesSourceWork> {
        require(subjectId in 1..Int.MAX_VALUE.toLong()) { "作品来源编号无效，请先核对来源" }
        return parseSeriesSourceWorks(get(networkSettings(), "/v0/subjects/$subjectId/subjects"))
            .filter { it.id != subjectId.toString() }
    }

    suspend fun relatedSubjects(subjectId: Long, subjectType: String): List<RemoteAnime> {
        require(subjectId > 0)
        val expectedType = if (normalizeSubjectType(subjectType) == "book") 1 else 2
        val response = get(networkSettings(), "/v0/subjects/$subjectId/subjects")
        val rows = publicArray(response)
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                if (row.optInt("type") != expectedType || row.optString("relation") !in SERIES_RELATIONS) continue
                row.toRemoteAnime(subjectType)?.let { add(it.withNormalizedImageUrl()) }
            }
        }.distinctBy { it.importId }
    }

    suspend fun subject(subjectId: Long, subjectType: String = "anime"): RemoteAnime? {
        if (subjectId <= 0) return null
        val settings = networkSettings()
        val response = get(settings, "/v0/subjects/$subjectId")
        return listOfNotNull(JSONObject(response).toRemoteAnime(normalizeSubjectType(subjectType))?.withNormalizedImageUrl()).withPublicCache(response).firstOrNull()
    }

    suspend fun searchCharacters(
        query: String,
        limit: Int = 24,
    ): List<RemoteCharacter> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return emptyList()
        val settings = networkSettings()
        val response = post(
            settings,
            "/v0/search/characters?limit=${limit.coerceIn(1, 50)}&offset=0",
            JSONObject()
                .put("keyword", keyword)
                .put("filter", JSONObject())
                .toString(),
        )
        return parseCharacterSearchResponse(response).map { character ->
            character.copy(imageUrl = normalizeBangumiImageUrl(character.imageUrl))
        }
    }

    suspend fun character(characterId: Long): RemoteCharacter? {
        if (characterId <= 0) return null
        val settings = networkSettings()
        val response = get(settings, "/v0/characters/$characterId")
        return JSONObject(response).toRemoteCharacter()?.let { character ->
            character.copy(imageUrl = normalizeBangumiImageUrl(character.imageUrl))
        }
    }

    suspend fun subjectCharacters(subjectId: Long): List<RemoteCharacter> {
        require(subjectId > 0) { "作品 ID 无效" }
        return parseSubjectCharacters(get(networkSettings(), "/v0/subjects/$subjectId/characters"))
    }

    suspend fun subjectPersons(subjectId: Long): BangumiStaffPage {
        require(subjectId in 1..Int.MAX_VALUE.toLong()) { "作品编号无效" }
        return parseBangumiStaff(get(networkSettings(), "/v0/subjects/$subjectId/persons"))
    }

    internal fun parseSubjectCharacters(response: String): List<RemoteCharacter> {
        val rows = publicArray(response)
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            row.toRemoteCharacter()?.copy(roleName = row.optString("relation").takeIf { it.isNotBlank() && it != "null" })
        }.distinctBy { it.id }
    }

    suspend fun fetchUserAnimeCollections(
        username: String,
        pageSize: Int = 50,
        maxItems: Int = 1_000,
    ): BangumiCollectionFetchResult {
        val cleanUsername = normalizeBangumiUsername(username)
        require(cleanUsername.isNotEmpty()) { "请输入 Bangumi 用户名或 UID" }

        val safePageSize = pageSize.coerceIn(1, 50)
        val safeMaxItems = maxItems.coerceIn(safePageSize, 1_000)
        val encodedUsername = URLEncoder.encode(cleanUsername, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val collected = linkedMapOf<Long, BangumiCollectionItem>()
        var offset = 0
        var reportedTotal = 0

        while (collected.size < safeMaxItems) {
            val settings = networkSettings()
            val response = get(
                settings,
                "/v0/users/$encodedUsername/collections" +
                    "?subject_type=2&limit=$safePageSize&offset=$offset",
            )
            val page = parseCollectionPage(response, fallbackLimit = safePageSize)
            reportedTotal = maxOf(reportedTotal, page.total)
            if (page.items.isEmpty()) break

            page.items.map { it.copy(coverUrl = normalizeBangumiImageUrl(it.coverUrl)) }.forEach { item ->
                if (collected.size < safeMaxItems) collected.putIfAbsent(item.subjectId, item)
            }
            if (collected.size >= reportedTotal || page.items.size < page.limit) break
            offset += page.limit.coerceAtLeast(1)
        }

        val items = collected.values.take(safeMaxItems)
        return BangumiCollectionFetchResult(
            items = items,
            reportedTotal = maxOf(reportedTotal, items.size),
            truncated = reportedTotal > items.size,
        )
    }

    /**
     * token 直连「我的收藏」（`/v0/users/-/collections`）。与 [fetchUserAnimeCollections]
     * 同一解析与分页逻辑，但带 Authorization，可读取私密收藏。401 原样抛给上层做
     * 「换新令牌重试一次 → 降级公开路径」处理。
     */
    suspend fun fetchMyAnimeCollections(
        accessToken: String,
        pageSize: Int = 50,
        maxItems: Int = 1_000,
    ): BangumiCollectionFetchResult {
        require(accessToken.isNotBlank()) { "Bangumi 授权已失效，请重新连接" }
        return fetchCollectionPages(pageSize, maxItems) { offset, limit ->
            getWithAuth(
                networkSettings(),
                "/v0/users/-/collections" +
                    "?subject_type=2&limit=$limit&offset=$offset",
                accessToken,
            )
        }
    }

    /**
     * 与 [fetchMyAnimeCollections] 同一分页与解析，页面由调用方提供
     * （例如经 AniMeow 服务器中转读取私密收藏）。[pageLoader] 返回 Bangumi 收藏页 JSON。
     */
    suspend fun fetchCollectionPages(
        pageSize: Int = 50,
        maxItems: Int = 1_000,
        pageLoader: suspend (offset: Int, limit: Int) -> String,
    ): BangumiCollectionFetchResult {
        val safePageSize = pageSize.coerceIn(1, 50)
        val safeMaxItems = maxItems.coerceIn(safePageSize, 1_000)
        val collected = linkedMapOf<Long, BangumiCollectionItem>()
        var offset = 0
        var reportedTotal = 0

        while (collected.size < safeMaxItems) {
            val response = pageLoader(offset, safePageSize)
            val page = parseCollectionPage(response, fallbackLimit = safePageSize)
            reportedTotal = maxOf(reportedTotal, page.total)
            if (page.items.isEmpty()) break

            page.items.map { it.copy(coverUrl = normalizeBangumiImageUrl(it.coverUrl)) }.forEach { item ->
                if (collected.size < safeMaxItems) collected.putIfAbsent(item.subjectId, item)
            }
            if (collected.size >= reportedTotal || page.items.size < page.limit) break
            offset += page.limit.coerceAtLeast(1)
        }

        val items = collected.values.take(safeMaxItems)
        return BangumiCollectionFetchResult(
            items = items,
            reportedTotal = maxOf(reportedTotal, items.size),
            truncated = reportedTotal > items.size,
        )
    }

    /**
     * 剧集索引：本篇（type=0）的「集数 → 剧集 ID」。写观看进度用剧集 ID，
     * 非整数集数（如 6.5）无法安全映射，P0 阶段跳过。
     */
    suspend fun fetchEpisodeIndex(subjectId: Long): Map<Int, Long> {
        require(subjectId > 0) { "作品 ID 无效" }
        val collected = linkedMapOf<Int, Long>()
        var offset = 0
        while (offset < EPISODE_INDEX_MAX) {
            val settings = networkSettings()
            val response = get(settings, "/v0/episodes?subject_id=$subjectId&type=0&limit=100&offset=$offset")
            val root = JSONObject(response)
            val data = root.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (index in 0 until data.length()) {
                val row = data.optJSONObject(index) ?: continue
                val episodeId = row.optLong("id", -1).takeIf { it > 0 } ?: continue
                val ep = row.optDouble("ep", -1.0)
                val epNumber = ep.takeIf { it > 0 && it == Math.floor(it) }?.toInt() ?: continue
                collected.putIfAbsent(epNumber, episodeId)
            }
            if (data.length() < 100) break
            offset += 100
        }
        return collected
    }

    /**
     * Read-only episode labels for detail/editor UI. Never changes the ep-based write index.
     * A full, unambiguous list matching the local season total is required before conversion.
     */
    suspend fun fetchEpisodeNumbering(subjectId: Long, expectedTotal: Int): EpisodeNumbering? {
        if (subjectId <= 0 || expectedTotal !in 1..EPISODE_INDEX_MAX) return null
        val key = subjectId to expectedTotal
        EpisodeNumberingCache.get(key)?.let { return it.numbering }
        val rows = mutableListOf<EpisodeNumberMetadata>()
        val settings = networkSettings()
        var offset = 0
        while (offset < expectedTotal) {
            val root = JSONObject(get(settings, "/v0/episodes?subject_id=$subjectId&type=0&limit=100&offset=$offset"))
            if (root.optInt("total", -1) != expectedTotal) return EpisodeNumberingCache.put(key, null)
            val data = root.optJSONArray("data") ?: return EpisodeNumberingCache.put(key, null)
            if (data.length() == 0 || data.length() > expectedTotal - offset) return EpisodeNumberingCache.put(key, null)
            for (index in 0 until data.length()) {
                val row = data.optJSONObject(index) ?: return EpisodeNumberingCache.put(key, null)
                rows += EpisodeNumberMetadata(row.optLong("id", -1), row.optInt("type", -1), row.optDouble("sort", Double.NaN))
            }
            offset += data.length()
        }
        return EpisodeNumberingCache.put(key, EpisodeNumbering.fromEpisodes(rows, expectedTotal))
    }

    /** 修改自己的收藏（状态/评分）。type 与 rate 都可空，只传需要改动的字段。 */
    suspend fun modifySelfCollection(accessToken: String, subjectId: Long, type: Int?, rate: Int?) {
        require(subjectId > 0) { "作品 ID 无效" }
        require(type != null || rate != null) { "没有需要同步的收藏变更" }
        val body = JSONObject().apply {
            type?.let { put("type", it) }
            rate?.let { put("rate", it) }
        }.toString()
        postWithAuth(networkSettings(), "/v0/users/-/collections/$subjectId", body, accessToken)
    }

    /**
     * 单集观看状态（0 未看 / 2 看过）。批量接口是 PATCH，
     * 而 HttpURLConnection 不支持 PATCH，因此逐集使用 PUT。
     */
    suspend fun setEpisodeCollection(accessToken: String, episodeId: Long, type: Int) {
        require(episodeId > 0) { "剧集 ID 无效" }
        putWithAuth(
            networkSettings(),
            "/v0/users/-/collections/-/episodes/$episodeId",
            JSONObject().put("type", type).toString(),
            accessToken,
        )
    }

    internal fun parseSearchResponse(json: String, subjectType: String = "anime"): List<RemoteAnime> {
        val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toRemoteAnime(subjectType)?.let(::add)
            }
        }.withPublicCache(json)
    }

    internal fun parseCalendarResponse(json: String): List<RemoteAnime> {
        val days = publicArray(json)
        val seen = hashSetOf<String>()
        return buildList {
            for (dayIndex in 0 until days.length()) {
                val day = days.optJSONObject(dayIndex) ?: continue
                val weekday = day.optJSONObject("weekday")?.optInt("id")?.takeIf { it in 1..7 }
                val items = day.optJSONArray("items") ?: continue
                for (itemIndex in 0 until items.length()) {
                    val remote = items.optJSONObject(itemIndex)?.toRemoteAnime("anime") ?: continue
                    if (seen.add(remote.id)) add(remote.copy(broadcastDay = weekday))
                }
            }
        }.withPublicCache(json)
    }

    internal fun parseCharacterSearchResponse(json: String): List<RemoteCharacter> {
        val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toRemoteCharacter()?.let(::add)
            }
        }
    }

    internal fun parseCollectionPage(
        json: String,
        fallbackLimit: Int = 50,
    ): BangumiCollectionPage {
        val trimmed = json.trim()
        val rootObject = trimmed.takeIf { it.startsWith("{") }?.let(::JSONObject)
        val data = rootObject?.optJSONArray("data")
            ?: trimmed.takeIf { it.startsWith("[") }?.let(::JSONArray)
            ?: JSONArray()
        val items = buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toBangumiCollectionItem()?.let(::add)
            }
        }
        val total = rootObject?.optInt("total", items.size)?.coerceAtLeast(items.size) ?: items.size
        val limit = rootObject?.optInt("limit", fallbackLimit)
            ?.takeIf { it > 0 }
            ?: fallbackLimit.coerceAtLeast(1)
        return BangumiCollectionPage(items = items, total = total, limit = limit)
    }

    private fun JSONObject.toRemoteAnime(subjectType: String): RemoteAnime? {
        val id = optLong("id", -1).takeIf { it > 0 }?.toString() ?: return null
        val original = optString("name").takeIf { it.isNotBlank() && it != "null" }
        val localized = optString("name_cn").takeIf { it.isNotBlank() && it != "null" }
        val title = localized ?: original ?: return null
        val images = optJSONObject("images")
        val rating = optJSONObject("rating")
        val tagsJson = optJSONArray("tags")
        val tags = buildList {
            if (tagsJson != null) {
                for (index in 0 until tagsJson.length()) {
                    tagsJson.optJSONObject(index)?.optString("name")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }
        val episodeCount = optInt("total_episodes", 0).takeIf { it > 0 }
            ?: optInt("eps", 0).takeIf { it > 0 }
        return RemoteAnime(
            source = RemoteCatalogSource.BANGUMI,
            id = id,
            title = title,
            originalTitle = original?.takeUnless { it == title },
            coverUrl = normalizeBangumiImageUrl(
                images?.optString("large")?.takeIf(String::isNotBlank)
                    ?: images?.optString("common")?.takeIf(String::isNotBlank),
            ),
            summary = optString("summary").takeIf(String::isNotBlank),
            score = rating?.optDouble("score", 0.0)?.takeIf { it > 0.0 },
            ratingCount = rating?.optInt("total", -1)?.takeIf { it >= 0 },
            sourceRank = rating?.optInt("rank", 0)?.takeIf { it > 0 },
            popularity = optJSONObject("collection")?.let { values ->
                listOf("wish", "collect", "doing", "on_hold", "dropped").sumOf { values.optInt(it, 0).coerceAtLeast(0).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            },
            episodes = episodeCount,
            airDate = optString("date").takeIf { it.isNotBlank() && it != "null" }
                ?: optString("air_date").takeIf { it.isNotBlank() && it != "null" },
            tags = tags.take(12),
            format = optString("platform").takeIf(String::isNotBlank),
            subjectType = normalizeSubjectType(subjectType),
            siteUrl = "https://bgm.tv/subject/$id",
            localizedTitles = buildMap {
                original?.let { put(if (Regex("[\\u3040-\\u30ff]").containsMatchIn(it)) "ja" else "und", it) }
                localized?.let { put("zh", it) }
            },
            titleLanguage = if (localized != null) "zh" else "und",
            localizedSummaries = optString("summary").takeIf { it.isNotBlank() && it != "null" }?.let {
                mapOf((if (Regex("[\\u3040-\\u30ff]").containsMatchIn(it)) "ja" else "zh") to it)
            } ?: emptyMap(),
            summaryLanguage = if (Regex("[\\u3040-\\u30ff]").containsMatchIn(optString("summary"))) "ja" else "zh",
            ratingDistribution = rating?.optJSONObject("count")?.let { counts ->
                buildMap {
                    for (score in 1..10) {
                        counts.optInt(score.toString(), -1).takeIf { it >= 0 }?.let { put(score, it) }
                    }
                }
            } ?: emptyMap(),
        )
    }

    private fun JSONObject.toBangumiCollectionItem(): BangumiCollectionItem? {
        val subject = optJSONObject("subject") ?: JSONObject()
        val subjectId = subject.optLong("id", -1).takeIf { it > 0 }
            ?: optLong("subject_id", -1).takeIf { it > 0 }
            ?: return null
        val originalTitle = subject.optString("name").trim()
        val localizedTitle = subject.optString("name_cn").trim()
        val title = localizedTitle.ifEmpty { originalTitle }.ifEmpty { return null }
        val images = subject.optJSONObject("images")
        val rating = subject.optJSONObject("rating")
        val rawTags = optJSONArray("tags")
        val tags = buildList {
            if (rawTags != null) {
                for (index in 0 until rawTags.length()) {
                    rawTags.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        }.distinctBy { it.lowercase(java.util.Locale.ROOT) }
        val totalEpisodes = subject.optInt("total_episodes", 0).takeIf { it > 0 }
            ?: subject.optInt("eps", 0).takeIf { it > 0 }
            ?: 0
        return BangumiCollectionItem(
            subjectId = subjectId,
            collectionType = optInt("type", 1),
            title = title,
            originalTitle = originalTitle.ifEmpty { title },
            coverUrl = normalizeBangumiImageUrl(
                images?.optString("large")?.takeIf(String::isNotBlank)
                    ?: images?.optString("medium")?.takeIf(String::isNotBlank)
                    ?: images?.optString("common")?.takeIf(String::isNotBlank),
            ),
            airDate = subject.optString("date").trim().takeIf(String::isNotEmpty),
            watchedEpisodes = optInt("ep_status", 0).coerceAtLeast(0),
            totalEpisodes = totalEpisodes,
            subjectScore = rating?.optDouble("score", 0.0)?.takeIf { it > 0.0 },
            userRate = optInt("rate", 0).takeIf { it > 0 },
            comment = optString("comment").trim().takeIf(String::isNotEmpty),
            tags = tags,
            updatedAt = optString("updated_at").trim().takeIf(String::isNotEmpty),
            summary = subject.optString("short_summary").trim().takeIf(String::isNotEmpty)
                ?: subject.optString("summary").trim().takeIf(String::isNotEmpty),
            format = subject.optString("platform").trim().takeIf(String::isNotEmpty),
        )
    }

    private fun JSONObject.toRemoteCharacter(): RemoteCharacter? {
        val id = optLong("id", -1).takeIf { it > 0 } ?: return null
        val name = optString("name").takeIf(String::isNotBlank) ?: return null
        val infobox = optJSONArray("infobox") ?: JSONArray()
        val nameCn = optString("name_cn").takeIf(String::isNotBlank)
            ?: infobox.findText("简体中文名", "中文名", "Chinese name")
        val gender = jsonText(opt("gender"))
            ?: infobox.findText("性别", "Gender")
        val bloodType = optString("blood_type").takeIf(String::isNotBlank)
            ?: infobox.findText("血型", "Blood type")
        val images = optJSONObject("images")
        return RemoteCharacter(
            id = id,
            name = name,
            nameCn = nameCn,
            imageUrl = normalizeBangumiImageUrl(
                images?.optString("large")?.takeIf(String::isNotBlank)
                    ?: images?.optString("medium")?.takeIf(String::isNotBlank)
                    ?: images?.optString("small")?.takeIf(String::isNotBlank),
            ),
            summary = optString("summary").takeIf(String::isNotBlank),
            gender = gender,
            birthYear = optNullableInt("birth_year"),
            birthMonth = optNullableInt("birth_mon") ?: optNullableInt("birth_month"),
            birthDay = optNullableInt("birth_day"),
            bloodType = bloodType,
            infoboxJson = infobox.toString(),
        )
    }

    private fun JSONArray.findText(vararg keys: String): String? {
        for (index in 0 until length()) {
            val row = optJSONObject(index) ?: continue
            val key = row.optString("key")
            if (keys.none { it.equals(key, ignoreCase = true) }) continue
            jsonText(row.opt("value"))?.takeIf(String::isNotBlank)?.let { return it }
        }
        return null
    }

    private fun JSONObject.optNullableInt(key: String): Int? = when (val value = opt(key)) {
        is Number -> value.toInt().takeIf { it > 0 }
        is String -> value.toIntOrNull()?.takeIf { it > 0 }
        else -> null
    }

    private fun jsonText(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim().takeIf(String::isNotEmpty)
        is Number, is Boolean -> value.toString()
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                jsonText(value.opt(index))?.let(::add)
            }
        }.joinToString(" / ").takeIf(String::isNotBlank)
        is JSONObject -> jsonText(value.opt("v"))
            ?: jsonText(value.opt("value"))
            ?: value.toString().takeIf { it != "{}" }
        else -> value.toString().takeIf(String::isNotBlank)
    }

    private suspend fun networkSettings(): DiscoveryNetworkSettings =
        (preferences?.snapshot() ?: DiscoveryNetworkSettings()).let { settings ->
            modeOverride?.let { settings.copy(bangumiApiMode = it) } ?: settings
        }

    private suspend fun get(settings: DiscoveryNetworkSettings, path: String): String {
        if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER &&
            !path.startsWith("/v0/users/")) return PublicMetadataService().bangumi("GET", path)
        return executeByMode(
            settings = settings,
            label = path,
            official = { client.get(OFFICIAL_BASE_URL + path) },
            proxy = { client.get(settings.bangumiApiProxyBase.trimEnd('/') + path) },
        )

    }

    private suspend fun getWithAuth(
        settings: DiscoveryNetworkSettings,
        path: String,
        accessToken: String,
    ): String {
        val headers = mapOf("Authorization" to "Bearer $accessToken")
        return executeByMode(
            settings = settings,
            label = path,
            official = { client.get(OFFICIAL_BASE_URL + path, headers) },
            proxy = { client.get(settings.bangumiApiProxyBase.trimEnd('/') + path, headers) },
        )
    }

    private suspend fun postWithAuth(
        settings: DiscoveryNetworkSettings,
        path: String,
        body: String,
        accessToken: String,
    ): String {
        val headers = mapOf("Authorization" to "Bearer $accessToken")
        return executeByMode(
            settings = settings,
            label = path,
            official = { client.post(OFFICIAL_BASE_URL + path, body, headers) },
            proxy = { client.post(settings.bangumiApiProxyBase.trimEnd('/') + path, body, headers) },
        )
    }

    private suspend fun putWithAuth(
        settings: DiscoveryNetworkSettings,
        path: String,
        body: String,
        accessToken: String,
    ): String {
        val headers = mapOf("Authorization" to "Bearer $accessToken")
        return executeByMode(
            settings = settings,
            label = path,
            official = { client.put(OFFICIAL_BASE_URL + path, body, headers) },
            proxy = { client.put(settings.bangumiApiProxyBase.trimEnd('/') + path, body, headers) },
        )
    }

    private suspend fun post(settings: DiscoveryNetworkSettings, path: String, body: String): String {
        if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER) return PublicMetadataService().bangumi("POST", path, body)
        return executeByMode(
            settings = settings,
            label = path,
            official = { client.post(OFFICIAL_BASE_URL + path, body) },
            proxy = { client.post(settings.bangumiApiProxyBase.trimEnd('/') + path, body) },
        )

    }

    private suspend fun <T> executeByMode(
        settings: DiscoveryNetworkSettings,
        label: String,
        official: suspend () -> T,
        proxy: suspend () -> T,
    ): T = when (settings.bangumiApiMode) {
        BangumiApiMode.OFFICIAL_ONLY -> official()
        BangumiApiMode.PROXY_ONLY -> proxy()
        BangumiApiMode.OFFICIAL_FIRST -> preferred(label, official, proxy)
        BangumiApiMode.PROXY_FIRST -> preferred(label, proxy, official)
        BangumiApiMode.AUTO -> preferred(label, official, proxy)
    }

    private suspend fun <T> preferred(
        label: String,
        first: suspend () -> T,
        fallback: suspend () -> T,
    ): T {
        val firstResult = runCatchingCancellable { first() }
        if (firstResult.isSuccess) return firstResult.getOrThrow()
        val firstError = firstResult.exceptionOrNull()!!
        if (!shouldTryRemoteFallback(firstError)) throw firstError
        return runCatchingCancellable { fallback() }.getOrElse { secondError ->
            // 两条线路的状态码都要留给上层：否则"用户不存在"和"线路故障"会被压成同一句话。
            if (firstError !== secondError) secondError.addSuppressed(firstError)
            throw IllegalStateException(
                "Bangumi $label 的首选与备用接口均不可用：${secondError.message.orEmpty()}",
                secondError,
            )
        }
    }

    private fun List<RemoteAnime>.normalizeImageUrls(): List<RemoteAnime> =
        map { it.withNormalizedImageUrl() }

    private fun RemoteAnime.withNormalizedImageUrl(): RemoteAnime =
        copy(coverUrl = normalizeBangumiImageUrl(coverUrl))

    private fun applyClientFilters(items: List<RemoteAnime>, filters: DiscoveryFilters): List<RemoteAnime> =
        applyDiscoveryFilters(items, filters, RemoteCatalogSource.BANGUMI)

    private companion object {
        const val OFFICIAL_BASE_URL = "https://api.bgm.tv"
        const val EPISODE_INDEX_MAX = 2_000
    }
}
