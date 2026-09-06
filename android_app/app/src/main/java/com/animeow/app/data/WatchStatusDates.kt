package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import java.time.LocalDate

/** Record a new transition; do not invent a start date for an imported partial watch. */
internal fun AnimeEntity.withWatchStatusDates(nextStatus: String, today: LocalDate = LocalDate.now()): AnimeEntity {
    if (status == nextStatus) return this
    return copy(
        status = nextStatus,
        watchStartDate = watchStartDate?.takeIf(String::isNotBlank)
            ?: today.toString().takeIf { nextStatus == "在看" && watchedEpisodes == 0 },
        watchFinishDate = watchFinishDate?.takeIf(String::isNotBlank)
            ?: today.toString().takeIf { nextStatus in setOf("看完", "已完成", "完成") },
    )
}
