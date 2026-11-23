package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import java.time.LocalDate
import java.time.Instant

/**
 * 视为"已看完"的状态名。
 *
 * 三处判定必须保持一致：进度补全（`normalizeCompletionProgress`）、看完日期写入（本文件）、
 * 以及 DAO 里的批量状态更新。此前进度补全只认"看完"，看完日期却认三个写法，
 * 于是把作品标成"已完成/完成"时进度不会补满，统计时长也跟着不准。
 */
internal val COMPLETED_STATUSES = setOf("看完", "已完成", "完成")

/** 自动停止追番提醒的状态：看完 + 弃坑（内置名为"弃坑"，"弃番"/"抛弃"为历史与导入写法）。 */
val REMINDER_STOP_STATUSES = COMPLETED_STATUSES + setOf("弃坑", "弃番", "抛弃")

/** Record a new transition; do not invent a start date for an imported partial watch. */
internal fun AnimeEntity.withWatchStatusDates(nextStatus: String, today: LocalDate = LocalDate.now()): AnimeEntity {
    if (status == nextStatus) return this
    val now = System.currentTimeMillis()
    return copy(
        status = nextStatus,
        statusChangedAt = now,
        updatedAt = now,
        watchStartDate = watchStartDate?.takeIf(String::isNotBlank)
            ?: today.toString().takeIf { nextStatus == "在看" && watchedEpisodes == 0 },
        watchFinishDate = watchFinishDate?.takeIf(String::isNotBlank)
            ?: today.toString().takeIf { nextStatus in COMPLETED_STATUSES },
    )
}

internal fun AnimeEntity.statusOrderTime(): Long = statusChangedAt
    ?: createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    ?: 0L

internal fun AnimeEntity.lastModifiedTime(): Long = listOfNotNull(
    updatedAt,
    statusChangedAt,
    createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
).maxOrNull() ?: 0L
