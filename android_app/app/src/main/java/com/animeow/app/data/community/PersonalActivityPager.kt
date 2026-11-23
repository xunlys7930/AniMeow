package com.animeow.app.data.community

enum class PersonalActivityTab(val label: String) {
    POSTS("帖子"), LIKES("点赞"), FAVORITES("收藏"),
}

data class PersonalActivityPage(
    val items: List<CommunityPost> = emptyList(),
    val nextBeforeId: Long? = null,
    val endReached: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

internal data class ActivityBatch(
    val items: List<CommunityPost>,
    val nextBeforeId: Long?,
    val endReached: Boolean,
)

/**
 * The current server exposes viewer engagement on feed pages, but no likes-history endpoint.
 * Walk its real cursor instead of filtering only the first feed page. Each request is bounded;
 * a sparse history retains a continuation so an empty batch is never presented as an empty history.
 */
internal suspend fun scanPersonalActivity(
    beforeId: Long?,
    knownIds: Set<Long> = emptySet(),
    matches: (CommunityPost) -> Boolean,
    loadPage: suspend (Long?) -> CommunityFeedPage,
): ActivityBatch {
    var cursor = beforeId
    val matchesById = linkedMapOf<Long, CommunityPost>()
    repeat(3) {
        val page = loadPage(cursor)
        val next = page.nextBeforeId
        check(next == null || (next > 0 && (cursor == null || next < cursor))) {
            "暂时无法继续加载记录，请刷新后重试"
        }
        page.items.filter(matches).forEach { matchesById[it.id] = it }
        cursor = next
        if (next == null || matchesById.keys.any { it !in knownIds }) {
            return ActivityBatch(matchesById.values.toList(), cursor, next == null)
        }
    }
    return ActivityBatch(matchesById.values.toList(), cursor, endReached = false)
}

internal fun mergePersonalActivity(
    existing: List<CommunityPost>,
    incoming: List<CommunityPost>,
): List<CommunityPost> = (existing + incoming).associateBy(CommunityPost::id).values.sortedByDescending(CommunityPost::id)
