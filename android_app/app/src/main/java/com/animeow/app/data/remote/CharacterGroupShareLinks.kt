package com.animeow.app.data.remote

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

private const val SHARE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private val shareCodeRegex = Regex("^[$SHARE_CODE_ALPHABET]{8}$")
private val prefixedShareCodeRegex = Regex(
    pattern = "(?i)(?:cg\\s*#\\s*|分享码\\s*[:：]?\\s*)([$SHARE_CODE_ALPHABET]{8})(?![A-Z0-9])",
)
private val communityIdRegex = Regex("(?i)^cg_[0-9a-f]{16}$")

data class CommunityLaunchRequest(
    val shareCode: String? = null,
    val communityId: String? = null,
) {
    val key: String
        get() = shareCode?.let { "share:$it" } ?: "group:${communityId.orEmpty()}"

    val displayToken: String
        get() = shareCode ?: communityId.orEmpty()
}

data class AniMeowDeepLink(
    val animeId: Long? = null,
    val community: CommunityLaunchRequest? = null,
)

fun normalizeCharacterGroupShareCode(value: String?): String? {
    val normalized = value.orEmpty()
        .trim()
        .removePrefixIgnoreCase("cg#")
        .replace(" ", "")
        .uppercase(Locale.ROOT)
    return normalized.takeIf(shareCodeRegex::matches)
}

fun extractCharacterGroupShareCode(value: CharSequence?): String? {
    val text = value?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return null
    prefixedShareCodeRegex.find(text)?.groupValues?.getOrNull(1)?.let { candidate ->
        return normalizeCharacterGroupShareCode(candidate)
    }
    return normalizeCharacterGroupShareCode(text)
}

fun parseAniMeowDeepLink(value: String?): AniMeowDeepLink? {
    val uri = runCatching { URI(value.orEmpty().trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("animeow", ignoreCase = true)) return null

    val rawSegments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
    val host = uri.host?.lowercase(Locale.ROOT)
    val route = host ?: rawSegments.firstOrNull()?.lowercase(Locale.ROOT) ?: return null
    val segments = if (host == null) rawSegments.drop(1) else rawSegments

    if (route == "anime" || route == "work") {
        val animeId = uri.queryParameter("id")?.toLongOrNull()
            ?: segments.firstOrNull()?.toLongOrNull()
        return animeId?.takeIf { it > 0 }?.let { AniMeowDeepLink(animeId = it) }
    }

    if (route !in setOf("community", "character-group", "character_group")) return null
    val queryCode = uri.queryParameter("shareCode") ?: uri.queryParameter("code")
    normalizeCharacterGroupShareCode(queryCode)?.let { code ->
        return AniMeowDeepLink(community = CommunityLaunchRequest(shareCode = code))
    }
    val queryId = uri.queryParameter("communityId") ?: uri.queryParameter("id")
    normalizeCommunityId(queryId)?.let { id ->
        return AniMeowDeepLink(community = CommunityLaunchRequest(communityId = id))
    }

    val first = segments.firstOrNull()
    val second = segments.getOrNull(1)
    if (first.equals("share", true) || first.equals("code", true)) {
        return normalizeCharacterGroupShareCode(second)?.let { code ->
            AniMeowDeepLink(community = CommunityLaunchRequest(shareCode = code))
        }
    }
    if (first.equals("group", true) || first.equals("detail", true)) {
        return normalizeCommunityId(second)?.let { id ->
            AniMeowDeepLink(community = CommunityLaunchRequest(communityId = id))
        }
    }
    normalizeCharacterGroupShareCode(first)?.let { code ->
        return AniMeowDeepLink(community = CommunityLaunchRequest(shareCode = code))
    }
    return normalizeCommunityId(first)?.let { id ->
        AniMeowDeepLink(community = CommunityLaunchRequest(communityId = id))
    }
}

private fun normalizeCommunityId(value: String?): String? = value.orEmpty()
    .trim()
    .lowercase(Locale.ROOT)
    .takeIf(communityIdRegex::matches)

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

private fun URI.queryParameter(name: String): String? = rawQuery.orEmpty()
    .split('&')
    .asSequence()
    .mapNotNull { pair ->
        val separator = pair.indexOf('=')
        val rawName = if (separator >= 0) pair.substring(0, separator) else pair
        if (!rawName.equals(name, ignoreCase = true)) return@mapNotNull null
        val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
        runCatching { URLDecoder.decode(rawValue, Charsets.UTF_8.name()) }.getOrNull()
    }
    .firstOrNull()
