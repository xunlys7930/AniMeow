package com.animeow.app.ui

import android.app.Application
import androidx.core.net.toUri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.animeow.app.data.LibraryRepository
import com.animeow.app.data.analysis.AnimeAnalysisService
import com.animeow.app.data.audio.AudioWork
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.bangumi.BangumiSyncReport
import com.animeow.app.data.cloud.CloudAccountService
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.cloud.CloudSyncSettings
import com.animeow.app.data.games.GameEntity
import com.animeow.app.data.importer.LegacyBackupImporter
import com.animeow.app.data.local.*
import com.animeow.app.data.media.CoverImageStore
import com.animeow.app.data.preferences.AppearanceSettingsCodec
import com.animeow.app.data.remote.*
import com.animeow.app.ui.analysis.MarkdownAnalysisContent
import com.animeow.app.ui.anime.BangumiExtrasContent
import com.animeow.app.ui.anime.BangumiExtrasUiState
import com.animeow.app.ui.anime.mergeAndSortBangumiStaff
import com.animeow.app.ui.audio.AudioDetailSheet
import com.animeow.app.ui.cloud.BangumiSyncStatusContent
import com.animeow.app.ui.cloud.CloudAccountUiState
import com.animeow.app.ui.components.ProvideActionFeedback
import com.animeow.app.ui.series.AddSeriesWorksSheet
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.util.prefixedUserError
import com.animeow.app.util.userSafeErrorMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class FeedbackPolishCompletionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bangumiStaffMergesDuplicatePersonJobsAndPrioritizesCoreRoles() {
        val raw = listOf(
            BangumiPerson(30L, "原画丙", "原画", "1, 5"),
            BangumiPerson(10L, "监督甲", "导演", ""),
            BangumiPerson(10L, "监督甲", "分镜／演出", "1"),
            BangumiPerson(20L, "脚本乙", "系列构成", ""),
            BangumiPerson(10L, "监督甲", "导演", "12"),
        )
        val merged = mergeAndSortBangumiStaff(raw)
        assertEquals(3, merged.size)
        assertEquals(listOf(10L, 20L, 30L), merged.map { it.id })
        assertTrue(merged[0].isCoreStaff)
        assertTrue(merged[1].isCoreStaff)
        assertFalse(merged[2].isCoreStaff)
        assertEquals("导演 ／ 分镜 ／ 演出", merged[0].job)
        assertEquals("1、12", merged[0].episodes)
    }

    @Test
    fun bangumiExtrasRendersRatingDistributionBarsAndMergedStaff() {
        val rating = RemoteAnime(
            source = RemoteCatalogSource.BANGUMI,
            id = "253",
            title = "星际牛仔",
            score = 9.1,
            ratingCount = 1500,
            sourceRank = 3,
            ratingDistribution = mapOf(10 to 900, 9 to 450, 8 to 150),
            cacheUpdatedAt = "2026-11-11T04:00:00Z",
        )
        val state = BangumiExtrasUiState(rating = rating)
        compose.setContent {
            MaterialTheme {
                BangumiExtrasContent(253L, "星际牛仔", 0, state, {}, {}, {})
            }
        }
        compose.onNodeWithText("各分段投票分布").assertExists()
        compose.onNodeWithText("10分").assertExists()
        compose.onNodeWithText("900").assertExists()
        compose.onNodeWithText("1500 人评分").assertExists()
    }

    @Test
    fun batchImportAppliesInitialStatusToNewWorksAndPreservesExistingStatus() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AniMeowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = LibraryRepository(db)
            val remoteCompleted = RemoteAnime(
                source = RemoteCatalogSource.BANGUMI,
                id = "1001",
                title = "吹响吧！上低音号",
                episodes = 13,
            )
            val (firstId, firstAdded) = repo.importBatchItem(remoteCompleted, "京吹系列", initialStatus = "看完")
            assertTrue(firstAdded)
            val savedFirst = checkNotNull(db.libraryDao().getAnime(firstId))
            assertEquals("看完", savedFirst.status)
            assertEquals(13, savedFirst.watchedEpisodes)

            // Re-importing an existing entry with a different initialStatus must NOT overwrite personal status/progress
            val (_, secondAdded) = repo.importBatchItem(remoteCompleted, "京吹系列", initialStatus = "想看")
            assertFalse(secondAdded)
            val preserved = checkNotNull(db.libraryDao().getAnime(firstId))
            assertEquals("看完", preserved.status)
            assertEquals(13, preserved.watchedEpisodes)
        } finally {
            db.close()
        }
    }

    @Test
    fun analysisServiceCountsUnifiedCompletedStatusesAndNeverLeaksPrivateMemo() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AniMeowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.libraryDao()
            dao.insertAnime(AnimeEntity(title = "A", status = "看完", memo = "绝密私人备注_不应出现在统计JSON"))
            dao.insertAnime(AnimeEntity(title = "B", status = "已看"))
            dao.insertAnime(AnimeEntity(title = "C", status = "读完", subjectType = "book"))
            dao.insertAnime(AnimeEntity(title = "D", status = "在看"))

            val cloud = CloudAccountService(app, NativeBackupService(app, db), LegacyBackupImporter(app, db))
            val service = AnimeAnalysisService(db, cloud)
            val built = service.buildStats()
            assertEquals(4, built.preview.totalEntries)
            assertEquals(3, built.preview.completedEntries)
            val jsonString = built.json.toString()
            assertFalse("private memo must never enter analysis payload", jsonString.contains("绝密私人备注"))
            assertFalse("memo key must never enter analysis payload", jsonString.contains("\"memo\""))
        } finally {
            db.close()
        }
    }

    @Test
    fun audioDetailSheetRendersReadOnlyMetadataLinkedSeriesAndOpensEditor() {
        var editClicked = false
        var openedSeriesId: Long? = null
        val work = AudioWork(
            id = 5L,
            syncId = "audio-sync-5",
            title = "治愈雨声 ASMR",
            originalTitle = "癒しの雨音",
            category = "ASMR",
            status = "在听",
            workCode = "RJ01234567",
            voiceActors = "早见沙织",
            circle = "RainStudio",
            durationSeconds = 3600,
            listenedSeconds = 1200,
            rating = 9,
            tags = "耳搔，助眠",
            review = "雨声层次很舒服",
        )
        val series = listOf(SeriesEntity(id = 42L, name = "雨音企划系列"))
        compose.setContent {
            MaterialTheme {
                AudioDetailSheet(
                    work = work,
                    busy = false,
                    linkedSeries = series,
                    onAddProgress = {},
                    onFinishProgress = {},
                    onEdit = { editClicked = true },
                    onSeriesOpen = { openedSeriesId = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("治愈雨声 ASMR").assertExists()
        compose.onNodeWithText("作品编号：RJ01234567").assertExists()
        compose.onNodeWithText("雨音企划系列").performClick()
        assertEquals(42L, openedSeriesId)
        compose.onNodeWithText("编辑资料与收听记录").performScrollTo().performClick()
        assertTrue(editClicked)
    }

    @Test
    fun addSeriesWorksSheetSupportsSelectingCrossMediaGamesAndAudio() {
        var savedCross: List<SeriesLinkedMediaItem> = emptyList()
        val games = listOf(GameEntity(id = 1L, syncId = "game-1", title = "命运石之门", status = "已玩"))
        val audio = listOf(AudioWork(id = 2L, syncId = "audio-2", title = "广播剧 CD", status = "已听", category = "广播剧"))
        compose.setContent {
            MaterialTheme {
                AddSeriesWorksSheet(
                    candidates = emptyList(),
                    seriesNames = emptyMap(),
                    onConfirm = {},
                    onDismiss = {},
                    availableGames = games,
                    availableAudioWorks = audio,
                    onConfirmCrossMedia = { savedCross = it },
                )
            }
        }
        compose.onNodeWithText("游戏 (1)").performClick()
        compose.onNodeWithText("命运石之门").performClick()
        compose.onNodeWithText("音声 (1)").performClick()
        compose.onNodeWithText("广播剧 CD").performClick()
        compose.onNodeWithText("添加 2 部").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                SeriesLinkedMediaItem("GAME", "game-1"),
                SeriesLinkedMediaItem("AUDIO", "audio-2"),
            ),
            savedCross,
        )
    }

    @Test
    fun markdownAnalysisContentRendersHeadingsBulletsAndBold() {
        compose.setContent {
            MaterialTheme {
                MarkdownAnalysisContent(
                    """
                    # 看番风格画像
                    ## 题材偏好
                    - 偏爱 **日常系** 与群像叙事
                    """.trimIndent(),
                )
            }
        }
        compose.onNodeWithText("看番风格画像").assertExists()
        compose.onNodeWithText("题材偏好").assertExists()
        compose.onNodeWithText("偏爱 日常系 与群像叙事").assertExists()
    }

    @Test
    fun wantToWatchUnificationSupportsDefaultSeedIncrementAndLegacyUnwatchedReset() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AniMeowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.libraryDao()
            val repo = LibraryRepository(db)
            assertEquals("想看", AnimeEntity(title = "默认新番").status)

            val wantId = dao.insertAnime(AnimeEntity(title = "葬送的芙莉莲", status = "想看", totalEpisodes = 28, watchedEpisodes = 0))
            repo.incrementProgress(wantId)
            val incrementedWant = checkNotNull(dao.getAnime(wantId))
            assertEquals("在看", incrementedWant.status)
            assertEquals(1, incrementedWant.watchedEpisodes)

            val bookUnwatchedId = dao.insertAnime(
                AnimeEntity(title = "败犬女主太多了！轻小说", subjectType = "book", status = "未看", totalEpisodes = 8, watchedEpisodes = 0),
            )
            val bookChange = checkNotNull(repo.incrementProgress(bookUnwatchedId))
            val incrementedBook = checkNotNull(dao.getAnime(bookUnwatchedId))
            assertEquals("在看", incrementedBook.status)
            assertEquals(1, incrementedBook.watchedEpisodes)
            assertTrue(repo.restoreProgress(bookChange))
            val restoredBook = checkNotNull(dao.getAnime(bookUnwatchedId))
            assertEquals("未看", restoredBook.status)
            assertEquals(0, restoredBook.watchedEpisodes)

            val bookWantId = dao.insertAnime(
                AnimeEntity(title = "安达与岛村", subjectType = "book", status = "想看", totalEpisodes = 12, watchedEpisodes = 0),
            )
            repo.incrementProgress(bookWantId)
            assertEquals("在看", checkNotNull(dao.getAnime(bookWantId)).status)

            val dirtyWantId = dao.insertAnime(AnimeEntity(title = "待看新番", status = "想看", watchedEpisodes = 3))
            val legacyUnwatchedId = dao.insertAnime(AnimeEntity(title = "旧版未看番", status = "未看", watchedEpisodes = 2))
            val resetCount = repo.resetUnwatchedEpisodeProgress()
            assertEquals(2, resetCount)
            assertEquals(0, checkNotNull(dao.getAnime(dirtyWantId)).watchedEpisodes)
            assertEquals(0, checkNotNull(dao.getAnime(legacyUnwatchedId)).watchedEpisodes)
        } finally {
            db.close()
        }
    }

    @Test
    fun userSafeErrorStripsInternalUrisPathsExceptionsAndPreventsDanglingColon() = runBlocking {
        assertEquals(
            "删除失败，请稍后重试",
            prefixedUserError("删除失败", IllegalStateException("")),
        )
        assertEquals(
            "导入失败，请稍后重试",
            prefixedUserError("导入失败", RuntimeException("java.io.IOException: Unable to resolve host")),
        )
        assertEquals(
            "保存失败，请稍后重试",
            userSafeErrorMessage("无法读取图片\nURI: content://media/external/images/media/42", "保存失败，请稍后重试"),
        )
        assertEquals(
            "删除失败：角色组已被删除",
            prefixedUserError("删除失败", IllegalStateException("角色组已被删除")),
        )

        val app = ApplicationProvider.getApplicationContext<Application>()
        val missing = File(app.cacheDir, "non-existent-cover-test.png").toUri()
        val thrown = try {
            CoverImageStore(app).importCharacterImage(missing)
            null
        } catch (e: IllegalStateException) {
            e
        }
        checkNotNull(thrown)
        assertEquals("找不到所选图片文件，请重新选择", thrown.message)
        assertFalse(thrown.message.orEmpty().contains("URI:"))
        assertFalse(thrown.message.orEmpty().contains(app.cacheDir.path))
    }

    @Test
    fun hapticFeedbackDefaultsToDisabledAndGatesComposeHaptics() {
        assertFalse(AppearanceSettings().hapticFeedback)
        assertFalse(AppearanceSettingsCodec.fromJson(JSONObject()).hapticFeedback)

        var platformPulses = 0
        val recordingHaptic = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                platformPulses += 1
            }
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalHapticFeedback provides recordingHaptic) {
                    androidx.compose.foundation.layout.Column {
                        ProvideActionFeedback(AppearanceSettings()) {
                            val haptic = LocalHapticFeedback.current
                            TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                                Text("默认关闭触感")
                            }
                        }
                        ProvideActionFeedback(AppearanceSettings(hapticFeedback = true)) {
                            val haptic = LocalHapticFeedback.current
                            TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                                Text("手动开启触感")
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("默认关闭触感").performClick()
        assertEquals(0, platformPulses)
        compose.onNodeWithText("手动开启触感").performClick()
        assertEquals(1, platformPulses)
    }

    @Test
    fun bangumiHomeSyncStatusDisplayCanBeToggledAndPersists() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = CloudSyncPreferences(app)
        val original = prefs.snapshot().bangumiShowHomeStatus
        try {
            prefs.setBangumiShowHomeStatus(false)
            assertFalse(prefs.snapshot().bangumiShowHomeStatus)
            prefs.setBangumiShowHomeStatus(true)
            assertTrue(prefs.snapshot().bangumiShowHomeStatus)
        } finally {
            prefs.setBangumiShowHomeStatus(original)
        }

        var toggledTo: Boolean? = null
        val report = BangumiSyncReport("2026-11-11T12:00:00Z", false, 0, 2, 0, "网络超时", emptyList(), false)
        val state = CloudAccountUiState(
            bangumiPendingCount = 2,
            syncSettings = CloudSyncSettings(
                bangumiProgressSync = true,
                bangumiLastReport = report,
                bangumiShowHomeStatus = true,
            ),
        )
        compose.setContent {
            MaterialTheme {
                BangumiSyncStatusContent(
                    state = state,
                    onDismiss = {},
                    onToggleHomeStatus = { toggledTo = it },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("在首页显示同步状态").assertExists()
        compose.onNode(isToggleable()).performClick()
        assertEquals(false, toggledTo)
    }
}
