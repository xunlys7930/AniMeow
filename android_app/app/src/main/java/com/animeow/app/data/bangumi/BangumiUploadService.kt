package com.animeow.app.data.bangumi

import android.content.Context
import androidx.room.withTransaction
import com.animeow.app.data.games.gameStatusFromCollection
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.games.GameEntity
import com.animeow.app.data.local.AnimeSourceReference
import com.animeow.app.data.local.encodeAnimeSourceReferences
import com.animeow.app.data.local.parseAnimeSourceReferences
import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.util.withDataTransferLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Confirmed upload and the existing automatic queues must not write concurrently in this process. */
internal object BangumiSyncGate { val mutex = Mutex() }

internal class BangumiUploadJournal(private val directory: File) {
    fun save(file: File, value: JSONObject, itemIndex: Int? = null) {
        check(directory.exists() || directory.mkdirs()) { "无法保存本机上传记录，本次停止" }
        // Persist each in-flight item separately; rewriting a growing batch for every row wears storage.
        val target = if (itemIndex != null && file.exists() && value.optString("state") == "running") itemFile(file, itemIndex) else file
        val payload = if (target == file) value else value.getJSONArray("items").getJSONObject(itemIndex!!)
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            // Fail closed if atomic replacement is unavailable: the preceding in-flight record must survive.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
    fun newFile() = File(directory, "${System.currentTimeMillis()}_${UUID.randomUUID()}.json")
    fun latest(userId: Long?): String? {
        if (userId == null) return null
        return directory.listFiles { file -> file.extension == "json" }.orEmpty().sortedByDescending(File::lastModified)
            .firstNotNullOfOrNull { file -> runCatching {
                val value = JSONObject(file.readText())
                if (value.optLong("user_id") != userId) return@runCatching null
                if (value.optString("state") == "running") {
                    val items = value.getJSONArray("items")
                    for (index in 0 until items.length()) {
                        val saved = itemFile(file, index)
                        if (saved.exists()) items.put(index, JSONObject(saved.readText()))
                    }
                }
                value.toString()
            }.getOrNull() }
    }
    private fun itemFile(file: File, index: Int) = File(directory, "${file.nameWithoutExtension}-$index.item")
}

internal class BangumiUploadService(
    context: Context,
    private val database: AniMeowDatabase,
    private val remote: BangumiUploadRemoteClient = BangumiUploadClient(context),
    private val journal: BangumiUploadJournal = BangumiUploadJournal(File(context.filesDir, "bangumi_upload_history")),
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val pageDelayMs: Long = 120,
    private val rowDelayMs: Long = 150,
) {
    private var active: BangumiUploadPreview? = null

    suspend fun preview(kind: BangumiUploadKind, onProgress: (Int, Int) -> Unit = { _, _ -> }): BangumiUploadPreview = withContext(Dispatchers.IO) {
        active = null
        val account = remote.captureAccount()
        remote.checkCapability()
        val locals = database.withTransaction { localItems(kind) }
        val localSubjectIds = locals.mapNotNullTo(hashSetOf()) { it.subjectId }
        val collected = linkedMapOf<Long, BangumiUploadRemote>()
        val remoteOnly = mutableListOf<BangumiUploadRemote>()
        val seen = hashSetOf<Long>()
        var total: Int? = null
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = remote.page(account, kind, offset)
            val rawTotal = page.opt("total") as? Number ?: error("收藏总数缺失，请重新读取")
            val count = rawTotal.toInt()
            val items = page.getJSONArray("data")
            check(rawTotal.toDouble() == count.toDouble() && count in 0..100000 && page.opt("offset") == offset && items.length() <= 50 &&
                (total == null || total == count)) { "收藏分页发生变化或不完整，请重新读取" }
            total = count
            for (i in 0 until items.length()) {
                val item = BangumiUploadRemote.parse(items.getJSONObject(i), kind)
                check(seen.add(item.subjectId)) { "收藏分页出现重复作品，请重新读取" }
                if (item.subjectId in localSubjectIds) collected[item.subjectId] = item else remoteOnly += item
            }
            onProgress(seen.size, count)
            if (seen.size == count) break
            check(items.length() > 0 && seen.size < count) { "收藏分页读取不完整，请重新读取" }
            offset += items.length()
            delay(pageDelayMs)
        }
        remote.verifyAccount(account)
        val pending = database.withTransaction {
            check(localItems(kind).associateBy { it.id } == locals.associateBy { it.id }) { "读取期间本机资料已变化，请重新读取" }
            pendingIds(account, kind)
        }
        val duplicates = locals.mapNotNull { it.subjectId }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val rows = locals.map { local ->
            val other = collected[local.subjectId]
            val reason = when {
                local.anime?.deletedAt != null || local.game?.deletedAt != null -> "回收站作品不会上传"
                local.subjectId == null || local.subjectId!! <= 0 -> "未确认 Bangumi 编号；请先在作品资料中核对来源"
                local.subjectId in duplicates -> "本机有重复 Bangumi 身份，请先整理"
                local.id in pending -> "这部作品仍有待上报改动，请先完成现有同步再读取"
                other == null && local.type == null -> "本机状态没有明确对应；新建收藏前请先确认状态"
                else -> null
            }
            BangumiUploadRow(local, other, reason)
        }
        BangumiUploadPreview(account, kind, rows, seen.size, now(), remoteOnly).also { active = it }
    }

    private suspend fun localItems(kind: BangumiUploadKind): List<BangumiUploadLocal> =
        if (kind == BangumiUploadKind.GAME) database.gameDao().all().map { BangumiUploadLocal(game = it) }
        else {
            val dao = database.libraryDao()
            val tags = dao.getTags().associateBy { it.id }
            val links = dao.getAnimeTags().groupBy { it.animeId }
            dao.getAllAnimesIncludingDeleted().map { anime -> BangumiUploadLocal(anime = anime,
                animeTags = links[anime.id].orEmpty().mapNotNull { tags[it.tagId]?.name }.sorted()) }.filter { it.kind == kind }
        }

    private suspend fun pendingIds(account: BangumiUploadAccount, kind: BangumiUploadKind): Set<Long> =
        if (kind == BangumiUploadKind.GAME) database.gameDao().pending(account.userId, account.bangumiId).mapTo(hashSetOf()) { it.gameId }
        else database.libraryDao().getBangumiPendingSyncs().mapTo(hashSetOf()) { it.animeId }

    private suspend fun unchanged(account: BangumiUploadAccount, local: BangumiUploadLocal): Boolean =
        database.withTransaction {
            val current = if (local.game != null) database.gameDao().get(local.id)?.let { BangumiUploadLocal(game = it) }
                else database.libraryDao().getAnime(local.id)?.let { anime -> BangumiUploadLocal(anime = anime,
                    animeTags = database.libraryDao().getAnimeTagsByAnimeId(anime.id).mapNotNull { database.libraryDao().getTag(it.tagId)?.name }.sorted()) }
            current == local && local.id !in pendingIds(account, local.kind)
        }

    private suspend fun applyLocal(account: BangumiUploadAccount, row: BangumiUploadRow, fields: JSONObject): Boolean = database.withTransaction {
        if (!unchanged(account, row.local)) return@withTransaction false
        val local = row.local
        local.anime?.let { old ->
            val status = if (fields.has("type")) when (fields.getInt("type")) {
                1 -> "想看"; 2 -> "看完"; 3 -> "在看"; 4 -> "搁置"; else -> "弃坑"
            } else old.status
            database.libraryDao().updateAnime(old.copy(status = status,
                statusChangedAt = if (status != old.status) System.currentTimeMillis() else old.statusChangedAt,
                rating = if (fields.has("rate")) fields.getInt("rate").takeIf { it > 0 }?.times(10) else old.rating,
                ratingGrade = if (fields.has("rate")) null else old.ratingGrade,
                review = if (fields.has("comment")) fields.getString("comment") else old.review,
                bookReadVolumes = if (fields.has("volumes")) fields.getInt("volumes") else old.bookReadVolumes,
                bookReadChapters = if (fields.has("chapters")) fields.getInt("chapters") else old.bookReadChapters,
                watchedEpisodes = if (fields.has("progress")) fields.getInt("progress") else old.watchedEpisodes))
            if (fields.has("tags")) {
                val dao = database.libraryDao()
                val names = fields.getJSONArray("tags").let { array -> (0 until array.length()).map(array::getString) }
                val ids = names.map { name -> dao.getTagByName(name)?.id ?: dao.insertTag(TagEntity(name = name)) }
                dao.clearAnimeTagsForAnime(old.id)
                dao.insertAnimeTags(ids.distinct().map { AnimeTagEntity(old.id, it) })
            }
        }
        local.game?.let { old -> database.gameDao().update(old.copy(
            status = if (fields.has("type")) gameStatusFromCollection(fields.getInt("type")) else old.status,
            rating = if (fields.has("rate")) fields.getInt("rate") else old.rating,
            tags = if (fields.has("tags")) fields.getJSONArray("tags").let { array -> (0 until array.length()).map(array::getString).joinToString("，") } else old.tags,
            review = if (fields.has("comment")) fields.getString("comment") else old.review,
            updatedAt = java.time.Instant.now().toString())) }
        // Direct DAO writes deliberately do not enqueue an echo of the downloaded values.
        true
    }

    fun lastReport(userId: Long?) = journal.latest(userId)

    suspend fun saveBookProgress(preview: BangumiUploadPreview, localId: Long, volumes: Int?, chapters: Int?) = withContext(Dispatchers.IO) {
        check(active === preview && now() - preview.createdAt in 0..600_000 && preview.kind == BangumiUploadKind.BOOK) { "对比已过期，请重新读取" }
        check((volumes == null || volumes in 0..10000) && (chapters == null || chapters in 0..10000)) { "卷数和话数请填写 0 至 10000，未知可留空" }
        val row = preview.rows.single { it.local.id == localId }
        BangumiSyncGate.mutex.withLock { withDataTransferLock {
            remote.verifyAccount(preview.account)
            database.withTransaction {
                check(unchanged(preview.account, row.local) && row.local.anime?.deletedAt == null) { "本机记录或待上报改动已变化，请重新读取" }
                database.libraryDao().updateAnime(row.local.anime!!.copy(bookReadVolumes = volumes, bookReadChapters = chapters))
            }
            active = null
        } }
    }

    private suspend fun identityExists(subjectId: Long) = database.libraryDao().getAllAnimesIncludingDeleted().any { anime ->
        anime.sourceReferences().any { it.source?.lowercase() == "bangumi" && it.externalId?.toLongOrNull() == subjectId }
    } || database.gameDao().byBangumiId(subjectId) != null

    suspend fun importRemote(preview: BangumiUploadPreview, subjectId: Long, includeProgress: Boolean): String = withContext(Dispatchers.IO) {
        check(active === preview && now() - preview.createdAt in 0..600_000) { "对比已过期，请重新读取" }
        val value = preview.remoteOnly.single { it.subjectId == subjectId }
        active = null
        BangumiSyncGate.mutex.withLock { withDataTransferLock {
            remote.verifyAccount(preview.account)
            check(!identityExists(subjectId)) { "本机已有相同关联（含回收站），请重新读取" }
            val fields = JSONObject().put("type", value.type).put("rate", value.rate)
            if (includeProgress) {
                check(preview.kind == BangumiUploadKind.ANIME && value.progress in 0..2000) { "该进度不能可靠导入" }
                fields.put("progress", value.progress)
            }
            val local = if (preview.kind == BangumiUploadKind.GAME) BangumiUploadLocal(game = GameEntity(title = value.title, bangumiId = subjectId))
                else BangumiUploadLocal(anime = AnimeEntity(title = value.title, externalSource = "bangumi", externalId = subjectId.toString(),
                    subjectType = if (preview.kind == BangumiUploadKind.BOOK) "book" else "anime"))
            val row = BangumiUploadRow(local, value)
            val prepared = preparedSnapshot(row, fields, remote.prepare(preview.account, row, fields, true))
            remote.verifyAccount(preview.account)
            val item = JSONObject().put("subject_id", subjectId).put("title", value.title).put("state", "in_flight")
                .put("download_fields", fields).put("local_before", JSONObject.NULL).put("before", prepared)
            val report = JSONObject().put("version", 1).put("user_id", preview.account.userId).put("bangumi_id", preview.account.bangumiId)
                .put("username", preview.account.username).put("subject_type", preview.kind.id).put("state", "running")
                .put("started_at", java.time.Instant.now().toString()).put("items", JSONArray(listOf(item)))
            val file = journal.newFile()
            journal.save(file, report)
            val id = database.withTransaction {
                check(!identityExists(subjectId)) { "保存前本机出现相同关联，请重新读取" }
                val status = when (value.type) { 1 -> "想看"; 2 -> "看完"; 3 -> "在看"; 4 -> "搁置"; else -> "弃坑" }
                local.anime?.let { database.libraryDao().insertAnime(it.copy(status = status,
                    rating = value.rate.takeIf { rate -> rate > 0 }?.times(10), watchedEpisodes = if (includeProgress) value.progress else 0,
                    createdAt = java.time.Instant.now().toString())) }
                    ?: database.gameDao().insert(local.game!!.copy(status = gameStatusFromCollection(value.type), rating = value.rate))
            }
            item.put("local_id", id).put("state", "downloaded").put("after", JSONObject(value.snapshot))
            report.put("state", "finished")
            journal.save(file, report)
            report.toString()
        } }
    }

    suspend fun proposeAssociation(preview: BangumiUploadPreview, localId: Long, subjectId: Long): BangumiUploadAssociation = withContext(Dispatchers.IO) {
        check(active === preview && now() - preview.createdAt in 0..600_000 && subjectId > 0) { "对比已过期或编号无效，请重新读取" }
        val row = preview.rows.single { it.local.id == localId }
        check(row.local.subjectId == null && row.local.anime?.deletedAt == null && row.local.game?.deletedAt == null) { "已有来源或回收站记录不能在此重新关联" }
        check(row.local.anime?.sourceReferences()?.none { it.source?.lowercase() == "bangumi" } != false) { "已有 Bangumi 来源，请先整理冲突" }
        remote.verifyAccount(preview.account)
        val metadata = remote.lookup(preview.account, preview.kind, subjectId)
        check(metadata.optLong("subject_id") == subjectId && metadata.optInt("subject_type") == preview.kind.id) { "作品编号或分类不一致" }
        val title = metadata.optString("title").takeIf { it.isNotBlank() } ?: error("作品资料缺失，请重试")
        BangumiUploadAssociation(preview, row.local, subjectId, title)
    }

    suspend fun confirmAssociation(proposal: BangumiUploadAssociation) = withContext(Dispatchers.IO) {
        val preview = proposal.preview
        check(active === preview && now() - preview.createdAt in 0..600_000) { "对比已过期，请重新读取" }
        BangumiSyncGate.mutex.withLock { withDataTransferLock {
            remote.verifyAccount(preview.account)
            database.withTransaction {
                check(unchanged(preview.account, proposal.local)) { "本机记录或待上报改动已变化，请重新读取" }
                check(!identityExists(proposal.subjectId)) { "此 Bangumi 编号已有关联记录（含回收站），请先整理" }
                proposal.local.anime?.let { old -> database.libraryDao().updateAnime(old.copy(mergedSourcesJson = encodeAnimeSourceReferences(
                    parseAnimeSourceReferences(old.mergedSourcesJson) + AnimeSourceReference(proposal.title, source = "bangumi",
                        externalId = proposal.subjectId.toString(), url = "https://bgm.tv/subject/${proposal.subjectId}")))) }
                proposal.local.game?.let { old -> database.gameDao().update(old.copy(bangumiId = proposal.subjectId)) }
            }
            active = null // Association changes identity; require a fresh full comparison before any data write.
        } }
    }

    private fun preparedSnapshot(row: BangumiUploadRow, fields: JSONObject, prepared: JSONObject): JSONObject {
        check(prepared.has("collection") && prepared.has("episode_fingerprint")) { "Bangumi 收藏状态校验信息缺失，请重新读取对比" }
        val collection = if (prepared.isNull("collection")) null else BangumiUploadRemote.parse(prepared.getJSONObject("collection"), row.local.kind)
        check(collection?.fingerprint == row.remote?.fingerprint && (collection == null || collection.subjectId == row.local.subjectId)) {
            "Bangumi 上的收藏刚发生变动，请重新读取对比后再试"
        }
        val snapshot = JSONObject().put("collection", collection?.snapshot?.let(::JSONObject) ?: JSONObject.NULL)
        if (fields.has("progress")) {
            val hash = prepared.getString("episode_fingerprint")
            val episodes = prepared.getJSONArray("episodes")
            val progress = collection?.progress ?: 0
            check(hash.matches(Regex("[a-f0-9]{64}")) && episodes.length() in 1..2000 && progress <= episodes.length() &&
                fields.getInt("progress") <= episodes.length()) { "Bangumi 章节资料不完整，暂无法同步该作品进度" }
            val ids = hashSetOf<Long>()
            val safeEpisodes = JSONArray()
            for (index in 0 until episodes.length()) {
                val item = episodes.getJSONObject(index)
                val id = item.opt("id") as? Number ?: error("Bangumi 章节编号缺失，暂无法同步进度")
                check(id.toLong() > 0 && id.toDouble() == id.toLong().toDouble() && ids.add(id.toLong()) &&
                    item.opt("ep") == index + 1 && item.opt("type") == if (index < progress) 2 else 0) { "Bangumi 章节顺序不连续，暂无法自动按集同步" }
                safeEpisodes.put(JSONObject().put("id", id.toLong()).put("ep", index + 1).put("type", item.getInt("type")))
            }
            snapshot.put("episodes", safeEpisodes).put("episode_fingerprint", hash)
        } else {
            check(prepared.isNull("episode_fingerprint")) { "对比信息已变动，请重新读取后再试" }
            snapshot.put("episodes", JSONObject.NULL).put("episode_fingerprint", JSONObject.NULL)
        }
        return snapshot
    }

    suspend fun execute(preview: BangumiUploadPreview, choices: Map<Long, BangumiUploadChoice>, privateNew: Boolean,
        shouldStop: () -> Boolean = { false }, onReport: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        check(active === preview && now() - preview.createdAt in 0..600_000) { "对比已过期或使用过，请重新读取" }
        val selected = preview.rows.filter { choices[it.local.id]?.selected == true }
        check(selected.isNotEmpty()) { "请先选择需要上传的作品字段" }
        val fields = selected.map { it.fields(choices.getValue(it.local.id)) }
        val downloads = selected.map { it.downloadFields(choices.getValue(it.local.id)) }
        active = null // A failed/unknown write must be reconciled by a new preview, never blindly replayed.
        BangumiSyncGate.mutex.withLock {
            withDataTransferLock {
                remote.verifyAccount(preview.account)
                val results = JSONArray(selected.mapIndexed { index, row -> JSONObject()
                    .put("subject_id", row.local.subjectId).put("local_id", row.local.id).put("title", row.local.title)
                    .put("fields", fields[index]).put("state", "not_executed")
                    .put("download_fields", downloads[index])
                    .put("local_before", JSONObject().put("status", row.local.status).put("rating", row.local.anime?.rating ?: row.local.game?.rating ?: JSONObject.NULL)
                        .put("rating_grade", row.local.anime?.ratingGrade ?: JSONObject.NULL).put("progress", row.local.progress ?: JSONObject.NULL)
                        .put("tags", JSONArray(row.local.tags)).put("comment", row.local.comment)
                        .put("book_volumes", row.local.bookVolumes ?: JSONObject.NULL).put("book_chapters", row.local.bookChapters ?: JSONObject.NULL))
                    .put("expected_collection", row.remote?.snapshot?.let(::JSONObject) ?: JSONObject.NULL) })
                val report = JSONObject().put("version", 1).put("user_id", preview.account.userId)
                    .put("bangumi_id", preview.account.bangumiId).put("username", preview.account.username)
                    .put("subject_type", preview.kind.id).put("private_new", privateNew)
                    .put("started_at", java.time.Instant.now().toString()).put("state", "running").put("items", results)
                val file = journal.newFile()
                var itemIndex: Int? = null
                suspend fun save() { journal.save(file, report, itemIndex); onReport(report.toString()) }
                save() // No network writes can happen before the journal is durable.
                var current: JSONObject? = null
                try {
                    for ((index, row) in selected.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (shouldStop()) break
                        current = results.getJSONObject(index)
                        itemIndex = index
                        if (!unchanged(preview.account, row.local)) {
                            current.put("state", "skipped").put("message", "对比期间本机记录有新改动，请重新读取对比")
                            save(); continue
                        }
                        remote.verifyAccount(preview.account)
                        val checking = JSONObject(fields[index].toString()).apply {
                            val values = downloads[index]
                            for (key in values.keys()) put(key, values.get(key))
                        }
                        val prepared = preparedSnapshot(row, checking, remote.prepare(preview.account, row, checking, privateNew))
                        current.put("before", prepared)
                        if (!unchanged(preview.account, row.local)) {
                            current.put("state", "skipped").put("message", "准备期间本机记录已变化，请重新对比")
                            save(); continue
                        }
                        current.put("state", "in_flight"); save()
                        val result = if (fields[index].length() > 0) {
                            val uploadSnapshot = JSONObject(prepared.toString()).apply {
                                if (!fields[index].has("progress")) put("episode_fingerprint", JSONObject.NULL)
                            }
                            remote.apply(preview.account, row, fields[index], privateNew, uploadSnapshot)
                        } else JSONObject().put("verified", true).put("collection", prepared.getJSONObject("collection"))
                        val after = result.getJSONObject("collection")
                        val verified = BangumiUploadRemote.parse(after, preview.kind)
                        check(result.optBoolean("verified") && verified.subjectId == row.local.subjectId &&
                            (!fields[index].has("type") || fields[index].getInt("type") == verified.type) &&
                            (!fields[index].has("rate") || fields[index].getInt("rate") == verified.rate) &&
                            (!fields[index].has("comment") || fields[index].getString("comment") == verified.comment) &&
                            (!fields[index].has("tags") || fields[index].getJSONArray("tags").toString() == JSONArray(verified.tags).toString()) &&
                            (!fields[index].has("volumes") || fields[index].getInt("volumes") == verified.volumes) &&
                            (!fields[index].has("chapters") || fields[index].getInt("chapters") == verified.progress) &&
                            (!fields[index].has("progress") || fields[index].getInt("progress") == verified.progress)) {
                                "未取得可靠的上传确认，请重新读取对比"
                            }
                        current.put("after", JSONObject(verified.snapshot))
                        if (downloads[index].length() > 0) {
                            remote.verifyAccount(preview.account)
                            val changed = applyLocal(preview.account, row, downloads[index])
                            current.put("state", if (changed) if (fields[index].length() > 0) "merged" else "downloaded" else if (fields[index].length() > 0) "uploaded" else "skipped")
                            if (!changed) current.put("message", "本机随后有新改动，未覆盖本机；请重新对比")
                        } else {
                            current.put("state", "uploaded")
                            if (!unchanged(preview.account, row.local)) current.put("message", "本次所选修改已上传；本机随后又有新改动，如需同步请重新对比")
                        }
                        save(); current = null; itemIndex = null
                        if (index < selected.lastIndex && !shouldStop()) delay(rowDelayMs)
                    }
                    report.put("state", if (shouldStop()) "stopped" else "finished")
                    save()
                } catch (error: CancellationException) {
                    current?.let { it.put("state", if (it.optString("state") == "in_flight") "uncertain" else "not_executed") }
                    report.put("state", "interrupted")
                    withContext(NonCancellable + Dispatchers.IO) { save() }
                    throw error
                } catch (error: Exception) {
                    current?.let { it.put("state", if (it.optString("state") == "in_flight") "uncertain" else "failed")
                        .put("message", bangumiUploadFailureMessage(error, "操作未完成，请重新读取对比后重试")) }
                    report.put("state", "failed")
                    save()
                }
                report.toString()
            }
        }
    }
}
