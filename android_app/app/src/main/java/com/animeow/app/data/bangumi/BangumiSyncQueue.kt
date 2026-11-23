package com.animeow.app.data.bangumi

import android.content.Context
import com.animeow.app.data.BANGUMI_SOURCE
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.BangumiPendingSyncEntity
import com.animeow.app.util.runCatchingCancellable
import java.time.Instant
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

internal object BangumiSyncKind {
    const val EPISODES = "episodes"
    const val STATUS = "status"
    const val RATING = "rating"
}

/** episodes 行的增量载荷：把 [add] 中的集数标为「看过」，把 [remove] 中的集数取消。 */
internal data class BangumiEpisodeDelta(
    val add: Set<Int> = emptySet(),
    val remove: Set<Int> = emptySet(),
) {
    val isEmpty: Boolean
        get() = add.isEmpty() && remove.isEmpty()

    /** 两笔增量先后应用时的合并：后写在单集上胜出；变回基准状态（该集首次变更前）的集抵消为无操作。 */
    fun mergeWith(newer: BangumiEpisodeDelta): BangumiEpisodeDelta = mergeEpisodeDeltas(this, newer)

    fun encode(): String = JSONObject()
        .put("add", JSONArray(add.sorted()))
        .put("remove", JSONArray(remove.sorted()))
        .toString()

    companion object {
        fun decode(json: String): BangumiEpisodeDelta {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return BangumiEpisodeDelta()
            return BangumiEpisodeDelta(
                add = root.optJSONArray("add").toIntSet(),
                remove = root.optJSONArray("remove").toIntSet(),
            )
        }
    }
}

/**
 * 合并两笔增量：同一集后写覆盖先写；变回基准状态的集整条抵消。
 * 每集首次出现的方向记住了基准（首见 add = 基准未看、首见 remove = 基准已看），
 * 所以「看过又撤销」既不会留下反向指令，也不会误伤用户在 Bangumi 网页端的手动状态。
 */
internal fun mergeEpisodeDeltas(older: BangumiEpisodeDelta, newer: BangumiEpisodeDelta): BangumiEpisodeDelta =
    BangumiEpisodeDelta(
        add = (older.add - newer.remove) + (newer.add - older.remove),
        remove = (older.remove - newer.add) + (newer.remove - older.add),
    )

/** 本地状态 → Bangumi 收藏类型（1 想看 / 2 看过 / 3 在看 / 4 搁置 / 5 抛弃）；识别不了返回 null，跳过同步。 */
internal fun bangumiCollectionType(status: String): Int? = when (status.trim()) {
    "想看" -> 1
    "看完", "已完成", "完成" -> 2
    "在看" -> 3
    "搁置" -> 4
    "弃坑" -> 5
    else -> null
}

/** 本地 0-100 十分位评分 → Bangumi 0-10 整数；null（清空）→ 0，即删除评分。 */
internal fun bangumiRate(rating: Int?): Int =
    rating?.let { Math.round(it / 10.0).toInt() }?.coerceIn(0, 10) ?: 0

/** 只有挂了 Bangumi 条目的作品才参与同步（externalSource = "bangumi", externalId = subjectId）。 */
internal fun bangumiSubjectIdOf(anime: AnimeEntity): Long? =
    anime.externalId?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.takeIf { anime.externalSource.equals(BANGUMI_SOURCE, ignoreCase = true) }

internal fun encodeCollectionType(type: Int): String = JSONObject().put("type", type).toString()

internal fun decodeCollectionType(json: String): Int? =
    runCatching { JSONObject(json) }.getOrNull()?.takeIf { it.has("type") }?.optInt("type")

internal fun encodeRate(rate: Int): String = JSONObject().put("rate", rate).toString()

internal fun decodeRate(json: String): Int? =
    runCatching { JSONObject(json) }.getOrNull()?.takeIf { it.has("rate") }?.optInt("rate")

private fun JSONArray?.toIntSet(): Set<Int> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            val value = optInt(index, Int.MIN_VALUE)
            if (value != Int.MIN_VALUE && value > 0) add(value)
        }
    }
}

/**
 * Bangumi 双向同步 P0 的本地待同步队列（Local-First）。
 *
 * 资料变更先落本地队列（合并同作品增量），联网后由 [BangumiSyncWorker] 批量上报。
 * 入队方法只做 DAO 与开关读写，返回 true 表示确实入队；调用方在事务提交后
 * 调用 [schedulePending] 触发一次防抖排空。删除作品绝不回写 Bangumi（方案 A）。
 */
class BangumiSyncQueue(
    private val database: AniMeowDatabase,
    private val context: Context,
) {
    private val dao = database.libraryDao()

    /** 进度（含状态）开关：incrementProgress / setProgress / updateStatuses / cycleStatus 使用。 */
    suspend fun progressSyncEnabled(): Boolean = settings().bangumiProgressSync

    /** 评分开关：saveAnimeRecord 使用。 */
    suspend fun ratingSyncEnabled(): Boolean = settings().bangumiRatingSync

    suspend fun enqueueEpisodes(animeId: Long, subjectId: Long, add: Set<Int>, remove: Set<Int>): Boolean {
        if (!progressSyncEnabled()) return false
        if (add.isEmpty() && remove.isEmpty()) return false
        // 同一集既加又减时以「取消」为准，避免矛盾增量上网
        val incoming = BangumiEpisodeDelta(add = add - remove, remove = remove)
        val existing = dao.getBangumiPendingSync(animeId, BangumiSyncKind.EPISODES)
        val merged = existing
            ?.let { BangumiEpisodeDelta.decode(it.payloadJson).mergeWith(incoming) }
            ?: incoming
        if (merged.isEmpty) {
            // 增量相互抵消：什么也不用上报
            existing?.let { dao.deleteBangumiPendingSync(animeId, BangumiSyncKind.EPISODES) }
            return false
        }
        dao.upsertBangumiPendingSync(
            BangumiPendingSyncEntity(
                animeId = animeId,
                subjectId = subjectId,
                kind = BangumiSyncKind.EPISODES,
                payloadJson = merged.encode(),
                // 首次意图的时间决定上报顺序；新变更把重试预算重新计满
                createdAt = existing?.createdAt ?: Instant.now().toString(),
                attempts = 0,
            ),
        )
        return true
    }

    suspend fun enqueueStatus(animeId: Long, subjectId: Long, type: Int): Boolean {
        if (!progressSyncEnabled()) return false
        val existing = dao.getBangumiPendingSync(animeId, BangumiSyncKind.STATUS)
        dao.upsertBangumiPendingSync(
            BangumiPendingSyncEntity(
                animeId = animeId,
                subjectId = subjectId,
                kind = BangumiSyncKind.STATUS,
                payloadJson = encodeCollectionType(type),
                createdAt = existing?.createdAt ?: Instant.now().toString(),
                attempts = 0,
            ),
        )
        return true
    }

    suspend fun enqueueRating(animeId: Long, subjectId: Long, rate: Int): Boolean {
        if (!ratingSyncEnabled()) return false
        val existing = dao.getBangumiPendingSync(animeId, BangumiSyncKind.RATING)
        dao.upsertBangumiPendingSync(
            BangumiPendingSyncEntity(
                animeId = animeId,
                subjectId = subjectId,
                kind = BangumiSyncKind.RATING,
                payloadJson = encodeRate(rate),
                createdAt = existing?.createdAt ?: Instant.now().toString(),
                attempts = 0,
            ),
        )
        return true
    }

    /** 事务提交后调用：3 秒防抖排空队列（同一时刻多次变更合并为一次上报）。调度失败绝不影响本地资料写入。 */
    suspend fun schedulePending() {
        runCatchingCancellable { BangumiSyncScheduler.schedule(context) }
    }

    private suspend fun settings() = CloudSyncPreferences(context).settings.first()
}
