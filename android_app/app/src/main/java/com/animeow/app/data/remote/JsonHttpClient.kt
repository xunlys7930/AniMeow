package com.animeow.app.data.remote

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JsonHttpClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
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
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
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
            val response = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) {
                throw RemoteHttpException(status, response.toString(Charsets.UTF_8).take(500))
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 12_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
        const val USER_AGENT = "AniMeow/2.0 Android (open-source anime tracker)"
    }
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
        401, 403 -> "$sourceName 的鉴权方式与当前服务不兼容，已自动跳过"
        404, 405, 410, 501 -> "$sourceName 暂不支持此功能，已自动跳过"
        in 500..599 -> "$sourceName 暂时不可用，已自动跳过"
        else -> "$sourceName 连接失败，已自动跳过"
    }
}
