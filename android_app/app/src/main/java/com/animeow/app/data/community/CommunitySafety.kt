package com.animeow.app.data.community

import com.animeow.app.data.cloud.CloudSession
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

enum class CommunityCapability(val key: String, val label: String) {
    POST("post", "发帖与评论"), MEDIA("media", "上传社区图片"), AVATAR("avatar", "更换头像"), PROFILE("profile", "修改社区资料");
}

data class CommunityRestriction(val capability: String, val reason: String, val expiresAt: String?) {
    val label: String get() = CommunityCapability.entries.find { it.key == capability }?.label ?: capability
}

data class AvatarReview(val id: Long, val status: String, val note: String) {
    val pending: Boolean get() = status in setOf("pending", "processing", "manual")
}

data class CommunitySafetyUser(val profile: CommunityProfile, val canRestrict: Boolean, val canResetAvatar: Boolean)
data class CommunitySafetyUserPage(val items: List<CommunitySafetyUser>, val nextAfterId: Long?)
data class CommunityImageReview(val id: Long, val targetType: String, val postId: Long?, val status: String,
    val note: String, val content: String, val createdAt: String, val author: CommunityProfile, val images: List<String>)
data class CommunityImageReviewPage(val items: List<CommunityImageReview>, val pendingCount: Int, val automatic: Boolean, val nextBeforeId: Long?)
data class CommunityReviewSummary(val pendingCount: Int = 0, val imageCount: Int = 0, val reportCount: Int = 0, val latestEventId: Long = 0, val seenThroughId: Long = 0) {
    fun needsNotification(lastNotifiedId: Long): Boolean = pendingCount > 0 && latestEventId > maxOf(lastNotifiedId, seenThroughId)
}

class CommunitySafetyUnavailableException : IllegalStateException("当前服务器尚未支持图片审核与社区限制，请更新服务器后使用")
class CommunityManagementForbiddenException : IllegalStateException("当前账号没有社区管理权限")

internal fun JSONObject.communityRestrictions(): List<CommunityRestriction> {
    val rows = optJSONArray("restrictions") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.let {
        CommunityRestriction(it.optString("capability"), it.optString("reason"), it.optionalText("expires_at"))
    } }
}

internal fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
private fun JSONObject.positiveId(key: String): Long? = optLong(key).takeIf { it > 0 }

internal suspend fun CommunityService.listSafetyUsers(session: CloudSession, query: String, afterId: Long? = null): CommunitySafetyUserPage {
    val data = requestJson("GET", "/api/community/admin/users?q=${URLEncoder.encode(query, "UTF-8")}" + (afterId?.let { "&after_id=$it" } ?: ""), session).getJSONObject("data")
    val rows = data.optJSONArray("items") ?: JSONArray()
    return CommunitySafetyUserPage((0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        CommunitySafetyUser(profileFromJson(row), row.optBoolean("can_restrict"), row.optBoolean("can_reset_avatar"))
    }, data.positiveId("next_after_id"))
}

internal suspend fun CommunityService.updateRestrictions(session: CloudSession, userId: Long, capabilities: Set<String>, reason: String, durationHours: Int?) {
    requestJson("PUT", "/api/community/admin/users/$userId/restrictions", session,
        JSONObject().put("capabilities", JSONArray(capabilities.toList())).put("reason", reason).put("duration_hours", durationHours ?: JSONObject.NULL))
}

internal suspend fun CommunityService.resetUserAvatar(session: CloudSession, userId: Long, reason: String) {
    requestJson("POST", "/api/community/admin/users/$userId/avatar/reset", session, JSONObject().put("reason", reason))
}

internal suspend fun CommunityService.imageReviews(session: CloudSession, status: String = "pending", beforeId: Long? = null): CommunityImageReviewPage {
    val data = requestJson("GET", "/api/community/admin/image-reviews?status=$status" + (beforeId?.let { "&before_id=$it" } ?: ""), session).getJSONObject("data")
    val rows = data.optJSONArray("items") ?: JSONArray()
    return CommunityImageReviewPage((0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val images = row.optJSONArray("images") ?: JSONArray()
        CommunityImageReview(row.getLong("id"), row.optString("target_type"), row.positiveId("post_id"), row.optString("status"),
            row.optString("note"), row.optString("content"), row.optString("created_at"), profileFromJson(row.getJSONObject("author")),
            (0 until images.length()).map { communityUrl(images.getString(it)) })
    }, data.optInt("pending_count"), data.optJSONObject("image_moderation")?.optBoolean("automatic") == true, data.positiveId("next_before_id"))
}

internal suspend fun CommunityService.resolveImageReview(session: CloudSession, id: Long, approved: Boolean, note: String) {
    requestJson("POST", "/api/community/admin/image-reviews/$id/resolve", session,
        JSONObject().put("action", if (approved) "approved" else "rejected").put("note", note))
}

internal suspend fun CommunityService.reviewSummary(session: CloudSession): CommunityReviewSummary {
    val data = requestJson("GET", "/api/community/admin/review-summary", session).getJSONObject("data")
    return CommunityReviewSummary(data.optInt("pending_count"), data.optInt("image_count"), data.optInt("report_count"), data.optLong("latest_event_id"), data.optLong("seen_through_id"))
}

internal suspend fun CommunityService.markReviewsSeen(session: CloudSession, throughId: Long) {
    requestJson("POST", "/api/community/admin/review-summary/read", session, JSONObject().put("through_id", throughId))
}
