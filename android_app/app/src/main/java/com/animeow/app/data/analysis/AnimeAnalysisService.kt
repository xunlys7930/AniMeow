package com.animeow.app.data.analysis

import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.CloudAccountService
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.local.AnimeAnalysisRecordEntity
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.remote.JsonHttpClient
import com.animeow.app.data.remote.RemoteHttpException
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject

data class AnimeAnalysisStatsPreview(
    val totalEntries: Int = 0,
    val completedEntries: Int = 0,
    val watchedEpisodes: Int = 0,
    val averageRating: Double? = null,
    val activeDays: Int = 0,
    val topTags: List<Pair<String, Int>> = emptyList(),
)

data class BuiltAnimeAnalysisStats(
    val json: JSONObject,
    val preview: AnimeAnalysisStatsPreview,
)

internal class AnimeAnalysisService(
    database: AniMeowDatabase,
    private val cloudService: CloudAccountService,
    private val customClient: JsonHttpClient = JsonHttpClient(readTimeoutMs = 60_000),
) {
    private val dao = database.libraryDao()

    fun observeRecords() = dao.observeAnimeAnalysisRecords()

    suspend fun buildStats(): BuiltAnimeAnalysisStats {
        val animes = dao.getActiveAnimes()
        val tags = dao.getTags()
        val links = dao.getAnimeTags()
        val records = dao.getWatchRecords()
        val statusCounts = animes.groupingBy { it.status.ifBlank { "未分类" } }.eachCount()
        val typeCounts = animes.groupingBy { normalizeSubjectType(it.subjectType) }.eachCount()
        val studioCounts = animes.mapNotNull { it.studio?.trim()?.takeIf(String::isNotEmpty) }
            .groupingBy(String::toString).eachCount()
        val yearCounts = animes.mapNotNull { it.airDate?.take(4)?.toIntOrNull()?.toString() }
            .groupingBy(String::toString).eachCount()
        val ratingDistribution = animes.mapNotNull(::ratingLabel).groupingBy(String::toString).eachCount()
        val numericRatings = animes.mapNotNull(AnimeEntity::rating)
            .filter { it > 0 }
            .map { it / 10.0 }
        val tagNames = tags.associate { it.id to it.name }
        val tagCounts = links.groupingBy { it.tagId }.eachCount()
            .mapNotNull { (id, count) -> tagNames[id]?.let { it to count } }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        val recordDates = records.mapNotNull { parseDate(it.recordDate) }.sorted()
        val recentMonths = recordDates.groupingBy { YearMonth.from(it).toString() }.eachCount()
        val watchedEpisodes = animes.sumOf(AnimeEntity::watchedEpisodes)
        val totalEpisodes = animes.sumOf(AnimeEntity::totalEpisodes)
        val completed = animes.count { it.status == "看完" }

        val json = JSONObject()
            .put("schema_version", 1)
            .put("generated_at", Instant.now().toString())
            .put("source", "animeow_android")
            .put(
                "request",
                JSONObject()
                    .put("language", "zh-CN")
                    .put("tone", "cute")
                    .put("task", "anime_habit_style_analysis"),
            )
            .put(
                "privacy",
                JSONObject()
                    .put("scope", "summary_only")
                    .put("contains_reviews", false)
                    .put("contains_cover_urls", false)
                    .put("contains_api_keys", false),
            )
            .put(
                "summary",
                JSONObject()
                    .put("total_entries", animes.size)
                    .put("completed_entries", completed)
                    .put("rated_entries", animes.count { ratingLabel(it) != null })
                    .put("numeric_rated_entries", numericRatings.size)
                    .put("average_rating", numericRatings.takeIf(List<Double>::isNotEmpty)?.average() ?: JSONObject.NULL)
                    .put("watched_episodes", watchedEpisodes)
                    .put("total_episodes", totalEpisodes)
                    .put(
                        "episode_completion_rate",
                        if (totalEpisodes == 0) JSONObject.NULL else watchedEpisodes.toDouble() / totalEpisodes,
                    ),
            )
            .put("status_counts", sortedEntries(statusCounts))
            .put("subject_type_counts", sortedEntries(typeCounts))
            .put("top_tags", pairEntries(tagCounts.take(20)))
            .put("top_studios", sortedEntries(studioCounts, 15))
            .put("air_year_counts", sortedEntries(yearCounts, 20))
            .put("rating_distribution", sortedEntries(ratingDistribution))
            .put(
                "watch_activity",
                JSONObject()
                    .put("record_count", records.size)
                    .put("active_days", recordDates.toSet().size)
                    .put("first_record_date", recordDates.firstOrNull()?.toString() ?: JSONObject.NULL)
                    .put("last_record_date", recordDates.lastOrNull()?.toString() ?: JSONObject.NULL)
                    .put("recent_month_counts", sortedEntries(recentMonths, 6)),
            )
        return BuiltAnimeAnalysisStats(
            json = json,
            preview = AnimeAnalysisStatsPreview(
                totalEntries = animes.size,
                completedEntries = completed,
                watchedEpisodes = watchedEpisodes,
                averageRating = numericRatings.takeIf(List<Double>::isNotEmpty)?.average(),
                activeDays = recordDates.toSet().size,
                topTags = tagCounts.take(5),
            ),
        )
    }

    suspend fun requestServerAnalysis(session: CloudSession): AnimeAnalysisRecordEntity {
        val stats = buildStats().json
        require(stats.getJSONObject("summary").optInt("total_entries") > 0) { "还没有可分析的追番数据" }
        val result = cloudService.analyzeAnimeStats(session, stats, BuildConfig.VERSION_NAME)
        return saveRecord(
            AnimeAnalysisRecordEntity(
                serverRecordId = result.id,
                userId = session.userId,
                username = session.username,
                model = result.model,
                analysis = result.analysis,
                statsJson = stats.toString(),
                createdAt = result.createdAt,
            ),
        )
    }

    suspend fun requestCustomAnalysis(
        settings: AnimeAnalysisSettings,
        apiKey: String,
        session: CloudSession?,
    ): AnimeAnalysisRecordEntity {
        val stats = buildStats().json
        require(stats.getJSONObject("summary").optInt("total_entries") > 0) { "还没有可分析的追番数据" }
        val endpoint = chatCompletionsUrl(settings.endpoint)
        val prompt = buildPrompt(stats, settings.promptTemplate)
        val body = JSONObject()
            .put("model", settings.model.trim().ifBlank { error("请填写模型名") })
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", settings.systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", prompt)),
            )
            .put("temperature", settings.temperature.coerceIn(0f, 2f))
        val headers = buildMap {
            put("Accept", "application/json")
            apiKey.trim().takeIf(String::isNotEmpty)?.let { put("Authorization", "Bearer $it") }
        }
        val response = try {
            customClient.post(endpoint, body.toString(), headers)
        } catch (exception: RemoteHttpException) {
            error(extractError(exception.responseBody).ifBlank { "自定义模型请求失败（HTTP ${exception.statusCode}）" })
        }
        val root = JSONObject(response)
        val analysis = extractAssistantText(root).trim()
        require(analysis.isNotEmpty()) { "自定义模型没有返回可用的分析内容" }
        return saveRecord(
            AnimeAnalysisRecordEntity(
                userId = session?.userId ?: 0,
                username = session?.username ?: "本地用户",
                model = settings.model.trim(),
                analysis = analysis,
                statsJson = stats.toString(),
                createdAt = Instant.now().toString(),
            ),
        )
    }

    suspend fun deleteRecord(recordId: Long) = dao.deleteAnimeAnalysisRecord(recordId)

    fun buildPrompt(stats: JSONObject, template: String): String {
        val clean = template.trim().ifBlank { DEFAULT_PROMPT_TEMPLATE }
        val prettyStats = stats.toString(2)
        return if ("{stats}" in clean) clean.replace("{stats}", prettyStats)
        else "$clean\n\n追番统计摘要：\n$prettyStats"
    }

    private suspend fun saveRecord(record: AnimeAnalysisRecordEntity): AnimeAnalysisRecordEntity {
        val id = dao.insertAnimeAnalysisRecord(record)
        return record.copy(id = id)
    }

    private fun chatCompletionsUrl(rawEndpoint: String): String {
        var clean = rawEndpoint.trim().trimEnd('/')
        require(clean.isNotEmpty()) { "请填写模型接口地址" }
        val uri = runCatching { URI(clean) }.getOrNull() ?: error("模型接口地址格式不正确")
        val localHost = uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        require(uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && localHost)) {
            "自定义模型需使用 HTTPS；本机调试地址除外"
        }
        if (!clean.endsWith("/chat/completions")) clean += "/chat/completions"
        return clean
    }

    private fun extractAssistantText(root: JSONObject): String {
        val choices = root.optJSONArray("choices")
        val first = choices?.optJSONObject(0)
        val content = first?.optJSONObject("message")?.opt("content")
        if (content is String) return content
        if (content is JSONArray) {
            return buildString {
                for (index in 0 until content.length()) {
                    when (val part = content.opt(index)) {
                        is String -> append(part)
                        is JSONObject -> append(part.optString("text"))
                    }
                }
            }
        }
        first?.optString("text")?.takeIf(String::isNotBlank)?.let { return it }
        return root.optString("output_text")
    }

    private fun extractError(body: String): String = runCatching {
        val root = JSONObject(body)
        root.optJSONObject("error")?.optString("message")
            ?.takeIf(String::isNotBlank)
            ?: root.optString("message")
    }.getOrDefault("")

    private fun ratingLabel(anime: AnimeEntity): String? = when {
        !anime.ratingGrade.isNullOrBlank() -> anime.ratingGrade
        !anime.funRatingTier.isNullOrBlank() -> anime.funRatingTier
        anime.rating != null -> when (anime.rating) {
            in 90..100 -> "9.0–10.0"
            in 80..89 -> "8.0–8.9"
            in 70..79 -> "7.0–7.9"
            in 60..69 -> "6.0–6.9"
            else -> "0.1–5.9"
        }
        else -> null
    }

    private fun parseDate(raw: String?): LocalDate? {
        val clean = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return runCatching { LocalDate.parse(clean.take(10)) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(clean).toLocalDate() }.getOrNull()
    }

    private fun sortedEntries(counts: Map<String, Int>, limit: Int = Int.MAX_VALUE): JSONArray =
        pairEntries(
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(limit)
                .map { it.key to it.value },
        )

    private fun pairEntries(items: List<Pair<String, Int>>): JSONArray = JSONArray().apply {
        items.forEach { (name, count) -> put(JSONObject().put("name", name).put("count", count)) }
    }
}
