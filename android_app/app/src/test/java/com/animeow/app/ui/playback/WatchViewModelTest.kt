package com.animeow.app.ui.playback

import android.app.Application
import android.os.Looper
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.playback.PlaybackCheckpoint
import com.animeow.app.data.remote.PlaybackEpisode
import com.animeow.app.data.remote.PlaybackStream
import com.animeow.app.data.remote.PlaybackWork
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class WatchViewModelTest {
    private val api = FakeWatchApi()
    private val storage = MemoryWatchStorage()
    private val model = WatchViewModel(api, storage)
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun start() { model.bindDiscussion(123, 456); model.setActive(true); idle(); assertEquals(456, model.state.value.program?.episodeId) }
    @After fun close() { model.setActive(false); idle() }

    @Test fun failedPreferenceCommitRestoresTheInMemoryMatchingValues() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val actual = app.getSharedPreferences("watch_interaction_v1", 0)
        actual.edit().clear().commit()
        var failCommit = false
        val preferences = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
        ) { _, method, arguments ->
            if (method.name == "edit") {
                val editor = actual.edit()
                java.lang.reflect.Proxy.newProxyInstance(
                    android.content.SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences.Editor::class.java),
                ) { _, editorMethod, editorArguments ->
                    val result = editorMethod.invoke(editor, *(editorArguments ?: emptyArray()))
                    if (editorMethod.name == "commit" && failCommit) { failCommit = false; false } else result
                }
            } else method.invoke(actual, *(arguments ?: emptyArray()))
        } as android.content.SharedPreferences
        val context = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        val local = WatchPreferences(context)
        local.write("mapping:test", "old")
        failCommit = true
        try {
            local.writeAll(mapOf("mapping:test" to "new", "program-link:test" to "new"))
            fail("The simulated failed commit must be reported")
        } catch (_: IllegalStateException) { }
        assertEquals("old", local.read("mapping:test"))
        assertNull(local.read("program-link:test"))
    }

    @Test fun onlyAnUnambiguousRegularEpisodeCanMatchAutomatically() {
        val rows = listOf(watchTestProgram, watchTestProgram.copy(episodeId = 459, type = 1))
        assertEquals(watchTestProgram, automaticWatchEpisode(rows, 1, "第 1 集"))
        assertNull(automaticWatchEpisode(rows, 1, "SP 1"))
        assertNull(automaticWatchEpisode(rows, 1, "第 1 集先行版"))
        assertNull(automaticWatchEpisode(rows + watchTestProgram.copy(episodeId = 460), 1, "第 1 集"))
        assertNull(automaticWatchEpisode(rows, null, "第 1 集"))
    }
    @Test fun continuousEpisodesRequireACompleteMatchingSeasonAndNoCompetingNumber() {
        val first = watchTestProgram.copy(number = 29.0)
        val second = first.copy(episodeId = 457, number = 30.0)
        assertEquals(first, automaticWatchEpisode(listOf(first, second), 1, "第 1 集", total = 2))
        assertNull(automaticWatchEpisode(listOf(first, second), 1, "第 1 集", total = 3))
        val overlapping = listOf(first.copy(number = 2.0), second.copy(number = 3.0))
        assertNull(automaticWatchEpisode(overlapping, 2, "第 2 集", total = 2))
        assertNull(automaticWatchEpisode(listOf(first.copy(number = null)), 1, "第 1 集"))
    }
    @Test fun numericTitlesUseTitleSearchAndExplicitBangumiReferencesUsePrograms() {
        start()
        val queries = mutableListOf<String>()
        api.onSearch = { keyword -> queries += keyword; listOf(WatchSubject(302189, "86")) }
        model.search(" 86 "); idle()
        assertEquals(listOf("86"), queries)
        assertEquals(listOf(302189), model.state.value.subjects.map(WatchSubject::id))
        model.search("https://bgm.tv/subject/302189/ep"); idle()
        assertEquals(setOf(302189), model.state.value.candidates.map(WatchProgram::subjectId).toSet())
        model.returnToWatchSubjects()
        api.onSearch = { emptyList() }
        model.search("未知标题"); idle()
        assertTrue(model.state.value.noSubjects)
        assertTrue(model.state.value.candidates.isEmpty())
        assertEquals(456, model.state.value.program?.episodeId)
    }
    @Test fun aConfirmedRegularMatchIsReusedForTheNextEpisodeOfThatPlaybackWork() {
        val anime = AnimeEntity(id = 3, title = "本机标题", originalTitle = "原名", totalEpisodes = 2)
        val first = watchTestProgram.copy(number = 29.0)
        val second = first.copy(episodeId = 457, number = 30.0)
        api.onPrograms = { listOf(first, second) }
        model.bind(anime, playback(1))
        model.setActive(true); idle()
        assertNull(model.state.value.program)
        assertEquals(listOf("原名", "播放来源标题"), model.state.value.alternateTitles)
        model.searchSubject(123); idle()
        assertEquals(123, model.state.value.selectedMatchSubject)
        assertEquals(first, model.state.value.suggestedMatch)
        model.choose(first); idle()
        assertNull(model.state.value.selectedMatchSubject)
        model.bind(anime, playback(2)); idle()
        assertEquals(second, model.state.value.program)
        model.bind(anime, playback(2, work = "another-work")); idle()
        assertNull(model.state.value.program)
    }
    @Test fun clearingMatchingSurvivesReopeningAndBlocksAutomaticMatchingUntilConfirmed() {
        val anime = AnimeEntity(id = 3, title = "本机标题", totalEpisodes = 2, externalSource = "bangumi", externalId = "123")
        model.bind(anime, playback(1)); model.setActive(true); idle()
        assertNotNull(model.state.value.program)
        model.draft("尚未发送的草稿")
        assertTrue(model.clearMatching()); idle()
        assertNull(model.state.value.program)
        assertTrue(model.state.value.danmaku.isEmpty())
        assertEquals("尚未发送的草稿", model.state.value.draft)
        model.setActive(false); model.setActive(true); idle()
        assertNull(model.state.value.program)
        model.bind(anime, playback(2)); idle()
        assertNull(model.state.value.program)
        model.searchSubject(123); idle()
        assertTrue(model.choose(watchTestProgram)); idle()
        assertNotNull(model.state.value.program)
    }

    @Test fun correctingTheWorkDoesNotRestoreAnOlderEpisodesWrongSubject() {
        val anime = AnimeEntity(id = 3, title = "本机标题", totalEpisodes = 2)
        api.onPrograms = { subject -> listOf(watchTestProgram.copy(subjectId = subject),
            watchTestProgram.copy(subjectId = subject, episodeId = 457, number = 2.0)) }
        model.bind(anime, playback(1)); model.setActive(true); idle()
        model.searchSubject(123); idle(); assertTrue(model.choose(model.state.value.candidates.first())); idle()
        model.bind(anime, playback(2)); idle()
        model.searchSubject(999); idle(); assertTrue(model.choose(model.state.value.candidates.last())); idle()
        model.bind(anime, playback(1)); idle()
        assertEquals(999, model.state.value.program?.subjectId)
    }

    @Test fun aFailedClearLeavesTheCurrentMappingAndContentInPlace() {
        start()
        val saved = storage.values.toMap()
        storage.reject = { it.startsWith("program-link:") }
        assertFalse(model.clearMatching()); idle()
        assertEquals(watchTestProgram, model.state.value.program)
        assertEquals(saved, storage.values)
    }

    @Test fun aManualSpecialEpisodeKeepsTheWorkWithoutGuessingTheNextEpisode() {
        val anime = AnimeEntity(id = 3, title = "本机标题", totalEpisodes = 2)
        val special = watchTestProgram.copy(episodeId = 459, type = 1)
        api.onPrograms = { listOf(special, watchTestProgram.copy(episodeId = 457, number = 2.0)) }
        model.bind(anime, playback(1)); model.setActive(true); idle()
        model.searchSubject(123); idle()
        model.choose(special); idle()
        model.bind(anime, playback(2)); idle()
        assertNull(model.state.value.program)
        assertTrue(model.state.value.candidates.isNotEmpty())
    }

    private fun playback(number: Int, work: String = "test-work") = PlaybackSession(
        id = number.toLong(), source = "fixture", work = PlaybackWork(work, "播放来源标题", ""),
        episode = PlaybackEpisode(number.toString(), "第 $number 集", number),
        stream = PlaybackStream("https://example.com/video.mp4", "video/mp4", emptyMap()),
        checkpoint = PlaybackCheckpoint("fixture", work, number.toString(), 0, 0),
    )
    @Test fun anUnknownWriteIsLookedUpBeforeRetryAndTheConfirmedContentIsNotPublishedTwice() {
        start(); model.draft("合成待发内容")
        api.onCreate = { input -> api.confirmed[input.clientId] = watchTestItem(); throw java.io.IOException("合成响应丢失") }
        model.send(false, false, false); idle()
        assertTrue(model.state.value.uncertain); assertEquals("合成待发内容", model.state.value.draft)
        model.send(false, false, false); idle()
        assertEquals(1, api.submitted.size); assertFalse(model.state.value.uncertain); assertEquals("", model.state.value.draft)
    }
    @Test fun anUnconfirmedRetryUsesTheOriginalTimestampAndPayloadEvenIfThePlayerHasMoved() {
        start(); model.sample(1000); model.draft("合成弹幕")
        var fail = true
        api.onCreate = { if (fail) throw java.io.IOException("合成断网") else watchTestItem() }
        model.send(true, true, true); idle(); val first = api.submitted.single()
        model.sample(45000); fail = false
        model.send(true, true, false); idle()
        assertEquals(listOf(first, first), api.submitted); assertEquals(1000L, first.timeMs)
    }
    @Test fun pendingWritesArePersistedBeforeAnyNetworkWrite() {
        start(); model.draft("合成内容"); storage.reject = { it.startsWith("pending:") }
        model.send(false, false, false); idle()
        assertTrue(api.submitted.isEmpty()); assertEquals("合成内容", model.state.value.draft)
    }
    @Test fun twoSendTapsShareOneBusyOperationAndDoNotClearAnEditedDraft() {
        start(); model.draft("第一份草稿")
        val gate = CompletableDeferred<Unit>(); api.onCreate = { gate.await(); watchTestItem() }
        model.send(false, false, false); model.send(false, false, false); idle()
        assertEquals(1, api.submitted.size); assertTrue(model.state.value.busy)
        model.draft("新的草稿"); gate.complete(Unit); idle()
        assertEquals("新的草稿", model.state.value.draft); assertFalse(model.state.value.busy)
    }
    @Test fun lateReadsFromAnOldAccountCannotPopulateTheNewAccountOrRestoreAdminData() {
        api.admin = true; start(); model.loadReviews(); idle(); assertTrue(model.state.value.reviews.isNotEmpty())
        val gate = CompletableDeferred<WatchPage>()
        api.onReviews = { withContext(NonCancellable) { gate.await() } }; model.loadReviews(); idle()
        model.setActive(false); api.login = CloudSession(2, "second", "second-synthetic-token"); api.admin = false
        model.setActive(true); idle(); gate.complete(WatchPage(listOf(watchTestItem(content = "旧账号管理内容")))); idle()
        assertEquals(2L, model.state.value.session?.userId); assertFalse(model.state.value.admin); assertTrue(model.state.value.reviews.isEmpty())
    }
    @Test fun aLateReadFromTheSameAccountCannotOverwriteANewerRefresh() {
        start(); val gate = CompletableDeferred<WatchPage>()
        api.onList = { _, kind, _, _, _ -> if (kind == "comment") withContext(NonCancellable) { gate.await() } else WatchPage(emptyList()) }
        model.loadComments(); idle()
        api.onList = { _, kind, _, _, _ -> WatchPage(if (kind == "comment") listOf(watchTestItem("2", "较新结果")) else emptyList()) }
        model.loadComments(); idle(); gate.complete(WatchPage(listOf(watchTestItem("1", "过时结果")))); idle()
        assertEquals(listOf("较新结果"), model.state.value.comments.map(WatchItem::content))
    }
    @Test fun draftsAreSeparatedByAccountAndAreRecoveredWhenTheSameAccountReturns() {
        start(); model.draft("账号一草稿"); model.setActive(false)
        val first = api.login; api.login = CloudSession(2, "second", "second-synthetic-token"); model.setActive(true); idle()
        assertEquals("", model.state.value.draft); model.draft("账号二草稿"); model.setActive(false)
        api.login = first; model.setActive(true); idle(); assertEquals("账号一草稿", model.state.value.draft)
    }
    @Test fun disablingDanmakuInvalidatesAnUncancellablePendingRead() {
        start(); val gate = CompletableDeferred<WatchPage>()
        api.onList = { _, _, _, _, _ -> withContext(NonCancellable) { gate.await() } }
        model.reloadDanmaku(); idle(); model.settings(model.state.value.settings.copy(enabled = false))
        gate.complete(WatchPage(listOf(watchTestItem().copy(kind = "danmaku", timeMs = 0)))); idle()
        assertTrue(model.state.value.danmaku.isEmpty())
    }
    @Test fun returningToADiscussionKeepsItsEpisodeInsteadOfSwitchingToTheOverallThread() {
        start(); model.setActive(false); model.setActive(true); idle()
        assertEquals(456, model.state.value.program?.episodeId); assertFalse(model.state.value.overall)
    }
    @Test fun returningFromAnotherScreenKeepsTheReplyRecipientAndDraft() {
        start(); model.replies(watchTestItem()); idle(); model.draft("给这条评论的回复")
        model.setActive(false); model.setActive(true); idle()
        assertEquals("1", model.state.value.parent?.id); assertEquals("给这条评论的回复", model.state.value.draft)
    }
    @Test fun losingModerationPermissionImmediatelyClearsPreviouslyLoadedPrivateRows() {
        api.admin = true; start(); model.loadReviews(); idle(); assertTrue(model.state.value.reviews.isNotEmpty())
        api.onReviews = { throw CommunityManagementForbiddenException() }
        model.loadReviews(); idle()
        assertFalse(model.state.value.admin); assertTrue(model.state.value.reviews.isEmpty())
    }
    @Test fun anExternalMatchCannotBeReusedAfterChoosingADifferentCanonicalEpisode() {
        api.externalPrograms = listOf(ExternalWatchProgram(9001, "合成外部作品", "第一集", "1"))
        start(); model.chooseExternal(api.externalPrograms.single()); idle(); assertEquals(9001L, model.state.value.externalEpisode)
        model.choose(model.state.value.candidates.first { it.episodeId == 0 }); idle()
        assertNull(model.state.value.externalEpisode)
    }
    @Test fun blockingAnAuthorInvalidatesPendingFocusAndDanmakuReadsFromBeforeTheBlock() {
        start(); val gate = CompletableDeferred<WatchPage>(); val focused = CompletableDeferred<WatchItem>()
        api.onList = { _, _, _, _, _ -> withContext(NonCancellable) { gate.await() } }
        api.onItem = { withContext(NonCancellable) { focused.await() } }
        model.reloadDanmaku(); model.loadFocus(1); idle()
        api.onList = { _, _, _, _, _ -> WatchPage(emptyList()) }
        model.block(watchTestItem().copy(authorId = 2)); idle()
        gate.complete(WatchPage(listOf(watchTestItem().copy(kind = "danmaku", authorId = 2, timeMs = 0))))
        focused.complete(watchTestItem().copy(authorId = 2)); idle()
        assertNull(model.state.value.focused); assertTrue(model.state.value.danmaku.isEmpty())
    }
    @Test fun anExplicitSessionRejectionClearsTheVisibleAccountAndPrivateReviewRowsWithoutWaitingForAnotherResume() {
        api.admin = true; start(); model.loadReviews(); idle()
        model.draft("合成过期会话草稿")
        api.onCreate = { api.login = null; throw com.animeow.app.data.cloud.CloudSessionExpiredException() }
        model.send(false, false, false); idle()
        assertNull(model.state.value.session); assertFalse(model.state.value.admin); assertTrue(model.state.value.reviews.isEmpty())
        assertEquals("", model.state.value.draft)
    }
}
