package com.animeow.app.util

import com.animeow.app.data.cloud.safeCloudMessage

private val UNSAFE_ERROR_MARKERS = listOf(
    "/",
    "\\",
    "content://",
    "file://",
    "http://",
    "https://",
    "Bearer",
    "token",
    "Exception",
    "SQLITE",
    "URI:",
    "\n",
)

internal fun userSafeErrorMessage(value: String?, fallback: String): String =
    safeCloudMessage(value)?.takeIf { message ->
        message.any { it in '\u4e00'..'\u9fff' } &&
            UNSAFE_ERROR_MARKERS.none { marker -> message.contains(marker, ignoreCase = true) }
    } ?: fallback

internal fun userSafeErrorMessage(error: Throwable, fallback: String): String =
    userSafeErrorMessage(error.message, fallback)

internal fun Throwable.userSafeMessage(fallback: String): String =
    userSafeErrorMessage(this, fallback)

internal fun prefixedUserError(
    prefix: String,
    error: Throwable,
    fallback: String = "${prefix.trimEnd('：', ':')}，请稍后重试",
): String {
    val cleanPrefix = prefix.trimEnd('：', ':')
    val detail = userSafeErrorMessage(error.message, "")
    return when {
        detail.isBlank() -> fallback
        detail.startsWith(cleanPrefix) -> detail
        else -> "$cleanPrefix：$detail"
    }
}
