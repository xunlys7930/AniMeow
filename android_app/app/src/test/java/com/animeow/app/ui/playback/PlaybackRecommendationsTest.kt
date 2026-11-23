package com.animeow.app.ui.playback

import android.app.Application
import android.app.Instrumentation
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.playback.*
import com.animeow.app.data.remote.MediaCatalogService
import com.animeow.app.data.remote.PlaybackWork
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class PlaybackRecommendationsTest {
    @Test fun automaticProgressCanBeDisabledWhileResumeAndIndependentBangumiSettingsRemain() = runBlocking {
        Fixture(AnimeEntity(title = "synthetic", totalEpisodes = 12, externalSource = "bangumi", externalId = "123")) { works("sample") }.use { fixture ->
            val cloud = com.animeow.app.data.cloud.CloudSyncPreferences(fixture.app)
            cloud.updateBangumiSyncConfiguration(progressSync = true, ratingSync = false, wifiOnly = true)
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { !model.state.value.busy && model.state.value.lines.isNotEmpty() }
            model.play(model.state.value.lines.first().episodes.first())
            waitUntil { !model.state.value.busy && model.state.value.session != null }
            val session = requireNotNull(model.state.value.session)
            model.settings(model.state.value.settings.copy(autoMarkProgress = false))
            model.sample(session.id, 0, 100000, true, 1f)
            for (second in 1..95) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
                model.sample(session.id, second * 1000L, 100000, true, 1f)
            }
            model.flush(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, fixture.app.database.libraryDao().getAnime(fixture.animeId)?.watchedEpisodes)
            assertTrue(fixture.app.database.libraryDao().getWatchRecords().isEmpty())
            assertNull(fixture.app.database.libraryDao().getBangumiPendingSync(fixture.animeId, "episodes"))
            assertNull(model.state.value.undo)
            assertFalse(PlaybackPreferences(fixture.app).read().autoMarkProgress)
            val saved = PlaybackHistory(fixture.app).read(PlaybackIdentities(fixture.app).key(requireNotNull(model.state.value.anime)))
            assertEquals(95000L, saved?.positionMs)
            assertFalse(requireNotNull(saved).handled)
            assertTrue(com.animeow.app.data.bangumi.BangumiSyncQueue(fixture.app.database, fixture.app).progressSyncEnabled())
            model.settings(model.state.value.settings.copy(autoMarkProgress = true))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            model.sample(session.id, 96000, 100000, true, 1f)
            waitUntil { model.state.value.undo != null }
            assertEquals(1, fixture.app.database.libraryDao().getAnime(fixture.animeId)?.watchedEpisodes)
            assertEquals(setOf(1), com.animeow.app.data.bangumi.BangumiEpisodeDelta.decode(requireNotNull(fixture.app.database.libraryDao().getBangumiPendingSync(fixture.animeId, "episodes")).payloadJson).add)
            model.undo()
            waitUntil { model.state.value.notice?.contains("已撤销") == true }
            assertEquals(0, fixture.app.database.libraryDao().getAnime(fixture.animeId)?.watchedEpisodes)
            assertNull(fixture.app.database.libraryDao().getBangumiPendingSync(fixture.animeId, "episodes"))
            model.settings(model.state.value.settings.copy(autoMarkProgress = false))
            model.settings(model.state.value.settings.copy(autoMarkProgress = true))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            model.sample(session.id, 97000, 100000, true, 1f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, fixture.app.database.libraryDao().getAnime(fixture.animeId)?.watchedEpisodes)
        }
    }

    @Test fun disablingBeforePendingProgressObtainsTheDataLockCancelsThatMark() = runBlocking {
        Fixture { works("sample") }.use { fixture ->
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { !model.state.value.busy && model.state.value.lines.isNotEmpty() }
            model.play(model.state.value.lines.first().episodes.first())
            waitUntil { !model.state.value.busy && model.state.value.session != null }
            val session = requireNotNull(model.state.value.session)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val lock = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.animeow.app.util.withDataTransferLock { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                model.sample(session.id, 0, 100000, true, 1f)
                for (second in 1..90) {
                    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
                    model.sample(session.id, second * 1000L, 100000, true, 1f)
                }
                shadowOf(Looper.getMainLooper()).idle()
                model.settings(model.state.value.settings.copy(autoMarkProgress = false))
            } finally { release.countDown(); lock.join() }
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            assertEquals(0, fixture.app.database.libraryDao().getAnime(fixture.animeId)?.watchedEpisodes)
            assertTrue(fixture.app.database.libraryDao().getWatchRecords().isEmpty())
            assertFalse(requireNotNull(model.checkpointFor(session.id)).handled)
        }
    }

    @Test fun completedNextOpensOnlyTheConfirmedNextEpisodeAndRejectsAStaleAction() = runBlocking {
        Fixture { works("sample") }.use { fixture ->
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { !model.state.value.busy && model.state.value.lines.isNotEmpty() }
            model.play(model.state.value.lines.first().episodes.first())
            waitUntil { !model.state.value.busy && model.state.value.session != null }
            val session = requireNotNull(model.state.value.session)
            model.sample(session.id, 100000, 100000, false, 1f); model.flush()
            val saved = requireNotNull(model.checkpointFor(session.id))
            val reopened = fixture.reopen(playbackResumeToken(saved))
            waitUntil { !reopened.state.value.busy && reopened.state.value.session != null }
            assertEquals("1-2", reopened.state.value.session?.episode?.id)
            assertEquals(listOf("1-1", "1-2"), fixture.resolvedEpisodes.toList())
            val before = fixture.resolveCalls
            val stale = fixture.reopen("stale action")
            waitUntil { !stale.state.value.busy && stale.state.value.error != null }
            assertNull(stale.state.value.session)
            assertEquals(before, fixture.resolveCalls)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun batchRetryIsSerialDeduplicatedAndReportsFailureSubmissionAndRemovedTask() = runBlocking {
        Fixture { works("sample") }.use { fixture ->
            fixture.failResolveEpisode = "1-1"
            org.robolectric.shadows.ShadowStatFs.registerStats(fixture.app.noBackupFilesDir.absolutePath, 1_000_000, 800_000, 800_000)
            val model = fixture.model
            val anime = requireNotNull(fixture.app.database.libraryDao().getAnime(fixture.animeId))
            val key = PlaybackIdentities(fixture.app).key(anime)
            val index = PlaybackDownloads.get(fixture.app).manager.downloadIndex as androidx.media3.exoplayer.offline.WritableDownloadIndex
            val rows = listOf("1-1", "1-2", "removed").mapIndexed { number, episode ->
                val info = PlaybackDownloadInfo(fixture.animeId, key, "合成作品", "seven", PlaybackWork("123", "作品", ""),
                    com.animeow.app.data.remote.PlaybackEpisode(episode, episode, number + 1), "1", "线路", emptyMap())
                val id = playbackDownloadId(key, "seven", "123", episode)
                val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(id, android.net.Uri.parse("https://cdn.example.test/sample.mp4"))
                    .setMimeType("video/mp4").setData(info.encode()).build()
                if (episode != "removed") index.putDownload(androidx.media3.exoplayer.offline.Download(request, androidx.media3.exoplayer.offline.Download.STATE_FAILED, 1L, 1L, 100L, 0, 1))
                PlaybackDownloadRow(id, info, androidx.media3.exoplayer.offline.Download.STATE_FAILED, 0, 100, 0f, "失败")
            }
            try {
                model.retryDownloads(rows + rows[1])
                waitUntil { !model.state.value.downloadBusy && model.state.value.downloadResults.size == 3 }
                assertEquals(listOf("失败", "已提交", "已跳过"), model.state.value.downloadResults.map { it.status })
                assertEquals(listOf("1-1", "1-2"), fixture.resolvedEpisodes.toList())
                assertEquals("已提交 1 项，失败 1 项，跳过 1 项", model.state.value.downloadNotice)
                assertEquals(listOf(rows[1].id, rows[0].id), visibleDownloads(rows.take(2).mapIndexed { i, row -> row.copy(state = androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, bytes = (i + 1) * 100L) }, 1, null, true).map { it.id })
                assertEquals(3, visibleDownloads(rows, 2, null, false).size)
                assertTrue(visibleDownloads(rows, 1, null, false).isEmpty())
                assertTrue(visibleDownloads(rows, 2, "other work", false).isEmpty())
            } finally { rows.forEach { index.removeDownload(it.id) } }
        }
    }

    @Test fun refreshingSourcesReceivesServerHotfixWithoutReplacingPlaybackOrCheckpoint() = runBlocking {
        Fixture { works("updated") }.use { fixture ->
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { model.state.value.lines.isNotEmpty() && !model.state.value.busy }
            model.play(model.state.value.lines.first().episodes.first())
            waitUntil { model.state.value.session != null && !model.state.value.busy }
            val session = requireNotNull(model.state.value.session)
            model.sample(session.id, 45000, 100000, true, 1f)
            fixture.sourcesResponse = """{"status":"success","data":[{"id":"giri","name":"giriGiriLove","version":"1.1.0","rulesVersion":"1.2.0"},{"id":"seven","name":"7sefun"},{"id":"mdzy","name":"魔都资源"}]}"""
            model.loadSources(refreshSearch = true)
            waitUntil { !model.state.value.sourcesLoading && model.state.value.sources.size == 3 && model.state.value.searchGroups.size == 3 && model.state.value.searchGroups.none { it.loading } }
            assertEquals("1.2.0", model.state.value.sources.first { it.id == "giri" }.rulesVersion)
            assertEquals("updated", model.state.value.searchGroups.first { it.source.id == "mdzy" }.results.single().title)
            assertSame(session, model.state.value.session)
            assertEquals("confirmed", model.state.value.work?.title)
            assertEquals(45000L, model.checkpointFor(session.id)?.positionMs)
        }
    }

    @Test fun failedRefreshKeepsExistingSourcesAndRecoversWithoutChangingPlayback() = runBlocking {
        Fixture { works("sample") }.use { fixture ->
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { !model.state.value.busy && model.state.value.lines.isNotEmpty() }
            val before = model.state.value
            fixture.failSources = true
            model.loadSources(refreshSearch = true)
            waitUntil { !model.state.value.sourcesLoading && model.state.value.sourcesError != null }
            assertEquals(before.sources, model.state.value.sources)
            assertEquals(before.work, model.state.value.work)
            assertNull(model.state.value.error)
            fixture.failSources = false
            model.loadSources(refreshSearch = true)
            waitUntil { !model.state.value.sourcesLoading && model.state.value.sourcesError == null }
            assertEquals(before.sources, model.state.value.sources)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun cachedOnlineChoiceCanResolveOtherEpisodesButExplicitOfflineChoiceCannot() = runBlocking {
        Fixture { works("sample") }.use { fixture ->
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { model.state.value.lines.isNotEmpty() && !model.state.value.busy }
            val snapshot = model.state.value
            val key = playbackHistoryKey(requireNotNull(snapshot.anime))
            val line = snapshot.lines.first()
            val episode = line.episodes.first()
            val info = PlaybackDownloadInfo(fixture.animeId, key, "synthetic", "seven", requireNotNull(snapshot.work), episode, line.id, line.name, emptyMap())
            val id = playbackDownloadId(key, "seven", info.work.id, episode.id)
            val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(id, android.net.Uri.parse("https://example.test/local.mp4"))
                .setMimeType("video/mp4").setData(info.encode()).build()
            val store = PlaybackDownloads.get(fixture.app)
            (store.manager.downloadIndex as androidx.media3.exoplayer.offline.WritableDownloadIndex).putDownload(
                androidx.media3.exoplayer.offline.Download(request, androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, 1L, 1L, 100L, 0, 0))
            try {
                model.play(episode)
                waitUntil { model.state.value.session?.offline == true && !model.state.value.busy }
                assertFalse(model.state.value.offlineOnly)
                val current = requireNotNull(model.state.value.session)
                model.play(line.episodes[1])
                waitUntil { model.state.value.session?.id != current.id && !model.state.value.busy }
                assertFalse(requireNotNull(model.state.value.session).offline)
                assertEquals(1, fixture.resolveCalls)
                model.openDownload(PlaybackDownloadRow(id, info, androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, 100, 100, 100f, "已下载"))
                waitUntil { model.state.value.offlineOnly && !model.state.value.busy }
                val offline = requireNotNull(model.state.value.session)
                model.play(line.episodes[1])
                waitUntil { !model.state.value.busy && model.state.value.error != null }
                assertEquals(offline.id, model.state.value.session?.id)
                assertTrue(model.state.value.error!!.contains("缓存已被移除"))
                assertEquals("Explicit offline playback must never resolve a missing episode online", 1, fixture.resolveCalls)
                model.sample(offline.id, 12000, 100000, false, 1f)
                model.flush()
                val reopenedOffline = fixture.reopen()
                waitUntil { reopenedOffline.state.value.session?.offline == true && !reopenedOffline.state.value.busy }
                assertEquals(12000L, reopenedOffline.state.value.session?.checkpoint?.positionMs)
                assertFalse("General resume keeps online selection while playing cached media", reopenedOffline.state.value.offlineOnly)
                assertEquals(1, fixture.resolveCalls)
                model.removeDownload(PlaybackDownloadRow(id, info, androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, 100, 100, 100f, "已下载"))
                (store.manager.downloadIndex as androidx.media3.exoplayer.offline.WritableDownloadIndex).removeDownload(id)
                assertNull(model.state.value.session)
                assertTrue(model.state.value.offlineOnly)
                model.play(episode)
                waitUntil { !model.state.value.busy && model.state.value.error != null }
                assertEquals("Deleting the current download must preserve offline-only selection", 1, fixture.resolveCalls)
                assertNull(model.state.value.session)
                model.choose("seven", PlaybackWork("123", "明确重新选择在线来源", ""))
                waitUntil { !model.state.value.busy && !model.state.value.offlineOnly }
                model.play(line.episodes[1])
                waitUntil { model.state.value.session?.id != offline.id && !model.state.value.busy }
                assertFalse(requireNotNull(model.state.value.session).offline)
                assertEquals(2, fixture.resolveCalls)


            } finally {
                (store.manager.downloadIndex as androidx.media3.exoplayer.offline.WritableDownloadIndex).removeDownload(id)
            }
        }
    }
    @Test fun cancellingAnOpeningAndSwitchingCandidateClearsTheOldRetryTarget() = runBlocking {
        Fixture { works("sample") }.use { fixture ->
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { !model.state.value.busy && model.state.value.lines.isNotEmpty() }
            model.play(model.state.value.lines.first().episodes.first())
            waitUntil { !model.state.value.busy && model.state.value.session != null }
            val current = requireNotNull(model.state.value.session)
            fixture.resolveGate = CountDownLatch(1)
            model.play(model.state.value.lines.first().episodes[1])
            waitUntil { fixture.resolveCalls == 2 }
            assertNotNull(model.state.value.openingTarget)
            model.source("giri")
            assertNull(model.state.value.openingTarget)
            assertNull(model.state.value.openingEpisode)
            assertEquals(current, model.state.value.session)
            fixture.resolveGate!!.countDown(); fixture.resolveGate = null
            model.retry()
            waitUntil { !model.state.value.busy && model.state.value.session?.id != current.id }
            assertEquals(current.episode.id, model.state.value.session?.episode?.id)
            assertEquals(current.episode.id, fixture.resolvedEpisodes.last())
        }
    }

    @Test fun layoutChangesDoNotReplaceSessionOrLoseCheckpoint() = runBlocking {
        Fixture { works("sample") }.use { fixture ->
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { model.state.value.lines.isNotEmpty() && !model.state.value.busy }
            model.play(model.state.value.lines.first().episodes.first())
            waitUntil { model.state.value.session != null && !model.state.value.busy }
            val session = requireNotNull(model.state.value.session)
            model.sample(session.id, 45000, 100000, true, 1f)
            for (layout in WatchingLayout.entries) {
                model.settings(model.state.value.settings.copy(layout = layout))
                assertSame(session, model.state.value.session)
                assertEquals(45000L, model.checkpointFor(session.id)?.positionMs)
            }
        }
    }
    @Test fun editingQueryAloneInvalidatesLateResultsBeforeNextSearch() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        Fixture { query ->
            if (query["q"] == "old" && query["source"] == "giri") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            works("old-result")
        }.use { fixture ->
            try {
                fixture.model.query("old"); fixture.model.search()
                waitUntil { started.count == 0L }
                fixture.model.query("new")
                release.countDown()
                repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertEquals("new", fixture.model.state.value.query)
                assertTrue(fixture.model.state.value.searchGroups.isEmpty())
            } finally { release.countDown() }
        }
    }
    @Test fun fastSourceRemainsSelectableWhenOtherSourceIsSlowOrFails() = runBlocking {
        val release = CountDownLatch(1)
        Fixture { query ->
            if (query["source"] == "giri") { release.await(5, TimeUnit.SECONDS); error("synthetic source outage") }
            works("fast")
        }.use { fixture ->
            val model = fixture.model
            try {
                model.search()
                waitUntil { model.state.value.searchGroups.any { it.source.id == "seven" && !it.loading } }
                assertTrue(model.state.value.searchGroups.first { it.source.id == "giri" }.loading)
                model.choose("seven", model.state.value.searchGroups.first { it.source.id == "seven" }.results.first())
                waitUntil { model.state.value.lines.isNotEmpty() && !model.state.value.busy }
                assertEquals("seven", model.state.value.source)
                release.countDown()
                waitUntil { model.state.value.searchGroups.none { it.loading } }
                assertNotNull(model.state.value.searchGroups.first { it.source.id == "giri" }.error)
                assertEquals("fast", model.state.value.searchGroups.first { it.source.id == "seven" }.results.single().title)
                assertEquals("fast", model.state.value.work?.title)
            } finally { release.countDown() }
        }
    }

    @Test fun supersededSearchCannotReplaceNewResults() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        Fixture { query ->
            if (query["q"] == "old" && query["source"] == "giri") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            works(query["q"].orEmpty())
        }.use { fixture ->
            try {
                fixture.model.query("old"); fixture.model.search()
                waitUntil { started.count == 0L }
                fixture.model.query("new"); fixture.model.search()
                waitUntil { fixture.model.state.value.searchGroups.size == 2 && fixture.model.state.value.searchGroups.none { it.loading } }
                release.countDown()
                repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertTrue(fixture.model.state.value.searchGroups.all { it.results.single().title == "new" })
            } finally { release.countDown() }
        }
    }

    @Test fun remembersOnlyRealPlayingSourceAndAutoNextRequiresForegroundSameLineAndExplicitSetting() = runBlocking {
        Fixture { works("sample") }.use { fixture ->
            val model = fixture.model
            val key = playbackHistoryKey(requireNotNull(fixture.app.database.libraryDao().getAnime(fixture.animeId)))
            val preferences = PlaybackPreferences(fixture.app)
            model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { model.state.value.lines.isNotEmpty() && !model.state.value.busy }
            model.play(model.state.value.lines.first().episodes.first())
            waitUntil { model.state.value.session != null && !model.state.value.busy }
            val first = requireNotNull(model.state.value.session)
            assertNull(preferences.preferredSource(key))
            model.sample(first.id, 0, 100_000, false, 1f); assertNull(preferences.preferredSource(key))
            model.sample(first.id, 0, 100_000, true, 1f); assertEquals("seven", preferences.preferredSource(key))
            model.ended(first.id, true); assertEquals(first.id, model.state.value.session?.id)
            model.settings(PlaybackSettings(1.5f, 30, true))
            assertEquals(PlaybackSettings(1.5f, 30, true), preferences.read())
            model.play(first.episode)
            waitUntil { model.state.value.session?.id != first.id && !model.state.value.busy }
            val restarted = requireNotNull(model.state.value.session)
            model.line("2")
            model.ended(restarted.id, false); assertEquals(restarted.id, model.state.value.session?.id)
            model.ended(restarted.id, true)
            waitUntil { model.state.value.session?.episode?.id == "1-2" && !model.state.value.busy }
            assertEquals("1", model.state.value.line)
            val second = requireNotNull(model.state.value.session)
            model.ended(restarted.id, true); assertEquals(second.id, model.state.value.session?.id)
            model.ended(second.id, true); assertFalse(model.state.value.busy)
            assertEquals(second.id, model.state.value.session?.id) // Next item is SP; never skip it or cross to line 2.
            val sourceReload = model.loadSources()
            waitUntil { sourceReload.isCompleted }
            assertEquals("seven", model.state.value.sources.first().id)
            assertEquals(0, fixture.app.database.libraryDao().getAnime(fixture.animeId)?.watchedEpisodes)
        }
    }

    @Test fun confirmedWorkReopensAtCheckpointWithoutRepeatingSearchAndFailedReplacementKeepsPlayback() = runBlocking {
        val searches = java.util.concurrent.atomic.AtomicInteger()
        Fixture { searches.incrementAndGet(); works("sample") }.use { fixture ->
            val model = fixture.model
            model.choose("seven", PlaybackWork("123", "已确认的季度", ""))
            waitUntil { model.state.value.lines.isNotEmpty() && !model.state.value.busy }
            model.play(model.state.value.lines.first().episodes.first())
            waitUntil { model.state.value.session != null && !model.state.value.busy }
            val first = requireNotNull(model.state.value.session)
            model.sample(first.id, 12500, 100000, false, 1f); model.flush()
            assertEquals(12500L, model.checkpointFor(first.id)?.positionMs)
            assertNull(model.checkpointFor(first.id + 1))
            waitUntil { model.state.value.searchGroups.none { it.loading } }
            val before = searches.get()
            val reopened = fixture.reopen()
            waitUntil { reopened.state.value.session != null && !reopened.state.value.busy }
            assertEquals("已确认的季度", reopened.state.value.work?.title)
            assertEquals(12500L, reopened.state.value.session?.checkpoint?.positionMs)
            assertEquals(before, searches.get())
            val active = reopened.state.value.session
            waitUntil { reopened.state.value.sources.size == 2 }
            fixture.failEpisodes = true
            reopened.choose("giri", PlaybackWork("wrong", "不应替换成功", ""))
            waitUntil { !reopened.state.value.busy && reopened.state.value.error != null }
            assertEquals(active, reopened.state.value.session)
            assertEquals("123", reopened.state.value.work?.id)
            assertEquals("seven", reopened.state.value.source)
        }
    }

    @Test fun commentTimestampJumpRequiresTheConfirmedSourceAndStartsPausedExactlyOnce() = runBlocking {
        Fixture(AnimeEntity(title = "synthetic", createdAt = "2026-11-06T00:00:00")) { works("sample") }.use { fixture ->
            fixture.model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { !fixture.model.state.value.busy && fixture.model.state.value.lines.isNotEmpty() }
            val jump = org.json.JSONObject().put("source", "seven").put("work", "123").put("episode", "1-2")
                .put("created_at", "2026-11-06T00:00:00").put("time", 12000).put("offset", 1000)
            val reopened = fixture.reopen(jump = jump.toString())
            waitUntil { !reopened.state.value.busy && reopened.state.value.session != null }
            val session = requireNotNull(reopened.state.value.session)
            assertNull(reopened.consumeWatchJump(session.copy(source = "giri")))
            val target = reopened.consumeWatchJump(session)
            assertEquals(13000L, target)
            val start = playbackStart(session.checkpoint, target, true)
            assertEquals(13000L, start.positionMs); assertFalse(start.autoplay)
            assertNull(reopened.consumeWatchJump(session))
            assertTrue(playbackStart(session.checkpoint, null, true).autoplay)
        }
    }

    @Test fun aStaleCommentJumpDoesNotResolveADifferentVideo() = runBlocking {
        Fixture(AnimeEntity(title = "synthetic", createdAt = "2026-11-06T00:00:00")) { works("sample") }.use { fixture ->
            fixture.model.choose("seven", PlaybackWork("123", "confirmed", ""))
            waitUntil { !fixture.model.state.value.busy && fixture.model.state.value.lines.isNotEmpty() }
            val before = fixture.resolveCalls
            val jump = org.json.JSONObject().put("source", "giri").put("work", "123").put("episode", "1-2")
                .put("created_at", "old-identity").put("time", 12000)
            val reopened = fixture.reopen(jump = jump.toString())
            waitUntil { reopened.state.value.loaded && reopened.state.value.error != null }
            assertNull(reopened.state.value.session); assertEquals(before, fixture.resolveCalls)
        }
    }

    private class Fixture(anime: AnimeEntity = AnimeEntity(title = "synthetic", totalEpisodes = 12), private val search: (Map<String, String>) -> String) : AutoCloseable {
        @Volatile var resolveCalls = 0
        @Volatile var resolveGate: CountDownLatch? = null
        val resolvedEpisodes = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var failEpisodes = false
        @Volatile var failSources = false
        @Volatile var failResolveEpisode: String? = null
        @Volatile var sourcesResponse = """{"status":"success","data":[{"id":"giri","name":"giriGiriLove"},{"id":"seven","name":"7sefun"}]}"""
        private val singleton = AniMeowDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        val app = Instrumentation.newApplication(AniMeowApplication::class.java, RuntimeEnvironment.getApplication()) as AniMeowApplication
        val animeId = runBlocking { app.database.libraryDao().insertAnime(anime) }
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val store = ViewModelStore()
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                val socket = server.accept()
                thread(isDaemon = true) { socket.use {
                    runCatching {
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val uri = URI(input.readLine().split(' ')[1])
                        while (!input.readLine().isNullOrBlank()) { }
                        val query = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                        }
                        val result = runCatching { when {
                            uri.path.endsWith("/sources") -> { check(!failSources) { "synthetic source list outage" }; sourcesResponse }
                            uri.path.endsWith("/search") -> search(query)
                            uri.path.endsWith("/episodes") -> {
                                check(!failEpisodes) { "synthetic episode outage" }
                                """{"status":"success","data":{"lines":[{"id":"1","name":"one","episodes":[{"id":"1-1","label":"01","number":1},{"id":"1-2","label":"02","number":2},{"id":"1-3","label":"SP","number":null}]},{"id":"2","name":"two","episodes":[{"id":"2-1","label":"01","number":1},{"id":"2-2","label":"02","number":2}]}]}}"""
                            }
                            else -> {
                                resolvedEpisodes.add(query["episodeId"].orEmpty()); resolveCalls++; resolveGate?.await(5, TimeUnit.SECONDS)
                                check(query["episodeId"] != failResolveEpisode) { "synthetic resolve outage" }
                                """{"status":"success","data":{"url":"https://cdn.example.test/sample.mp4","mimeType":"video/mp4","headers":{}}}"""
                            }
                        } }
                        val body = result.getOrDefault("""{"status":"error"}""").toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply { write("HTTP/1.1 ${if (result.isSuccess) "200 OK" else "503 Service Unavailable"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                    }
                } }
            }
        }
        val model = PlaybackViewModel(app, animeId, MediaCatalogService(baseUrl = "http://127.0.0.1:${server.localPort}"))
        init { store.put("playback", model); waitUntil { model.state.value.sources.size == 2 && !model.state.value.busy } }
        fun reopen(nextFrom: String? = null, jump: String? = null) = PlaybackViewModel(app, animeId, MediaCatalogService(baseUrl = "http://127.0.0.1:${server.localPort}"), initialNextFrom = nextFrom, initialWatchJump = jump).also { store.put("reopened-${System.nanoTime()}", it) }
        override fun close() { resolveGate?.countDown(); store.clear(); server.close(); worker.join(2000); app.database.close(); singleton.set(null, null) }
    }
    companion object {
        private fun works(title: String) = """{"status":"success","data":[{"id":"123","title":"$title","coverUrl":""}]}"""
        private fun waitUntil(condition: () -> Boolean) {
            repeat(500) { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(10) }
            fail("Playback state did not settle")
        }
    }
}
