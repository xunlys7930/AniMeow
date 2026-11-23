package com.animeow.app.data.local

import org.json.JSONArray
import org.json.JSONObject

val SERIES_LINKED_MEDIA_KINDS = setOf("GAME", "AUDIO")
private const val MAX_WORK_SYNC_ID_LENGTH = 128

data class SeriesLinkedMediaItem(
    val kind: String,
    val workSyncId: String,
    val role: String? = null,
) {
    val key: String get() = "$kind:$workSyncId"
}

fun parseSeriesLinkedMedia(rawJson: String?): List<SeriesLinkedMediaItem> {
    val text = rawJson?.trim()?.takeIf(String::isNotEmpty) ?: return emptyList()
    val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    val byKey = linkedMapOf<String, SeriesLinkedMediaItem>()
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index) ?: continue
        val kind = obj.optString("kind").trim().uppercase().takeIf { it in SERIES_LINKED_MEDIA_KINDS } ?: continue
        val syncId = obj.optString("workSyncId").trim().takeIf { it.isNotEmpty() && it != "null" && it.length <= MAX_WORK_SYNC_ID_LENGTH } ?: continue
        val role = obj.optString("role").trim().takeIf { it in SERIES_ROLES }
        val item = SeriesLinkedMediaItem(kind = kind, workSyncId = syncId, role = role)
        val existing = byKey[item.key]
        if (existing == null) {
            byKey[item.key] = item
        } else if (existing.role == null && role != null) {
            byKey[item.key] = existing.copy(role = role)
        }
    }
    return byKey.values.toList()
}

fun encodeSeriesLinkedMedia(items: List<SeriesLinkedMediaItem>): String? {
    val normalized = linkedMapOf<String, SeriesLinkedMediaItem>()
    items.forEach { raw ->
        val kind = raw.kind.trim().uppercase().takeIf { it in SERIES_LINKED_MEDIA_KINDS } ?: return@forEach
        val syncId = raw.workSyncId.trim().takeIf { it.isNotEmpty() && it.length <= MAX_WORK_SYNC_ID_LENGTH } ?: return@forEach
        val role = raw.role?.trim()?.takeIf { it in SERIES_ROLES }
        val item = SeriesLinkedMediaItem(kind = kind, workSyncId = syncId, role = role)
        val existing = normalized[item.key]
        if (existing == null) {
            normalized[item.key] = item
        } else if (existing.role == null && role != null) {
            normalized[item.key] = existing.copy(role = role)
        }
    }
    if (normalized.isEmpty()) return null
    val array = JSONArray()
    normalized.values.forEach { item ->
        val obj = JSONObject().put("kind", item.kind).put("workSyncId", item.workSyncId)
        if (item.role != null) obj.put("role", item.role)
        array.put(obj)
    }
    return array.toString()
}

fun normalizeSeriesLinkedMediaJson(
    rawJson: String?,
    gameSyncRemap: Map<String, String?> = emptyMap(),
): String? {
    val parsed = parseSeriesLinkedMedia(rawJson).map { item ->
        val remapped = if (item.kind == "GAME") gameSyncRemap[item.workSyncId]?.trim()?.takeIf(String::isNotEmpty) else null
        if (remapped != null) item.copy(workSyncId = remapped) else item
    }
    return encodeSeriesLinkedMedia(parsed)
}

fun mergeSeriesLinkedMediaJson(
    localJson: String?,
    incomingJson: String?,
    gameSyncRemap: Map<String, String?> = emptyMap(),
): String? {
    val localItems = parseSeriesLinkedMedia(localJson)
    val incomingItems = parseSeriesLinkedMedia(incomingJson).map { item ->
        val remapped = if (item.kind == "GAME") gameSyncRemap[item.workSyncId]?.trim()?.takeIf(String::isNotEmpty) else null
        if (remapped != null) item.copy(workSyncId = remapped) else item
    }
    return encodeSeriesLinkedMedia(localItems + incomingItems)
}

fun normalizeAnimeMemo(rawMemo: String?): String? =
    rawMemo?.trim()?.takeIf(String::isNotEmpty)?.take(10_000)

data class SeriesLinkedCrossMediaEntry(
    val kind: String,
    val workSyncId: String,
    val workId: Long,
    val title: String,
    val coverUrl: String,
    val status: String,
    val role: String? = null,
) {
    val key: String get() = "$kind:$workSyncId"
    val kindLabel: String get() = if (kind == "GAME") "游戏" else "音声"
}

fun resolveSeriesLinkedCrossMedia(
    rawJson: String?,
    games: List<com.animeow.app.data.games.GameEntity>,
    audioWorks: List<com.animeow.app.data.audio.AudioWork>,
): List<SeriesLinkedCrossMediaEntry> {
    val items = parseSeriesLinkedMedia(rawJson)
    if (items.isEmpty()) return emptyList()
    val gameBySync = games.associateBy { it.syncId }
    val audioBySync = audioWorks.associateBy { it.syncId }
    return items.mapNotNull { item ->
        when (item.kind) {
            "GAME" -> gameBySync[item.workSyncId]?.let { game ->
                SeriesLinkedCrossMediaEntry(
                    kind = "GAME",
                    workSyncId = game.syncId,
                    workId = game.id,
                    title = game.title,
                    coverUrl = game.coverUrl,
                    status = game.status,
                    role = item.role,
                )
            }
            "AUDIO" -> audioBySync[item.workSyncId]?.let { audio ->
                SeriesLinkedCrossMediaEntry(
                    kind = "AUDIO",
                    workSyncId = audio.syncId,
                    workId = audio.id,
                    title = audio.title,
                    coverUrl = audio.coverUrl,
                    status = audio.status,
                    role = item.role,
                )
            }
            else -> null
        }
    }
}

fun seriesLinkedToWork(
    seriesList: List<SeriesEntity>,
    kind: String,
    workSyncId: String,
): List<SeriesEntity> {
    if (workSyncId.isBlank()) return emptyList()
    val normalizedKind = kind.trim().uppercase()
    return seriesList.filter { series ->
        parseSeriesLinkedMedia(series.linkedMediaJson).any {
            it.kind == normalizedKind && it.workSyncId == workSyncId
        }
    }
}
