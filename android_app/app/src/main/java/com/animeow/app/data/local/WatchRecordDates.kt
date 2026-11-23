package com.animeow.app.data.local

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun watchRecordDate(value: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value?.take(10)) }.getOrNull()
