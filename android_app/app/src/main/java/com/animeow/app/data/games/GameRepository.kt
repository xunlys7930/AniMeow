package com.animeow.app.data.games

import android.content.Context
import androidx.room.withTransaction
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.bangumi.BangumiRelayService
import com.animeow.app.data.bangumi.BangumiSyncScheduler
import com.animeow.app.data.cloud.BangumiAccountService
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.data.cloud.SecureBangumiTokenStore
import com.animeow.app.util.withDataTransferLock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import com.animeow.app.data.bangumi.BangumiSyncGate
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class GameRepository(private val context: Context, private val database: AniMeowDatabase) {
    private val dao = database.gameDao()
    val games = dao.observe()
    val pendingCount = dao.observePendingCount()

    suspend fun updateTags(ids: Set<Long>?, selected: String, remove: Boolean, rename: String? = null) = withDataTransferLock {
        require(gameTagNames(selected).isNotEmpty()) { "请先选择标签" }
        require(rename == null || rename.isNotBlank() && gameTagNames(rename).size == 1) { "请输入有效标签名称" }
        database.withTransaction {
            val now = Instant.now().toString()
            dao.all().filter { it.deletedAt == null && (ids == null || it.id in ids) }.forEach { row ->
                val next = if (rename != null) gameTagNames(row.tags).map { if (it == selected) rename else it }.distinct().joinToString("，")
                    else updatedGameTags(row.tags, selected, remove)
                require(next.length <= 1024) { "游戏标签请控制在 1024 字以内" }
                if (next != row.tags) {
                    dao.update(row.copy(tags = next, updatedAt = now))
                    dao.rebasePending(row.id, row.syncId, row.updatedAt, now)
                }
            }
        }
    }

    suspend fun enrich(id: Long, metadata: GameEntity): GameEntity? = withDataTransferLock {
        val old = dao.get(id)?.takeIf { it.deletedAt == null && it.catalogKey == metadata.catalogKey } ?: return@withDataTransferLock null
        old.copy(originalTitle = old.originalTitle.ifBlank { metadata.originalTitle }, coverUrl = old.coverUrl.ifBlank { metadata.coverUrl },
            synopsis = old.synopsis.ifBlank { metadata.synopsis }, platforms = old.platforms.ifBlank { metadata.platforms },
            developer = old.developer.ifBlank { metadata.developer }, publisher = old.publisher.ifBlank { metadata.publisher },
            genre = old.genre.ifBlank { metadata.genre }, releaseDate = old.releaseDate.ifBlank { metadata.releaseDate },
            score = old.score.takeIf { it > 0 } ?: metadata.score).also { dao.update(it) }
    }

    suspend fun save(game: GameEntity): Long = withDataTransferLock {
        require(game.title.isNotBlank() && game.title.length <= 200) { "请填写 1–200 字的游戏名称" }
        val tags = game.tags.split(',', '，').map(String::trim).filter(String::isNotBlank).distinct().joinToString("，")
        require(tags.length <= 1024) { "游戏标签请控制在 1024 字以内" }
        require(game.vndbId == null || (game.bangumiId == null && game.vndbId.matches(Regex("v[1-9]\\d{0,8}")))) { "游戏来源编号无效" }
        require(game.status in gameStatuses && game.playMinutes in 0..60_000_000L && game.rating in 0..10) { "记录数值无效" }
        val saved = game.copy(title = game.title.trim(), tags = tags, updatedAt = Instant.now().toString())
        val id = database.withTransaction {
            val existing = game.bangumiId?.let { dao.byBangumiId(it) } ?: game.vndbId?.let { dao.byVndbId(it) }
                ?: if (game.id == 0L && (game.bangumiId != null || game.vndbId != null))
                    dao.all().filter { sameCatalogGame(it, game) }.singleOrNull() else null
            if (game.id == 0L && existing != null && existing.deletedAt == null) return@withTransaction existing.id
            val row = if (game.id == 0L && existing != null) saved.copy(id = existing.id, syncId = existing.syncId, deletedAt = null) else saved
            val previous = if (row.id > 0) dao.get(row.id) else null
            val id = if (row.id == 0L) dao.insert(row) else { dao.update(row); row.id }
            queue(row.copy(id = id), previous?.takeIf { it.deletedAt == null })
            id
        }
        if (game.bangumiId != null) BangumiSyncScheduler.schedule(context)
        id
    }

    suspend fun delete(game: GameEntity) = withDataTransferLock {
        dao.update(game.copy(deletedAt = Instant.now().toString(), updatedAt = Instant.now().toString()))
    }

    suspend fun updateSelection(ids: Set<Long>, status: String? = null, delete: Boolean = false): Int = withDataTransferLock {
        require(ids.isNotEmpty() && ids.all { it > 0 }) { "请先选择游戏" }
        require((delete && status == null) || (!delete && status in gameStatuses)) { "请选择有效的批量操作" }
        val count = database.withTransaction {
            val now = Instant.now().toString()
            ids.mapNotNull { dao.get(it)?.takeIf { row -> row.deletedAt == null } }.count { previous ->
                if (!delete && previous.status == status) false else {
                    val next = if (delete) previous.copy(deletedAt = now, updatedAt = now)
                        else previous.copy(status = status!!, updatedAt = now)
                    dao.update(next)
                    if (!delete) queue(next, previous)
                    true
                }
            }
        }
        if (!delete && count > 0) BangumiSyncScheduler.schedule(context)
        count
    }

    private suspend fun queue(game: GameEntity, previous: GameEntity?) {
        if (game.bangumiId == null || game.deletedAt != null) return
        val settings = CloudSyncPreferences(context).snapshot()
        if (!settings.bangumiProgressSync && !settings.bangumiRatingSync) return
        val session = SecureCloudSessionStore(context).load() ?: return
        val binding = SecureBangumiTokenStore(context).load()?.takeIf { it.userId == session.userId } ?: return
        val pending = dao.pending(session.userId, binding.bangumiId).firstOrNull { it.gameId == game.id && it.gameSyncId == game.syncId }
        val (type, rate) = gameSyncChanges(previous, game, pending, settings.bangumiProgressSync, settings.bangumiRatingSync)
        if (type == null && rate == null) return
        dao.enqueue(GamePendingSync(game.id, game.syncId, game.bangumiId!!, session.userId, binding.bangumiId,
            type, rate, game.updatedAt))
    }

    /** Explicit upload is also available when automatic reporting is disabled. */
    suspend fun uploadCollections(): Int {
        val session = SecureCloudSessionStore(context).load() ?: error("请先登录云账号")
        val profile = BangumiAccountService(context).fetchProfile().bangumi ?: error("请先在同步设置中连接 Bangumi")
        check(SecureCloudSessionStore(context).load()?.userId == session.userId) { "账号已变化，请重新同步" }
        withDataTransferLock {
            dao.all().filter { it.bangumiId != null }.forEach { game ->
                dao.enqueue(GamePendingSync(game.id, game.syncId, game.bangumiId!!, session.userId, profile.id, gameCollectionType(game.status), game.rating, game.updatedAt))
            }
        }
        return drain(force = true, expectedOwner = session.userId, expectedBinding = profile.id)
    }

    suspend fun drain(force: Boolean = false, expectedOwner: Long? = null, expectedBinding: Long? = null): Int = BangumiSyncGate.mutex.withLock {
        val session = SecureCloudSessionStore(context).load() ?: return@withLock 0
        check(expectedOwner == null || expectedOwner == session.userId) { "账号已变化，请重新同步" }
        if (dao.pendingCount() == 0) return@withLock 0
        val settings = CloudSyncPreferences(context).snapshot()
        if (!force && !settings.bangumiProgressSync && !settings.bangumiRatingSync) return@withLock 0
        val binding = BangumiAccountService(context).fetchProfile().bangumi ?: return@withLock 0
        check(expectedBinding == null || expectedBinding == binding.id) { "Bangumi 账号已变化，请重新同步" }
        val relay = BangumiRelayService(context, expectedUserId = session.userId, expectedBangumiId = binding.id)
        var count = 0
        for (pending in dao.pending(session.userId, binding.id)) {
            val game = dao.get(pending.gameId)
            if (game?.deletedAt == null && game?.bangumiId == pending.subjectId && game?.syncId == pending.gameSyncId && game?.updatedAt == pending.updatedAt) {
                check(SecureCloudSessionStore(context).load()?.userId == session.userId) { "账号已变化，请重新同步" }
                val type = pending.collectionType.takeIf { force || settings.bangumiProgressSync }
                val rate = pending.rate.takeIf { force || settings.bangumiRatingSync }
                if (type == null && rate == null) continue
                relay.writeCollection(game.bangumiId, type, rate)
                count++
            }
            dao.acknowledge(pending.gameId, session.userId, binding.id, pending.updatedAt)
        }
        count
    }

    /** Import only collection fields; never replace local play time, dates or review. */
    suspend fun importCollections(): Int = BangumiSyncGate.mutex.withLock {
        val session = SecureCloudSessionStore(context).load() ?: error("请先登录云账号")
        val binding = BangumiAccountService(context).fetchProfile().bangumi ?: error("请先连接 Bangumi")
        val relay = BangumiRelayService(context, expectedUserId = session.userId, expectedBangumiId = binding.id)
        val rows = mutableListOf<JSONObject>()
        var offset = 0
        do {
            val page = JSONObject(relay.fetchMyGameCollectionsPage(offset))
            val data = page.getJSONArray("data")
            for (i in 0 until data.length()) rows += data.getJSONObject(i)
            offset += data.length()
            check(offset <= 10000) { "游戏收藏较多，请分批整理后重试" }
        } while (data.length() > 0 && offset < page.optInt("total"))
        withDataTransferLock {
            check(SecureCloudSessionStore(context).load()?.userId == session.userId) { "账号已变化，请重新导入" }
            val pending = dao.pending(session.userId, binding.id).map { it.gameId }.toSet()
            database.withTransaction {
                var count = 0
                for (row in rows) {
                    val subject = row.optJSONObject("subject") ?: continue
                    if (subject.optInt("type") != 4) continue
                    val id = row.optLong("subject_id", subject.optLong("id"))
                    if (id <= 0) continue
                    val old = dao.byBangumiId(id)
                    if (old?.deletedAt != null || old?.id in pending) continue
                    val title = subject.optString("name_cn").ifBlank { subject.optString("name") }
                    if (title.isBlank()) continue
                    val cover = subject.optJSONObject("images")?.optString("large").orEmpty()
                        .replace("https://lain.bgm.tv/", "https://img-bgm.xunlys.top/")
                        .replace("https://lain.bangumi.tv/", "https://img-bgm.xunlys.top/")
                    val game = (old ?: GameEntity(bangumiId = id, title = title, originalTitle = subject.optString("name"), coverUrl = cover,
                        releaseDate = subject.optString("date"))).copy(status = gameStatusFromCollection(row.optInt("type")),
                        rating = row.optInt("rate").coerceIn(0, 10), updatedAt = Instant.now().toString())
                    if (old == null) dao.insert(game) else dao.update(game)
                    count++
                }
                count
            }
        }
    }
}
