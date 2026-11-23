package com.animeow.app.data.bangumi

import com.animeow.app.data.cloud.BangumiApiException
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.cloud.isBangumiAuthRejected
import com.animeow.app.data.remote.RemoteHttpException
import com.animeow.app.data.remote.collectRemoteFailures
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bangumi 同步的纯逻辑：失败分类、线路选择、结果汇总与展示文案。
 * 不碰数据库与网络，便于 JVM 单测（BangumiSyncPolicyTest）。
 */

/** 一次上报失败对队列的影响。只有 [PERMANENT] 会把改动移出队列。 */
internal enum class BangumiSyncFailureKind {
    /** 未登录 AniMeow / 会话失效：保留全部改动，等用户登录。 */
    SESSION_BLOCKED,

    /** 未绑定或 Bangumi 授权过期：保留全部改动，等用户重新连接。 */
    AUTH_BLOCKED,

    /** 服务器还没有中转接口（旧后端 404）且直连也不通：保留全部改动。 */
    RELAY_MISSING,

    /** 请求过于频繁：保留，稍后重试。 */
    RATE_LIMITED,

    /** 网络或服务暂时故障：保留，稍后重试。 */
    TRANSIENT,

    /** Bangumi 明确拒绝（条目不存在、参数无效等）：重试也不会成功，移出队列并告知用户。 */
    PERMANENT,
}

internal data class BangumiSyncFailure(
    val kind: BangumiSyncFailureKind,
    val reason: String,
    /** 网络层失败：同一轮后续行大概率同样失败，停下来等下次。 */
    val networkLevel: Boolean = false,
) {
    /** 这一行失败后是否停止本轮其余行（它们会原样保留）。 */
    val stopsRun: Boolean
        get() = networkLevel || kind != BangumiSyncFailureKind.TRANSIENT && kind != BangumiSyncFailureKind.PERMANENT

    /** 是否值得由 WorkManager 退避后自动再试（授权 / 会话 / 中转未开通需要人工或服务器变化）。 */
    val retryable: Boolean
        get() = kind == BangumiSyncFailureKind.TRANSIENT || kind == BangumiSyncFailureKind.RATE_LIMITED
}

private val DEFINITIVE_BANGUMI_STATUSES = setOf(400, 404, 422)

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(8)

internal fun isNetworkFailure(error: Throwable): Boolean = error.causes().any { it is IOException }

/**
 * 直连结果是否为 Bangumi 的明确业务答复（条目不存在 / 参数无效）。
 * 只看最外层链上的第一个 HTTP 状态：`preferred()` 在官方明确回 400/404 时不会再试镜像，
 * 所以这是官方（或用户自配代理）的真实答复；镜像对这些路由只会回 403。
 */
internal fun isDefinitiveBangumiAnswer(error: Throwable): Boolean {
    val http = error.causes().filterIsInstance<RemoteHttpException>().firstOrNull() ?: return false
    return http.statusCode in DEFINITIVE_BANGUMI_STATUSES
}

private fun isAccountBlocker(error: Throwable): Boolean = error.causes().any {
    it is CloudSessionExpiredException ||
        it.message == "请先登录云账号" ||
        (it is BangumiApiException && it.code in setOf("bangumi_not_bound", "bangumi_auth_expired"))
}

/**
 * 直连失败后是否改走服务器中转：网络故障、镜像 403/405、5xx、令牌被拒（服务器会自己换新）都转；
 * Bangumi 的明确业务答复与账号问题（中转同样会失败）不转。
 */
internal fun shouldFallbackToRelay(directError: Throwable): Boolean {
    if (isAccountBlocker(directError)) return false
    if (isDefinitiveBangumiAnswer(directError)) return false
    return true
}

/** 直连失败是否说明「这条线路现在不通」，需要记下来让后续请求先走中转。 */
internal fun isDirectRouteDown(directError: Throwable): Boolean =
    shouldFallbackToRelay(directError) && !isBangumiAuthRejected(directError)

internal fun classifyBangumiSyncFailure(error: Throwable): BangumiSyncFailure {
    val chain = error.causes().toList()
    chain.firstOrNull { it is CloudSessionExpiredException }?.let {
        return BangumiSyncFailure(BangumiSyncFailureKind.SESSION_BLOCKED, "AniMeow 登录状态已失效，重新登录后会继续同步")
    }
    if (chain.any { it.message == "请先登录云账号" }) {
        return BangumiSyncFailure(BangumiSyncFailureKind.SESSION_BLOCKED, "登录 AniMeow 账号并绑定 Bangumi 后才能同步")
    }
    chain.filterIsInstance<BangumiApiException>().firstOrNull()?.let { api ->
        when (api.code) {
            "bangumi_not_bound" -> return BangumiSyncFailure(
                BangumiSyncFailureKind.AUTH_BLOCKED,
                "尚未绑定 Bangumi 账号，绑定后会继续同步",
            )
            "bangumi_auth_expired" -> return BangumiSyncFailure(
                BangumiSyncFailureKind.AUTH_BLOCKED,
                "Bangumi 授权已过期，在「账号与同步」重新连接后会继续同步",
            )
        }
    }
    if (chain.any { it is BangumiRelayUnavailableException }) {
        return BangumiSyncFailure(
            BangumiSyncFailureKind.RELAY_MISSING,
            "直连 Bangumi 失败，服务器暂未开通 Bangumi 中转；改动已保留在本机",
        )
    }
    chain.filterIsInstance<BangumiRelayException>().firstOrNull()?.let { relay ->
        return when {
            relay.httpStatus == 429 -> BangumiSyncFailure(BangumiSyncFailureKind.RATE_LIMITED, "请求过于频繁，稍后会自动重试")
            relay.httpStatus == 400 -> BangumiSyncFailure(
                BangumiSyncFailureKind.PERMANENT,
                if (relay.upstreamStatus == 404) "Bangumi 上没有这个条目，或该条目尚未收藏" else "Bangumi 拒绝了这次修改",
            )
            else -> BangumiSyncFailure(BangumiSyncFailureKind.TRANSIENT, "服务器或 Bangumi 暂时不可用，稍后会自动重试")
        }
    }
    if (isNetworkFailure(error)) {
        return BangumiSyncFailure(
            BangumiSyncFailureKind.TRANSIENT,
            "网络连接不稳定，改动已保留，稍后会自动重试",
            networkLevel = true,
        )
    }
    val statuses = collectRemoteFailures(error).map { it.statusCode }
    return when {
        statuses.contains(429) -> BangumiSyncFailure(BangumiSyncFailureKind.RATE_LIMITED, "请求过于频繁，稍后会自动重试")
        isDefinitiveBangumiAnswer(error) -> BangumiSyncFailure(
            BangumiSyncFailureKind.PERMANENT,
            if (statuses.firstOrNull() == 404) "Bangumi 上没有这个条目，或该条目尚未收藏" else "Bangumi 拒绝了这次修改",
        )
        isBangumiAuthRejected(error) -> BangumiSyncFailure(
            BangumiSyncFailureKind.TRANSIENT,
            "Bangumi 暂时拒绝了授权，稍后会自动重试；持续失败请重新连接",
        )
        else -> BangumiSyncFailure(BangumiSyncFailureKind.TRANSIENT, "Bangumi 暂时无法访问，稍后会自动重试")
    }
}

// ---------------------------------------------------------------- 线路选择

internal enum class BangumiSyncRoute { DIRECT, RELAY }

/** 直连 / 中转最近一次失败的时间，只在进程内记忆（WorkManager 冷启动时会重新探一次直连）。 */
internal object BangumiRouteMemory {
    const val TTL_MS = 10L * 60 * 1000

    @Volatile
    var directFailedAtMs: Long? = null
        private set

    @Volatile
    var relayMissingAtMs: Long? = null
        private set

    fun markDirectDown(nowMs: Long = System.currentTimeMillis()) {
        directFailedAtMs = nowMs
    }

    fun markDirectOk() {
        directFailedAtMs = null
    }

    fun restoreDirectFailedAt(atMs: Long?) {
        if (directFailedAtMs == null && atMs != null) directFailedAtMs = atMs
    }

    fun markRelayMissing(nowMs: Long = System.currentTimeMillis()) {
        relayMissingAtMs = nowMs
    }

    fun markRelayOk() {
        relayMissingAtMs = null
    }

    fun plan(nowMs: Long = System.currentTimeMillis()): List<BangumiSyncRoute> =
        planBangumiRoutes(nowMs, directFailedAtMs, relayMissingAtMs)
}

/**
 * 默认先直连（能访问 api.bgm.tv 的用户最快）；直连 10 分钟内失败过则先走中转，
 * 省掉一次必然的超时。中转刚确认未开通时仍然先直连。两条线路总是都会尝试。
 */
internal fun planBangumiRoutes(nowMs: Long, directFailedAtMs: Long?, relayMissingAtMs: Long?): List<BangumiSyncRoute> {
    val directRecentlyDown = directFailedAtMs != null && nowMs - directFailedAtMs in 0 until BangumiRouteMemory.TTL_MS
    val relayRecentlyMissing = relayMissingAtMs != null && nowMs - relayMissingAtMs in 0 until BangumiRouteMemory.TTL_MS
    return if (directRecentlyDown && !relayRecentlyMissing) {
        listOf(BangumiSyncRoute.RELAY, BangumiSyncRoute.DIRECT)
    } else {
        listOf(BangumiSyncRoute.DIRECT, BangumiSyncRoute.RELAY)
    }
}

// ---------------------------------------------------------------- 结果汇总

/** 最近一次（有待上报改动的）同步结果，持久化在 CloudSyncPreferences。 */
data class BangumiSyncReport(
    val finishedAt: String,
    val manual: Boolean,
    val uploaded: Int,
    val remaining: Int,
    val dropped: Int,
    /** 本轮保留改动的原因（remaining > 0 时）。 */
    val reason: String? = null,
    /** 被移出队列的改动：「作品名：原因」，最多几条。 */
    val droppedItems: List<String> = emptyList(),
    /** 本轮是否有行经服务器中转上报成功。 */
    val usedRelay: Boolean = false,
) {
    fun encode(): String = JSONObject()
        .put("finishedAt", finishedAt)
        .put("manual", manual)
        .put("uploaded", uploaded)
        .put("remaining", remaining)
        .put("dropped", dropped)
        .put("reason", reason ?: JSONObject.NULL)
        .put("droppedItems", JSONArray(droppedItems))
        .put("usedRelay", usedRelay)
        .toString()

    companion object {
        const val MAX_DROPPED_ITEMS = 5

        fun decode(json: String?): BangumiSyncReport? {
            if (json.isNullOrBlank()) return null
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val finishedAt = root.optString("finishedAt").takeIf(String::isNotBlank) ?: return null
            val items = root.optJSONArray("droppedItems")
            return BangumiSyncReport(
                finishedAt = finishedAt,
                manual = root.optBoolean("manual"),
                uploaded = root.optInt("uploaded").coerceAtLeast(0),
                remaining = root.optInt("remaining").coerceAtLeast(0),
                dropped = root.optInt("dropped").coerceAtLeast(0),
                reason = root.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                droppedItems = buildList {
                    if (items != null) {
                        for (index in 0 until minOf(items.length(), MAX_DROPPED_ITEMS)) {
                            items.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                },
                usedRelay = root.optBoolean("usedRelay"),
            )
        }
    }
}

/** 手动「立即同步」完成后的提示（Snackbar）。 */
internal fun bangumiSyncResultMessage(report: BangumiSyncReport): String {
    val parts = mutableListOf<String>()
    if (report.uploaded > 0) parts += "已上报 ${report.uploaded} 项改动到 Bangumi"
    if (report.dropped > 0) {
        parts += "${report.dropped} 项被 Bangumi 拒绝（已跳过）" +
            (report.droppedItems.firstOrNull()?.let { "：$it" } ?: "")
    }
    if (report.remaining > 0) {
        val kept = "${report.remaining} 项改动仍在等待上报"
        parts += report.reason?.let { "$kept：$it" } ?: kept
    }
    return parts.joinToString("；").ifEmpty { "没有待上报的改动" }
}

/** 同步卡片上的队列状态。「所有改动均已上报」只在队列为空且上次没有被拒改动时出现。 */
internal fun bangumiQueueStatusText(pendingCount: Int, report: BangumiSyncReport?): String = when {
    pendingCount > 0 && report?.reason != null -> "同步未完成 · $pendingCount 项等待重试"
    pendingCount > 0 -> "待上报 $pendingCount 项改动"
    report != null && report.dropped > 0 -> "当前无待同步项；上次有 ${report.dropped} 项被 Bangumi 拒绝，未能同步"
    report == null -> "暂无待同步改动"
    else -> "所有改动均已上报"
}

/** 同步卡片上的「上次同步」一行；没有记录时返回 null。 */
internal fun bangumiLastRunText(report: BangumiSyncReport?, zone: ZoneId = ZoneId.systemDefault()): String? {
    report ?: return null
    val time = runCatching {
        DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(zone).format(Instant.parse(report.finishedAt))
    }.getOrNull() ?: return null
    val detail = buildList {
        add("上报 ${report.uploaded} 项")
        if (report.dropped > 0) add("被拒 ${report.dropped} 项")
        add("剩余 ${report.remaining} 项")
    }.joinToString(" · ")
    val via = if (report.usedRelay) "（经服务器中转）" else ""
    return "上次同步：$time$via · $detail"
}
