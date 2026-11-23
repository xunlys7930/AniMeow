package com.animeow.app.data.bangumi

import android.content.Context
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.cloud.BangumiAccountService
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.cloud.isBangumiAuthRejected
import com.animeow.app.data.local.BangumiPendingSyncEntity
import com.animeow.app.data.remote.BangumiService
import com.animeow.app.data.remote.JsonHttpClient
import com.animeow.app.data.remote.RemoteHttpException
import com.animeow.app.util.runCatchingCancellable
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * 排空 [BangumiSyncQueue]：后台任务（[BangumiSyncWorker]）与「立即同步」按钮共用。
 *
 * 线路：每行先按 [BangumiRouteMemory.plan] 选直连（手机 → api.bgm.tv）或服务器中转
 * （手机 → AniMeow 服务器 → 镜像 → Bangumi）。直连不通就转中转；中转在旧后端上 404
 * 表示「暂未开通」，此时改动原样保留。
 *
 * 队列红线：只有 Bangumi 明确拒绝（[BangumiSyncFailureKind.PERMANENT]）的改动才移出队列，
 * 并把作品名与原因写进结果；网络、服务故障、限流、授权过期、中转未开通一律保留。
 * 删除作品只撤回未上报的增量，绝不回写 Bangumi（方案 A）。
 */
internal class BangumiSyncRunner(private val application: AniMeowApplication) {
    private val context: Context get() = application
    private val preferences = CloudSyncPreferences(application)

    /**
     * 排空一次。开关全关或队列为空时返回 null 且不改写上次结果。
     * 同一进程内串行执行（后台任务与手动同步不会同时推同一行）。
     */
    suspend fun drain(manual: Boolean): BangumiSyncReport? = BangumiSyncGate.mutex.withLock { drainLocked(manual) }

    private suspend fun drainLocked(manual: Boolean): BangumiSyncReport? {
        val settings = preferences.snapshot()
        if (!settings.bangumiProgressSync && !settings.bangumiRatingSync) return null
        BangumiRouteMemory.restoreDirectFailedAt(settings.bangumiDirectFailedAtMs)
        val dao = application.database.libraryDao()
        val rows = dao.getBangumiPendingSyncs()
        if (rows.isEmpty()) return null

        val pusher = RowPusher(settings.bangumiDirectFailedAtMs)
        var uploaded = 0
        var dropped = 0
        val droppedItems = mutableListOf<String>()
        var stopReason: String? = null
        var retryable = false
        var lastReason: String? = null

        run drainLoop@{
            rows.groupBy { it.animeId }.forEach { (animeId, animeRows) ->
                val anime = dao.getAnime(animeId)?.takeIf { it.deletedAt == null }
                val subjectId = anime?.let { bangumiSubjectIdOf(it) }
                if (anime == null || subjectId == null) {
                    // 作品已删除或不再关联 Bangumi：只撤回未上报的本地意图，Bangumi 侧保持原样
                    animeRows.forEach { dao.deleteBangumiPendingSync(animeId, it.kind) }
                    return@forEach
                }
                val (activeRows, gated) = animeRows.partition { row ->
                    if (row.kind == BangumiSyncKind.RATING) settings.bangumiRatingSync else settings.bangumiProgressSync
                }
                // 开关已关闭的类别撤回本地意图：开关重开只上报之后的新变更
                gated.forEach { dao.deleteBangumiPendingSync(animeId, it.kind) }
                // 单集接口要求条目已在收藏中：先写收藏状态，再写单集与评分
                val active = activeRows.sortedBy { if (it.kind == BangumiSyncKind.STATUS) 0 else 1 }
                for (row in active) {
                    val failure = pusher.push(subjectId, row)
                    if (failure == null) {
                        dao.deleteBangumiPendingSyncIfPayload(animeId, row.kind, row.payloadJson)
                        uploaded += 1
                        continue
                    }
                    val classified = classifyBangumiSyncFailure(failure)
                    application.operationLog.record(
                        "Bangumi 同步未完成：${classified.reason}",
                        "账号与同步",
                        mapOf("kind" to row.kind, "类别" to classified.kind.name),
                    )
                    if (failure !is BangumiRelayUnavailableException) {
                        application.operationLog.recordError(failure, "Bangumi 同步")
                    }
                    if (classified.kind == BangumiSyncFailureKind.PERMANENT) {
                        dao.deleteBangumiPendingSyncIfPayload(animeId, row.kind, row.payloadJson)
                        dropped += 1
                        if (droppedItems.size < BangumiSyncReport.MAX_DROPPED_ITEMS) {
                            droppedItems += "《${anime.title}》${kindLabel(row.kind)}：${classified.reason}"
                        }
                        continue
                    }
                    // 保留：attempts 只作诊断计数，不再据此丢弃改动
                    dao.setBangumiPendingSyncAttempts(animeId, row.kind, row.attempts + 1)
                    lastReason = classified.reason
                    retryable = retryable || classified.retryable
                    if (classified.stopsRun) {
                        stopReason = classified.reason
                        return@drainLoop
                    }
                }
            }
        }

        val remaining = dao.bangumiPendingSyncCount()
        val report = BangumiSyncReport(
            finishedAt = Instant.now().toString(),
            manual = manual,
            uploaded = uploaded,
            remaining = remaining,
            dropped = dropped,
            reason = if (remaining > 0) stopReason ?: lastReason else null,
            droppedItems = droppedItems,
            usedRelay = pusher.usedRelay,
        )
        preferences.recordBangumiSyncReport(report)
        application.operationLog.record(
            "Bangumi 同步结束",
            "账号与同步",
            mapOf(
                "uploaded" to uploaded,
                "dropped" to dropped,
                "remaining" to remaining,
                "relay" to pusher.usedRelay,
                "manual" to manual,
            ),
        )
        lastRetryable = retryable && remaining > 0
        return report
    }

    /** 上一轮 [drain] 结束时是否还有可自动重试的改动（供后台任务决定是否退避重试）。 */
    var lastRetryable: Boolean = false
        private set

    private fun kindLabel(kind: String): String = when (kind) {
        BangumiSyncKind.EPISODES -> "进度"
        BangumiSyncKind.STATUS -> "状态"
        BangumiSyncKind.RATING -> "评分"
        else -> ""
    }

    /** 单行上报：按线路计划依次尝试；返回 null 表示成功，否则返回用于分类的失败。 */
    private inner class RowPusher(private var persistedDirectFailedAtMs: Long?) {
        private val accountService = BangumiAccountService(context = context)

        // 直连用短超时：大陆网络下 api.bgm.tv 常被重置或一直挂起，不能每行都等满默认超时
        private val direct = BangumiService(
            client = JsonHttpClient(connectTimeoutMs = DIRECT_CONNECT_TIMEOUT_MS, readTimeoutMs = DIRECT_READ_TIMEOUT_MS),
            context = context,
        )
        private val relay = BangumiRelayService(context)
        private var token: String? = null
        var usedRelay = false
            private set

        suspend fun push(subjectId: Long, row: BangumiPendingSyncEntity): Throwable? {
            var directError: Throwable? = null
            var relayMissing: BangumiRelayUnavailableException? = null
            for (route in BangumiRouteMemory.plan()) {
                when (route) {
                    BangumiSyncRoute.DIRECT -> {
                        val error = pushDirect(subjectId, row) ?: run {
                            BangumiRouteMemory.markDirectOk()
                            if (persistedDirectFailedAtMs != null) {
                                persistedDirectFailedAtMs = null
                                runCatchingCancellable { preferences.setBangumiDirectFailedAtMs(null) }
                            }
                            return null
                        }
                        directError = error
                        if (isDirectRouteDown(error)) {
                            val failedAt = System.currentTimeMillis()
                            BangumiRouteMemory.markDirectDown(failedAt)
                            persistedDirectFailedAtMs = failedAt
                            runCatchingCancellable { preferences.setBangumiDirectFailedAtMs(failedAt) }
                        }
                        // 明确答复或账号问题：中转也会得到同样结论，不再多打一次
                        if (!shouldFallbackToRelay(error)) return error
                    }
                    BangumiSyncRoute.RELAY -> {
                        val error = pushRelay(subjectId, row) ?: run {
                            BangumiRouteMemory.markRelayOk()
                            usedRelay = true
                            return null
                        }
                        if (error !is BangumiRelayUnavailableException) return error
                        BangumiRouteMemory.markRelayMissing()
                        relayMissing = error
                    }
                }
            }
            // 两条线路都没成功：优先报「中转未开通」，把直连原因挂在 suppressed 里留给诊断
            if (relayMissing != null) {
                directError?.takeIf { it !== relayMissing }?.let(relayMissing::addSuppressed)
                return relayMissing
            }
            return directError
        }

        private suspend fun pushDirect(subjectId: Long, row: BangumiPendingSyncEntity): Throwable? {
            val current = token ?: runCatchingCancellable { accountService.accessToken() }
                .getOrElse { return it }
                .also { token = it }
            val first = runCatchingCancellable { pushRowDirect(subjectId, row, current) }
            if (first.isSuccess) return null
            val firstError = first.exceptionOrNull()!!
            if (!isBangumiAuthRejected(firstError)) return firstError
            // BGM 拒绝令牌 → 向服务端换新后重试本行一次
            val fresh = runCatchingCancellable { accountService.refreshTokens().accessToken }
                .getOrElse { return it }
            token = fresh
            return runCatchingCancellable { pushRowDirect(subjectId, row, fresh) }.exceptionOrNull()
        }

        private suspend fun pushRelay(subjectId: Long, row: BangumiPendingSyncEntity): Throwable? =
            runCatchingCancellable {
                when (row.kind) {
                    BangumiSyncKind.EPISODES -> relay.writeEpisodes(subjectId, BangumiEpisodeDelta.decode(row.payloadJson))
                    BangumiSyncKind.STATUS -> decodeCollectionType(row.payloadJson)?.let { type ->
                        relay.writeCollection(subjectId, type = type, rate = null)
                    }
                    BangumiSyncKind.RATING -> decodeRate(row.payloadJson)?.let { rate ->
                        relay.writeCollection(subjectId, type = null, rate = rate)
                    }
                }
                Unit
            }.exceptionOrNull()

        private suspend fun pushRowDirect(subjectId: Long, row: BangumiPendingSyncEntity, token: String) {
            when (row.kind) {
                BangumiSyncKind.EPISODES -> {
                    val delta = BangumiEpisodeDelta.decode(row.payloadJson)
                    // 写进度用剧集 ID 而不是集数；映射不到的集数（SP 等）跳过
                    val episodeIndex = direct.fetchEpisodeIndex(subjectId)
                    var collectionEnsured = false
                    suspend fun mark(episodeId: Long, type: Int) {
                        try {
                            direct.setEpisodeCollection(token, episodeId, type)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // 单集接口要求条目已在收藏中；未收藏时先以「在看」加入收藏再重试一次
                            if (collectionEnsured || type != EPISODE_COLLECTION_DONE || !isNotCollected(error)) throw error
                            collectionEnsured = true
                            direct.modifySelfCollection(token, subjectId, type = COLLECTION_TYPE_DOING, rate = null)
                            direct.setEpisodeCollection(token, episodeId, type)
                        }
                    }
                    delta.add.forEach { ep -> episodeIndex[ep]?.let { mark(it, EPISODE_COLLECTION_DONE) } }
                    delta.remove.forEach { ep -> episodeIndex[ep]?.let { mark(it, EPISODE_COLLECTION_NONE) } }
                }
                BangumiSyncKind.STATUS ->
                    decodeCollectionType(row.payloadJson)?.let { type ->
                        direct.modifySelfCollection(token, subjectId, type = type, rate = null)
                    }
                BangumiSyncKind.RATING ->
                    decodeRate(row.payloadJson)?.let { rate ->
                        direct.modifySelfCollection(token, subjectId, type = null, rate = rate)
                    }
            }
        }

        private fun isNotCollected(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(6).any {
                it is RemoteHttpException && it.statusCode in setOf(400, 404)
            }
    }

    companion object {
        private const val DIRECT_CONNECT_TIMEOUT_MS = 2_500
        private const val DIRECT_READ_TIMEOUT_MS = 8_000
        private const val EPISODE_COLLECTION_DONE = 2
        private const val EPISODE_COLLECTION_NONE = 0
        private const val COLLECTION_TYPE_DOING = 3
    }
}
