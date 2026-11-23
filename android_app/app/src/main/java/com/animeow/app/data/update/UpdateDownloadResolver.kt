package com.animeow.app.data.update

import com.animeow.app.data.remote.remoteTransportFailure
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun updateDownloadUri(value: String, baseUrl: String? = null): URI? = runCatching {
    val raw = URI(value.trim())
    val uri = if (!raw.isAbsolute && !baseUrl.isNullOrBlank()) URI(baseUrl).resolve(raw) else raw
    uri.takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }
}.getOrNull()

/** Resolve redirects before handing the request to Android's system downloader. */
internal class UpdateDownloadResolver {
    suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        var target = updateDownloadUri(url) ?: error("下载地址无效，请重新检查更新")
        val visited = mutableSetOf<String>()
        repeat(6) {
            check(visited.add(target.toString())) { "下载线路重复跳转，请更换线路后重试" }
            var response = probe(target, "HEAD")
            // Some older file servers do not implement HEAD. Request only the first byte.
            if (response.status in setOf(405, 501)) response = probe(target, "GET")
            if (response.status in setOf(301, 302, 303, 307, 308)) {
                val location = response.location?.takeIf { it.isNotBlank() }
                    ?: error("下载线路没有返回安装包地址，请稍后重试")
                val next = updateDownloadUri(location, target.toString())
                    ?: error("下载线路返回了无效地址，请更换线路后重试")
                check(!(target.scheme.equals("https", true) && next.scheme.equals("http", true))) {
                    "下载线路无法保持安全连接，请更换线路或稍后重试"
                }
                target = next
            } else {
                check(response.status in 200..299) {
                    when (response.status) {
                        401, 403 -> "此安装包暂时无法下载，请更换线路或稍后重试"
                        404, 410 -> "此安装包已失效，请重新检查更新"
                        in 500..599 -> "下载服务器暂时不可用，请稍后重试"
                        else -> "下载线路暂时不可用，请更换线路后重试"
                    }
                }
                val type = response.type.orEmpty().substringBefore(';').lowercase()
                check(type !in setOf("text/html", "text/plain", "application/json", "application/xml", "text/xml")) {
                    "这条线路返回的是网页，未找到安装包，请更换线路"
                }
                return@withContext target.toString()
            }
        }
        error("下载线路跳转过多，请更换线路后重试")
    }

    private suspend fun probe(uri: URI, method: String): ProbeResult = suspendCancellableCoroutine { continuation ->
        val connection = uri.toURL().openConnection() as HttpURLConnection
        continuation.invokeOnCancellation { connection.disconnect() }
        try {
            if (!continuation.isActive) return@suspendCancellableCoroutine
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            if (method == "GET") connection.setRequestProperty("Range", "bytes=0-0")
            // Download routes are public. Neither user sessions nor shared API credentials
            // may be forwarded across a host/protocol redirect.
            continuation.resume(ProbeResult(connection.responseCode, connection.getHeaderField("Location"), connection.contentType))
        } catch (error: Exception) {
            continuation.resumeWithException(remoteTransportFailure(error))
        } finally { connection.disconnect() }
    }

    private data class ProbeResult(val status: Int, val location: String?, val type: String?)
}
