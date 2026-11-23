package com.animeow.app.data.reader.legado

import android.security.NetworkSecurityPolicy
import com.animeow.app.data.reader.LegadoLanConfig
import com.animeow.app.util.userSafeMessage
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LegadoLanClient {
    private const val CONNECT_TIMEOUT_MS = 3_500
    private const val READ_TIMEOUT_MS = 8_000

    suspend fun fetchBookshelf(config: LegadoLanConfig): List<LegadoBookItem> = withContext(Dispatchers.IO) {
        val (host, port) = normalizeLanEndpoint(config)
        val cleartextPermitted = runCatching {
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
        }.getOrDefault(true)
        if (!cleartextPermitted) {
            throw IllegalStateException(
                "当前安装包安全策略未开放局域网 HTTP 明文连接，请改用上方「备份文件导入（.zip 或 .json）」或「HTTPS WebDAV」同步",
            )
        }
        val url = "http://$host:$port/getBookshelf"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("《开源阅读》局域网服务响应异常（状态码 $code），请确认已在《开源阅读》中开启「Web 服务」")
            }
            connection.inputStream.use { LegadoBookshelfParser.parseStream(it, "bookshelf.json") }
        } catch (error: Exception) {
            if (error is IllegalArgumentException || error is IllegalStateException) throw error
            throw IllegalStateException(
                error.userSafeMessage("无法连接《开源阅读》Web 服务，请确认两台设备在同一 Wi-Fi 且《开源阅读》已开启「Web 服务」"),
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun normalizeLanEndpoint(config: LegadoLanConfig): Pair<String, Int> {
        var rawHost = config.host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()
        var port = config.port
        if (':' in rawHost) {
            val hostPart = rawHost.substringBefore(':')
            val portPart = rawHost.substringAfter(':').toIntOrNull()
            rawHost = hostPart
            if (portPart != null) port = portPart
        }
        require(rawHost.isNotEmpty()) { "请填写《开源阅读》显示的局域网 IP 地址（如 192.168.1.10）" }
        require(port in 1..65535) { "端口号需在 1～65535 之间（《开源阅读》默认为 1122）" }
        require(LegadoWebDavClient.isPrivateOrLoopbackHost(rawHost)) {
            "局域网直连仅允许同一局域网私有地址（如 192.168.x.x 或 10.x.x.x），公网同步请使用 HTTPS WebDAV"
        }
        return rawHost to port
    }
}
