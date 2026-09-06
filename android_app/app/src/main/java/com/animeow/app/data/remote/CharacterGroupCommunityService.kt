package com.animeow.app.data.remote

import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.cloudRemoteFailure
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class CommunityCharacterGroup(
    val id: String,
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val shareCode: String? = null,
    val username: String? = null,
    val characterCount: Int = 0,
    val workCount: Int = 0,
    val downloadCount: Int = 0,
    val isPublic: Boolean = true,
    val updatedAt: String? = null,
)

data class CommunityCharacterGroupPackage(
    val summary: CommunityCharacterGroup,
    val payloadJson: String,
)

internal class CharacterGroupCommunityService(
    private val client: JsonHttpClient = JsonHttpClient(),
) {
    val isConfigured: Boolean get() = BuildConfig.CLOUD_API_BASE.isNotBlank()

    suspend fun list(query: String? = null, limit: Int = 40): List<CommunityCharacterGroup> {
        val keyword = query?.trim().orEmpty()
        val url = buildString {
            append(baseUrl())
            append("/api/community/character-groups?limit=")
            append(limit.coerceIn(1, 80))
            append("&offset=0")
            if (keyword.isNotEmpty()) {
                append("&keyword=")
                append(URLEncoder.encode(keyword, Charsets.UTF_8.name()))
            }
        }
        val root = JSONObject(client.get(url))
        checkSuccess(root)
        val data = root.optJSONObject("data")
        val items = data?.optJSONArray("items") ?: root.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.toCommunitySummary()?.let(::add)
            }
        }
    }

    suspend fun fetch(id: String): CommunityCharacterGroupPackage =
        fetchPath("/api/community/character-groups/${encodePath(id)}")

    suspend fun fetchByShareCode(code: String): CommunityCharacterGroupPackage =
        fetchPath(
            "/api/community/character-groups/share/${encodePath(code.trim().uppercase(java.util.Locale.ROOT))}",
        )

    suspend fun listMine(session: CloudSession): List<CommunityCharacterGroup> = authenticatedRequest {
        val root = JSONObject(client.get(baseUrl() + "/api/user/character-groups", authHeaders(session)))
        checkSuccess(root)
        val items = root.optJSONArray("data") ?: JSONArray()
        buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.toCommunitySummary()?.let(::add)
            }
        }
    }

    suspend fun publish(
        session: CloudSession,
        payloadJson: String,
        isPublic: Boolean,
    ): CommunityCharacterGroup = authenticatedRequest {
        val body = JSONObject()
            .put("payload", JSONObject(payloadJson))
            .put("is_public", isPublic)
        val root = JSONObject(client.post(
            baseUrl() + "/api/community/character-groups",
            body.toString(),
            authHeaders(session),
        ))
        checkSuccess(root)
        root.getJSONObject("data").toCommunitySummary() ?: error("发布结果缺少角色组摘要")
    }

    suspend fun update(
        session: CloudSession,
        communityId: String,
        payloadJson: String,
        isPublic: Boolean,
    ): CommunityCharacterGroup = authenticatedRequest {
        val body = JSONObject()
            .put("payload", JSONObject(payloadJson))
            .put("is_public", isPublic)
        val root = JSONObject(client.put(
            baseUrl() + "/api/community/character-groups/${encodePath(communityId)}",
            body.toString(),
            authHeaders(session),
        ))
        checkSuccess(root)
        root.getJSONObject("data").toCommunitySummary() ?: error("更新结果缺少角色组摘要")
    }

    suspend fun delete(session: CloudSession, communityId: String) = authenticatedRequest {
        val root = JSONObject(client.delete(
            baseUrl() + "/api/community/character-groups/${encodePath(communityId)}",
            authHeaders(session),
        ))
        checkSuccess(root)
    }

    private suspend fun fetchPath(path: String): CommunityCharacterGroupPackage {
        val root = JSONObject(client.get(baseUrl() + path))
        checkSuccess(root)
        val data = root.optJSONObject("data") ?: error("社区角色组数据格式不正确")
        val payload = data.optJSONObject("payload") ?: error("社区角色组缺少收藏包")
        return CommunityCharacterGroupPackage(
            summary = data.toCommunitySummary() ?: error("社区角色组摘要缺失"),
            payloadJson = payload.toString(),
        )
    }

    private fun baseUrl(): String = BuildConfig.CLOUD_API_BASE.trim().trimEnd('/')
        .takeIf(String::isNotEmpty)
        ?: error("尚未配置云端 API 地址")

    private fun checkSuccess(root: JSONObject) {
        if (root.optString("status") != "success") {
            error(root.optString("message").ifBlank { "社区请求失败" })
        }
    }

    private fun JSONObject.toCommunitySummary(): CommunityCharacterGroup? {
        val id = optString("community_id").takeIf(String::isNotBlank)
            ?: optString("id").takeIf(String::isNotBlank)
            ?: return null
        return CommunityCharacterGroup(
            id = id,
            name = optString("name").ifBlank { "未命名角色组" },
            description = optString("description").takeIf(String::isNotBlank),
            coverUrl = optString("cover_url").takeIf(String::isNotBlank),
            shareCode = optString("share_code").takeIf(String::isNotBlank),
            username = optString("username").takeIf(String::isNotBlank),
            characterCount = optInt("character_count", 0),
            workCount = optInt("work_count", 0),
            downloadCount = optInt("download_count", 0),
            isPublic = optBoolean("is_public", true),
            updatedAt = optString("updated_at").takeIf(String::isNotBlank),
        )
    }

    private fun authHeaders(session: CloudSession): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${session.token}",
        "Accept" to "application/json",
    )

    private suspend fun <T> authenticatedRequest(block: suspend () -> T): T = try {
        block()
    } catch (exception: RemoteHttpException) {
        throw cloudRemoteFailure(
            statusCode = exception.statusCode,
            responseBody = exception.responseBody,
            authenticated = true,
        )
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
