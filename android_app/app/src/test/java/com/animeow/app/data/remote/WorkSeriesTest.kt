package com.animeow.app.data.remote

import android.app.Application
import com.animeow.app.data.MediaSeriesWork
import com.animeow.app.ui.series.matchingSourceSeriesWorks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class WorkSeriesTest {
    @Test fun sourceCandidatesMatchOnlyExplicitIdentitiesAndKeepCacheScope() {
        val page = parseSourceSeries("""{"groups":[{"id":"series","name":"来源系列","url":"https://bgm.tv/subject/1","works":[{"source":"bangumi","id":"2","title":"同名作品","relation":"续集","url":"https://bgm.tv/subject/2"}]}],"partial":true,"_animeow_cache":{"stale":true}}""")
        assertTrue(page.partial); assertTrue(page.stale)
        val exact = MediaSeriesWork("a", "自定义名称", "now", "bangumi", "2")
        val sameTitle = MediaSeriesWork("b", "同名作品", "now", "vndb", "v2")
        assertEquals(listOf(exact), matchingSourceSeriesWorks(listOf(exact, sameTitle), page.groups.single().works))
    }
    @Test fun untrustedLinksAndSourceIdsAreRejected() {
        val json = """{"groups":[{"id":"x","name":"x","url":"https://evil.test/","works":[]}]}"""
        assertTrue(runCatching { parseSourceSeries(json) }.isFailure)
        assertTrue(runCatching { parseSourceSeries("{}") }.isFailure)
    }
}
