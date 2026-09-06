package com.animeow.app.data.remote

import android.content.Context
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.preferences.BangumiApiMode
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.util.runCatchingCancellable
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

internal class BangumiService(
    private val client: JsonHttpClient = JsonHttpClient(),
    context: Context? = null,
) {
    private val preferences = context?.applicationContext?.let(::DiscoveryPreferences)
    @Volatile
    private var officialUnavailableForRun = false

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

    suspend fun subject(subjectId: Long, subjectType: String = "anime"): RemoteAnime? {
        if (subjectId <= 0) return null
        val settings = networkSettings()
        val response = get(settings, "/v0/subjects/$subjectId")
        return JSONObject(response).toRemoteAnime(normalizeSubjectType(subjectType))?.withNormalizedImageUrl()
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

    suspend fun fetchUserAnimeCollections(
        username: String,
        pageSize: Int = 50,
        maxItems: Int = 1_000,
    ): BangumiCollectionFetchResult {
        val cleanUsername = username.trim()
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

    internal fun parseSearchResponse(json: String, subjectType: String = "anime"): List<RemoteAnime> {
        val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toRemoteAnime(subjectType)?.let(::add)
            }
        }
    }

    internal fun parseCalendarResponse(json: String): List<RemoteAnime> {
        val days = JSONArray(json)
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
        }
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
        val original = optString("name").takeIf(String::isNotBlank)
        val localized = optString("name_cn").takeIf(String::isNotBlank)
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
            episodes = episodeCount,
            airDate = optString("date").takeIf { it.isNotBlank() && it != "null" }
                ?: optString("air_date").takeIf { it.isNotBlank() && it != "null" },
            tags = tags.take(12),
            format = optString("platform").takeIf(String::isNotBlank),
            subjectType = normalizeSubjectType(subjectType),
            siteUrl = "https://bgm.tv/subject/$id",
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
        preferences?.snapshot() ?: DiscoveryNetworkSettings()

    private suspend fun get(settings: DiscoveryNetworkSettings, path: String): String =
        executeByMode(
            settings = settings,
            label = path,
            official = { client.get(OFFICIAL_BASE_URL + path) },
            proxy = { client.get(settings.bangumiApiProxyBase.trimEnd('/') + path) },
        )

    private suspend fun post(settings: DiscoveryNetworkSettings, path: String, body: String): String =
        executeByMode(
            settings = settings,
            label = path,
            official = { client.post(OFFICIAL_BASE_URL + path, body) },
            proxy = { client.post(settings.bangumiApiProxyBase.trimEnd('/') + path, body) },
        )

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
        BangumiApiMode.AUTO -> if (officialUnavailableForRun) {
            preferred(label, proxy, official)
        } else {
            raceOfficialAndProxy(label, official, proxy)
        }
    }

    private suspend fun <T> preferred(
        label: String,
        first: suspend () -> T,
        fallback: suspend () -> T,
    ): T {
        val firstResult = runCatchingCancellable { first() }
        if (firstResult.isSuccess) return firstResult.getOrThrow()
        return runCatchingCancellable { fallback() }.getOrElse { secondError ->
            throw IllegalStateException(
                "Bangumi $label 的首选与备用接口均不可用：${secondError.message.orEmpty()}",
                secondError,
            )
        }
    }

    private suspend fun <T> raceOfficialAndProxy(
        label: String,
        official: suspend () -> T,
        proxy: suspend () -> T,
    ): T = supervisorScope {
        val officialTask = async { runCatchingCancellable { official() } }
        val proxyTask = async { runCatchingCancellable { proxy() } }
        val first = select<Pair<Boolean, Result<T>>> {
            officialTask.onAwait { true to it }
            proxyTask.onAwait { false to it }
        }
        if (first.second.isSuccess) {
            if (!first.first) officialUnavailableForRun = true
            if (first.first) proxyTask.cancel() else officialTask.cancel()
            return@supervisorScope first.second.getOrThrow()
        }
        val second = if (first.first) proxyTask.await() else officialTask.await()
        if (!first.first || first.first && second.isSuccess) officialUnavailableForRun = true
        second.getOrElse { error ->
            throw IllegalStateException(
                "Bangumi $label 的官方与代理接口均不可用：${error.message.orEmpty()}",
                error,
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
    }
}
