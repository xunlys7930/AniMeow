package com.animeow.app.data.local

/**
 * 分集资料（单集标题、时长、简介、评价、评分）的输入规范与展示换算。
 *
 * 集号始终使用本季集数（1 起），与 `watchedEpisodes`、`watch_records` 的存储含义一致；
 * 连续集号只在界面上换算展示，不写入数据库。评分沿用作品评分的 0..10 一位小数口径，
 * 存成十分位（1..100），复用 [formatAnimeRating] 与 [parseAnimeRatingInput]。
 */

const val EPISODE_TITLE_MAX_LENGTH = 120
const val EPISODE_TEXT_MAX_LENGTH = 2000
const val EPISODE_DURATION_MAX_MINUTES = 1440

/** 单部作品最多枚举多少集。超过时只列出用户已记录的集号，不按异常输入生成整表。 */
const val EPISODE_ROW_LIMIT = 10_000

fun sanitizeEpisodeTitleInput(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').trim().take(EPISODE_TITLE_MAX_LENGTH)

fun sanitizeEpisodeTextInput(value: String): String =
    value.replace("\r\n", "\n").trim().take(EPISODE_TEXT_MAX_LENGTH)

/** 只接受整数分钟；允许带「分钟 / 分 / min / m」后缀。空白与非法输入都返回 null。 */
fun parseEpisodeDurationMinutes(value: String): Int? {
    val digits = value.trim()
        .removeSuffix("分钟").removeSuffix("分").removeSuffix("min").removeSuffix("m")
        .trim()
    if (digits.isEmpty() || !digits.all(Char::isDigit)) return null
    return digits.toIntOrNull()?.takeIf { it in 1..EPISODE_DURATION_MAX_MINUTES }
}

fun formatEpisodeDuration(minutes: Int?): String {
    val value = minutes?.takeIf { it in 1..EPISODE_DURATION_MAX_MINUTES } ?: return ""
    if (value < 60) return "$value 分钟"
    val hours = value / 60
    val rest = value % 60
    return if (rest == 0) "$hours 小时" else "$hours 小时 $rest 分钟"
}

/** 动画说「集」，书籍沿用原版的「话」，与观看进度的既有说法一致。 */
fun episodeUnitLabel(subjectType: String?): String = if (subjectType.isBookSubjectType()) "话" else "集"

/**
 * 分集列表要展示的集号：本季 1..totalEpisodes，加上用户已记录但超出总集数的集号
 * （总集数为 0 或曾改动时仍能看到已填内容）。总集数超过 [EPISODE_ROW_LIMIT] 时只列出已记录集号。
 */
fun episodeRowNumbers(totalEpisodes: Int, storedEpisodes: Collection<Int>): List<Int> {
    val total = totalEpisodes.coerceAtLeast(0)
    val numbers = sortedSetOf<Int>()
    if (total <= EPISODE_ROW_LIMIT) {
        for (number in 1..total) numbers += number
    }
    storedEpisodes.forEach { number -> if (number >= 1) numbers += number }
    return numbers.toList()
}

/** 总集数大到无法整季枚举时，界面要说明列表为什么只显示已记录的集号。 */
fun episodeListIsTruncated(totalEpisodes: Int): Boolean = totalEpisodes > EPISODE_ROW_LIMIT

/**
 * 把编辑框里的原始文本整理成一行可存储的分集资料。
 * 所有字段都为空时返回 null，调用方据此删除该集已有记录，而不是留下空行。
 */
fun episodeDetailOrNull(
    animeId: Long,
    episode: Int,
    title: String,
    durationMinutes: Int?,
    synopsis: String,
    review: String,
    rating: Int?,
    updatedAt: String,
): EpisodeDetailEntity? {
    if (animeId <= 0 || episode < 1) return null
    val cleanTitle = sanitizeEpisodeTitleInput(title).ifBlank { null }
    val cleanSynopsis = sanitizeEpisodeTextInput(synopsis).ifBlank { null }
    val cleanReview = sanitizeEpisodeTextInput(review).ifBlank { null }
    val cleanDuration = durationMinutes?.takeIf { it in 1..EPISODE_DURATION_MAX_MINUTES }
    val cleanRating = rating?.takeIf { it in 1..100 }
    if (cleanTitle == null && cleanSynopsis == null && cleanReview == null &&
        cleanDuration == null && cleanRating == null
    ) {
        return null
    }
    return EpisodeDetailEntity(
        animeId = animeId,
        episode = episode,
        title = cleanTitle,
        durationMinutes = cleanDuration,
        synopsis = cleanSynopsis,
        review = cleanReview,
        rating = cleanRating,
        updatedAt = updatedAt,
    )
}

/** 分集列表一行的副标题。空字符串表示这一集还没有补充任何资料。 */
fun episodeDetailSummary(detail: EpisodeDetailEntity?): String {
    if (detail == null) return ""
    val parts = mutableListOf<String>()
    formatEpisodeDuration(detail.durationMinutes).takeIf(String::isNotEmpty)?.let(parts::add)
    formatAnimeRating(detail.rating).takeIf(String::isNotEmpty)?.let { parts += "评分 $it" }
    if (!detail.synopsis.isNullOrBlank()) parts += "有简介"
    if (!detail.review.isNullOrBlank()) parts += "有评价"
    return parts.joinToString(" · ")
}
