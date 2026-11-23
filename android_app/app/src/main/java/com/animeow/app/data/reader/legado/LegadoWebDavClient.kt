package com.animeow.app.data.reader.legado

import android.util.Base64
import android.util.Xml
import com.animeow.app.BuildConfig
import com.animeow.app.data.reader.LegadoWebDavConfig
import com.animeow.app.util.userSafeMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

data class LegadoWebDavFetchResult(
    val sourceFileName: String,
    val books: List<LegadoBookItem>,
)

object LegadoWebDavClient {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_PROPFIND_BYTES = 2 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

    suspend fun fetchBookshelf(config: LegadoWebDavConfig): LegadoWebDavFetchResult = withContext(Dispatchers.IO) {
        val baseUri = validateWebDavEndpoint(config.serverUrl)
        require(config.username.isNotBlank() && config.password.isNotBlank()) {
            "请填写完整的 WebDAV 账号与应用密码"
        }
        val targetUrl = buildTargetUrl(baseUri, config.remotePath)
        val loweredPath = URI(targetUrl).path.orEmpty().lowercase(Locale.ROOT)
        if (loweredPath.endsWith(".zip") || loweredPath.endsWith(".json")) {
            val fileName = loweredPath.substringAfterLast('/')
            val bytes = downloadBytes(targetUrl, config, MAX_ARCHIVE_BYTES)
            val books = ByteArrayInputStream(bytes).use { LegadoBookshelfParser.parseStream(it, fileName) }
            return@withContext LegadoWebDavFetchResult(sourceFileName = fileName, books = books)
        }

        val directoryUrl = if (targetUrl.endsWith("/")) targetUrl else "$targetUrl/"
        val entries = listDirectoryEntries(directoryUrl, config)
        val chosen = selectBestEntry(entries)
            ?: throw IllegalStateException("未在该 WebDAV 目录中找到《开源阅读》备份（backup*.zip 或 bookshelf.json），请核对远端目录路径")
        val resolvedDownloadUrl = resolveEntryHref(baseUri, chosen.href)
        val bytes = downloadBytes(resolvedDownloadUrl, config, MAX_ARCHIVE_BYTES)
        val books = ByteArrayInputStream(bytes).use { LegadoBookshelfParser.parseStream(it, chosen.fileName) }
        LegadoWebDavFetchResult(sourceFileName = chosen.fileName, books = books)
    }

    internal fun validateWebDavEndpoint(rawUrl: String): URI {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "请填写 WebDAV 服务器地址" }
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw IllegalArgumentException("WebDAV 地址格式不正确，请检查后重试")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") {
            "WebDAV 地址必须使用 HTTPS 协议"
        }
        val host = uri.host?.trim()?.lowercase(Locale.ROOT)
        require(!host.isNullOrEmpty()) { "WebDAV 地址缺少服务器域名" }
        if (scheme == "http" && !isPrivateOrLoopbackHost(host)) {
            throw IllegalArgumentException("为保护账号密码安全，公网 WebDAV 服务器必须使用 HTTPS 加密连接")
        }
        return uri
    }

    internal fun isPrivateOrLoopbackHost(host: String): Boolean {
        val clean = host.lowercase(Locale.ROOT)
        if (clean == "localhost" || clean == "127.0.0.1" || clean == "::1") return true
        val parts = clean.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return false
        val first = parts[0].toInt()
        val second = parts[1].toInt()
        return first == 10 ||
            (first == 192 && second == 168) ||
            (first == 172 && second in 16..31) ||
            (first == 127)
    }

    private fun buildTargetUrl(baseUri: URI, remotePath: String): String {
        val baseText = baseUri.toString().trimEnd('/')
        val cleanRemote = remotePath.trim()
        if (cleanRemote.isEmpty() || cleanRemote == "/") return "$baseText/"
        if (cleanRemote.startsWith("https://", true) || cleanRemote.startsWith("http://", true)) {
            val targetUri = validateWebDavEndpoint(cleanRemote)
            require(targetUri.host.equals(baseUri.host, ignoreCase = true)) {
                "远端路径域名必须与 WebDAV 服务器一致"
            }
            return targetUri.toString()
        }
        val basePath = baseUri.path.orEmpty().trimEnd('/')
        val normalizedSub = "/" + cleanRemote.trimStart('/')
        return if (basePath.isNotEmpty() && normalizedSub.startsWith("$basePath/")) {
            "${baseUri.scheme}://${baseUri.authority}$normalizedSub"
        } else {
            "$baseText$normalizedSub"
        }
    }

    private fun resolveEntryHref(baseUri: URI, href: String): String {
        val trimmed = href.trim()
        if (trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true)) {
            val resolved = URI(trimmed)
            require(resolved.host.equals(baseUri.host, ignoreCase = true)) {
                "WebDAV 返回了跨站下载链接，已出于安全原因拦截"
            }
            return resolved.toString()
        }
        val slashHref = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return "${baseUri.scheme}://${baseUri.authority}$slashHref"
    }

    internal data class WebDavEntry(
        val href: String,
        val fileName: String,
        val isCollection: Boolean,
        val lastModifiedText: String,
    )

    private fun listDirectoryEntries(directoryUrl: String, config: LegadoWebDavConfig): List<WebDavEntry> {
        val connection = openAuthenticatedConnection(directoryUrl, config, method = "PROPFIND")
        connection.setRequestProperty("Depth", "1")
        connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw mapHttpFailure(code)
            }
            val xmlBytes = connection.inputStream.use { readLimited(it, MAX_PROPFIND_BYTES) }
            return parsePropfindXml(ByteArrayInputStream(xmlBytes))
        } catch (error: Exception) {
            if (error is IllegalArgumentException || error is IllegalStateException) throw error
            throw IllegalStateException(error.userSafeMessage("连接 WebDAV 服务器失败，请检查地址与网络"))
        } finally {
            connection.disconnect()
        }
    }

    internal fun parsePropfindXml(input: InputStream): List<WebDavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, "UTF-8")
        val entries = mutableListOf<WebDavEntry>()
        var inResponse = false
        var href = ""
        var lastModified = ""
        var isCollection = false
        var currentTag = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val localName = parser.name?.lowercase(Locale.ROOT).orEmpty()
                    currentTag = localName
                    if (localName == "response") {
                        inResponse = true
                        href = ""
                        lastModified = ""
                        isCollection = false
                    } else if (inResponse && localName == "collection") {
                        isCollection = true
                    }
                }
                XmlPullParser.TEXT -> if (inResponse) {
                    val text = parser.text.orEmpty().trim()
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            "href" -> if (href.isEmpty()) href = text
                            "getlastmodified" -> lastModified = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val localName = parser.name?.lowercase(Locale.ROOT).orEmpty()
                    if (localName == "response" && inResponse) {
                        val decodedName = runCatching {
                            java.net.URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")
                        }.getOrDefault(href.trimEnd('/').substringAfterLast('/'))
                        if (href.isNotBlank() && decodedName.isNotBlank()) {
                            entries += WebDavEntry(
                                href = href,
                                fileName = decodedName,
                                isCollection = isCollection,
                                lastModifiedText = lastModified,
                            )
                        }
                        inResponse = false
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return entries
    }

    internal fun selectBestEntry(entries: List<WebDavEntry>): WebDavEntry? {
        val files = entries.filterNot { it.isCollection }
        val backupZips = files.filter { entry ->
            val lower = entry.fileName.lowercase(Locale.ROOT)
            lower.endsWith(".zip") && (lower.startsWith("backup") || lower.contains("legado"))
        }
        if (backupZips.isNotEmpty()) {
            return backupZips.maxByOrNull { it.fileName.lowercase(Locale.ROOT) }
        }
        val directJson = files.firstOrNull { it.fileName.equals("bookshelf.json", ignoreCase = true) }
        if (directJson != null) return directJson
        return files.filter { it.fileName.lowercase(Locale.ROOT).endsWith(".zip") }
            .maxByOrNull { it.fileName.lowercase(Locale.ROOT) }
    }

    private fun downloadBytes(url: String, config: LegadoWebDavConfig, maxBytes: Int): ByteArray {
        val connection = openAuthenticatedConnection(url, config, method = "GET")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw mapHttpFailure(code)
            }
            return connection.inputStream.use { readLimited(it, maxBytes) }
        } catch (error: Exception) {
            if (error is IllegalArgumentException || error is IllegalStateException) throw error
            throw IllegalStateException(error.userSafeMessage("下载 WebDAV 备份文件失败，请检查网络连接"))
        } finally {
            connection.disconnect()
        }
    }

    private fun openAuthenticatedConnection(
        url: String,
        config: LegadoWebDavConfig,
        method: String,
    ): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = method
            val credentials = "${config.username.trim()}:${config.password}"
            val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            setRequestProperty("Authorization", "Basic $encoded")
            setRequestProperty("User-Agent", "AniMeow-LegadoSync/${BuildConfig.VERSION_NAME}")
        }
        return connection
    }

    private fun mapHttpFailure(statusCode: Int): IllegalStateException = when (statusCode) {
        401, 403 -> IllegalStateException("WebDAV 账号或应用密码校验未通过，请确认使用的是网盘生成的独立应用密码")
        404 -> IllegalStateException("未找到指定的 WebDAV 目录或文件，请检查远端目录（如 /legado/）是否已创建备份")
        405 -> IllegalStateException("该地址不支持 WebDAV 目录读取，请直接填写 backup.zip 或 bookshelf.json 的完整地址")
        in 500..599 -> IllegalStateException("WebDAV 网盘服务暂时繁忙，请稍后重试")
        else -> IllegalStateException("WebDAV 请求未成功（状态码 $statusCode），请检查地址与权限")
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= maxBytes) { "远端备份文件体积超过安全限制，请仅同步《开源阅读》书架备份" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
