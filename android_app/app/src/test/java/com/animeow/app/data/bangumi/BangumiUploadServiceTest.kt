package com.animeow.app.data.bangumi

import android.app.Application
import androidx.room.Room
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.BangumiPendingSyncEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

internal fun uploadFixtureRow(id: Long, kind: Int = 2, rate: Int = 7, type: Int = 3, progress: Int = 1): JSONObject = JSONObject()
    .put("subject_id", id).put("subject_type", kind).put("type", type).put("rate", rate).put("ep_status", progress)
    .put("vol_status", 0).put("private", true).put("comment", "合成私密短评").put("tags", JSONArray(listOf("保留")))
    .let { json -> json.put("fingerprint", MessageDigest.getInstance("SHA-256").digest(json.toString().toByteArray()).joinToString("") { "%02x".format(it) }) }

internal class FakeBangumiUploadRemote : BangumiUploadRemoteClient {
    var account = BangumiUploadAccount(1, 700, "synthetic")
    var supported = true
    val rows = linkedMapOf<Long, JSONObject>()
    val applied = mutableListOf<JSONObject>()
    var pageLoader: ((Int) -> JSONObject)? = null
    var beforePrepare: suspend () -> Unit = {}
    var onApply: suspend () -> Unit = {}
    override suspend fun captureAccount() = account
    override suspend fun verifyAccount(account: BangumiUploadAccount) { check(this.account == account) { "账号已变化，请重新读取" } }
    override suspend fun checkCapability() { check(supported) { "服务器尚未开通" } }
    override suspend fun lookup(account: BangumiUploadAccount, kind: BangumiUploadKind, subjectId: Long) =
        JSONObject().put("subject_id", subjectId).put("subject_type", kind.id).put("title", "合成关联作品$subjectId")
    override suspend fun page(account: BangumiUploadAccount, kind: BangumiUploadKind, offset: Int): JSONObject = pageLoader?.invoke(offset)
        ?: JSONObject().put("total", rows.size).put("offset", offset).put("data", JSONArray(rows.values.drop(offset).take(50)))
    override suspend fun prepare(account: BangumiUploadAccount, row: BangumiUploadRow, fields: JSONObject, privateNew: Boolean): JSONObject {
        beforePrepare()
        val current = rows[row.local.subjectId]
        check(current?.getString("fingerprint") == row.remote?.fingerprint) { "远端已变化" }
        return JSONObject().put("collection", current ?: JSONObject.NULL)
            .put("episode_fingerprint", if (fields.has("progress")) "a".repeat(64) else JSONObject.NULL)
            .put("episodes", if (fields.has("progress")) JSONArray((1..3).map { ep -> JSONObject().put("id", ep + 100).put("ep", ep)
                .put("type", if (ep <= (current?.optInt("ep_status") ?: 0)) 2 else 0) }) else JSONObject.NULL)
    }
    override suspend fun apply(account: BangumiUploadAccount, row: BangumiUploadRow, fields: JSONObject, privateNew: Boolean, prepared: JSONObject): JSONObject {
        applied += JSONObject(fields.toString())
        onApply()
        val previous = rows[row.local.subjectId]
        val next = uploadFixtureRow(row.local.subjectId!!, row.local.kind.id,
            fields.optInt("rate", previous?.optInt("rate") ?: 0), fields.optInt("type", previous?.optInt("type") ?: 3),
            fields.optInt("chapters", fields.optInt("progress", previous?.optInt("ep_status") ?: 0)))
        next.put("vol_status", fields.optInt("volumes", previous?.optInt("vol_status") ?: 0))
        rows[row.local.subjectId!!] = next
        if (fields.has("tags")) next.put("tags", fields.getJSONArray("tags"))
        if (fields.has("comment")) next.put("comment", fields.getString("comment"))
        return JSONObject().put("verified", true).put("collection", next)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class BangumiUploadServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AniMeowDatabase
    private lateinit var directory: File
    private lateinit var remote: FakeBangumiUploadRemote
    private lateinit var journal: BangumiUploadJournal
    private var clock = 1000L
    private fun service() = BangumiUploadService(context, db, remote, journal, { clock }, 0, 0)
    private suspend fun local(id: Long = 1, rate: Int? = 90, status: String = "在看", progress: Int = 3, extra: String? = "私有感想"): Long =
        db.libraryDao().insertAnime(AnimeEntity(title = "合成作品$id", externalSource = "bangumi", externalId = "$id",
            status = status, rating = rate, watchedEpisodes = progress, totalEpisodes = 12, review = extra))
    private fun chosen(preview: BangumiUploadPreview) = preview.rows.filter { it.blocked == null }.associate { it.local.id to it.available.copy(tags = false, comment = false) }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "upload-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        remote = FakeBangumiUploadRemote(); journal = BangumiUploadJournal(directory)
    }
    @After fun close() { db.close(); directory.deleteRecursively() }

    @Test fun fullAuthenticatedReadExceedsTheOldThousandItemLimit() = runBlocking {
        local(); repeat(1051) { remote.rows[it + 1L] = uploadFixtureRow(it + 1L) }
        val preview = service().preview(BangumiUploadKind.ANIME)
        assertEquals(1051, preview.remoteCount); assertEquals(1, preview.rows.size)
        assertEquals(1L, preview.rows.single().remote!!.subjectId)
    }

    @Test fun duplicatesMissingPagesChangedTotalsAndMissingPrivacyBlockPreview() = runBlocking {
        local()
        val invalid = listOf<(Int) -> JSONObject>(
            { offset -> JSONObject().put("total", 2).put("offset", offset).put("data", JSONArray(listOf(uploadFixtureRow(1), uploadFixtureRow(1)))) },
            { offset -> JSONObject().put("total", 2).put("offset", offset).put("data", JSONArray(if (offset == 0) listOf(uploadFixtureRow(1)) else emptyList<JSONObject>())) },
            { offset -> JSONObject().put("total", if (offset == 0) 2 else 3).put("offset", offset).put("data", JSONArray(listOf(uploadFixtureRow(offset + 1L)))) },
            { offset -> JSONObject().put("total", 1).put("offset", offset).put("data", JSONArray(listOf(uploadFixtureRow(1).apply { remove("private") }))) },
        )
        for (page in invalid) {
            remote.pageLoader = page
            assertTrue(runCatching { service().preview(BangumiUploadKind.ANIME) }.isFailure)
        }
        assertTrue(remote.applied.isEmpty())
    }

    @Test fun confirmedUploadDoesNotModifyLocalPersonalRecordsAndProducesDurableSnapshots() = runBlocking {
        local(); remote.rows[1] = uploadFixtureRow(1)
        val original = db.libraryDao().getAllAnimesIncludingDeleted()
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        val report = JSONObject(service.execute(preview, chosen(preview), true))
        assertEquals("uploaded", report.getJSONArray("items").getJSONObject(0).getString("state"))
        assertEquals(original, db.libraryDao().getAllAnimesIncludingDeleted())
        assertNotNull(report.getJSONArray("items").getJSONObject(0).getJSONObject("before").getJSONArray("episodes"))
        assertEquals(report.toString(), service.lastReport(1))
        assertNull(service.lastReport(2)); assertFalse(report.toString().contains("Bearer"))
        assertTrue(runCatching { service.execute(preview, chosen(preview), true) }.isFailure)
    }

    @Test fun localChangesAndPendingQueueAreNeverOverwrittenOrDiscarded() = runBlocking {
        val id = local(); remote.rows[1] = uploadFixtureRow(1)
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        db.libraryDao().updateAnime(db.libraryDao().getAnime(id)!!.copy(review = "刚修改的感想"))
        val result = JSONObject(service.execute(preview, chosen(preview), true)).getJSONArray("items").getJSONObject(0)
        assertEquals("skipped", result.getString("state")); assertTrue(remote.applied.isEmpty())
        db.libraryDao().upsertBangumiPendingSync(BangumiPendingSyncEntity(animeId = id, subjectId = 1,
            kind = BangumiSyncKind.RATING, payloadJson = "{\"rate\":9}", createdAt = "now"))
        val next = service.preview(BangumiUploadKind.ANIME)
        assertTrue(next.rows.single().blocked!!.contains("待上报"))
        assertEquals(1, db.libraryDao().getBangumiPendingSyncs().size)
    }

    @Test fun changedBindingOrExpiredPreviewCannotStartWriting() = runBlocking {
        local(); val service = service(); var preview = service.preview(BangumiUploadKind.ANIME)
        remote.account = remote.account.copy(bangumiId = 701)
        assertTrue(runCatching { service.execute(preview, chosen(preview), true) }.isFailure)
        preview = service.preview(BangumiUploadKind.ANIME); clock += 600001
        assertTrue(runCatching { service.execute(preview, chosen(preview), true) }.isFailure)
        assertTrue(remote.applied.isEmpty())
    }

    @Test fun localEditDuringPreparationSkipsBeforeApply() = runBlocking {
        val id = local(); val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        remote.beforePrepare = { db.libraryDao().updateAnime(db.libraryDao().getAnime(id)!!.copy(watchedEpisodes = 4)) }
        val report = JSONObject(service.execute(preview, chosen(preview), true))
        assertEquals("skipped", report.getJSONArray("items").getJSONObject(0).getString("state"))
        assertTrue(remote.applied.isEmpty())
    }

    @Test fun unknownWriteOutcomeStopsRemainingRowsAndRequiresANewPreview() = runBlocking {
        local(1); local(2); val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        remote.onApply = { error("合成网络失败") }
        val report = JSONObject(service.execute(preview, chosen(preview), true))
        assertEquals("uncertain", report.getJSONArray("items").getJSONObject(0).getString("state"))
        assertEquals("not_executed", report.getJSONArray("items").getJSONObject(1).getString("state"))
        assertEquals(1, remote.applied.size)
    }

    @Test fun stopFinishesCurrentItemAndKeepsTheRestUnexecuted() = runBlocking {
        local(1); local(2); val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        var stop = false; remote.onApply = { stop = true }
        val report = JSONObject(service.execute(preview, chosen(preview), true, { stop }))
        assertEquals("stopped", report.getString("state")); assertEquals(1, remote.applied.size)
        assertEquals("uploaded", report.getJSONArray("items").getJSONObject(0).getString("state"))
        assertEquals("not_executed", report.getJSONArray("items").getJSONObject(1).getString("state"))
    }

    @Test fun cancellationKeepsAnUncertainJournalAndReleasesTheSharedQueueGate() = runBlocking {
        local(); val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        val entered = CompletableDeferred<Unit>()
        remote.onApply = { entered.complete(Unit); awaitCancellation() }
        val job = launch { service.execute(preview, chosen(preview), true) }
        entered.await(); job.cancelAndJoin()
        val saved = JSONObject(service.lastReport(1)!!)
        assertEquals("interrupted", saved.getString("state"))
        assertEquals("uncertain", saved.getJSONArray("items").getJSONObject(0).getString("state"))
        withTimeout(1000) { BangumiSyncGate.mutex.withLock { assertTrue(true) } }
    }

    @Test fun aFailedJournalCannotStartAnyNetworkWrite() = runBlocking {
        journal = BangumiUploadJournal(File(directory, "not-a-directory").apply { writeText("synthetic") })
        local(); val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        assertTrue(runCatching { service.execute(preview, chosen(preview), true) }.isFailure)
        assertTrue(remote.applied.isEmpty())
    }

    @Test fun fractionalScoresLetterGradesCustomStatusAndUnlinkedSourcesStayProtected() {
        for (anime in listOf(AnimeEntity(id = 1, title = "合成", rating = 85), AnimeEntity(id = 1, title = "合成", rating = 100, ratingGrade = "A"))) {
            assertNull(BangumiUploadLocal(anime).rate)
        }
        assertNull(BangumiUploadLocal(AnimeEntity(title = "合成", status = "自定义状态")).type)
        assertNull(BangumiUploadLocal(AnimeEntity(title = "合成", externalSource = "anilist", externalId = "1")).subjectId)
        assertNull(BangumiUploadLocal(AnimeEntity(title = "合成", subjectType = "book", watchedEpisodes = 3)).progress)
        assertEquals(1, BangumiUploadLocal(AnimeEntity(title = "合成", status = "未看")).type)
        assertEquals("上传失败", bangumiUploadFailureMessage(java.io.IOException("/private/token"), "上传失败"))
        assertEquals("上传失败", bangumiUploadFailureMessage(IllegalStateException("失败 /private/token"), "上传失败"))
    }

    @Test fun downloadOnlyReplacesSelectedPersonalFieldsWithoutEchoOrHistory() = runBlocking {
        val id = local(rate = 85)
        val old = db.libraryDao().getAnime(id)!!
        db.libraryDao().updateAnime(old.copy(ratingGrade = "A"))
        remote.rows[1] = uploadFixtureRow(1, type = 2, rate = 0, progress = 1)
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        val report = JSONObject(service.execute(preview, mapOf(id to BangumiUploadChoice(downloadStatus = true, downloadRating = true, downloadProgress = true)), true))
        val saved = db.libraryDao().getAnime(id)!!
        assertEquals("看完", saved.status); assertNull(saved.rating); assertNull(saved.ratingGrade)
        assertEquals(1, saved.watchedEpisodes); assertEquals(old.review, saved.review)
        assertEquals(old.totalEpisodes, saved.totalEpisodes); assertEquals(old.watchFinishDate, saved.watchFinishDate)
        assertTrue(db.libraryDao().getBangumiPendingSyncs().isEmpty()); assertTrue(remote.applied.isEmpty())
        val item = report.getJSONArray("items").getJSONObject(0)
        assertEquals("downloaded", item.getString("state")); assertEquals(85, item.getJSONObject("local_before").getInt("rating"))
        assertEquals("downloaded", JSONObject(service.lastReport(1)!!).getJSONArray("items").getJSONObject(0).getString("state"))
    }

    @Test fun mixedDirectionsUploadOnlySelectedFieldsAndPreserveOtherLocalValues() = runBlocking {
        val id = local(rate = 90, status = "想看")
        remote.rows[1] = uploadFixtureRow(1, type = 3, rate = 7, progress = 1)
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        val result = JSONObject(service.execute(preview, mapOf(id to BangumiUploadChoice(rating = true, downloadStatus = true, downloadProgress = true)), true))
        assertEquals(setOf("rate"), remote.applied.single().keys().asSequence().toSet())
        val local = db.libraryDao().getAnime(id)!!
        assertEquals("在看", local.status); assertEquals(90, local.rating); assertEquals(1, local.watchedEpisodes)
        assertEquals("merged", result.getJSONArray("items").getJSONObject(0).getString("state"))
    }

    @Test fun anEditDuringRemoteWritePreventsTheDownloadFromOverwritingNewIntent() = runBlocking {
        val id = local(status = "想看"); remote.rows[1] = uploadFixtureRow(1)
        remote.onApply = { db.libraryDao().updateAnime(db.libraryDao().getAnime(id)!!.copy(status = "自定义新状态")) }
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        val report = JSONObject(service.execute(preview, mapOf(id to BangumiUploadChoice(rating = true, downloadStatus = true)), true))
        assertEquals("自定义新状态", db.libraryDao().getAnime(id)!!.status)
        assertEquals("uploaded", report.getJSONArray("items").getJSONObject(0).getString("state"))
    }

    @Test fun oppositeDirectionsAndNonexistentRemoteCannotDownload() = runBlocking {
        val id = local(); remote.rows[1] = uploadFixtureRow(1)
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        assertTrue(runCatching { service.execute(preview, mapOf(id to BangumiUploadChoice(rating = true, downloadRating = true)), true) }.isFailure)
        assertTrue(remote.applied.isEmpty())
        remote.rows.clear()
        val missing = service.preview(BangumiUploadKind.ANIME)
        assertTrue(runCatching { service.execute(missing, mapOf(id to BangumiUploadChoice(downloadRating = true)), true) }.isFailure)
        assertEquals(90, db.libraryDao().getAnime(id)!!.rating)
    }

    @Test fun downloadingGameFieldsPreservesPlayTimeTagsAndReview() = runBlocking {
        val id = db.gameDao().insert(com.animeow.app.data.games.GameEntity(title = "合成游戏", bangumiId = 1,
            status = "想玩", rating = 9, playMinutes = 321, tags = "本机标签", review = "本机感想"))
        remote.rows[1] = uploadFixtureRow(1, kind = 4, rate = 0)
        val service = service(); val preview = service.preview(BangumiUploadKind.GAME)
        service.execute(preview, mapOf(id to BangumiUploadChoice(downloadRating = true, downloadStatus = true)), true)
        val saved = db.gameDao().get(id)!!
        assertEquals("在玩", saved.status); assertEquals(0, saved.rating); assertEquals(321L, saved.playMinutes)
        assertEquals("本机标签", saved.tags); assertEquals("本机感想", saved.review); assertEquals(0, db.gameDao().pendingCount())
    }

    @Test fun confirmedAssociationPreservesForeignSourceAndRequiresFreshPreview() = runBlocking {
        val id = db.libraryDao().insertAnime(AnimeEntity(title = "异源本机标题", externalSource = "anilist", externalId = "99", rating = 85))
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        val proposal = service.proposeAssociation(preview, id, 42)
        assertEquals("合成关联作品42", proposal.title)
        assertNull(BangumiUploadLocal(db.libraryDao().getAnime(id)).subjectId)
        service.confirmAssociation(proposal)
        val updated = db.libraryDao().getAnime(id)!!
        assertEquals("anilist", updated.externalSource); assertEquals("99", updated.externalId); assertEquals(85, updated.rating)
        assertEquals(42L, BangumiUploadLocal(updated).subjectId)
        assertTrue(remote.applied.isEmpty()); assertTrue(db.libraryDao().getBangumiPendingSyncs().isEmpty())
        assertTrue(runCatching { service.execute(preview, mapOf(id to BangumiUploadChoice(status = true)), true) }.isFailure)
        assertEquals(42L, service.preview(BangumiUploadKind.ANIME).rows.single().local.subjectId)
    }

    @Test fun associationRefusesDuplicateTrashedIdentityAndChangedLocalRecord() = runBlocking {
        val id = db.libraryDao().insertAnime(AnimeEntity(title = "未关联"))
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        val proposal = service.proposeAssociation(preview, id, 42)
        db.libraryDao().insertAnime(AnimeEntity(title = "回收站已有", externalSource = "bangumi", externalId = "42", deletedAt = "synthetic"))
        assertTrue(runCatching { service.confirmAssociation(proposal) }.isFailure)
        assertNull(BangumiUploadLocal(db.libraryDao().getAnime(id)).subjectId)
        db.libraryDao().updateAnime(db.libraryDao().getAnime(id)!!.copy(review = "新改动"))
        assertTrue(runCatching { service.confirmAssociation(proposal) }.isFailure)
        assertEquals("新改动", db.libraryDao().getAnime(id)!!.review)
    }

    @Test fun associationRefusesAccountChangesAndExistingConflictingBangumiReferences() = runBlocking {
        val id = db.libraryDao().insertAnime(AnimeEntity(title = "未关联"))
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        val proposal = service.proposeAssociation(preview, id, 42)
        remote.account = remote.account.copy(bangumiId = 701)
        assertTrue(runCatching { service.confirmAssociation(proposal) }.isFailure)
        assertNull(BangumiUploadLocal(db.libraryDao().getAnime(id)).subjectId)
        val conflicting = com.animeow.app.data.local.encodeAnimeSourceReferences(listOf(
            com.animeow.app.data.local.AnimeSourceReference("A", source = "bangumi", externalId = "41"),
            com.animeow.app.data.local.AnimeSourceReference("B", source = "bangumi", externalId = "42")))
        db.libraryDao().updateAnime(db.libraryDao().getAnime(id)!!.copy(mergedSourcesJson = conflicting))
        val newer = service.preview(BangumiUploadKind.ANIME)
        assertNull(newer.rows.single().local.subjectId)
        assertTrue(runCatching { service.proposeAssociation(newer, id, 43) }.isFailure)
    }

    @Test fun importingRemoteOnlyCreatesPersonalValuesWithoutRemoteWritesOrPublicRating() = runBlocking {
        remote.rows[42] = uploadFixtureRow(42, type = 2, rate = 8, progress = 2).put("title", "远端合成作品")
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        assertTrue(preview.rows.isEmpty()); assertEquals(42L, preview.remoteOnly.single().subjectId)
        val report = JSONObject(service.importRemote(preview, 42, true))
        val saved = db.libraryDao().getAllAnimesIncludingDeleted().single()
        assertEquals("远端合成作品", saved.title); assertEquals("看完", saved.status); assertEquals(80, saved.rating)
        assertEquals(2, saved.watchedEpisodes); assertNull(saved.review)
        assertTrue(remote.applied.isEmpty()); assertTrue(db.libraryDao().getBangumiPendingSyncs().isEmpty())
        assertEquals(saved.id, report.getJSONArray("items").getJSONObject(0).getLong("local_id"))
        assertEquals("downloaded", JSONObject(service.lastReport(1)!!).getJSONArray("items").getJSONObject(0).getString("state"))
        assertTrue(runCatching { service.importRemote(preview, 42, true) }.isFailure)
    }

    @Test fun importingRemoteOnlyRechecksRemoteAndLocalIdentityBeforeCreating() = runBlocking {
        remote.rows[42] = uploadFixtureRow(42)
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        db.libraryDao().insertAnime(AnimeEntity(title = "刚加入的回收站记录", externalSource = "bangumi", externalId = "42", deletedAt = "synthetic"))
        assertTrue(runCatching { service.importRemote(preview, 42, false) }.isFailure)
        assertEquals(1, db.libraryDao().getAllAnimesIncludingDeleted().size)
        remote.rows[43] = uploadFixtureRow(43)
        val newer = service.preview(BangumiUploadKind.ANIME)
        remote.rows[43] = uploadFixtureRow(43, rate = 9)
        assertTrue(runCatching { service.importRemote(newer, 43, false) }.isFailure)
        assertEquals(1, db.libraryDao().getAllAnimesIncludingDeleted().size)
    }

    @Test fun remoteBookAndGameCreationDoNotInventProgressOrTime() = runBlocking {
        val service = service()
        remote.rows[42] = uploadFixtureRow(42, kind = 1, rate = 0, progress = 17)
        service.importRemote(service.preview(BangumiUploadKind.BOOK), 42, false)
        val book = db.libraryDao().getAllAnimesIncludingDeleted().single()
        assertEquals("book", book.subjectType); assertEquals(0, book.watchedEpisodes); assertNull(book.rating)
        remote.rows.clear(); remote.rows[43] = uploadFixtureRow(43, kind = 4, rate = 6)
        service.importRemote(service.preview(BangumiUploadKind.GAME), 43, false)
        val game = db.gameDao().all().single()
        assertEquals(6, game.rating); assertEquals(0L, game.playMinutes); assertEquals("在玩", game.status)
        assertTrue(remote.applied.isEmpty())
    }

    @Test fun tagsAndCommentsAreExplicitAndTheirLocalReplacementPreservesGlobalTags() = runBlocking {
        val id = local()
        val dao = db.libraryDao()
        val tag = dao.insertTag(com.animeow.app.data.local.TagEntity(name = "旧标签", color = 123))
        dao.insertAnimeTags(listOf(com.animeow.app.data.local.AnimeTagEntity(id, tag)))
        remote.rows[1] = uploadFixtureRow(1).put("tags", JSONArray(listOf("新标签"))).put("comment", "远端短评")
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        service.execute(preview, mapOf(id to BangumiUploadChoice(downloadTags = true, downloadComment = true)), true)
        assertEquals("远端短评", dao.getAnime(id)!!.review)
        assertEquals(listOf("新标签"), dao.getTagIdsForAnime(id).map { dao.getTag(it)!!.name })
        assertEquals(123L, dao.getTag(tag)!!.color)
        assertTrue(remote.applied.isEmpty())
        val newer = service.preview(BangumiUploadKind.ANIME)
        dao.updateAnime(dao.getAnime(id)!!.copy(review = ""))
        dao.clearAnimeTagsForAnime(id)
        val changed = service.preview(BangumiUploadKind.ANIME)
        service.execute(changed, mapOf(id to BangumiUploadChoice(tags = true, comment = true)), true)
        assertEquals(0, remote.applied.single().getJSONArray("tags").length())
        assertEquals("", remote.applied.single().getString("comment"))
    }

    @Test fun tagEditsDuringPreparationAreProtectedEvenIfAnimeEntityIsUnchanged() = runBlocking {
        val id = local(); remote.rows[1] = uploadFixtureRow(1)
        val service = service(); val preview = service.preview(BangumiUploadKind.ANIME)
        remote.beforePrepare = {
            val tag = db.libraryDao().insertTag(com.animeow.app.data.local.TagEntity(name = "新本机改动"))
            db.libraryDao().insertAnimeTags(listOf(com.animeow.app.data.local.AnimeTagEntity(id, tag)))
        }
        val report = JSONObject(service.execute(preview, mapOf(id to BangumiUploadChoice(downloadTags = true)), true))
        assertEquals("skipped", report.getJSONArray("items").getJSONObject(0).getString("state"))
        assertEquals("新本机改动", db.libraryDao().getTags().single().name)
    }

    @Test fun invalidTagFormsAndLongCommentsAreNeverSilentlyTransformed() {
        assertFalse(validBangumiUploadTags(listOf("含 空格")))
        assertFalse(validBangumiUploadTags(listOf("a", "a")))
        val game = BangumiUploadLocal(game = com.animeow.app.data.games.GameEntity(title = "合成", bangumiId = 1, review = "长".repeat(5001)))
        val remote = BangumiUploadRemote.parse(uploadFixtureRow(1, kind = 4).put("tags", JSONArray(listOf("含,逗号"))), BangumiUploadKind.GAME)
        val row = BangumiUploadRow(game, remote)
        assertFalse(row.available.comment); assertFalse(row.downloadable.tags)
    }

    @Test fun bookVolumeAndChapterProgressNeverGuessTheOldGenericCounter() = runBlocking {
        val id = db.libraryDao().insertAnime(AnimeEntity(title = "书籍", subjectType = "book", watchedEpisodes = 55,
            externalSource = "bangumi", externalId = "42"))
        remote.rows[42] = uploadFixtureRow(42, kind = 1, progress = 17).put("vol_status", 3)
        val service = service(); val preview = service.preview(BangumiUploadKind.BOOK)
        val row = preview.rows.single()
        assertFalse(row.available.volumes); assertFalse(row.available.chapters)
        service.execute(preview, mapOf(id to BangumiUploadChoice(downloadVolumes = true, downloadChapters = true)), true)
        val saved = db.libraryDao().getAnime(id)!!
        assertEquals(3, saved.bookReadVolumes); assertEquals(17, saved.bookReadChapters); assertEquals(55, saved.watchedEpisodes)
        val newer = service.preview(BangumiUploadKind.BOOK)
        service.saveBookProgress(newer, id, 0, 5)
        assertEquals(55, db.libraryDao().getAnime(id)!!.watchedEpisodes)
        val latest = service.preview(BangumiUploadKind.BOOK)
        service.execute(latest, mapOf(id to BangumiUploadChoice(volumes = true, chapters = true)), true)
        assertEquals(0, remote.applied.single().getInt("volumes")); assertEquals(5, remote.applied.single().getInt("chapters"))
        assertFalse(remote.applied.single().has("progress"))
        assertTrue(runCatching { service.saveBookProgress(service.preview(BangumiUploadKind.BOOK), id, -1, 2) }.isFailure)
    }

    @Test fun abruptProcessLossReconstructsTheLastDurableItemWithoutReplayingIt() {
        val file = journal.newFile()
        val report = JSONObject().put("user_id", 1).put("state", "running").put("items", JSONArray(listOf(
            JSONObject().put("state", "not_executed"), JSONObject().put("state", "not_executed"))))
        journal.save(file, report)
        report.getJSONArray("items").getJSONObject(0).put("state", "in_flight")
        journal.save(file, report, 0)
        val reopened = JSONObject(BangumiUploadJournal(directory).latest(1)!!)
        assertEquals("in_flight", reopened.getJSONArray("items").getJSONObject(0).getString("state"))
        assertEquals("not_executed", reopened.getJSONArray("items").getJSONObject(1).getString("state"))
    }

    @Test fun booksAndGamesUseTheSameConfirmedUploadWithoutChangingTheirLocalProgressOrTime() = runBlocking {
        val bookId = db.libraryDao().insertAnime(AnimeEntity(title = "合成书籍", subjectType = "book", externalSource = "bangumi",
            externalId = "8", status = "想看", rating = 90, watchedEpisodes = 4, totalEpisodes = 10))
        remote.rows[8] = uploadFixtureRow(8, kind = 1)
        val service = service(); val book = service.preview(BangumiUploadKind.BOOK)
        assertFalse(book.rows.single().available.progress)
        service.execute(book, chosen(book), true)
        assertEquals(4, db.libraryDao().getAnime(bookId)!!.watchedEpisodes)
        val gameId = db.gameDao().insert(com.animeow.app.data.games.GameEntity(title = "合成游戏", bangumiId = 9,
            status = "在玩", rating = 6, playMinutes = 321))
        val original = db.gameDao().get(gameId)
        remote.rows.clear(); remote.rows[9] = uploadFixtureRow(9, kind = 4)
        val game = service.preview(BangumiUploadKind.GAME)
        assertFalse(game.rows.single().available.progress)
        val result = JSONObject(service.execute(game, chosen(game), true))
        assertEquals("uploaded", result.getJSONArray("items").getJSONObject(0).getString("state"))
        assertEquals(original, db.gameDao().get(gameId))
    }

    @Test fun snapshotsWhitelistFieldsAndNeverCopyUnexpectedUpstreamCredentials() {
        val parsed = BangumiUploadRemote.parse(uploadFixtureRow(1).put("access_token", "synthetic-secret"), BangumiUploadKind.ANIME)
        assertFalse(parsed.snapshot.contains("access_token")); assertFalse(parsed.snapshot.contains("synthetic-secret"))
        assertTrue(parsed.snapshot.contains("comment"))
    }
}
