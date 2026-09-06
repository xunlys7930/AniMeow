package com.animeow.app.data.cloud

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.animeow.app.BuildConfig
import com.animeow.app.data.backup.NativeBackupFormatException
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.backup.NativeBackupSummary
import com.animeow.app.data.backup.NativeRestorePoint
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.backup.NativeRestoreStrategy
import com.animeow.app.data.backup.PreparedNativeRestore
import com.animeow.app.data.importer.LegacyBackupImporter
import com.animeow.app.data.importer.LegacyImportSummary
import com.animeow.app.data.importer.PreparedLegacyImport
import com.animeow.app.data.remote.JsonHttpClient
import com.animeow.app.data.remote.RemoteHttpException
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class CloudBackupInfo(
    val id: Long,
    val fileName: String,
    val payloadSize: Long,
    val uploadDate: String,
    val createdAt: String,
    val contentHash: String? = null,
    val deviceId: String? = null,
    val clientModifiedAt: String? = null,
)

data class CloudFeedbackItem(
    val id: Long,
    val username: String,
    val content: String,
    val status: String,
    val reply: String?,
    val replyAt: String?,
    val isDeveloper: Boolean,
    val createdAt: String,
)

data class CloudFeedbackPool(
    val items: List<CloudFeedbackItem>,
    val viewerIsAdmin: Boolean,
    val viewerIsDeveloper: Boolean,
)

data class CloudAnimeAnalysisResult(
    val id: Long,
    val model: String,
    val analysis: String,
    val createdAt: String,
)

enum class CloudBackupFormat {
    NATIVE,
    LEGACY,
}

data class CloudRestorePreview(
    val animeCount: Int,
    val characterCount: Int,
    val tagCount: Int,
    val coverCount: Int,
)

class PreparedCloudRestore internal constructor(
    val backup: CloudBackupInfo,
    val format: CloudBackupFormat,
    val preview: CloudRestorePreview,
    internal val nativePrepared: PreparedNativeRestore? = null,
    internal val legacyPrepared: PreparedLegacyImport? = null,
    internal val legacySummary: LegacyImportSummary? = null,
    internal var restorePoint: NativeRestorePoint? = null,
) {
    internal val nativeSummary: NativeBackupSummary?
        get() = nativePrepared?.summary
}

class PreparedCloudUpload internal constructor(
    internal val file: File,
    val summary: NativeBackupSummary,
)

data class CloudRestoreResult(
    val summary: NativeBackupSummary,
    val restorePoint: NativeRestorePoint,
)

internal class CloudAccountService(
    context: Context,
    private val nativeBackupService: NativeBackupService,
    private val legacyBackupImporter: LegacyBackupImporter,
    private val client: JsonHttpClient = JsonHttpClient(),
) {
    private val appContext = context.applicationContext
    private val sessionStore = SecureCloudSessionStore(appContext)
    private val deviceRegistrationId = DeviceRegistrationId.from(appContext)

    val isConfigured: Boolean get() = BuildConfig.CLOUD_API_BASE.isNotBlank()

    fun loadSession(): CloudSession? = sessionStore.load()

    fun logout() = sessionStore.clear()

    suspend fun register(username: String, password: String): CloudSession = authenticate(
        path = "/api/auth/register",
        body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("device_registration_id", deviceRegistrationId),
    )

    suspend fun login(username: String, password: String): CloudSession = authenticate(
        path = "/api/auth/login",
        body = JSONObject().put("username", username.trim()).put("password", password),
    )

    suspend fun resetPassword(username: String, password: String, inviteCode: String) {
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("device_registration_id", deviceRegistrationId)
        inviteCode.trim().takeIf(String::isNotEmpty)?.let {
            body.put("invite_code", it.uppercase(java.util.Locale.ROOT))
        }
        requestJson(
            method = "POST",
            path = "/api/auth/reset-password",
            body = body,
        )
    }

    suspend fun listBackups(session: CloudSession): List<CloudBackupInfo> {
        val root = requestJson("GET", "/api/user/backups", session)
        return root.optJSONArray("data").toCloudBackups().sortedByDescending(CloudBackupInfo::id)
    }

    suspend fun prepareUpload(): PreparedCloudUpload {
        val directory = File(appContext.cacheDir, "shared/cloud_upload").apply { mkdirs() }
        val file = File(directory, "AniMeow_${System.currentTimeMillis()}.animeow.zip")
        try {
            val summary = nativeBackupService.export(file)
            require(file.length() in 1..MAX_CLOUD_BACKUP_BYTES) {
                "云端备份需小于 ${MAX_CLOUD_BACKUP_BYTES / 1024 / 1024} MB"
            }
            return PreparedCloudUpload(file, summary)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    suspend fun uploadPrepared(
        session: CloudSession,
        prepared: PreparedCloudUpload,
        deviceId: String? = null,
    ): CloudBackupInfo {
        try {
            // Read file once, compute SHA-256 of the actual ZIP bytes being
            // uploaded.  The server independently computes sha256(payload)
            // on the decoded bytes, so the hash must be over the file content,
            // not over the logical backup fingerprint.
            val fileBytes = prepared.file.readBytes()
            val contentHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(fileBytes)
                .joinToString("") { "%02x".format(it) }
            val body = JSONObject()
                .put("file_name", prepared.file.name)
                .put("backup_base64", Base64.encodeToString(fileBytes, Base64.NO_WRAP))
                .put("content_hash", contentHash)
                .put("client_modified_at", prepared.summary.createdAt)
            deviceId?.takeIf(String::isNotBlank)?.let { body.put("device_id", it) }
            val root = requestJson("POST", "/api/user/backups", session, body)
            return root.getJSONObject("data").toCloudBackup()
        } finally {
            prepared.file.delete()
        }
    }

    suspend fun uploadBackup(session: CloudSession, deviceId: String? = null): CloudBackupInfo =
        uploadPrepared(session, prepareUpload(), deviceId)

    fun discard(prepared: PreparedCloudUpload) {
        prepared.file.delete()
    }

    suspend fun prepareRestore(session: CloudSession, backup: CloudBackupInfo): PreparedCloudRestore {
        val bytes = try {
            client.getBytes(
                url = baseUrl() + "/api/user/backups/${backup.id}/download",
                headers = mapOf("Authorization" to "Bearer ${session.token}", "Accept" to "application/zip"),
            )
        } catch (exception: RemoteHttpException) {
            throwRemoteFailure(exception, authenticated = true)
        }
        require(bytes.size.toLong() in 1..MAX_CLOUD_BACKUP_BYTES) { "云端备份文件大小异常" }
        val directory = File(appContext.cacheDir, "shared/cloud_restore").apply { mkdirs() }
        val file = File(directory, "restore_${backup.id}_${System.currentTimeMillis()}.zip")
        return try {
            file.writeBytes(bytes)
            val uri = fileProviderUri(file)
            try {
                val native = nativeBackupService.prepareRestore(uri)
                PreparedCloudRestore(
                    backup = backup,
                    format = CloudBackupFormat.NATIVE,
                    preview = CloudRestorePreview(
                        animeCount = native.summary.animeCount,
                        characterCount = native.summary.characterCount,
                        tagCount = native.summary.tagCount,
                        coverCount = native.summary.coverCount,
                    ),
                    nativePrepared = native,
                )
            } catch (nativeFormatError: NativeBackupFormatException) {
                val legacy = try {
                    legacyBackupImporter.prepare(uri)
                } catch (legacyError: Throwable) {
                    nativeFormatError.addSuppressed(legacyError)
                    throw nativeFormatError
                }
                PreparedCloudRestore(
                    backup = backup,
                    format = CloudBackupFormat.LEGACY,
                    preview = CloudRestorePreview(
                        animeCount = legacy.summary.animeCount,
                        characterCount = legacy.summary.characterCount,
                        tagCount = legacy.summary.tagCount,
                        coverCount = legacy.summary.coverCount,
                    ),
                    legacyPrepared = legacy,
                    legacySummary = legacy.summary,
                )
            }
        } finally {
            file.delete()
        }
    }

    suspend fun restore(prepared: PreparedCloudRestore): CloudRestoreResult {
        return restore(prepared, NativeRestoreSelection())
    }

    suspend fun restore(
        prepared: PreparedCloudRestore,
        selection: NativeRestoreSelection,
    ): CloudRestoreResult {
        return when (prepared.format) {
            CloudBackupFormat.NATIVE -> {
                val native = requireNotNull(prepared.nativePrepared) { "原生云备份预检状态无效" }
                val restorePoint = prepared.restorePoint
                    ?: nativeBackupService.createRestorePoint("before_cloud_${prepared.backup.id}")
                        .also { prepared.restorePoint = it }
                CloudRestoreResult(
                    summary = nativeBackupService.restore(native, selection),
                    restorePoint = restorePoint,
                )
            }
            CloudBackupFormat.LEGACY -> {
                require(selection.libraryAndCharacters) {
                    "旧版云备份只包含资料库数据，请选择恢复资料库与封面"
                }
                val legacy = requireNotNull(prepared.legacyPrepared) { "旧版云备份预检状态无效" }
                val restorePoint = prepared.restorePoint
                    ?: nativeBackupService.createRestorePoint("before_cloud_legacy_${prepared.backup.id}")
                        .also {
                            prepared.restorePoint = it
                            legacy.restorePointCreated = true
                        }
                legacyBackupImporter.importPrepared(legacy)
                CloudRestoreResult(
                    summary = nativeBackupService.summarizeCurrent(),
                    restorePoint = restorePoint,
                )
            }
        }
    }

    suspend fun merge(
        prepared: PreparedCloudRestore,
        selection: NativeRestoreSelection,
    ): CloudRestoreResult {
        require(prepared.format == CloudBackupFormat.NATIVE) {
            "旧版云备份只能完整迁移，不能与 2.0 数据逐项合并"
        }
        val native = requireNotNull(prepared.nativePrepared) { "原生云备份预检状态无效" }
        val restorePoint = prepared.restorePoint
            ?: nativeBackupService.createRestorePoint("before_cloud_merge_${prepared.backup.id}")
                .also { prepared.restorePoint = it }
        return CloudRestoreResult(
            summary = nativeBackupService.restore(
                prepared = native,
                selection = selection,
                strategy = NativeRestoreStrategy.MERGE_KEEP_LOCAL,
            ),
            restorePoint = restorePoint,
        )
    }

    fun discard(prepared: PreparedCloudRestore) {
        prepared.nativePrepared?.let(nativeBackupService::discard)
        prepared.legacyPrepared?.let(legacyBackupImporter::discard)
    }

    suspend fun deleteBackup(session: CloudSession, backup: CloudBackupInfo) {
        requestJson("DELETE", "/api/user/backups/${backup.id}", session)
    }

    fun listRestorePoints(): List<NativeRestorePoint> = nativeBackupService.listRestorePoints()

    suspend fun prepareRestorePoint(point: NativeRestorePoint): PreparedNativeRestore =
        nativeBackupService.prepareRestore(point.file)

    suspend fun restorePoint(prepared: PreparedNativeRestore): NativeBackupSummary =
        nativeBackupService.restore(prepared)

    fun discardRestorePoint(prepared: PreparedNativeRestore) = nativeBackupService.discard(prepared)

    suspend fun listFeedback(session: CloudSession? = null): CloudFeedbackPool {
        val root = requestJson("GET", "/api/feedback", session)
        val data = root.optJSONArray("data") ?: JSONArray()
        val viewer = root.optJSONObject("viewer")
        val items = buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.let { json ->
                    add(
                        CloudFeedbackItem(
                            id = json.optLong("id"),
                            username = json.optString("username"),
                            content = json.optString("content"),
                            status = json.optString("status", "open"),
                            reply = if (json.isNull("reply")) null else json.optString("reply").takeIf(String::isNotBlank),
                            replyAt = if (json.isNull("reply_at")) null else json.optString("reply_at").takeIf(String::isNotBlank),
                            isDeveloper = json.optBoolean("is_developer"),
                            createdAt = json.optString("created_at"),
                        ),
                    )
                }
            }
        }
        return CloudFeedbackPool(
            items = items,
            viewerIsAdmin = viewer?.optBoolean("is_admin") == true,
            viewerIsDeveloper = viewer?.optBoolean("is_developer") == true,
        )
    }

    suspend fun submitFeedback(session: CloudSession, content: String): CloudFeedbackItem {
        val clean = content.trim()
        require(clean.length in 5..1000) { "反馈内容需为 5-1000 字" }
        val json = requestJson("POST", "/api/feedback", session, JSONObject().put("content", clean))
            .getJSONObject("data")
        return CloudFeedbackItem(
            id = json.optLong("id"),
            username = json.optString("username", session.username),
            content = json.optString("content", clean),
            status = json.optString("status", "open"),
            reply = if (json.isNull("reply")) null else json.optString("reply").takeIf(String::isNotBlank),
            replyAt = if (json.isNull("reply_at")) null else json.optString("reply_at").takeIf(String::isNotBlank),
            isDeveloper = json.optBoolean("is_developer"),
            createdAt = json.optString("created_at", Instant.now().toString()),
        )
    }

    suspend fun setFeedbackStatus(session: CloudSession, feedbackId: Long, status: String) {
        requestJson(
            "POST",
            "/api/feedback/$feedbackId/status",
            session,
            JSONObject().put("status", status),
        )
    }

    suspend fun replyFeedback(session: CloudSession, feedbackId: Long, reply: String) {
        requestJson(
            "POST",
            "/api/feedback/$feedbackId/reply",
            session,
            JSONObject().put("reply", reply.trim()),
        )
    }

    suspend fun analyzeAnimeStats(
        session: CloudSession,
        stats: JSONObject,
        clientVersion: String,
    ): CloudAnimeAnalysisResult {
        val data = requestJson(
            method = "POST",
            path = "/api/user/anime-analysis",
            session = session,
            body = JSONObject()
                .put("stats", stats)
                .put("client_version", clientVersion.take(32)),
        ).getJSONObject("data")
        return CloudAnimeAnalysisResult(
            id = data.optLong("id"),
            model = data.optString("model", "server"),
            analysis = data.optString("analysis").ifBlank { error("云端模型没有返回分析内容") },
            createdAt = data.optString("created_at", Instant.now().toString()),
        )
    }

    private suspend fun authenticate(path: String, body: JSONObject): CloudSession {
        require(body.optString("username").matches(Regex("[A-Za-z0-9_]{3,24}"))) {
            "用户名需为 3-24 位字母、数字或下划线"
        }
        require(body.optString("password").length in 6..72) { "密码需为 6-72 位" }
        val data = requestJson("POST", path, body = body).getJSONObject("data")
        val user = data.getJSONObject("user")
        val session = CloudSession(
            userId = user.getLong("id"),
            username = user.getString("username"),
            token = data.getString("token"),
        )
        sessionStore.save(session)
        return session
    }

    private suspend fun requestJson(
        method: String,
        path: String,
        session: CloudSession? = null,
        body: JSONObject? = null,
    ): JSONObject {
        val headers = buildMap {
            put("Accept", "application/json")
            session?.let { put("Authorization", "Bearer ${it.token}") }
        }
        val response = try {
            when (method) {
                "GET" -> client.get(baseUrl() + path, headers)
                "POST" -> client.post(baseUrl() + path, body?.toString().orEmpty(), headers)
                "PUT" -> client.put(baseUrl() + path, body?.toString().orEmpty(), headers)
                "DELETE" -> client.delete(baseUrl() + path, headers)
                else -> error("不支持的云端请求方法")
            }
        } catch (exception: RemoteHttpException) {
            throwRemoteFailure(exception, authenticated = session != null)
        }
        val root = JSONObject(response)
        if (root.optString("status") != "success") {
            error(safeCloudMessage(root.optString("message")) ?: "云端请求失败，请稍后重试")
        }
        return root
    }

    private fun baseUrl(): String = BuildConfig.CLOUD_API_BASE.trim().trimEnd('/')
        .takeIf(String::isNotEmpty)
        ?: error("当前构建尚未配置云端 API 地址")

    private fun throwRemoteFailure(exception: RemoteHttpException, authenticated: Boolean): Nothing {
        val failure = cloudRemoteFailure(
            statusCode = exception.statusCode,
            responseBody = exception.responseBody,
            authenticated = authenticated,
        )
        if (failure is CloudSessionExpiredException) sessionStore.clear()
        throw failure
    }

    private fun fileProviderUri(file: File): Uri = FileProvider.getUriForFile(
        appContext,
        "${appContext.packageName}.fileprovider",
        file,
    )

    private fun JSONObject.toCloudBackup(): CloudBackupInfo = CloudBackupInfo(
        id = getLong("id"),
        fileName = optString("file_name", "AniMeow_cloud.zip"),
        payloadSize = optLong("payload_size"),
        uploadDate = optString("upload_date"),
        createdAt = optString("created_at"),
        contentHash = optString("content_hash").takeIf(String::isNotBlank),
        deviceId = optString("device_id").takeIf(String::isNotBlank),
        clientModifiedAt = optString("client_modified_at").takeIf(String::isNotBlank),
    )

    private fun JSONArray?.toCloudBackups(): List<CloudBackupInfo> {
        val array = this ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(it.toCloudBackup()) }
            }
        }
    }

    private companion object {
        const val MAX_CLOUD_BACKUP_BYTES = 20L * 1024 * 1024
    }
}
