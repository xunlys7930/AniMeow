package com.animeow.app.data.community

import com.animeow.app.data.cloud.CloudSession
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * 社区改版（标签、关注、相关推荐、浏览数）的客户端模型与解析。
 *
 * 所有新字段都按“可能缺失”处理：连接未升级的服务器时，计数为 null（界面省略），
 * 关注状态为 null（隐藏关注按钮），标签接口不可用时隐藏标签行。
 */

const val COMMUNITY_MAX_POST_TAGS = 5
const val COMMUNITY_MAX_TAG_LENGTH = 16

data class CommunityProfileStats(
    val postCount: Int? = null,
    val followerCount: Int? = null,
    val followingCount: Int? = null,
    val likesReceived: Int? = null,
) {
    val isEmpty: Boolean get() = postCount == null && followerCount == null && followingCount == null && likesReceived == null
}

data class CommunityTag(val tag: String, val postCount: Int, val isSuggested: Boolean)

data class CommunityTagList(
    val items: List<CommunityTag>,
    val maxTags: Int = COMMUNITY_MAX_POST_TAGS,
    val maxLength: Int = COMMUNITY_MAX_TAG_LENGTH,
)

data class CommunityFollowResult(val following: Boolean, val followerCount: Int?)

/** 关注名单一页；nextOffset 为空表示没有更多。 */
data class CommunityFollowPage(val items: List<CommunityProfile>, val nextOffset: Int?)

/** 服务器尚未提供该接口（旧版后端）。界面应隐藏入口，而不是显示错误。 */
class CommunityFeatureUnavailableException : IllegalStateException("当前服务器暂不支持此功能")

/** 下一页游标：计数/精华排序优先用 offset，其余用 before_id。两者都为空表示没有更多。 */
data class CommunityFeedCursor(val beforeId: Long? = null, val offset: Int? = null) {
    val hasMore: Boolean get() = beforeId != null || offset != null
}

fun CommunityFeedPage.nextCursor(): CommunityFeedCursor =
    if (nextOffset != null) CommunityFeedCursor(offset = nextOffset) else CommunityFeedCursor(beforeId = nextBeforeId)

/**
 * 旧服务器会忽略 sort=following 和 tag 参数，直接返回全部最新帖子。
 * 只有响应回显了相同的筛选条件时才信任结果。
 */
fun CommunityFeedPage.matchesRequest(sort: String, tag: String?): Boolean {
    if (sort == "following" && appliedSort != "following") return false
    if (tag != null && !tag.equals(appliedTag, ignoreCase = true)) return false
    return true
}

/** 与服务端相同的标签规则：去掉开头的 #/＃，合并空白，1–16 字，不含 # 与逗号等分隔符。 */
fun normalizeCommunityTag(raw: String?): String? {
    if (raw == null) return null
    val text = raw
        .filterNot { it.isISOControl() || it.code in 0x200B..0x200F || it.code == 0xFEFF }
        .trim()
        .trimStart('#', '＃', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (text.isEmpty() || text.any { it in "#＃,，;；、<>" }) return null
    return text.takeIf { it.codePointCount(0, it.length) <= COMMUNITY_MAX_TAG_LENGTH }
}

/** 追加一个标签：规范化、按不区分大小写去重、不超过上限。无效或超限时返回原列表。 */
fun List<String>.withCommunityTag(raw: String, maxTags: Int = COMMUNITY_MAX_POST_TAGS): List<String> {
    val tag = normalizeCommunityTag(raw) ?: return this
    if (any { it.equals(tag, ignoreCase = true) } || size >= maxTags) return this
    return this + tag
}

internal fun JSONObject.optCount(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    val number = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    } ?: return null
    return number.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

internal fun JSONObject.optNullableBoolean(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
        else -> null
    }
}

internal fun parseCommunityProfileStats(json: JSONObject?): CommunityProfileStats? {
    json ?: return null
    return CommunityProfileStats(
        postCount = json.optCount("post_count"),
        followerCount = json.optCount("follower_count"),
        followingCount = json.optCount("following_count"),
        likesReceived = json.optCount("likes_received"),
    ).takeUnless { it.isEmpty }
}

internal fun parseCommunityPostTags(array: JSONArray?): List<String> {
    array ?: return emptyList()
    val result = mutableListOf<String>()
    for (index in 0 until array.length()) {
        val tag = normalizeCommunityTag(array.optString(index)) ?: continue
        if (result.none { it.equals(tag, ignoreCase = true) }) result += tag
    }
    return result
}

internal fun parseCommunityTagList(data: JSONObject): CommunityTagList {
    val items = data.optJSONArray("items") ?: JSONArray()
    val tags = mutableListOf<CommunityTag>()
    for (index in 0 until items.length()) {
        val row = items.optJSONObject(index)
        val tag = normalizeCommunityTag(row?.optString("tag") ?: items.optString(index)) ?: continue
        if (tags.any { it.tag.equals(tag, ignoreCase = true) }) continue
        tags += CommunityTag(tag, row?.optCount("post_count") ?: 0, row?.optBoolean("is_suggested") ?: false)
    }
    return CommunityTagList(
        items = tags,
        maxTags = (data.optCount("max_tags") ?: COMMUNITY_MAX_POST_TAGS).coerceIn(1, COMMUNITY_MAX_POST_TAGS),
        maxLength = (data.optCount("max_length") ?: COMMUNITY_MAX_TAG_LENGTH).coerceIn(1, COMMUNITY_MAX_TAG_LENGTH),
    )
}

internal fun parseCommunityFollowResult(data: JSONObject): CommunityFollowResult =
    CommunityFollowResult(following = data.optBoolean("following"), followerCount = data.optCount("follower_count"))

/** 路径是否属于“旧服务器可能没有”的新接口；这些接口的 404/405/501 视为功能不可用。 */
internal fun isOptionalCommunityFeaturePath(path: String): Boolean {
    val clean = path.substringBefore('?')
    return clean == "/api/community/tags" || clean == "/api/community/follows" ||
        (clean.startsWith("/api/community/posts/") && clean.endsWith("/related"))
}

internal suspend fun CommunityService.listTags(session: CloudSession?): CommunityTagList =
    parseCommunityTagList(requestJson("GET", "/api/community/tags?limit=12", session).getJSONObject("data"))

internal suspend fun CommunityService.relatedPosts(session: CloudSession?, postId: Long, limit: Int = 6): List<CommunityPost> {
    val data = requestJson("GET", "/api/community/posts/$postId/related?limit=${limit.coerceIn(1, 12)}", session)
        .getJSONObject("data")
    return postsFromJson(data.optJSONArray("items"))
}

internal suspend fun CommunityService.setFollowing(session: CloudSession, userId: Long, following: Boolean): CommunityFollowResult =
    parseCommunityFollowResult(
        requestJson(
            "POST",
            "/api/community/follows",
            session,
            JSONObject().put("user_id", userId).put("following", following),
        ).getJSONObject("data"),
    )

/**
 * 关注名单：direction 为 following（关注中）或 followers（关注者）。
 * 旧服务器不支持时抛 [CommunityFeatureUnavailableException]，界面隐藏入口而不是报错。
 */
internal suspend fun CommunityService.listFollows(
    session: CloudSession,
    userId: Long,
    direction: String,
    offset: Int = 0,
    limit: Int = 30,
): CommunityFollowPage {
    val data = requestJson(
        "GET",
        "/api/community/follows?user_id=$userId&direction=${encodeCommunityQuery(direction)}&offset=$offset&limit=${limit.coerceIn(1, 50)}",
        session,
    ).getJSONObject("data")
    val items = data.optJSONArray("items") ?: JSONArray()
    val profiles = buildList {
        for (index in 0 until items.length()) {
            items.optJSONObject(index)?.let { json ->
                add(
                    CommunityProfile(
                        userId = json.optLong("user_id"),
                        username = json.optString("username"),
                        nickname = json.optString("nickname").ifBlank { json.optString("username") },
                        signature = "",
                        profileVisibility = "members",
                        isPrivate = false,
                        isAdmin = false,
                        isDeveloper = json.optBoolean("is_developer"),
                        avatarUrl = null,
                        showStatisticsCard = false,
                        statistics = emptyMap(),
                        updatedAt = null,
                        isFollowing = json.optNullableBoolean("is_following"),
                    ),
                )
            }
        }
    }
    return CommunityFollowPage(profiles, data.optCount("next_offset"))
}

internal fun encodeCommunityQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
