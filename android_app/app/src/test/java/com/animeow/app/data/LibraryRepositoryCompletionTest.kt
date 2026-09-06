package com.animeow.app.data

import com.animeow.app.data.local.AnimeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryRepositoryCompletionTest {
    @Test
    fun completionStatusFillsKnownTotal() {
        val source = AnimeEntity(
            title = "测试作品",
            status = "看完",
            watchedEpisodes = 3,
            totalEpisodes = 12,
        )

        assertEquals(12, normalizeCompletionProgress(source).watchedEpisodes)
    }

    @Test
    fun customStatusAndUnknownTotalStayUntouched() {
        val custom = AnimeEntity(
            title = "测试作品",
            status = "已补完",
            watchedEpisodes = 3,
            totalEpisodes = 12,
        )
        val unknownTotal = custom.copy(status = "看完", totalEpisodes = 0)

        assertSame(custom, normalizeCompletionProgress(custom))
        assertSame(unknownTotal, normalizeCompletionProgress(unknownTotal))
    }
}
