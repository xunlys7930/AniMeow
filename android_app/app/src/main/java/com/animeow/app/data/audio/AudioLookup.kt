package com.animeow.app.data.audio

import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal fun audioLookupCode(value: String): String? {
    val input = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    if (input.matches(Regex("\\d{6,8}"))) return "RJ$input"
    if (input.matches(Regex("(?i)RJ\\d{6,8}"))) return input.uppercase(Locale.ROOT)
    val uri = runCatching { URI(input) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) ||
        uri.host?.lowercase(Locale.ROOT) !in listOf("www.dlsite.com", "dlsite.com")) return null
    return Regex("(?i)/(RJ\\d{6,8})\\.html(?:/|$)").find(uri.path.orEmpty())?.groupValues?.get(1)?.uppercase(Locale.ROOT)
}
