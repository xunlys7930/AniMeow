package com.animeow.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分集资料的输入规范、列表集号与空草稿删除语义。 */
class EpisodeDetailsTest {
    @Test
    fun durationAcceptsPlainMinutesAndUnitSuffixes() {
        assertEquals(24, parseEpisodeDurationMinutes("24"))
        assertEquals(24, parseEpisodeDurationMinutes(" 24 "))
        assertEquals(24, parseEpisodeDurationMinutes("24分钟"))
        assertEquals(24, parseEpisodeDurationMinutes("24分"))
        assertEquals(90, parseEpisodeDurationMinutes("90 min"))
        assertEquals(1, parseEpisodeDurationMinutes("1m"))
        assertEquals(1440, parseEpisodeDurationMinutes("1440"))
    }

    @Test
    fun durationRejectsBlankZeroAndOutOfRangeValues() {
        assertNull(parseEpisodeDurationMinutes(""))
        assertNull(parseEpisodeDurationMinutes("   "))
        assertNull(parseEpisodeDurationMinutes("0"))
        assertNull(parseEpisodeDurationMinutes("-5"))
        assertNull(parseEpisodeDurationMinutes("1441"))
        assertNull(parseEpisodeDurationMinutes("24.5"))
        assertNull(parseEpisodeDurationMinutes("二十四"))
    }

    @Test
    fun durationFormatsHoursOnlyWhenItHelps() {
        assertEquals("", formatEpisodeDuration(null))
        assertEquals("", formatEpisodeDuration(0))
        assertEquals("", formatEpisodeDuration(1441))
        assertEquals("24 分钟", formatEpisodeDuration(24))
        assertEquals("59 分钟", formatEpisodeDuration(59))
        assertEquals("1 小时", formatEpisodeDuration(60))
        assertEquals("1 小时 24 分钟", formatEpisodeDuration(84))
    }

    @Test
    fun titleCollapsesLineBreaksAndStopsAtTheLimit() {
        assertEquals("标题 第二行", sanitizeEpisodeTitleInput("  标题\n第二行 "))
        assertEquals(EPISODE_TITLE_MAX_LENGTH, sanitizeEpisodeTitleInput("a".repeat(500)).length)
        assertEquals(EPISODE_TEXT_MAX_LENGTH, sanitizeEpisodeTextInput("b".repeat(5000)).length)
    }

    @Test
    fun emptyDraftDeletesInsteadOfStoringABlankRow() {
        assertNull(episodeDetailOrNull(1L, 1, "", null, "  ", "", null, "2026-10-08T00:00:00Z"))
        assertNull(episodeDetailOrNull(1L, 1, "", 0, "", "", 0, "2026-10-08T00:00:00Z"))
        assertNull(episodeDetailOrNull(1L, 0, "第零集", null, "", "", null, "2026-10-08T00:00:00Z"))
    }

    @Test
    fun draftKeepsOnlyTheFieldsTheUserFilled() {
        val detail = episodeDetailOrNull(7L, 3, "  初次见面 ", 24, "  剧情提要 ", "", 85, "2026-10-08T00:00:00Z")
        requireNotNull(detail)
        assertEquals(7L, detail.animeId)
        assertEquals(3, detail.episode)
        assertEquals("初次见面", detail.title)
        assertEquals(24, detail.durationMinutes)
        assertEquals("剧情提要", detail.synopsis)
        assertNull(detail.review)
        assertEquals(85, detail.rating)
    }

    @Test
    fun rowNumbersFollowTheSeasonAndKeepStoredExtras() {
        assertEquals(listOf(1, 2, 3), episodeRowNumbers(3, emptyList()))
        assertEquals(listOf(1, 2, 3), episodeRowNumbers(3, listOf(2)))
        assertEquals(listOf(1, 2, 3, 7), episodeRowNumbers(3, listOf(7)))
        assertEquals(listOf(1, 2), episodeRowNumbers(0, listOf(2, 1)))
        assertEquals(emptyList<Int>(), episodeRowNumbers(0, emptyList()))
    }

    @Test
    fun oversizedTotalsListOnlyWhatIsRecorded() {
        assertFalse(episodeListIsTruncated(EPISODE_ROW_LIMIT))
        assertTrue(episodeListIsTruncated(EPISODE_ROW_LIMIT + 1))
        assertEquals(listOf(5), episodeRowNumbers(EPISODE_ROW_LIMIT + 1, listOf(5)))
    }

    @Test
    fun summaryDescribesWhatHasBeenRecorded() {
        assertEquals("", episodeDetailSummary(null))
        val detail = EpisodeDetailEntity(
            animeId = 1L,
            episode = 4,
            title = "转折",
            durationMinutes = 24,
            synopsis = "剧情提要",
            review = null,
            rating = 85,
            updatedAt = null,
        )
        assertEquals("24 分钟 · 评分 8.5 · 有简介", episodeDetailSummary(detail))
    }

    @Test
    fun unitLabelFollowsSubjectType() {
        assertEquals("集", episodeUnitLabel("anime"))
        assertEquals("话", episodeUnitLabel("book"))
        assertEquals("话", episodeUnitLabel("manga"))
        assertEquals("集", episodeUnitLabel(null))
    }
}
