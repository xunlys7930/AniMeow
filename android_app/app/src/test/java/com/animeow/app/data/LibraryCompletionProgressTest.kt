package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 覆盖「状态改为看完时进度自动补满」这条线上缺陷。
 *
 * 症状：作品是 0/12，改成"看完"后详情仍显示 0/12，统计的"估算时长"也不变
 * （它按 `watchedEpisodes * 24 / 60` 计算）。
 * 原因是 `updateStatuses` / `cycleStatus` 调用 `withWatchStatusDates` 时漏了
 * `normalizeCompletionProgress`，于是只有新增/编辑路径会补进度。
 */
class LibraryCompletionProgressTest {
    private fun anime(
        status: String = "在看",
        watched: Int = 0,
        total: Int = 12,
        startDate: String? = null,
        finishDate: String? = null,
    ) = AnimeEntity(
        id = 1,
        title = "测试作品",
        status = status,
        watchedEpisodes = watched,
        totalEpisodes = total,
        watchStartDate = startDate,
        watchFinishDate = finishDate,
    )

    @Test
    fun markingCompletedFillsWatchedEpisodes() {
        val updated = normalizeCompletionProgress(anime(watched = 0).withWatchStatusDates("看完"))
        assertEquals("看完", updated.status)
        assertEquals(12, updated.watchedEpisodes)
    }

    @Test
    fun everyCompletedLabelIsTreatedTheSameWay() {
        // 三个写法在看完日期与 DAO 批量更新里本来就等价，进度补全以前只认"看完"。
        assertEquals(12, normalizeCompletionProgress(anime(watched = 0).copy(status = "已完成")).watchedEpisodes)
        assertEquals(12, normalizeCompletionProgress(anime(watched = 0).copy(status = "完成")).watchedEpisodes)
        assertEquals(12, normalizeCompletionProgress(anime(watched = 0).copy(status = " 看完 ")).watchedEpisodes)
    }

    @Test
    fun partialProgressIsNotLostWhenSwitchingToAFullCompleteStatus() {
        val updated = normalizeCompletionProgress(anime(watched = 7).withWatchStatusDates("看完"))
        assertEquals(12, updated.watchedEpisodes)
    }

    @Test
    fun otherStatusesKeepTheirProgress() {
        val watching = normalizeCompletionProgress(anime(status = "在看", watched = 3).withWatchStatusDates("搁置"))
        assertEquals(3, watching.watchedEpisodes)
        val dropped = normalizeCompletionProgress(anime(status = "在看", watched = 3).withWatchStatusDates("弃番"))
        assertEquals(3, dropped.watchedEpisodes)
    }

    @Test
    fun unknownEpisodeCountIsLeftAloneInsteadOfGuessing() {
        // 总集数未知（0）时不能把进度写成 0 或猜一个值。
        val unknown = normalizeCompletionProgress(anime(watched = 4, total = 0).withWatchStatusDates("看完"))
        assertEquals(4, unknown.watchedEpisodes)
    }

    @Test
    fun alreadyCompleteStaysUntouched() {
        val done = anime(status = "看完", watched = 12)
        assertEquals(12, normalizeCompletionProgress(done).watchedEpisodes)
    }

    @Test
    fun completionWritesTheFinishDateButNotASyntheticStartDate() {
        val today = LocalDate.of(2026, 9, 19)
        val updated = normalizeCompletionProgress(anime(watched = 0).withWatchStatusDates("看完", today))
        assertEquals("2026-09-19", updated.watchFinishDate)
        assertEquals("不应凭空补一个开追日期", null, updated.watchStartDate)
    }

    @Test
    fun statisticsEstimateGrowsWithTheFilledEpisodes() {
        // 未填写单集时长时，统计仍按已看集数 × 24 分钟估算。
        val before = anime(watched = 0)
        val after = normalizeCompletionProgress(before.withWatchStatusDates("看完"))
        assertEquals(0, before.watchedEpisodes * 24 / 60)
        assertEquals(4, after.watchedEpisodes * 24 / 60)
    }
}
