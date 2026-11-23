package com.animeow.app.data.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.ui.community.CommunityStatisticMetric
import com.animeow.app.ui.community.DefaultCommunityStatisticKeys
import com.animeow.app.ui.community.statisticsPayload
import com.animeow.app.ui.statistics.StatisticsUiState
import kotlin.math.abs
import kotlin.math.round

internal fun resolvedCommunityStatisticKeys(profile: CommunityProfile): Set<String> =
    profile.statistics.keys
        .filter { key -> CommunityStatisticMetric.entries.any { it.key == key } }
        .toSet()
        .ifEmpty { DefaultCommunityStatisticKeys }

internal fun normalizeCommunityStatisticPayload(
    statistics: StatisticsUiState,
    selectedKeys: Set<String>,
): Map<String, Double> = statisticsPayload(statistics, selectedKeys).mapValues { (key, value) ->
    if (key == CommunityStatisticMetric.AVERAGE_RATING.key) {
        round(value.coerceIn(0.0, 10.0) * 10.0) / 10.0
    } else {
        round(value.coerceAtLeast(0.0))
    }
}

internal fun shouldAutoSyncCommunityStatistics(
    profile: CommunityProfile,
    normalizedLocal: Map<String, Double>,
): Boolean {
    if (!profile.showStatisticsCard) return false
    if (profile.restrictions.any { it.capability == "profile" }) return false
    if (normalizedLocal.isEmpty()) return false
    val knownRemote = profile.statistics.filterKeys { key ->
        CommunityStatisticMetric.entries.any { it.key == key }
    }
    // Avoid overwriting populated cloud stats with all-zero stats on an un-restored device.
    if (normalizedLocal.values.all { it <= 0.0 } && knownRemote.values.any { it > 0.0 }) return false
    if (knownRemote.size != normalizedLocal.size) return true
    return normalizedLocal.any { (key, value) ->
        val remoteValue = knownRemote[key] ?: return@any true
        abs(remoteValue - value) > 1e-4
    }
}

internal data class SyncedStatisticsSignature(
    val userId: Long,
    val sessionToken: String,
    val statistics: Map<String, Double>,
)

internal class CommunityStatisticsAutoSyncTracker {
    @Volatile
    private var lastSynced: SyncedStatisticsSignature? = null

    fun needsSync(session: CloudSession, profile: CommunityProfile, payload: Map<String, Double>): Boolean {
        val last = lastSynced
        if (last != null &&
            last.userId == session.userId &&
            last.sessionToken == session.token &&
            sameStatisticMaps(last.statistics, payload)
        ) {
            return false
        }
        return shouldAutoSyncCommunityStatistics(profile, payload)
    }

    fun markSynced(session: CloudSession, payload: Map<String, Double>) {
        lastSynced = SyncedStatisticsSignature(session.userId, session.token, payload)
    }

    fun reset() {
        lastSynced = null
    }
}

private fun sameStatisticMaps(a: Map<String, Double>, b: Map<String, Double>): Boolean {
    if (a.size != b.size) return false
    return a.all { (key, value) ->
        val other = b[key] ?: return@all false
        abs(value - other) <= 1e-4
    }
}
