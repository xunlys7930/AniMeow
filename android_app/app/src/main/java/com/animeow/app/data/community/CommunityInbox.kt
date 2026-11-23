package com.animeow.app.data.community

enum class CommunityInboxFilter(val key: String, val label: String) {
    ALL("all", "全部"), LIKES("likes", "点赞"), COMMENTS("comments", "评论"), FAVORITES("favorites", "收藏");
}

data class CommunityNotification(
    val id: Long,
    val kind: String,
    val postId: Long,
    val commentId: Long?,
    val actor: CommunityProfile,
    val postExcerpt: String,
    val commentExcerpt: String,
    val createdAt: String,
    val isRead: Boolean,
) {
    val actionLabel: String get() = when (kind) {
        "post_like" -> "赞了你的帖子"
        "comment_like" -> "赞了你的评论"
        "comment" -> "评论了你的帖子"
        "reply" -> "回复了你的评论"
        "favorite" -> "收藏了你的帖子"
        else -> "与你互动"
    }
}

data class CommunityInboxSummary(val unreadCount: Int = 0, val latestId: Long? = null)
data class CommunityInboxPage(
    val items: List<CommunityNotification>,
    val nextBeforeId: Long?,
    val summary: CommunityInboxSummary,
)

internal class CommunityInboxUnavailableException : IllegalStateException("消息服务暂未启用，请稍后重试")

internal fun mergeCommunityInbox(
    existing: List<CommunityNotification>,
    incoming: List<CommunityNotification>,
): List<CommunityNotification> {
    val byId = existing.associateByTo(linkedMapOf()) { it.id }
    incoming.forEach { item -> byId[item.id] = item.copy(isRead = item.isRead || byId[item.id]?.isRead == true) }
    return byId.values.sortedByDescending { it.id }
}
