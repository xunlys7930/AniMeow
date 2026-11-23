package com.animeow.app.data.media

import com.animeow.app.data.remote.normalizeBangumiImageUrl
import java.net.URI

internal fun canonicalRemoteImageUrl(value: String?, proxyBase: String): String? {
    val normalized = normalizeBangumiImageUrl(value) ?: return null
    val base = proxyBase.trim().trimEnd('/')
    if (base.isEmpty()) return normalized
    if (normalized == base || normalized.startsWith("$base/")) {
        val suffix = normalized.removePrefix(base)
        return "https://lain.bgm.tv${suffix.takeIf(String::isNotEmpty) ?: "/"}"
    }
    return normalized
}

internal fun bangumiImageProxyFallbackUrl(value: String?, proxyBase: String): String? {
    val original = canonicalRemoteImageUrl(value, proxyBase) ?: return null
    val uri = runCatching { URI(original) }.getOrNull() ?: return null
    val isBgmHost = uri.host?.let { host ->
        host.equals("lain.bgm.tv", ignoreCase = true) ||
            host.equals("bgm.tv", ignoreCase = true) ||
            host.equals("bangumi.in", ignoreCase = true)
    } ?: false
    if (!isBgmHost) return null
    val base = proxyBase.trim().trimEnd('/').takeIf(String::isNotEmpty) ?: return null
    return buildString {
        append(base)
        append(uri.rawPath.orEmpty().ifEmpty { "/" })
        uri.rawQuery?.takeIf(String::isNotEmpty)?.let { append('?').append(it) }
        uri.rawFragment?.takeIf(String::isNotEmpty)?.let { append('#').append(it) }
    }
}

internal fun isRemoteImageUrl(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return normalized.startsWith("https://", ignoreCase = true) ||
        normalized.startsWith("http://", ignoreCase = true) ||
        normalized.startsWith("//")
}

/** Keeps authenticated/local media on its own path; public external covers use one server URL. */
internal fun publicServerImageUrl(value: String, apiBase: String): String? {
    val base = runCatching { URI(apiBase.trim().trimEnd('/')) }.getOrNull() ?: return null
    if (base.scheme !in listOf("https", "http") || base.host == null || base.userInfo != null) return null
    val source = runCatching { URI(value) }.getOrNull() ?: return null
    if (source.scheme !in listOf("https", "http") || source.host == null || source.userInfo != null) return null
    if (source.scheme == base.scheme && source.authority == base.authority) return value
    return base.toString() + "/api/catalog/images?url=" + java.net.URLEncoder.encode(value, "UTF-8")
}
