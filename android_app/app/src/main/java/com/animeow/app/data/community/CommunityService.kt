package com.animeow.app.data.community

import android.content.Context
import android.util.Base64
import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.data.cloud.cloudRemoteFailure
import com.animeow.app.data.cloud.safeCloudMessage
import com.animeow.app.data.remote.JsonHttpClient
import com.animeow.app.data.remote.RemoteHttpException
import org.json.JSONArray
import org.json.JSONObject

data class CommunityLimits(
    val postTextLength: Int = 2_000,
    val postImages: Int = 4,
    val imageBytes: Int = 1024 * 1024,
    val postMediaBytes: Int = 3 * 1024 * 1024,
    val avatarBytes: Int = 512 * 1024,
)

data class CommunityProfile(
    val userId: Long,
    val username: String,
    val nickname: String,
    val signature: String,
    val profileVisibility: String,
    val isPrivate: Boolean,
    val isAdmin: Boolean,
    val isDeveloper: Boolean,
    val avatarUrl: String?,
    val showStatisticsCard: Boolean,
    val statistics: Map<String, Double>,
    val updatedAt: String?,
)

data class CommunityMembership(
    val receiveMessages: Boolean,
    val joinedAt: String?,
)

data class CommunityGroup(
    val id: Long,
    val name: String,
    val description: String,
    val joinCode: String,
    val isOfficial: Boolean,
    val isActive: Boolean,
    val membership: CommunityMembership?,
)

data class CommunityMedia(
    val id: Long,
    val mimeType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val url: String,
)

data class CommunityPost(
    val id: Long,
    val groupId: Long,
    val content: String,
    val media: List<CommunityMedia>,
    val author: CommunityProfile,
    val moderationStatus: String,
    val isQuarantined: Boolean,
    val isPinned: Boolean,
    val pinnedAt: String?,
    val isFeatured: Boolean,
    val editedAt: String?,
    val likeCount: Int,
    val commentCount: Int,
    val likedByViewer: Boolean,
    val isFavorited: Boolean,
    val canReport: Boolean,
    val canDelete: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

data class CommunityComment(
    val id: Long,
    val postId: Long,
    val parentId: Long?,
    val replyToUserId: Long?,
    val content: String,
    val author: CommunityProfile,
    val likeCount: Int,
    val likedByViewer: Boolean,
    val replyCount: Int,
    val canDelete: Boolean,
    val createdAt: String,
)

data class CommunityCommentPage(
    val items: List<CommunityComment>,
    val nextBeforeId: Long?,
)

data class CommunityModerationLogEntry(
    val id: Long,
    val actorId: Long,
    val actorUsername: String,
    val action: String,
    val quarantineId: Long?,
    val reportId: Long?,
    val postId: Long?,
    val feedbackId: Long?,
    val targetUserId: Long?,
    val previousValue: String?,
    val newValue: String?,
    val note: String?,
    val createdAt: String,
)

data class SensitiveWord(
    val id: Long,
    val word: String,
    val createdAt: String,
)

data class SensitiveWordPage(
    val words: List<SensitiveWord>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

data class CommunityStats(
    val postCount: Int = 0,
    val commentCount: Int = 0,
    val likeCount: Int = 0,
    val favoriteCount: Int = 0,
    val blockCount: Int = 0,
    val userCount: Int = 0,
    val pendingReports: Int = 0,
    val quarantineCount: Int = 0,
    val avgReportResolutionSeconds: Long = 0,
)

data class CommunityBootstrap(
    val currentUser: CommunityProfile?,
    val group: CommunityGroup,
    val limits: CommunityLimits,
)

data class CommunityFeedPage(
    val group: CommunityGroup,
    val items: List<CommunityPost>,
    val nextBeforeId: Long?,
)

data class CommunityUploadImage(
    val mimeType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

data class CommunityReport(
    val id: Long,
    val postId: Long,
    val reason: String,
    val details: String,
    val reporterUsername: String,
    val createdAt: String,
)

data class CommunityReviewCase(
    val id: Long,
    val author: CommunityProfile,
    val triggerPostId: Long,
    val reportCount: Int,
    val quarantinedPostCount: Int,
    val createdAt: String,
    val reports: List<CommunityReport>,
    val posts: List<CommunityPost>,
)

data class CommunityResolvedReview(
    val id: Long,
    val author: CommunityProfile,
    val triggerPostId: Long,
    val status: String,
    val reviewerUsername: String?,
    val reviewNote: String?,
    val quarantinedPostCount: Int,
    val resolvedAt: String,
    val createdAt: String,
    val posts: List<CommunityPost>,
)

internal class CommunityService(
    context: Context,
    private val client: JsonHttpClient = JsonHttpClient(),
) {
    private val sessionStore = SecureCloudSessionStore(context.applicationContext)

    val isConfigured: Boolean get() = BuildConfig.CLOUD_API_BASE.isNotBlank()

    fun loadSession(): CloudSession? = sessionStore.load()

    suspend fun bootstrap(session: CloudSession?): CommunityBootstrap {
        val data = requestJson("GET", "/api/community/bootstrap", session).getJSONObject("data")
        return CommunityBootstrap(
            currentUser = data.optJSONObject("current_user")?.toCommunityProfile(),
            group = data.getJSONObject("group").toCommunityGroup(),
            limits = data.optJSONObject("limits")?.toCommunityLimits() ?: CommunityLimits(),
        )
    }

    suspend fun listPosts(
        session: CloudSession?,
        groupId: Long,
        sort: String = "newest",
        featuredOnly: Boolean = false,
        beforeId: Long? = null,
        limit: Int = 20,
    ): CommunityFeedPage {
        val query = buildString {
            append("/api/community/posts?group_id=").append(groupId)
            append("&sort=").append(java.net.URLEncoder.encode(sort, "UTF-8"))
            if (featuredOnly) append("&featured=1")
            append("&limit=").append(limit.coerceIn(1, 50))
            beforeId?.let { append("&before_id=").append(it) }
        }
        val data = requestJson("GET", query, session).getJSONObject("data")
        return CommunityFeedPage(
            group = data.getJSONObject("group").toCommunityGroup(),
            items = data.optJSONArray("items").toPosts(),
            nextBeforeId = data.optLongOrNull("next_before_id"),
        )
    }

    suspend fun getPost(session: CloudSession?, postId: Long): CommunityPost =
        requestJson("GET", "/api/community/posts/$postId", session)
            .getJSONObject("data")
            .toCommunityPost()

    suspend fun createPost(
        session: CloudSession,
        groupId: Long,
        content: String,
        images: List<CommunityUploadImage>,
        presetMedia: List<String> = emptyList(),
    ): CommunityPost {
        val body = JSONObject()
            .put("group_id", groupId)
            .put("content", content.trim())
            .put("images", JSONArray().apply {
                images.forEach { image ->
                    put(
                        JSONObject()
                            .put("mime_type", image.mimeType)
                            .put("data_base64", Base64.encodeToString(image.bytes, Base64.NO_WRAP))
                            .put("width", image.width)
                            .put("height", image.height),
                    )
                }
            })
        if (presetMedia.isNotEmpty()) {
            body.put("preset_media", JSONArray().apply {
                presetMedia.forEach { put(it) }
            })
        }
        return requestJson("POST", "/api/community/posts", session, body)
            .getJSONObject("data")
            .toCommunityPost()
    }

    suspend fun deletePost(session: CloudSession, postId: Long) {
        requestJson("DELETE", "/api/community/posts/$postId", session)
    }

    suspend fun reportPost(
        session: CloudSession,
        postId: Long,
        reason: String,
        details: String,
    ) {
        requestJson(
            "POST",
            "/api/community/posts/$postId/report",
            session,
            JSONObject().put("reason", reason).put("details", details.trim()),
        )
    }

    suspend fun joinGroup(session: CloudSession, code: String): CommunityGroup =
        requestJson(
            "POST",
            "/api/community/groups/join",
            session,
            JSONObject().put("code", code.trim().uppercase()),
        ).getJSONObject("data").toCommunityGroup()

    suspend fun updateGroupPreferences(
        session: CloudSession,
        groupId: Long,
        receiveMessages: Boolean,
    ): CommunityGroup = requestJson(
        "PUT",
        "/api/community/groups/$groupId/preferences",
        session,
        JSONObject().put("receive_messages", receiveMessages),
    ).getJSONObject("data").toCommunityGroup()

    suspend fun getProfile(session: CloudSession, userId: Long): CommunityProfile =
        requestJson("GET", "/api/community/profiles/$userId", session)
            .getJSONObject("data")
            .toCommunityProfile()

    suspend fun updateProfile(
        session: CloudSession,
        nickname: String,
        signature: String,
        profileVisibility: String,
        showStatisticsCard: Boolean,
        statistics: Map<String, Double>,
        avatar: CommunityUploadImage? = null,
        removeAvatar: Boolean = false,
        presetAvatar: String? = null,
    ): CommunityProfile {
        val body = JSONObject()
            .put("nickname", nickname.trim())
            .put("signature", signature.trim())
            .put("profile_visibility", profileVisibility)
            .put("show_statistics_card", showStatisticsCard)
            .put("statistics", JSONObject().apply {
                statistics.forEach { (key, value) -> put(key, value) }
            })
        if (!presetAvatar.isNullOrBlank()) {
            body.put("preset_avatar", presetAvatar)
        } else {
            when {
                avatar != null -> body.put(
                    "avatar",
                    JSONObject()
                        .put("mime_type", avatar.mimeType)
                        .put("data_base64", Base64.encodeToString(avatar.bytes, Base64.NO_WRAP))
                        .put("width", avatar.width)
                        .put("height", avatar.height),
                )
                removeAvatar -> body.put("avatar", JSONObject.NULL)
            }
        }
        return requestJson("PUT", "/api/community/profile/me", session, body)
            .getJSONObject("data")
            .toCommunityProfile()
    }

    suspend fun listReviews(session: CloudSession): List<CommunityReviewCase> {
        val items = requestJson("GET", "/api/community/admin/reviews", session)
            .getJSONObject("data")
            .optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(it.toCommunityReviewCase()) }
            }
        }
    }

    suspend fun resolveReview(
        session: CloudSession,
        quarantineId: Long,
        confirmViolation: Boolean,
        note: String,
    ) {
        requestJson(
            "POST",
            "/api/community/admin/reviews/$quarantineId/resolve",
            session,
            JSONObject()
                .put("action", if (confirmViolation) "confirm_violation" else "restore")
                .put("note", note.trim()),
        )
    }

    suspend fun listResolvedReviews(session: CloudSession): List<CommunityResolvedReview> {
        val items = requestJson("GET", "/api/community/admin/reviews/resolved", session)
            .getJSONObject("data")
            .optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(it.toCommunityResolvedReview()) }
            }
        }
    }

    suspend fun overrideReview(
        session: CloudSession,
        quarantineId: Long,
        confirmViolation: Boolean,
        note: String,
    ) {
        requestJson(
            "POST",
            "/api/community/admin/reviews/$quarantineId/override",
            session,
            JSONObject()
                .put("action", if (confirmViolation) "confirm_violation" else "restore")
                .put("note", note.trim()),
        )
    }

    suspend fun listModerationLog(session: CloudSession): List<CommunityModerationLogEntry> {
        val items = requestJson("GET", "/api/community/admin/moderation-log", session)
            .getJSONArray("data")
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(it.toModerationLogEntry()) }
            }
        }
    }

    suspend fun listComments(
        session: CloudSession?,
        postId: Long,
        parentId: Long? = null,
        sort: String = "oldest",
        beforeId: Long? = null,
        limit: Int = 50,
    ): CommunityCommentPage {
        val query = buildString {
            append("/api/community/posts/").append(postId).append("/comments")
            append("?sort=").append(java.net.URLEncoder.encode(sort, "UTF-8"))
            append("&limit=").append(limit.coerceIn(1, 100))
            parentId?.let { append("&parent_id=").append(it) }
            beforeId?.let { append("&before_id=").append(it) }
        }
        val data = requestJson("GET", query, session).getJSONObject("data")
        val items = data.optJSONArray("items") ?: JSONArray()
        return CommunityCommentPage(
            items = buildList {
                for (index in 0 until items.length()) {
                    items.optJSONObject(index)?.let { add(it.toCommunityComment()) }
                }
            },
            nextBeforeId = data.optLongOrNull("next_before_id"),
        )
    }

    suspend fun createComment(session: CloudSession, postId: Long, content: String): CommunityComment =
        requestJson(
            "POST",
            "/api/community/posts/$postId/comments",
            session,
            JSONObject().put("content", content.trim()),
        ).getJSONObject("data").toCommunityComment()

    suspend fun replyComment(session: CloudSession, commentId: Long, content: String): CommunityComment =
        requestJson(
            "POST",
            "/api/community/comments/$commentId/reply",
            session,
            JSONObject().put("content", content.trim()),
        ).getJSONObject("data").toCommunityComment()

    suspend fun deleteComment(session: CloudSession, commentId: Long) {
        requestJson("DELETE", "/api/community/comments/$commentId", session)
    }

    suspend fun like(session: CloudSession, targetType: String, targetId: Long): Boolean {
        val data = requestJson(
            "POST",
            "/api/community/like",
            session,
            JSONObject().put("target_type", targetType).put("target_id", targetId),
        ).getJSONObject("data")
        return data.optBoolean("liked")
    }

    suspend fun pinPost(session: CloudSession, postId: Long, pin: Boolean) {
        requestJson(
            "POST",
            "/api/community/posts/$postId/pin",
            session,
            JSONObject().put("pin", pin),
        )
    }

    suspend fun listUserPosts(
        session: CloudSession?,
        userId: Long,
        beforeId: Long? = null,
        limit: Int = 30,
    ): CommunityFeedPage {
        val query = buildString {
            append("/api/community/profiles/").append(userId).append("/posts")
            append("?limit=").append(limit.coerceIn(1, 50))
            beforeId?.let { append("&before_id=").append(it) }
        }
        val data = requestJson("GET", query, session).getJSONObject("data")
        val items = data.optJSONArray("items") ?: JSONArray()
        return CommunityFeedPage(
            group = CommunityGroup(
                id = 0L, name = "", description = "", joinCode = "",
                isOfficial = true, isActive = true, membership = null,
            ),
            items = buildList {
                for (index in 0 until items.length()) {
                    items.optJSONObject(index)?.let { add(it.toCommunityPost()) }
                }
            },
            nextBeforeId = data.optLongOrNull("next_before_id"),
        )
    }

    suspend fun listUsers(
        session: CloudSession,
        query: String? = null,
        role: String? = null,
    ): List<CommunityProfile> {
        val path = buildString {
            append("/api/admin/users")
            val params = buildList {
                query?.takeIf(String::isNotBlank)?.let { add("q=" + java.net.URLEncoder.encode(it, "UTF-8")) }
                role?.takeIf(String::isNotBlank)?.let { add("role=" + java.net.URLEncoder.encode(it, "UTF-8")) }
            }
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
        val items = requestJson("GET", path, session).getJSONArray("data")
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { json ->
                    add(
                        CommunityProfile(
                            userId = json.optLong("id"),
                            username = json.optString("username"),
                            nickname = json.optString("username"),
                            signature = "",
                            profileVisibility = "members",
                            isPrivate = false,
                            isAdmin = json.optBoolean("is_admin"),
                            isDeveloper = json.optBoolean("is_developer"),
                            avatarUrl = null,
                            showStatisticsCard = false,
                            statistics = emptyMap(),
                            updatedAt = null,
                        ),
                    )
                }
            }
        }
    }

    suspend fun setUserAdmin(session: CloudSession, userId: Long, isAdmin: Boolean) {
        requestJson(
            "POST",
            "/api/admin/users/$userId/admin",
            session,
            JSONObject().put("is_admin", isAdmin),
        )
    }

    suspend fun listSensitiveWords(
        session: CloudSession,
        page: Int = 1,
        pageSize: Int = 50,
        search: String? = null,
    ): SensitiveWordPage {
        val path = buildString {
            append("/api/community/admin/sensitive-words?page=")
            append(page.coerceAtLeast(1))
            append("&pageSize=")
            append(pageSize.coerceIn(1, 200))
            if (!search.isNullOrBlank()) {
                append("&search=")
                append(java.net.URLEncoder.encode(search.trim(), "UTF-8"))
            }
        }
        val root = requestJson("GET", path, session)
        val items = root.getJSONArray("data")
        val words = buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { json ->
                    add(
                        SensitiveWord(
                            id = json.optLong("id"),
                            word = json.optString("word"),
                            createdAt = json.optString("created_at"),
                        ),
                    )
                }
            }
        }
        val pagination = root.optJSONObject("pagination")
        return SensitiveWordPage(
            words = words,
            total = pagination?.optInt("total") ?: words.size,
            page = pagination?.optInt("page") ?: page,
            pageSize = pagination?.optInt("pageSize") ?: pageSize,
        )
    }

    suspend fun addSensitiveWord(session: CloudSession, word: String) {
        requestJson(
            "POST",
            "/api/community/admin/sensitive-words",
            session,
            JSONObject().put("word", word.trim()),
        )
    }

    suspend fun deleteSensitiveWord(session: CloudSession, id: Long) {
        requestJson("DELETE", "/api/community/admin/sensitive-words/$id", session)
    }

    suspend fun importSensitiveWords(session: CloudSession, words: List<String>): Int {
        val body = JSONObject().put(
            "words",
            JSONArray().apply { words.forEach { put(it) } },
        )
        val data = requestJson("POST", "/api/community/admin/sensitive-words/import", session, body)
            .optJSONObject("data")
        return data?.optInt("added") ?: 0
    }

    suspend fun editPost(session: CloudSession, postId: Long, content: String) {
        requestJson("PUT", "/api/community/posts/$postId", session, JSONObject().put("content", content.trim()))
    }

    suspend fun editComment(session: CloudSession, commentId: Long, content: String) {
        requestJson("PUT", "/api/community/comments/$commentId", session, JSONObject().put("content", content.trim()))
    }

    suspend fun blockUser(session: CloudSession, userId: Long) {
        requestJson("POST", "/api/community/blocks", session, JSONObject().put("blocked_user_id", userId))
    }

    suspend fun unblockUser(session: CloudSession, userId: Long) {
        requestJson("DELETE", "/api/community/blocks/$userId", session)
    }

    suspend fun listBlocks(session: CloudSession): List<CommunityProfile> {
        val items = requestJson("GET", "/api/community/blocks", session).getJSONArray("data")
        return buildList {
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
                        ),
                    )
                }
            }
        }
    }

    suspend fun featurePost(session: CloudSession, postId: Long, feature: Boolean) {
        requestJson("POST", "/api/community/posts/$postId/feature", session, JSONObject().put("feature", feature))
    }

    suspend fun favoritePost(session: CloudSession, postId: Long): Boolean {
        val data = requestJson(
            "POST",
            "/api/community/favorites",
            session,
            JSONObject().put("post_id", postId),
        ).getJSONObject("data")
        return data.optBoolean("favorited")
    }

    suspend fun listFavorites(session: CloudSession): List<CommunityPost> {
        val items = requestJson("GET", "/api/community/favorites", session)
            .getJSONObject("data")
            .optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(it.toCommunityPost()) }
            }
        }
    }

    suspend fun searchPosts(
        session: CloudSession,
        keyword: String,
        sort: String = "newest",
        featuredOnly: Boolean = false,
        beforeId: Long? = null,
        limit: Int = 30,
    ): CommunityFeedPage {
        val query = buildString {
            append("/api/community/posts/search?q=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
            append("&sort=").append(java.net.URLEncoder.encode(sort, "UTF-8"))
            if (featuredOnly) append("&featured=1")
            append("&limit=").append(limit.coerceIn(1, 50))
            beforeId?.let { append("&before_id=").append(it) }
        }
        val data = requestJson("GET", query, session).getJSONObject("data")
        val items = data.optJSONArray("items") ?: JSONArray()
        return CommunityFeedPage(
            group = CommunityGroup(
                id = 0L, name = "搜索结果", description = "", joinCode = "",
                isOfficial = true, isActive = true, membership = null,
            ),
            items = buildList {
                for (index in 0 until items.length()) {
                    items.optJSONObject(index)?.let { add(it.toCommunityPost()) }
                }
            },
            nextBeforeId = data.optLongOrNull("next_before_id"),
        )
    }

    suspend fun communityStats(session: CloudSession): CommunityStats {
        val data = requestJson("GET", "/api/community/admin/stats", session).getJSONObject("data")
        return CommunityStats(
            postCount = data.optInt("post_count"),
            commentCount = data.optInt("comment_count"),
            likeCount = data.optInt("like_count"),
            favoriteCount = data.optInt("favorite_count"),
            blockCount = data.optInt("block_count"),
            userCount = data.optInt("user_count"),
            pendingReports = data.optInt("pending_reports"),
            quarantineCount = data.optInt("quarantine_count"),
            avgReportResolutionSeconds = data.optLong("avg_report_resolution_seconds"),
        )
    }

    suspend fun exportAudit(session: CloudSession): String {
        val data = requestJson("GET", "/api/community/admin/export-audit", session)
            .getJSONObject("data")
        return data.toString(2)
    }

    private suspend fun requestJson(
        method: String,
        path: String,
        session: CloudSession?,
        body: JSONObject? = null,
    ): JSONObject {
        val headers = mutableMapOf("Accept" to "application/json")
        if (session != null) {
            headers["Authorization"] = "Bearer ${session.token}"
        }
        val response = try {
            when (method) {
                "GET" -> client.get(baseUrl() + path, headers)
                "POST" -> client.post(baseUrl() + path, body?.toString().orEmpty(), headers)
                "PUT" -> client.put(baseUrl() + path, body?.toString().orEmpty(), headers)
                "DELETE" -> client.delete(baseUrl() + path, headers)
                else -> error("不支持的社区请求方法")
            }
        } catch (exception: RemoteHttpException) {
            val failure = cloudRemoteFailure(exception.statusCode, exception.responseBody, authenticated = session != null)
            if (session != null && failure is CloudSessionExpiredException) sessionStore.clear()
            throw failure
        }
        val root = JSONObject(response)
        if (root.optString("status") != "success") {
            error(safeCloudMessage(root.optString("message")) ?: "社区请求失败，请稍后重试")
        }
        return root
    }

    private fun JSONObject.toCommunityLimits() = CommunityLimits(
        postTextLength = optInt("post_text", 2_000),
        postImages = optInt("post_images", 4),
        imageBytes = optInt("image_bytes", 1024 * 1024),
        postMediaBytes = optInt("post_media_bytes", 3 * 1024 * 1024),
        avatarBytes = optInt("avatar_bytes", 512 * 1024),
    )

    private fun JSONObject.toCommunityProfile(): CommunityProfile {
        val stats = linkedMapOf<String, Double>()
        optJSONObject("statistics")?.let { json ->
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optDouble(key, Double.NaN)
                if (value.isFinite()) stats[key] = value
            }
        }
        return CommunityProfile(
            userId = optLong("user_id"),
            username = optString("username"),
            nickname = optString("nickname").ifBlank { optString("username", "喵友") },
            signature = optString("signature"),
            profileVisibility = optString("profile_visibility", "members"),
            isPrivate = optBoolean("is_private"),
            isAdmin = optBoolean("is_admin"),
            isDeveloper = optBoolean("is_developer"),
            avatarUrl = optString("avatar_url").takeIf(String::isNotBlank)?.let(::absoluteUrl),
            showStatisticsCard = optBoolean("show_statistics_card"),
            statistics = stats,
            updatedAt = optString("updated_at").takeIf(String::isNotBlank),
        )
    }

    private fun JSONObject.toCommunityGroup(): CommunityGroup = CommunityGroup(
        id = optLong("id"),
        name = optString("name", "追番喵茶话会"),
        description = optString("description"),
        joinCode = optString("join_code"),
        isOfficial = optBoolean("is_official"),
        isActive = optBoolean("is_active", true),
        membership = optJSONObject("membership")?.let { membership ->
            CommunityMembership(
                receiveMessages = membership.optBoolean("receive_messages", true),
                joinedAt = membership.optString("joined_at").takeIf(String::isNotBlank),
            )
        },
    )

    private fun JSONObject.toCommunityPost(): CommunityPost = CommunityPost(
        id = optLong("id"),
        groupId = optLong("group_id"),
        content = optString("content"),
        media = buildList {
            val items = optJSONArray("media") ?: JSONArray()
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { media ->
                    add(
                        CommunityMedia(
                            id = media.optLong("id"),
                            mimeType = media.optString("mime_type"),
                            byteSize = media.optLong("byte_size"),
                            width = media.optIntOrNull("width"),
                            height = media.optIntOrNull("height"),
                            url = absoluteUrl(media.optString("url")),
                        ),
                    )
                }
            }
        },
        author = getJSONObject("author").toCommunityProfile(),
        moderationStatus = optString("moderation_status", "visible"),
        isQuarantined = optBoolean("is_quarantined"),
        isPinned = optBoolean("is_pinned"),
        pinnedAt = optString("pinned_at").takeIf(String::isNotBlank),
        isFeatured = optBoolean("is_featured"),
        editedAt = optString("edited_at").takeIf(String::isNotBlank),
        likeCount = optInt("like_count"),
        commentCount = optInt("comment_count"),
        likedByViewer = optBoolean("liked_by_viewer"),
        isFavorited = optBoolean("is_favorited"),
        canReport = optBoolean("can_report"),
        canDelete = optBoolean("can_delete"),
        createdAt = optString("created_at"),
        updatedAt = optString("updated_at"),
    )

    private fun JSONArray?.toPosts(): List<CommunityPost> = buildList {
        val array = this@toPosts ?: return@buildList
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { add(it.toCommunityPost()) }
        }
    }

    private fun JSONObject.toCommunityReviewCase(): CommunityReviewCase = CommunityReviewCase(
        id = optLong("id"),
        author = getJSONObject("author").toCommunityProfile(),
        triggerPostId = optLong("trigger_post_id"),
        reportCount = optInt("report_count"),
        quarantinedPostCount = optInt("quarantined_post_count"),
        createdAt = optString("created_at"),
        reports = buildList {
            val array = optJSONArray("reports") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { report ->
                    add(
                        CommunityReport(
                            id = report.optLong("id"),
                            postId = report.optLong("post_id"),
                            reason = report.optString("reason"),
                            details = report.optString("details"),
                            reporterUsername = report.optString("reporter_username"),
                            createdAt = report.optString("created_at"),
                        ),
                    )
                }
            }
        },
        posts = optJSONArray("posts").toPosts(),
    )

    private fun JSONObject.toCommunityComment(): CommunityComment = CommunityComment(
        id = optLong("id"),
        postId = optLong("post_id"),
        parentId = optLongOrNull("parent_id"),
        replyToUserId = optLongOrNull("reply_to_user_id"),
        content = optString("content"),
        author = getJSONObject("author").toCommunityProfile(),
        likeCount = optInt("like_count"),
        likedByViewer = optBoolean("liked_by_viewer"),
        replyCount = optInt("reply_count"),
        canDelete = optBoolean("can_delete"),
        createdAt = optString("created_at"),
    )

    private fun JSONObject.toModerationLogEntry(): CommunityModerationLogEntry =
        CommunityModerationLogEntry(
            id = optLong("id"),
            actorId = optLong("actor_id"),
            actorUsername = optString("actor_username"),
            action = optString("action"),
            quarantineId = optLongOrNull("quarantine_id"),
            reportId = optLongOrNull("report_id"),
            postId = optLongOrNull("post_id"),
            feedbackId = optLongOrNull("feedback_id"),
            targetUserId = optLongOrNull("target_user_id"),
            previousValue = optString("previous_value").takeIf(String::isNotBlank),
            newValue = optString("new_value").takeIf(String::isNotBlank),
            note = optString("note").takeIf(String::isNotBlank),
            createdAt = optString("created_at"),
        )

    private fun JSONObject.toCommunityResolvedReview(): CommunityResolvedReview =
        CommunityResolvedReview(
            id = optLong("id"),
            author = getJSONObject("author").toCommunityProfile(),
            triggerPostId = optLong("trigger_post_id"),
            status = optString("status"),
            reviewerUsername = optString("reviewer_username").takeIf(String::isNotBlank),
            reviewNote = optString("review_note").takeIf(String::isNotBlank),
            quarantinedPostCount = optInt("quarantined_post_count"),
            resolvedAt = optString("resolved_at"),
            createdAt = optString("created_at"),
            posts = optJSONArray("posts").toPosts(),
        )

    private fun JSONObject.optLongOrNull(key: String): Long? =
        takeUnless { isNull(key) }?.optLong(key)?.takeIf { it > 0L }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        takeUnless { isNull(key) }?.optInt(key)?.takeIf { it > 0 }

    private fun absoluteUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> baseUrl() + path
        else -> baseUrl() + "/" + path
    }

    private fun baseUrl(): String = BuildConfig.CLOUD_API_BASE.trim().trimEnd('/')
        .takeIf(String::isNotEmpty)
        ?: error("当前构建尚未配置云端 API 地址")
}
