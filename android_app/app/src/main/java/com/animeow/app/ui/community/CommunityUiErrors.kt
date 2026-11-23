package com.animeow.app.ui.community

import com.animeow.app.data.cloud.safeCloudMessage

internal fun communityFailureMessage(value: String?, fallback: String): String = safeCloudMessage(value)?.takeIf { message ->
    message.any { it in '\u4e00'..'\u9fff' } && listOf("/", "\\", "Bearer", "token", "\n").none { message.contains(it, ignoreCase = true) }
} ?: fallback
