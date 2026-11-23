package com.animeow.app.data.remote

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class JsonHttpClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxResponseBytes: Int? = null,
) {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request(url = url, method = "GET", headers = headers)

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        requestBytes(url = url, method = "GET", headers = headers)

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String = request(url = url, method = "POST", body = body, headers = headers)

    suspend fun put(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String = request(url = url, method = "PUT", body = body, headers = headers)

    suspend fun delete(url: String, headers: Map<String, String> = emptyMap()): String =
        request(url = url, method = "DELETE", headers = headers)

    private suspend fun request(
        url: String,
        method: String,
        body: String? = null,
        headers: Map<String, String>,
    ): String = requestBytes(url, method, body, headers).toString(Charsets.UTF_8)

    private suspend fun requestBytes(
        url: String,
        method: String,
        body: String? = null,
        headers: Map<String, String>,
    ): ByteArray = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = URL(url).openConnection() as HttpURLConnection
            // A cancelled picker/feed/backup request must unblock a pending socket read as well.
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                headers.forEach(connection::setRequestProperty)
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.use { input ->
                    if (maxResponseBytes == null) input.readBytes() else {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > maxResponseBytes) throw java.io.IOException("返回内容过大")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } ?: ByteArray(0)
                if (status !in 200..299) {
                    throw RemoteHttpException(status, response.toString(Charsets.UTF_8).take(500))
                }
                continuation.resume(response)
            } catch (error: Exception) {
                continuation.resumeWithException(remoteTransportFailure(error))
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 12_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000

        // Bangumi 官方 UA 政策（2022-06 起）：开发者个人 ID + 应用名 + 版本号 + 项目主页；
        // 版本号随发版自动更新，不允许写死
        val USER_AGENT = "xunlys7930/AniMeow/${com.animeow.app.BuildConfig.VERSION_NAME} (+https://github.com/xunlys7930/AniMeow)"
    }
}

/** Keep socket addresses and TLS internals out of messages displayed on app screens. */
internal fun remoteTransportFailure(error: Exception): Exception {
    val message = when (error) {
        is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
        is java.net.UnknownHostException -> "网络连接不可用，请检查网络后重试"
        is java.net.ConnectException -> "暂时连接不上服务，请稍后重试"
        is javax.net.ssl.SSLException -> "无法建立安全连接，请检查网络和设备时间"
        is java.io.IOException -> "网络连接中断，请稍后重试"
        else -> return error
    }
    return java.io.IOException(message, error)
}

internal class RemoteHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("远程服务暂时不可用")

internal fun userFriendlyRemoteFailure(
    sourceName: String,
    error: Throwable,
): String {
    val http = generateSequence(error) { it.cause }
        .filterIsInstance<RemoteHttpException>()
        .firstOrNull()
    return when (http?.statusCode) {
        401 -> "$sourceName 拒绝访问，请检查数据源地址或访问配置"
        403 -> "$sourceName 访问受限，请检查网络或在数据源设置中启用代理"
        429 -> "$sourceName 请求过于频繁或额度暂时用尽，请稍后重试；如使用代理，其额度由全体用户共享、每日刷新"
        404, 405, 410, 501 -> "$sourceName 暂不支持此功能，已自动跳过"
        in 500..599 -> "$sourceName 暂时不可用，已自动跳过"
        else -> "$sourceName 连接失败，已自动跳过"
    }
}

/** A valid empty result or bad request should never spend a second request on a proxy. */
internal fun shouldTryRemoteFallback(error: Throwable): Boolean {
    val http = generateSequence(error) { it.cause }.filterIsInstance<RemoteHttpException>().firstOrNull()
    return http == null || http.statusCode in listOf(401, 403, 408, 429) || http.statusCode >= 500
}

/**
 * 收集一次失败里所有 HTTP 状态码。
 *
 * `preferred()` 会把"首选线路失败"挂到最终异常上，所以只看最外层会漏掉另一条线路的结果。
 */
internal fun collectRemoteFailures(error: Throwable): List<RemoteHttpException> {
    val found = mutableListOf<RemoteHttpException>()
    val visited = mutableSetOf<Throwable>()

    fun visit(current: Throwable?) {
        if (current == null || !visited.add(current)) return
        if (current is RemoteHttpException) found += current
        visit(current.cause)
        current.suppressed?.forEach(::visit)
    }

    visit(error)
    return found
}

/**
 * 把 Bangumi 失败翻译成用户能照着做的提示。
 *
 * `RemoteHttpException` 的 message 是一句恒定的"远程服务暂时不可用"，状态码在界面层丢失，
 * 于是"用户名不存在(404)"和"线路挂了(502)"长得一模一样——这正是 2026-09-19 那次
 * "去掉 @ 也没用"排查困难的直接原因。这里按状态码给出可执行的下一步。
 */
internal fun bangumiFailureMessage(error: Throwable, fallback: String): String {
    val statuses = collectRemoteFailures(error).map { it.statusCode }
    return when {
        statuses.contains(404) -> "找不到这个 Bangumi 用户，请确认用户名或 UID 后重试"
        statuses.contains(401) -> "Bangumi 拒绝了这次读取，请稍后重试；持续失败请更换 Bangumi 线路"
        statuses.contains(403) -> "当前 Bangumi 线路不支持这个请求，请到设置里更换线路后重试"
        statuses.contains(429) -> "请求过于频繁，请稍后重试"
        statuses.any { it >= 500 } -> "Bangumi 服务端暂时不可用，请稍后重试"
        else -> error.embeddedMessage() ?: error.message?.takeIf(String::isNotBlank) ?: fallback
    }
}

/** 后端把真实原因放在 JSON 的 message 字段里时，取出来给用户看。 */
private fun Throwable.embeddedMessage(): String? =
    message?.substringAfter("message\":\"")?.substringBefore('"')?.takeIf(String::isNotBlank)

internal fun requireValidRemoteBase(value: String) {
    val uri = runCatching { java.net.URI(value) }.getOrNull()
    require(uri != null && uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.fragment == null) { "请输入有效的 HTTP(S) 地址，不要包含账号密码" }
}
