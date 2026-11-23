package com.animeow.app.data.bangumi

import android.content.Context
import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.BangumiAccountService
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.data.remote.JsonHttpClient
import com.animeow.app.data.remote.RemoteHttpException
import com.animeow.app.data.cloud.bangumiFailure
import com.animeow.app.data.cloud.safeCloudMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal interface BangumiUploadRemoteClient {
    suspend fun captureAccount(): BangumiUploadAccount
    suspend fun verifyAccount(account: BangumiUploadAccount)
    suspend fun checkCapability()
    suspend fun lookup(account: BangumiUploadAccount, kind: BangumiUploadKind, subjectId: Long): JSONObject
    suspend fun page(account: BangumiUploadAccount, kind: BangumiUploadKind, offset: Int): JSONObject
    suspend fun prepare(account: BangumiUploadAccount, row: BangumiUploadRow, fields: JSONObject, privateNew: Boolean): JSONObject
    suspend fun apply(account: BangumiUploadAccount, row: BangumiUploadRow, fields: JSONObject, privateNew: Boolean, prepared: JSONObject): JSONObject
}

internal class BangumiUploadClient(context: Context,
    private val client: JsonHttpClient = JsonHttpClient(readTimeoutMs = 120_000, maxResponseBytes = 8 * 1024 * 1024),
    private val apiBase: String = BuildConfig.CLOUD_API_BASE,
) : BangumiUploadRemoteClient {
    private val sessions = SecureCloudSessionStore(context)
    private val accounts = BangumiAccountService(context, apiBase = apiBase)
    private var pinned: CloudSession? = null
    private var verifiedBangumiId: Long? = null
    private var verifiedAtMs: Long = 0L

    override suspend fun captureAccount(): BangumiUploadAccount {
        val session = sessions.load() ?: error("请先登录 AniMeow 账号并连接 Bangumi")
        pinned = session
        val binding = accounts.fetchProfile().bangumi ?: error("请先在账号与同步中连接 Bangumi")
        verifySession()
        verifiedBangumiId = binding.id
        verifiedAtMs = android.os.SystemClock.elapsedRealtime()
        return BangumiUploadAccount(session.userId, binding.id, binding.username)
    }

    override suspend fun verifyAccount(account: BangumiUploadAccount) {
        check(verifySession().userId == account.userId) { "账号已变化，请重新读取" }
        val now = android.os.SystemClock.elapsedRealtime()
        if (verifiedBangumiId != account.bangumiId || now - verifiedAtMs >= ACCOUNT_VERIFY_TTL_MS) {
            check(accounts.fetchProfile().bangumi?.id == account.bangumiId) { "Bangumi 账号已变化，请重新读取" }
            verifiedBangumiId = account.bangumiId
            verifiedAtMs = android.os.SystemClock.elapsedRealtime()
        }
        verifySession()
    }

    private fun verifySession(): CloudSession {
        val expected = pinned ?: error("请先读取收藏对比")
        check(sessions.load() == expected) { "登录状态已变化，请重新读取收藏" }
        return expected
    }

    override suspend fun checkCapability() {
        val capability = request("GET", "/status")
        check(capability.optInt("upload") == 1 && capability.optInt("merge") == 1) { "服务器尚未支持收藏合并，请先更新服务器" }
    }

    override suspend fun page(account: BangumiUploadAccount, kind: BangumiUploadKind, offset: Int): JSONObject =
        request("GET", "/collections?subject_type=${kind.id}&offset=$offset&expected_bangumi_id=${account.bangumiId}")

    override suspend fun lookup(account: BangumiUploadAccount, kind: BangumiUploadKind, subjectId: Long): JSONObject =
        request("GET", "/subject?subject_id=$subjectId&subject_type=${kind.id}&expected_bangumi_id=${account.bangumiId}")

    private fun body(account: BangumiUploadAccount, row: BangumiUploadRow, fields: JSONObject, privateNew: Boolean) = JSONObject()
        .put("subject_id", row.local.subjectId).put("subject_type", row.local.kind.id)
        .put("expected_bangumi_id", account.bangumiId)
        .put("expected_fingerprint", row.remote?.fingerprint ?: JSONObject.NULL)
        .put("fields", fields).put("private_new", privateNew)

    override suspend fun prepare(account: BangumiUploadAccount, row: BangumiUploadRow, fields: JSONObject, privateNew: Boolean): JSONObject =
        request("POST", "/prepare", body(account, row, fields, privateNew))

    override suspend fun apply(account: BangumiUploadAccount, row: BangumiUploadRow, fields: JSONObject, privateNew: Boolean, prepared: JSONObject): JSONObject =
        request("POST", "/apply", body(account, row, fields, privateNew)
            .put("expected_episode_fingerprint", prepared.opt("episode_fingerprint") ?: JSONObject.NULL))

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val session = verifySession()
        val headers = mapOf("Authorization" to "Bearer ${session.token}", "Accept" to "application/json")
        val url = apiBase.trimEnd('/') + "/api/auth/bangumi/relay/upload" + path
        val response = try {
            if (method == "GET") client.get(url, headers) else client.post(url, body.toString(), headers)
        } catch (error: RemoteHttpException) {
            if (error.statusCode == 404) error("云端服务暂未开启收藏对比同步功能，请稍后再试")
            if (error.statusCode == 401) throw bangumiFailure(error.statusCode, error.responseBody, session)
            val message = runCatching { JSONObject(error.responseBody).optString("message") }.getOrNull()
            throw IllegalStateException(safeCloudMessage(message) ?: "上传暂时失败，请重新读取对比后重试")
        }
        verifySession()
        val root = JSONObject(response)
        check(root.optString("status") == "success") { "服务器未确认操作成功，请重新读取" }
        root.getJSONObject("data")
    }

    private companion object {
        const val ACCOUNT_VERIFY_TTL_MS = 20_000L
    }
}
