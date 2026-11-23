package com.animeow.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.animeow.app.BuildConfig
import com.animeow.app.data.remote.JsonHttpClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class AppUpdateDownloadOption(
    val name: String,
    val url: String,
)

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val updateLog: String,
    val isForceUpdate: Boolean,
    val downloads: List<AppUpdateDownloadOption>,
    val downloadCount: Long,
)

/** downloadUrl is the first downloadUrls entry's legacy alias, not another mirror. */
internal fun normalizeUpdateDownloads(
    namedOptions: List<AppUpdateDownloadOption>,
    fallbackUrl: String?,
    baseUrl: String? = null,
): List<AppUpdateDownloadOption> {
    val candidates = namedOptions.ifEmpty {
        fallbackUrl?.takeIf { it.isNotBlank() }?.let { listOf(AppUpdateDownloadOption("server", it)) }.orEmpty()
    }
    return candidates.mapNotNull { option ->
        updateDownloadUri(option.url, baseUrl)?.let { option.copy(url = it.toString()) }
    }.filter { option ->
        val uri = updateDownloadUri(option.url)
        val host = uri?.host?.lowercase(java.util.Locale.ROOT).orEmpty()
        val name = option.name.lowercase(java.util.Locale.ROOT)
        uri?.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http", "https") &&
            host.isNotEmpty() && uri?.userInfo == null &&
            listOf("github", "gitee", "lanzou", "lanzoup", "蓝奏", "qq群").none { it in name || it in host }
    }.map { it.copy(url = it.url.trim()) }
        .distinctBy { it.url }
        .mapIndexed { index, option ->
            option.copy(name = if (index == 0) "服务器线路" else "服务器备用线路 ${index + 1}")
        }
}

internal data class TrackedUpdateDownload(
    val downloadId: Long,
    val versionName: String,
    val forceUpdate: Boolean,
    val state: UpdateDownloadState,
)

internal sealed interface UpdateDownloadState {
    data class InProgress(val progressPercent: Int?) : UpdateDownloadState
    data object Ready : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
    data object Missing : UpdateDownloadState
}

internal sealed interface UpdateInstallerPreparation {
    data class Ready(val intent: Intent) : UpdateInstallerPreparation
    data class PermissionRequired(val intent: Intent) : UpdateInstallerPreparation
    data class Unavailable(val message: String) : UpdateInstallerPreparation
}

internal class AppUpdateService(
    context: Context,
    private val client: JsonHttpClient = JsonHttpClient(connectTimeoutMs = 10_000, readTimeoutMs = 12_000),
) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val isConfigured: Boolean get() = BuildConfig.CLOUD_API_BASE.isNotBlank()

    suspend fun check(): AppUpdateInfo {
        check(isConfigured) { "更新服务尚未配置，请联系开发者" }
        val root = JSONObject(client.get(BuildConfig.CLOUD_API_BASE.trimEnd('/') + "/api/check-update"))
        check(root.optString("status") == "success") {
            root.optString("message").ifBlank { "更新服务器返回了无效结果" }
        }
        val data = root.optJSONObject("data") ?: error("更新服务器未返回版本信息")
        val namedDownloads = buildList {
            data.optJSONObject("downloadUrls")?.let { options ->
                options.keys().forEach { key ->
                    options.optString(key).trim().takeIf { it.isNotBlank() }?.let { url ->
                        add(AppUpdateDownloadOption(key.trim().ifBlank { "备用线路" }, url))
                    }
                }
            }
        }
        val downloads = normalizeUpdateDownloads(namedDownloads, data.optString("downloadUrl"), BuildConfig.CLOUD_API_BASE)
        return AppUpdateInfo(
            versionCode = data.optInt("versionCode", 0),
            versionName = data.optString("versionName").trim(),
            updateLog = data.optString("updateLog").ifBlank { "本次更新未提供详细说明" },
            isForceUpdate = data.optBoolean("isForceUpdate", false),
            downloads = downloads,
            downloadCount = data.optLong("downloadCount", 0L),
        )
    }

    suspend fun enqueueDownload(info: AppUpdateInfo, option: AppUpdateDownloadOption): Long = enqueueMutex.withLock {
      var queuedId: Long? = null
      try { withContext(Dispatchers.IO) {
        require(option in info.downloads) { "所选下载线路已失效，请重新检查更新" }
        require(isHttpUrl(option.url)) { "服务器未配置可用的安装包下载地址" }
        val resolvedUrl = UpdateDownloadResolver().resolve(option.url)
        currentCoroutineContext().ensureActive()
        val safeVersion = info.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
        val relativePath = "updates/AniMeow_$safeVersion.apk"
        val root = checkNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "外部应用目录当前不可用"
        }
        val destination = File(root, relativePath)
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            error("旧安装包正在使用，请稍后重试或先清理更新缓存")
        }

        val request = DownloadManager.Request(Uri.parse(resolvedUrl))
            .setTitle("AniMeow ${info.versionName}")
            .setDescription("正在通过${option.name}下载安装包")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                relativePath,
            )
        val id = downloadManager.enqueue(request)
        queuedId = id
        preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_FILE_PATH, destination.absolutePath)
            .putInt(KEY_TARGET_VERSION_CODE, info.versionCode)
            .putString(KEY_TARGET_VERSION_NAME, info.versionName)
            .putBoolean(KEY_FORCE_UPDATE, info.isForceUpdate)
            .apply()
        id
      } } catch (error: Throwable) {
          // withContext has prompt cancellation: a successfully queued ID can otherwise be
          // lost when cancellation arrives just before returning to the main thread.
          queuedId?.let { id -> withContext(NonCancellable + Dispatchers.IO) {
              if (isTrackedDownload(id)) discardTrackedDownload(id) else runCatching { downloadManager.remove(id) }
          } }
          throw error
      }
    }

    fun trackedDownload(): TrackedUpdateDownload? {
        val id = trackedDownloadId() ?: return null
        if (trackedTargetAlreadyInstalled()) {
            clearTrackedDownload()
            return null
        }
        val state = downloadState(id)
        if (state == UpdateDownloadState.Missing) {
            clearTrackedDownload()
            return null
        }
        return TrackedUpdateDownload(
            downloadId = id,
            versionName = preferences.getString(KEY_TARGET_VERSION_NAME, null).orEmpty(),
            forceUpdate = preferences.getBoolean(KEY_FORCE_UPDATE, false),
            state = state,
        )
    }

    fun isTrackedDownload(downloadId: Long): Boolean = trackedDownloadId() == downloadId

    fun discardTrackedDownload(downloadId: Long) {
        if (!isTrackedDownload(downloadId)) return
        runCatching { downloadManager.remove(downloadId) }
        runCatching { trackedFile()?.takeIf(File::exists)?.delete() }
        clearTrackedDownload()
    }

    fun downloadState(downloadId: Long): UpdateDownloadState {
        if (!isTrackedDownload(downloadId)) return UpdateDownloadState.Missing
        val query = DownloadManager.Query().setFilterById(downloadId)
        return runCatching {
            downloadManager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use UpdateDownloadState.Missing
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val file = trackedFile()
                        if (file != null && file.isFile && file.length() > 0L) {
                            UpdateDownloadState.Ready
                        } else {
                            UpdateDownloadState.Failed("安装包已被移动或清理，请重新下载")
                        }
                    }
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    -> {
                        val downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        )
                        val total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        )
                        val progress = total.takeIf { it > 0L }
                            ?.let { ((downloaded * 100L) / it).toInt().coerceIn(0, 100) }
                        UpdateDownloadState.InProgress(progress)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        UpdateDownloadState.Failed(updateDownloadFailureMessage(reason))
                    }
                    else -> UpdateDownloadState.Missing
                }
            } ?: UpdateDownloadState.Missing
        }.getOrElse { UpdateDownloadState.Failed(it.message ?: "无法读取系统下载状态") }
    }

    fun launchInstaller(downloadId: Long): UpdateDownloadState {
        val state = downloadState(downloadId)
        if (state == UpdateDownloadState.Ready) {
            appContext.startActivity(
                Intent(appContext, UpdateInstallerActivity::class.java)
                    .putExtra(UpdateInstallerActivity.EXTRA_DOWNLOAD_ID, downloadId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return state
    }

    fun prepareInstaller(downloadId: Long): UpdateInstallerPreparation {
        when (val state = downloadState(downloadId)) {
            UpdateDownloadState.Ready -> Unit
            is UpdateDownloadState.InProgress -> return UpdateInstallerPreparation.Unavailable(
                state.progressPercent?.let { "安装包仍在下载：$it%" } ?: "安装包仍在下载",
            )
            is UpdateDownloadState.Failed -> return UpdateInstallerPreparation.Unavailable(state.message)
            UpdateDownloadState.Missing -> return UpdateInstallerPreparation.Unavailable("找不到对应的更新下载记录")
        }
        val file = trackedFile() ?: return UpdateInstallerPreparation.Unavailable("安装包路径已失效")
        val updateRoot = File(
            checkNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
            "updates",
        ).canonicalFile
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull()
            ?: return UpdateInstallerPreparation.Unavailable("无法读取安装包")
        val expectedPrefix = updateRoot.path + File.separator
        if (
            !canonicalFile.path.startsWith(expectedPrefix) ||
            canonicalFile.extension.lowercase(java.util.Locale.ROOT) != "apk"
        ) {
            return UpdateInstallerPreparation.Unavailable("安装包位置不安全，请重新下载")
        }
        val archive = appContext.packageManager.getPackageArchiveInfo(canonicalFile.path, 0)
            ?: return invalidateTrackedDownload(downloadId, "下载文件不是有效的 Android 安装包")
        if (archive.packageName != appContext.packageName) {
            return invalidateTrackedDownload(downloadId, "安装包包名与当前 AniMeow 不一致，已阻止打开")
        }
        val expectedVersionCode = preferences.getInt(KEY_TARGET_VERSION_CODE, 0).toLong()
        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archive)
        if (expectedVersionCode > 0L && archiveVersionCode != expectedVersionCode) {
            return invalidateTrackedDownload(
                downloadId,
                "安装包版本号与更新服务器声明不一致，已阻止打开",
            )
        }
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            return UpdateInstallerPreparation.PermissionRequired(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}"),
                ),
            )
        }
        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            canonicalFile,
        )
        return UpdateInstallerPreparation.Ready(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun trackedDownloadId(): Long? = preferences.getLong(KEY_DOWNLOAD_ID, -1L).takeIf { it > 0L }

    private fun trackedFile(): File? = preferences.getString(KEY_FILE_PATH, null)
        ?.takeIf(String::isNotBlank)
        ?.let(::File)

    private fun trackedTargetAlreadyInstalled(): Boolean {
        val targetCode = preferences.getInt(KEY_TARGET_VERSION_CODE, 0)
        val targetName = preferences.getString(KEY_TARGET_VERSION_NAME, null).orEmpty()
        return !isUpdateAvailable(
            currentCode = BuildConfig.VERSION_CODE,
            currentName = BuildConfig.VERSION_NAME,
            latestCode = targetCode,
            latestName = targetName,
        )
    }

    private fun clearTrackedDownload() {
        preferences.edit().clear().apply()
    }

    private fun invalidateTrackedDownload(downloadId: Long, message: String): UpdateInstallerPreparation.Unavailable {
        discardTrackedDownload(downloadId)
        return UpdateInstallerPreparation.Unavailable("$message；错误文件已清理，请重新检查更新")
    }

    companion object {
        private val enqueueMutex = Mutex()
        fun isUpdateAvailable(
            currentCode: Int,
            currentName: String,
            latestCode: Int,
            latestName: String,
        ): Boolean = when {
            latestCode > currentCode -> true
            latestCode in 1 until currentCode -> false
            else -> isNewerVersion(currentName, latestName)
        }

        fun isNewerVersion(current: String, latest: String): Boolean =
            compareVersions(latest, current) > 0

        private fun compareVersions(left: String, right: String): Int {
            val leftVersion = parseVersion(left)
            val rightVersion = parseVersion(right)
            val size = maxOf(leftVersion.numbers.size, rightVersion.numbers.size)
            repeat(size) { index ->
                val result = leftVersion.numbers.getOrElse(index) { 0 }
                    .compareTo(rightVersion.numbers.getOrElse(index) { 0 })
                if (result != 0) return result
            }
            if (leftVersion.preRelease.isEmpty() && rightVersion.preRelease.isNotEmpty()) return 1
            if (leftVersion.preRelease.isNotEmpty() && rightVersion.preRelease.isEmpty()) return -1
            val tokenCount = maxOf(leftVersion.preRelease.size, rightVersion.preRelease.size)
            repeat(tokenCount) { index ->
                val leftToken = leftVersion.preRelease.getOrNull(index) ?: return -1
                val rightToken = rightVersion.preRelease.getOrNull(index) ?: return 1
                val result = compareVersionTokens(leftToken, rightToken)
                if (result != 0) return result
            }
            return 0
        }

        private fun compareVersionTokens(left: VersionToken, right: VersionToken): Int = when {
            left.number != null && right.number != null -> left.number.compareTo(right.number)
            left.number != null -> -1
            right.number != null -> 1
            else -> preReleaseRank(left.text).compareTo(preReleaseRank(right.text))
                .takeIf { it != 0 }
                ?: left.text.compareTo(right.text, ignoreCase = true)
        }

        private fun preReleaseRank(value: String): Int = when (value.lowercase(java.util.Locale.ROOT)) {
            "dev", "snapshot" -> 0
            "alpha", "a" -> 1
            "beta", "b" -> 2
            "preview", "pre" -> 3
            "rc" -> 4
            else -> 5
        }

        private fun parseVersion(value: String): ParsedVersion {
            val normalized = value.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val main = normalized.substringBefore('-')
            val numbers = main.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val preRelease = normalized.substringAfter('-', "")
                .let { PRE_RELEASE_TOKEN.findAll(it).map(MatchResult::value).toList() }
                .map { token ->
                    token.toLongOrNull()?.let { VersionToken(number = it) } ?: VersionToken(text = token)
                }
            return ParsedVersion(numbers.ifEmpty { listOf(0) }, preRelease)
        }

        private fun isHttpUrl(value: String): Boolean =
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

        private val PRE_RELEASE_TOKEN = Regex("[A-Za-z]+|\\d+")
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val PREFERENCES_NAME = "app_update_download"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_TARGET_VERSION_CODE = "target_version_code"
        private const val KEY_TARGET_VERSION_NAME = "target_version_name"
        private const val KEY_FORCE_UPDATE = "force_update"
    }
}

internal fun updateDownloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请腾出空间后重新下载"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储位置不可用，请检查存储空间后重试"
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "安装包文件已存在，请重新检查更新后下载"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载线路重复跳转，请更换线路后重试"
    DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "下载连接中断，请检查网络后重新下载"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE, in 400..599 -> "下载服务器暂时无法提供安装包，请重新检查更新或更换线路"
    else -> "系统下载未能完成，请检查网络和存储空间后重试"
}

private data class ParsedVersion(
    val numbers: List<Int>,
    val preRelease: List<VersionToken>,
)

private data class VersionToken(
    val number: Long? = null,
    val text: String = "",
)
