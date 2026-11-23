package com.animeow.app.data.remote

import com.animeow.app.BuildConfig
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class ServerPagedResult(
    val items: List<RemoteAnime>,
    val page: Int = 1,
    val pageSize: Int = 24,
    val total: Int = 0,
    val totalPages: Int? = null,
    val hasMore: Boolean = false,
    val filtersApplied: Boolean = false,
    val totalKnown: Boolean = false,
)

internal class ServerCatalogService(
    private val client: JsonHttpClient = JsonHttpClient(),
) {
    val isConfigured: Boolean
        get() = BuildConfig.CLOUD_API_BASE.isNotBlank()

    suspend fun seasonal(filters: DiscoveryFilters, query: String, page: Int): RemoteFetchResult {
        check(isConfigured) { "当前构建未配置新番总览服务器" }
        val url = buildString {
            append(baseUrl())
            append("/api/catalog/seasons?year=${filters.year}&month=${filters.seasonMonth}&page=$page&limit=24")
            appendQuery("sort", filters.sort.name)
            query.trim().takeIf(String::isNotEmpty)?.let { appendQuery("keyword", it) }
            filters.tag?.takeIf(String::isNotBlank)?.let { appendQuery("tag", it) }
            filters.format?.takeIf(String::isNotBlank)?.let { appendQuery("format", it) }
            filters.minimumScore?.let { appendQuery("minimumScore", (it / 10.0).toString()) }
        }
        val response = try { client.get(url, authHeaders()) } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (failure is RemoteHttpException && failure.statusCode == 404) error("服务器尚未提供新番总览，请稍后重试")
            if (failure is RemoteHttpException && failure.statusCode == 503) error("服务器尚未备好这一季度的片单，请稍后重试或选择其他季度")
            error("暂时无法获取新番片单，请检查网络后重试")
        }
        return parseSeasonalPage(response)
    }

    internal fun parseSeasonalPage(response: String): RemoteFetchResult {
        val root = JSONObject(response)
        checkSuccess(root)
        val rows = root.getJSONArray("data")
        val items = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.toRemoteAnime() }
        val pagination = root.getJSONObject("pagination")
        val snapshot = root.getJSONObject("snapshot")
        val updated = java.time.Instant.parse(snapshot.getString("updatedAt"))
            .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val stale = snapshot.optBoolean("stale")
        val excluded = snapshot.optInt("excludedCount", 0)
        return RemoteFetchResult(items = items, page = pagination.getInt("page"),
            totalPages = pagination.getInt("totalPages"), totalCount = pagination.getInt("total"),
            totalCountExact = true, hasMore = pagination.getBoolean("hasMore"),
            resultScope = "服务器整理 · Bangumi · 更新于 $updated；人气、趋势与综合排名保留来源人气顺序" +
                if (excluded > 0) "；另有 $excluded 条因日期缺失或不在本季未收录" else "",
            warnings = if (stale) listOf("片单更新暂有延迟，当前显示上次成功整理的数据") else emptyList())
    }

    suspend fun lookup(source: String, id: String): RemoteAnime? {
        if (!isConfigured) return null
        val root = JSONObject(client.get(baseUrl() + "/api/catalog/lookup?source=${encode(source)}&id=${encode(id)}", authHeaders()))
        checkSuccess(root)
        return root.optJSONObject("data")?.toRemoteAnime()
    }

    suspend fun metadataSearch(query: String, subjectType: String): List<RemoteAnime> {
        if (!isConfigured) return emptyList()
        val root = try {
            JSONObject(client.get(baseUrl() + "/api/catalog/search?q=${encode(query)}&type=${encode(subjectType)}&limit=24", authHeaders()))
        } catch (error: RemoteHttpException) {
            if (error.statusCode in listOf(404, 405, 501)) return search(query).filter { it.subjectType == subjectType }
            throw error
        }
        checkSuccess(root)
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.toRemoteAnime() }
    }

    suspend fun contribute(source: String, id: String): RemoteAnime? {
        if (!isConfigured) return null
        val body = JSONObject().put("source", source).put("id", id).toString()
        val root = JSONObject(client.post(baseUrl() + "/api/catalog/contributions", body, authHeaders()))
        checkSuccess(root)
        return root.optJSONObject("data")?.toRemoteAnime()
    }

    suspend fun proxyAniList(operation: String, variables: JSONObject): String {
        check(isConfigured) { "尚未配置 AniList 备用线路，请在数据源设置中填写代理地址" }
        return client.post(baseUrl() + "/api/catalog/proxy/anilist",
            JSONObject().put("operation", operation).put("variables", variables).toString(), authHeaders())
    }

    suspend fun search(
        query: String,
        filters: DiscoveryFilters = DiscoveryFilters(),
        limit: Int = 50,
    ): List<RemoteAnime> {
        require(isConfigured) { "当前构建未配置资料库服务器或访问令牌" }
        return searchPaged(query, filters, page = 1, pageSize = limit).items
    }

    suspend fun searchPaged(
        query: String,
        filters: DiscoveryFilters = DiscoveryFilters(),
        page: Int = 1,
        pageSize: Int = 24,
        subjectType: String? = null,
    ): ServerPagedResult {
        require(isConfigured) { "当前构建未配置资料库服务器或访问令牌" }
        return searchPage(query, filters, limit = pageSize.coerceIn(1, 100), page = page.coerceAtLeast(1), subjectType = subjectType)
    }

    private suspend fun searchPage(
        query: String,
        filters: DiscoveryFilters,
        limit: Int,
        page: Int = 1,
        subjectType: String? = null,
    ): ServerPagedResult {
        val offset = (page - 1) * limit
        val url = buildString {
            append(baseUrl())
            append("/api/search?limit=${limit.coerceIn(1, 100)}&page=$page&offset=$offset")
            subjectType?.let { appendQuery("subjectType", it) }
            filters.minimumScore?.let { appendQuery("minimumScore", (it / 10.0).toString()) }
            query.trim().takeIf(String::isNotEmpty)?.let { appendQuery("keyword", it) }
            filters.tag?.takeIf(String::isNotBlank)?.let { appendQuery("tag", it) }
            filters.year?.let { appendQuery("year", it.toString()) }
            filters.seasonMonth?.takeIf { filters.year != null }?.let { appendQuery("month", it.toString()) }
            filters.format?.takeIf(String::isNotBlank)?.let { appendQuery("format", it) }
            appendQuery(
                "sort",
                when (filters.sort) {
                    DiscoverySort.SCORE, DiscoverySort.RANK, DiscoverySort.POPULARITY -> "score"
                    DiscoverySort.NEWEST -> "air_date"
                    DiscoverySort.TRENDING -> "trending"
                },
            )
        }
        val root = JSONObject(client.get(url, authHeaders()))
        checkSuccess(root)
        val data = root.optJSONArray("data") ?: JSONArray()
        val rawItems = buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toRemoteAnime()?.let(::add)
            }
        }
        val metadata = parseServerDiscoveryPageInfo(root.optJSONObject("pagination"), page, limit, data.length())
        // Yearless seasons are intentionally not sent to old servers, whose month predicate
        // would otherwise discard valid dates before we can apply the shared quarter boundary.
        val localSeason = filters.seasonMonth != null && filters.year == null
        val localFormat = !filters.format.isNullOrBlank()
        val filteredItems = if (metadata.filtersApplied && !localSeason && !localFormat) rawItems else {
            val remaining = if (metadata.filtersApplied) DiscoveryFilters(
                seasonMonth = filters.seasonMonth.takeIf { localSeason },
                format = filters.format, sort = DiscoverySort.TRENDING,
            ) else filters
            applyDiscoveryFilters(rawItems, remaining, RemoteCatalogSource.SERVER)
                .filter { subjectType == null || com.animeow.app.data.local.normalizeSubjectType(it.subjectType) == subjectType }
        }
        return metadata.copy(items = filteredItems, filtersApplied = metadata.filtersApplied && !localSeason && !localFormat)
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

    /**
     * 请求服务器后台刷新指定动画的封面。
     *
     * 服务器会异步从多数据源（Bangumi / AniList）查找封面并回填到数据库，
     * 接口立即返回，不阻塞客户端。下次刷新发现列表时即可看到新封面。
     *
     * @param source 数据源标识（如 "bangumi" / "anilist"）
     * @param id 数据源中的作品 ID
     * @param title 作品标题（备用搜索关键词）
     */
    suspend fun refreshCover(source: String?, id: String?, title: String?) {
        if (!isConfigured) return
        val body = JSONObject()
        if (!source.isNullOrBlank() && !id.isNullOrBlank()) {
            body.put("source", source).put("id", id)
        }
        if (!title.isNullOrBlank()) {
            body.put("title", title)
        }
        if (body.length() == 0) return
        runCatching {
            client.post(baseUrl() + "/api/catalog/cover-refresh", body.toString(), authHeaders())
        }
    }

    /**
     * 从服务器搜索角色（多数据源 + 服务器缓存）。
     *
     * 服务器会从 Bangumi 和 AniList 搜索角色，并将结果缓存到服务器，
     * 下次搜索同样关键词时直接从服务器返回，速度更快。
     *
     * @param query 搜索关键词
     * @param limit 返回数量上限
     * @return 角色列表（RemoteCharacter 格式），失败时返回空列表
     */
    suspend fun searchCharacters(query: String, limit: Int = 24): List<RemoteCharacter> {
        if (query.isBlank()) return emptyList()
        return requestCharacters("/api/character/search?q=${encode(query.trim())}&limit=${limit.coerceIn(1, 50)}")?.first.orEmpty()
    }

    suspend fun getAnimeCharactersByBgmId(bgmId: Long): Pair<List<RemoteCharacter>, Boolean>? =
        getAnimeCharactersBySource("bangumi", bgmId)

    suspend fun getAnimeCharactersBySource(source: String, id: Long): Pair<List<RemoteCharacter>, Boolean>? {
        if (source !in setOf("bangumi", "anilist") || id <= 0) return null
        return requestCharacters("/api/catalog/$source/$id/characters")
    }

    /** animeId is the server catalog ID, never a local Room row ID. */
    suspend fun getAnimeCharacters(animeId: Long): Pair<List<RemoteCharacter>, Boolean>? {
        if (animeId <= 0) return null
        return requestCharacters("/api/catalog/anime/$animeId/characters")
    }

    private suspend fun requestCharacters(path: String): Pair<List<RemoteCharacter>, Boolean>? {
        if (!isConfigured) return null
        return com.animeow.app.util.runCatchingCancellable {
            val root = JSONObject(client.get(baseUrl() + path, authHeaders()))
            if (root.optString("status") != "success") return@runCatchingCancellable null
            val data = root.optJSONObject("data") ?: return@runCatchingCancellable null
            val characters = data.optJSONArray("characters") ?: return@runCatchingCancellable null
            val list = buildList {
                for (i in 0 until characters.length()) {
                    val item = characters.optJSONObject(i) ?: continue
                    parseCatalogCharacter(item)?.let(::add)
                }
            }
            if (characters.length() > 0 && list.isEmpty()) return@runCatchingCancellable null
            list to data.optBoolean("cached", false)
        }.getOrNull()
    }

    /**
     * 将角色与动画的关联同步到服务器。
     *
     * 客户端从远程数据源获取角色后，可以调用此接口将角色信息和关联关系同步到服务器，
     * 方便后续直接从服务器快速获取角色数据。
     *
     * @param animeId 动画在服务器数据库中的 ID
     * @param character 角色数据
     * @param roleName 角色名/职位（如"主角"、"配角"等）
     * @param sortOrder 排序序号
     */
    suspend fun linkCharacterToAnime(
        animeId: Long,
        character: RemoteCharacter,
        source: String = character.source.key,
        roleName: String? = null,
        sortOrder: Int = 0,
    ): Boolean {
        if (!isConfigured || animeId <= 0) return false
        return runCatching {
            val body = JSONObject()
                .put("anime_id", animeId)
                .put("role_name", roleName ?: JSONObject.NULL)
                .put("sort_order", sortOrder)
                .put("character", JSONObject().apply {
                    put("id", character.id)
                    put("source", source)
                    put("name", character.name)
                    put("name_cn", character.nameCn ?: JSONObject.NULL)
                    put("image_url", character.imageUrl ?: JSONObject.NULL)
                    put("summary", character.summary ?: JSONObject.NULL)
                    put("gender", character.gender ?: JSONObject.NULL)
                })
            val root = JSONObject(client.post("${baseUrl()}/api/character/link", body.toString(), authHeaders()))
            root.optString("status") == "success"
        }.getOrDefault(false)
    }

    private fun JSONObject.toRemoteAnime(): RemoteAnime? {
        val databaseId = optLong("id", -1).takeIf { it > 0 }?.toString() ?: return null
        val titles = localizedStrings("titles")
        val summaries = localizedStrings("summaries")
        val title = titles[titles.preferredLanguage()] ?: cleanString("name_cn")
            ?: cleanString("name") ?: cleanString("name_original")
            ?: return null
        val originSource = optString("source").takeIf(String::isNotBlank) ?: "server"
        val originId = opt("api_id")?.toString()?.takeIf(String::isNotBlank) ?: databaseId
        val totalEpisodes = optInt("total_eps", optInt("eps", 0)).takeIf { it > 0 }
        return RemoteAnime(
            source = RemoteCatalogSource.SERVER,
            id = databaseId,
            title = title,
            originalTitle = optString("name_original").takeIf(String::isNotBlank)?.takeUnless { it == title },
            coverUrl = optJSONObject("cover_urls")?.optString("proxy")?.takeIf { it.isNotBlank() && it != "null" }
                ?: optString("cover_url").takeIf(String::isNotBlank)?.replace("/api/catalog/proxy/image", "/api/catalog/images")
                ?: optString("image").takeIf(String::isNotBlank),
            summary = summaries[summaries.preferredLanguage()] ?: cleanString("summary"),
            score = optDouble("score", 0.0).takeIf { it > 0.0 },
            ratingCount = optInt("rating_count", -1).takeIf { !isNull("rating_count") && it >= 0 },
            sourceRank = optInt("source_rank", 0).takeIf { it > 0 },
            popularity = optInt("source_popularity", -1).takeIf { !isNull("source_popularity") && it >= 0 },
            episodes = totalEpisodes,
            airDate = optString("air_date").takeIf(String::isNotBlank),
            studio = optString("studio").takeIf(String::isNotBlank),
            tags = optString("tags").split(Regex("[,，/、]")).map(String::trim).filter(String::isNotBlank),
            format = optString("format").takeIf(String::isNotBlank)
                ?: optString("eps_breakdown").takeIf(String::isNotBlank),
            siteUrl = when (originSource.lowercase(java.util.Locale.ROOT)) {
                "bangumi" -> "https://bgm.tv/subject/$originId"
                "anilist" -> "https://anilist.co/anime/$originId"
                "kitsu" -> "https://kitsu.app/anime/$originId"
                "tvmaze" -> "https://www.tvmaze.com/shows/$originId"
                else -> null
            },
            originSource = originSource,
            originId = originId,
            subjectType = com.animeow.app.data.local.normalizeSubjectType(optString("subject_type")),
            catalogUpdatedAt = optString("catalog_verified_at").takeIf { it.isNotBlank() && it != "null" },
            coverUrls = listOf("bangumi", "anilist", "proxy").mapNotNull { source ->
                (optJSONObject("cover_urls")?.optString(source)?.takeIf { it.isNotBlank() && it != "null" }
                    ?: optString("cover_${source}_url").takeIf { it.isNotBlank() && it != "null" })?.let { source to it }
            }.toMap(),
            localizedTitles = titles, localizedSummaries = summaries,
            titleLanguage = cleanString("title_language"), summaryLanguage = cleanString("summary_language"),
            metadataReferences = buildSet {
                add(MetadataReference(originSource, originId))
                listOf("titles", "summaries").forEach { field ->
                    val refs = optJSONObject("language_sources")?.optJSONObject(field)
                    refs?.keys()?.forEach { language ->
                        val ref = refs.optJSONObject(language)
                        val source = ref?.cleanString("source")
                        val id = ref?.cleanString("id")
                        if (source != null && id != null) add(MetadataReference(source, id))
                    }
                }
            },
        )
    }

    private fun JSONObject.cleanString(key: String): String? = optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.localizedStrings(key: String): Map<String, String> {
        val values = optJSONObject(key) ?: return emptyMap()
        return values.keys().asSequence().take(12).mapNotNull { language -> values.cleanString(language)?.let { language to it } }.toMap()
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

/** A successful row query does not imply the separate COUNT query succeeded. */
internal fun parseServerDiscoveryPageInfo(pagination: JSONObject?, page: Int, limit: Int, rawCount: Int): ServerPagedResult {
    val total = pagination?.optInt("total", -1)?.takeIf { it >= 0 && pagination?.isNull("total") == false }
    val totalKnown = total != null && pagination?.optBoolean("totalKnown", true) == true
    val totalPages = if (totalKnown) pagination?.optInt("totalPages", -1)?.takeIf { it >= 0 }
        ?: maxOf(1, (total!! + limit - 1) / limit) else null
    val hasMore = pagination?.optBoolean("hasMore", if (totalKnown) page.toLong() * limit < total!! else rawCount == limit)
        ?: (rawCount == limit)
    return ServerPagedResult(emptyList(), page, limit, total ?: 0, totalPages, hasMore,
        filtersApplied = pagination?.optBoolean("filtersApplied", false) == true, totalKnown = totalKnown)
}
