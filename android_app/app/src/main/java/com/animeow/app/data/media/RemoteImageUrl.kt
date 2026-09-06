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
