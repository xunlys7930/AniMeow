package com.animeow.app.data.backup

import com.animeow.app.data.local.encodeAnimeSourceReferences
import com.animeow.app.data.local.parseAnimeSourceReferences

import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.EPISODE_DURATION_MAX_MINUTES
import com.animeow.app.data.local.normalizeSubjectType
import java.math.BigDecimal
import java.time.LocalTime
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Produces a non-destructive union of two native database snapshots.
 *
 * Local values win when the same logical record was edited on both devices. Remote values fill
 * empty metadata, higher viewing progress is retained, and remote-only records/relations are
 * remapped to collision-free local IDs. This keeps a selective cloud merge from replacing an
 * entire category merely because one record changed.
 */
internal object NativeBackupMerger {
    fun merge(
        local: JSONObject,
        remote: JSONObject,
        includeCore: Boolean,
        includeAnalysis: Boolean,
        onAnimeIdsMapped: (Map<Long, Long>) -> Unit = {},
    ): JSONObject {
        val result = JSONObject(local.toString())
        if (includeCore) onAnimeIdsMapped(mergeCore(result, remote))
        if (includeAnalysis) mergeAnalysis(result, remote)
        return result
    }

    private fun mergeCore(result: JSONObject, remote: JSONObject): Map<Long, Long> {
        mergeSimpleEntityTable(result, remote, table = "audio_works",
            keys = { row -> listOfNotNull(row.normalizedText("syncId")?.let { "sync:$it" }) },
            mergeMatched = { local, incoming ->
                val remoteTime = runCatching { java.time.Instant.parse(incoming.optString("updatedAt")) }.getOrNull()
                val localTime = runCatching { java.time.Instant.parse(local.optString("updatedAt")) }.getOrNull()
                if (remoteTime != null && (localTime == null || remoteTime > localTime)) {
                    incoming.keys().forEach { key -> if (key != "id" && key != "syncId") local.put(key, incoming.opt(key)) }
                }
            },
        )
        val gameMap = mergeSimpleEntityTable(result, remote, table = "games",
            keys = { row -> listOfNotNull(
                row.normalizedText("syncId")?.let { "sync:$it" },
                row.positiveLong("bangumiId")?.let { "bgm:$it" },
                row.normalizedText("vndbId")?.takeIf { it.matches(Regex("v[1-9]\\d{0,8}")) }?.let { "vndb:$it" },
            ) },
            mergeMatched = { local, incoming ->
                // Last edit wins, including corrections to zero and deletion tombstones.
                val incomingTime = runCatching { java.time.Instant.parse(incoming.optString("updatedAt")) }.getOrNull()
                val localTime = runCatching { java.time.Instant.parse(local.optString("updatedAt")) }.getOrNull()
                if (incomingTime != null && (localTime == null || incomingTime > localTime)) {
                    incoming.keys().forEach { key -> if (key != "id" && key != "syncId") local.put(key, incoming.opt(key)) }
                }
            },
        )
        val mediaSeriesMap = mergeSimpleEntityTable(result, remote, table = "media_series",
            keys = { row -> listOfNotNull(row.normalizedText("syncId")?.let { "sync:$it" }) },
            prepareIncoming = { it.optString("kind") in setOf("GAME", "AUDIO") && !it.normalizedText("syncId").isNullOrBlank() },
            canMatch = { local, incoming -> local.optString("kind") == incoming.optString("kind") },
            mergeMatched = ::mergeLatestMediaRecord,
        )
        val canonicalGames = result.rows("games").associateBy { it.long("id") }
        val gameSyncMap = remote.rows("games").associate { row ->
            row.optString("syncId") to gameMap[row.long("id")]?.let { canonicalGames[it]?.optString("syncId") }
        }
        val seriesById = result.rows("media_series").associateBy { it.long("id") }
        mergeSimpleEntityTable(result, remote, table = "media_series_members",
            keys = { listOf("${it.optString("kind")}:${it.optString("workSyncId")}") },
            prepareIncoming = { row ->
                val kind = row.optString("kind")
                if (kind == "GAME") gameSyncMap[row.optString("workSyncId")]?.let { row.put("workSyncId", it) }
                val remoteSeries = row.positiveLong("seriesId")
                val mapped = remoteSeries?.let(mediaSeriesMap::get)
                val validSeries = if (row.isNull("seriesId")) true else mapped != null && seriesById[mapped]?.optString("kind") == kind
                if (validSeries) row.put("seriesId", mapped ?: JSONObject.NULL)
                kind in setOf("GAME", "AUDIO") && !row.normalizedText("workSyncId").isNullOrBlank() && validSeries &&
                    row.normalizeRequiredInt("sortOrder", default = 0, range = 0..Int.MAX_VALUE)
            }, mergeMatched = ::mergeLatestMediaRecord,
        )
        mergeSimpleEntityTable(
            result,
            remote,
            table = "watch_statuses",
            keys = { row -> listOfNotNull(row.normalizedText("name")?.let { "name:$it" }) },
            prepareIncoming = { row ->
                if (row.text("name") == "未看") row.put("name", "想看")
                row.normalizeRequiredInt("sortOrder", default = 0, range = 0..Int.MAX_VALUE)
                true
            },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "color")
                local.putMinimumNonNegativeInt("sortOrder", incoming)
            },
        )

        val seriesMap = mergeSimpleEntityTable(
            result,
            remote,
            table = "series",
            keys = { row -> listOfNotNull(row.normalizedText("name")?.let { "name:$it" }) },
            prepareIncoming = { row ->
                row.put(
                    "linkedMediaJson",
                    com.animeow.app.data.local.normalizeSeriesLinkedMediaJson(row.text("linkedMediaJson"), gameSyncMap)
                        ?: JSONObject.NULL,
                )
                true
            },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "description", "customCoverUrl")
                local.putEarliest("createdAt", incoming)
                local.put(
                    "linkedMediaJson",
                    com.animeow.app.data.local.mergeSeriesLinkedMediaJson(
                        local.text("linkedMediaJson"),
                        incoming.text("linkedMediaJson"),
                    ) ?: JSONObject.NULL,
                )
            },
        )
        val tagMap = mergeSimpleEntityTable(
            result,
            remote,
            table = "tags",
            keys = { row -> listOfNotNull(row.normalizedText("name")?.let { "name:$it" }) },
            prepareIncoming = { row ->
                // 兼容没有 blocked 列的旧备份：把缺失/布尔/非法值统一成 0/1，
                // 避免入库时触发 NOT NULL 约束错误，同时不丢失显式的“屏蔽”状态。
                row.normalizeTagBlocked()
                true
            },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "color", "blocked", "sortOrder")
            },
        )
        val characterMap = mergeSimpleEntityTable(
            result,
            remote,
            table = "characters",
            keys = ::characterKeys,
            prepareIncoming = { row ->
                row.normalizeCharacterNumbers()
                true
            },
            canMatch = ::canMatchCharacter,
            mergeMatched = ::mergeCharacter,
        )
        val characterTagMap = mergeSimpleEntityTable(
            result,
            remote,
            table = "character_tags",
            keys = { row -> listOfNotNull(row.normalizedText("name")?.let { "name:$it" }) },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "color")
                local.putEarliest("createdAt", incoming)
            },
        )
        val groupMap = mergeSimpleEntityTable(
            result,
            remote,
            table = "character_groups",
            keys = ::characterGroupKeys,
            canMatch = ::canMatchCharacterGroup,
            mergeMatched = ::mergeCharacterGroup,
        )

        val animeMap = mergeSimpleEntityTable(
            result,
            remote,
            table = "animes",
            keys = ::animeKeys,
            prepareIncoming = { row ->
                if (row.text("status") == "未看") row.put("status", "想看")
                row.normalizeAnimeValues()
                row.remapNullable("seriesId", seriesMap)
            },
            canMatch = ::canMatchAnime,
            mergeMatched = ::mergeAnime,
        )

        mergeIdEntityTable(
            result,
            remote,
            table = "watch_records",
            prepareIncoming = { row ->
                row.normalizeRequiredInt("episode", range = 0..Int.MAX_VALUE) &&
                    row.remapRequired("animeId", animeMap)
            },
            key = { row ->
                "${row.long("animeId")}|${row.nonNegativeInt("episode")}|${row.text("recordDate").orEmpty()}"
            },
            mergeMatched = { local, incoming -> local.fillMissingFrom(incoming, "status", "recordDate") },
        )
        mergeCompositeTable(
            result,
            remote,
            table = "episode_details",
            prepareIncoming = { row ->
                row.normalizeOptionalInt("durationMinutes", 1..EPISODE_DURATION_MAX_MINUTES)
                row.normalizeOptionalInt("rating", 1..100)
                row.normalizeRequiredInt("episode", range = 1..Int.MAX_VALUE) &&
                    row.remapRequired("animeId", animeMap)
            },
            key = { row -> "${row.long("animeId")}|${row.nonNegativeInt("episode")}" },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "title", "durationMinutes", "synopsis", "review", "rating")
                local.putLatest("updatedAt", incoming)
            },
        )
        mergeCompositeTable(
            result,
            remote,
            table = "book_reading_states",
            prepareIncoming = { row ->
                // 本机 SAF content:// URI 属于设备私有路径，合并远端快照时一律剥离
                row.put("localFileUri", JSONObject.NULL)
                row.normalizeRequiredInt("chapterIndex", range = 0..1_000_000, default = 0)
                row.normalizeRequiredInt("totalChapters", range = 0..1_000_000, default = 0)
                row.normalizeRequiredInt("blockIndex", range = 0..10_000_000, default = 0)
                row.normalizeRequiredInt("charOffset", range = 0..100_000_000, default = 0)
                row.normalizeRequiredInt("progressPermille", range = 0..1000, default = 0)
                row.normalizeOptionalInt("legadoChapterIndex", 0..1_000_000)
                row.normalizeOptionalInt("legadoTotalChapters", 0..1_000_000)
                when {
                    !row.has("syncProgressToMain") || row.isNull("syncProgressToMain") -> row.put("syncProgressToMain", 1)
                    row.opt("syncProgressToMain") is Boolean -> row.put("syncProgressToMain", if (row.optBoolean("syncProgressToMain")) 1 else 0)
                    else -> row.normalizeRequiredInt("syncProgressToMain", range = 0..1, default = 1)
                }
                if (row.text("updatedAt") == null) row.put("updatedAt", "")
                row.remapRequired("animeId", animeMap)
            },
            key = { row -> "${row.long("animeId")}" },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(
                    incoming,
                    "localFileName",
                    "fileFormat",
                    "fileFingerprint",
                    "charsetName",
                    "legadoBookUrl",
                    "legadoOriginName",
                    "legadoAuthor",
                )
                val incomingAhead = incoming.nonNegativeInt("progressPermille") > local.nonNegativeInt("progressPermille") ||
                    (incoming.nonNegativeInt("progressPermille") == local.nonNegativeInt("progressPermille") &&
                        incoming.nonNegativeInt("chapterIndex") > local.nonNegativeInt("chapterIndex"))
                if (incomingAhead) {
                    local.put("chapterIndex", incoming.nonNegativeInt("chapterIndex"))
                    if (incoming.hasMeaningful("chapterTitle")) local.put("chapterTitle", incoming.opt("chapterTitle"))
                    local.put("blockIndex", incoming.nonNegativeInt("blockIndex"))
                    local.put("charOffset", incoming.nonNegativeInt("charOffset"))
                    local.put("progressPermille", incoming.nonNegativeInt("progressPermille"))
                } else {
                    local.fillMissingFrom(incoming, "chapterTitle")
                }
                local.putMaximumNonNegativeInt("totalChapters", incoming)
                local.putLatest("lastReadAt", incoming)
                val localLegadoIdx = local.nullableInt("legadoChapterIndex") ?: -1
                val incomingLegadoIdx = incoming.nullableInt("legadoChapterIndex") ?: -1
                if (incomingLegadoIdx > localLegadoIdx) {
                    local.put("legadoChapterIndex", incomingLegadoIdx)
                    if (incoming.hasMeaningful("legadoChapterTitle")) local.put("legadoChapterTitle", incoming.opt("legadoChapterTitle"))
                    if (incoming.hasMeaningful("legadoBookUrl")) local.put("legadoBookUrl", incoming.opt("legadoBookUrl"))
                    if (incoming.hasMeaningful("legadoOriginName")) local.put("legadoOriginName", incoming.opt("legadoOriginName"))
                    if (incoming.hasMeaningful("legadoAuthor")) local.put("legadoAuthor", incoming.opt("legadoAuthor"))
                } else {
                    local.fillMissingFrom(incoming, "legadoChapterTitle")
                }
                val maxLegadoTotal = listOfNotNull(
                    local.nullableInt("legadoTotalChapters"),
                    incoming.nullableInt("legadoTotalChapters"),
                ).maxOrNull()
                local.put("legadoTotalChapters", maxLegadoTotal ?: JSONObject.NULL)
                local.putLatest("legadoLastReadAt", incoming)
                local.putLatest("updatedAt", incoming)
            },
        )
        mergeIdEntityTable(
            result,
            remote,
            table = "book_bookmarks",
            prepareIncoming = { row ->
                row.normalizeRequiredInt("chapterIndex", range = 0..1_000_000, default = 0)
                row.normalizeRequiredInt("blockIndex", range = 0..10_000_000, default = 0)
                row.normalizeRequiredInt("charOffset", range = 0..100_000_000, default = 0)
                if (row.text("chapterTitle") == null) row.put("chapterTitle", "")
                if (row.text("snippet") == null) row.put("snippet", "")
                if (row.text("createdAt") == null) row.put("createdAt", "")
                row.remapRequired("animeId", animeMap)
            },
            key = { row ->
                "${row.long("animeId")}|${row.nonNegativeInt("chapterIndex")}|${row.nonNegativeInt("blockIndex")}|${row.nonNegativeInt("charOffset")}"
            },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "chapterTitle", "snippet", "note", "createdAt")
            },
        )
        mergeCompositeTable(
            result,
            remote,
            table = "anime_tags",
            prepareIncoming = { row ->
                row.remapRequired("animeId", animeMap) && row.remapRequired("tagId", tagMap)
            },
            key = { row -> "${row.long("animeId")}|${row.long("tagId")}" },
        )
        mergeCompositeTable(
            result,
            remote,
            table = "anime_characters",
            prepareIncoming = { row ->
                row.normalizeRequiredInt("sortOrder", default = 0, range = 0..Int.MAX_VALUE)
                row.remapRequired("animeId", animeMap) && row.remapRequired("characterId", characterMap)
            },
            key = { row -> "${row.long("animeId")}|${row.long("characterId")}" },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "roleName", "voiceActor")
                local.putMinimumNonNegativeInt("sortOrder", incoming)
            },
        )
        mergeIdEntityTable(
            result,
            remote,
            table = "character_relations",
            prepareIncoming = { row ->
                row.normalizeRequiredInt("strength", default = 3, range = 1..5)
                if (
                    !row.remapRequired("sourceCharacterId", characterMap) ||
                    !row.remapRequired("targetCharacterId", characterMap)
                ) {
                    false
                } else {
                    val first = row.long("sourceCharacterId")
                    val second = row.long("targetCharacterId")
                    if (first == second) false else {
                        if (first > second) {
                            val sourceRole = row.opt("sourceRole") ?: JSONObject.NULL
                            row.put("sourceRole", row.opt("targetRole") ?: JSONObject.NULL)
                            row.put("targetRole", sourceRole)
                        }
                        row.put("sourceCharacterId", minOf(first, second))
                        row.put("targetCharacterId", maxOf(first, second))
                        true
                    }
                }
            },
            key = { row ->
                "${row.long("sourceCharacterId")}|${row.long("targetCharacterId")}"
            },
            mergeMatched = { local, incoming ->
                // An empty role is intentional, so only NULL/missing roles may be filled.
                listOf("sourceRole", "targetRole").forEach { field ->
                    if (local.isNull(field) && !incoming.isNull(field)) local.put(field, incoming.get(field))
                }
                local.fillMissingFrom(incoming, "relationType", "note", "strength", "createdAt")
                local.putLatest("updatedAt", incoming)
            },
        )
        mergeCompositeTable(
            result,
            remote,
            table = "character_tag_links",
            prepareIncoming = { row ->
                row.remapRequired("characterId", characterMap) &&
                    row.remapRequired("tagId", characterTagMap)
            },
            key = { row -> "${row.long("characterId")}|${row.long("tagId")}" },
        )
        mergeCompositeTable(
            result,
            remote,
            table = "character_group_characters",
            prepareIncoming = { row ->
                row.normalizeRequiredInt("sortOrder", default = 0, range = 0..Int.MAX_VALUE)
                row.remapRequired("groupId", groupMap) &&
                    row.remapRequired("characterId", characterMap)
            },
            key = { row -> "${row.long("groupId")}|${row.long("characterId")}" },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "roleName")
                local.putMinimumNonNegativeInt("sortOrder", incoming)
            },
        )
        mergeCompositeTable(
            result,
            remote,
            table = "character_group_works",
            prepareIncoming = { row ->
                row.normalizeRequiredInt("sortOrder", default = 0, range = 0..Int.MAX_VALUE)
                row.remapRequired("groupId", groupMap) && row.remapRequired("animeId", animeMap)
            },
            key = { row -> "${row.long("groupId")}|${row.long("animeId")}" },
            mergeMatched = { local, incoming ->
                local.putMinimumNonNegativeInt("sortOrder", incoming)
            },
        )
        return animeMap
    }

    private fun mergeAnalysis(result: JSONObject, remote: JSONObject) {
        mergeIdEntityTable(
            result,
            remote,
            table = "anime_analysis_records",
            prepareIncoming = { true },
            key = { row ->
                row.nullableLong("serverRecordId")?.let { "server:$it" }
                    ?: "local:${row.text("createdAt").orEmpty()}|${row.text("model").orEmpty()}|" +
                        row.text("analysis").orEmpty()
            },
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "userId", "username", "model", "analysis", "statsJson", "createdAt")
            },
        )
    }

    private fun mergeLatestMediaRecord(local: JSONObject, incoming: JSONObject) {
        val remoteTime = runCatching { java.time.Instant.parse(incoming.optString("updatedAt")) }.getOrNull()
        val localTime = runCatching { java.time.Instant.parse(local.optString("updatedAt")) }.getOrNull()
        if (remoteTime != null && (localTime == null || remoteTime > localTime)) {
            incoming.keys().forEach { key -> if (key != "id" && key != "syncId") local.put(key, incoming.opt(key)) }
        }
    }

    private fun mergeSimpleEntityTable(
        result: JSONObject,
        remote: JSONObject,
        table: String,
        keys: (JSONObject) -> List<String>,
        prepareIncoming: (JSONObject) -> Boolean = { true },
        canMatch: (JSONObject, JSONObject) -> Boolean = { _, _ -> true },
        mergeMatched: (JSONObject, JSONObject) -> Unit,
    ): Map<Long, Long> {
        val localRows = result.rows(table)
        val remoteRows = remote.rows(table)
        val byKey = linkedMapOf<String, MutableList<JSONObject>>()
        fun index(row: JSONObject) {
            keys(row).distinct().forEach { key ->
                val candidates = byKey.getOrPut(key) { mutableListOf() }
                if (candidates.none { it === row }) candidates += row
            }
        }
        localRows.forEach(::index)
        val usedIds = localRows.mapNotNull { it.positiveLong("id") }.toMutableSet()
        val idMap = mutableMapOf<Long, Long>()
        remoteRows.forEach { source ->
            val incoming = JSONObject(source.toString())
            val remoteId = incoming.nullableLong("id") ?: return@forEach
            if (!prepareIncoming(incoming)) return@forEach
            val existing = keys(incoming).asSequence()
                .flatMap { key -> byKey[key].orEmpty().asSequence() }
                .distinct()
                .firstOrNull { candidate -> canMatch(candidate, incoming) }
            if (existing != null) {
                val targetId = existing.long("id")
                idMap[remoteId] = targetId
                mergeMatched(existing, incoming)
                index(existing)
            } else {
                val targetId = allocateId(remoteId, usedIds)
                incoming.put("id", targetId)
                usedIds += targetId
                idMap[remoteId] = targetId
                localRows += incoming
                index(incoming)
            }
        }
        result.put(table, JSONArray(localRows))
        return idMap
    }

    private fun mergeIdEntityTable(
        result: JSONObject,
        remote: JSONObject,
        table: String,
        prepareIncoming: (JSONObject) -> Boolean,
        key: (JSONObject) -> String,
        mergeMatched: (JSONObject, JSONObject) -> Unit,
    ) {
        val localRows = result.rows(table)
        val byKey = localRows.associateByTo(linkedMapOf(), key)
        val usedIds = localRows.mapNotNull { it.positiveLong("id") }.toMutableSet()
        remote.rows(table).forEach { source ->
            val incoming = JSONObject(source.toString())
            if (!prepareIncoming(incoming)) return@forEach
            val recordKey = key(incoming)
            val existing = byKey[recordKey]
            if (existing != null) {
                mergeMatched(existing, incoming)
            } else {
                val remoteId = incoming.nullableLong("id") ?: 0L
                val targetId = allocateId(remoteId, usedIds)
                incoming.put("id", targetId)
                usedIds += targetId
                localRows += incoming
                byKey[recordKey] = incoming
            }
        }
        result.put(table, JSONArray(localRows))
    }

    private fun mergeCompositeTable(
        result: JSONObject,
        remote: JSONObject,
        table: String,
        prepareIncoming: (JSONObject) -> Boolean,
        key: (JSONObject) -> String,
        mergeMatched: (JSONObject, JSONObject) -> Unit = { _, _ -> },
    ) {
        val localRows = result.rows(table)
        val byKey = localRows.associateByTo(linkedMapOf(), key)
        remote.rows(table).forEach { source ->
            val incoming = JSONObject(source.toString())
            if (!prepareIncoming(incoming)) return@forEach
            val recordKey = key(incoming)
            val existing = byKey[recordKey]
            if (existing == null) {
                localRows += incoming
                byKey[recordKey] = incoming
            } else {
                mergeMatched(existing, incoming)
            }
        }
        result.put(table, JSONArray(localRows))
    }

    private fun mergeAnime(local: JSONObject, incoming: JSONObject) {
        local.put("sourceRatingJson", com.animeow.app.data.remote.mergeSourceRatingSnapshots(
            local.text("sourceRatingJson"), incoming.text("sourceRatingJson")) ?: JSONObject.NULL)
        local.normalizeAnimeValues()
        incoming.normalizeAnimeValues()
        local.fillMissingFrom(
            incoming,
            "coverUrl",
            "funRatingTier",
            "review",
            "memo",
            "seriesId",
            "airDate",
            "studio",
            "voiceActors",
            "coverOrientation",
            "sourceRatingJson",
            "watchStartDate",
            "watchFinishDate",
            "originalTitle",
            "synopsis",
            "mediaFormat",
        )
        local.mergeAnimeRatingFrom(incoming)
        local.mergeReminderFrom(incoming)
        if (local.nullableInt("broadcastDay") !in 1..7 && incoming.nullableInt("broadcastDay") in 1..7) {
            local.put("broadcastDay", incoming.opt("broadcastDay"))
            local.put("broadcastTime", incoming.opt("broadcastTime") ?: JSONObject.NULL)
        }
        local.mergeExternalIdentityFrom(incoming)
        local.put("mergedSourcesJson", encodeAnimeSourceReferences(
            parseAnimeSourceReferences(local.text("mergedSourcesJson")) + parseAnimeSourceReferences(incoming.text("mergedSourcesJson")),
        ) ?: JSONObject.NULL)
        listOf("watchedEpisodes", "totalEpisodes", "tvEpisodes", "spEpisodes").forEach { field ->
            local.putMaximumNonNegativeInt(field, incoming)
        }
        if (
            local.text("status").isNullOrBlank() ||
            local.text("status") in setOf("想看", "未看") && incoming.nonNegativeInt("watchedEpisodes") > 0
        ) {
            incoming.text("status")?.let {
                local.put("status", it)
                local.put("statusChangedAt", incoming.opt("statusChangedAt") ?: JSONObject.NULL)
            }
        } else if (local.text("status") == incoming.text("status")) {
            local.fillMissingFrom(incoming, "statusChangedAt")
        }
        val localUpdated = local.optLong("updatedAt", 0L).takeIf { !local.isNull("updatedAt") && it > 0L }
        val incomingUpdated = incoming.optLong("updatedAt", 0L).takeIf { !incoming.isNull("updatedAt") && it > 0L }
        if (incomingUpdated != null && (localUpdated == null || incomingUpdated > localUpdated)) {
            local.put("updatedAt", incomingUpdated)
        }
        if (local.isNull("seriesRole") && local.optLong("seriesId", -1) == incoming.optLong("seriesId", -2)) {
            incoming.text("seriesRole")?.takeIf { it in com.animeow.app.data.local.SERIES_ROLES }?.let { local.put("seriesRole", it) }
        }
        local.putEarliest("createdAt", incoming)
        for (field in listOf("bookReadVolumes", "bookReadChapters")) {
            if (local.isNull(field) && incoming.nullableInt(field) in 0..10000) local.put(field, incoming.getInt(field))
        }
        local.putEarliest("watchStartDate", incoming)
        local.putLatest("watchFinishDate", incoming)
        val localDeleted = local.text("deletedAt")
        val incomingDeleted = incoming.text("deletedAt")
        if (localDeleted.isNullOrBlank() || incomingDeleted.isNullOrBlank()) {
            local.put("deletedAt", JSONObject.NULL)
        } else {
            local.put("deletedAt", maxOf(localDeleted, incomingDeleted))
        }
        local.normalizeAnimeCompletion()
    }

    private fun mergeCharacter(local: JSONObject, incoming: JSONObject) {
        local.normalizeCharacterNumbers()
        incoming.normalizeCharacterNumbers()
        local.fillMissingFrom(
            incoming,
            "bgmId",
            "anilistId",
            "nameCn",
            "imageUrl",
            "imageDisplayMode",
            "summary",
            "gender",
            "birthYear",
            "birthMonth",
            "birthDay",
            "bloodType",
            "infoboxJson",
            "rating",
            "review",
        )
        local.putLatest("updatedAt", incoming)
    }

    private fun mergeCharacterGroup(local: JSONObject, incoming: JSONObject) {
        local.fillMissingFrom(
            incoming,
            "description",
            "coverUrl",
            "communityId",
            "shareCode",
            "source",
            "isPublic",
            "extraJson",
            "createdAt",
        )
        local.putLatest("updatedAt", incoming)
    }

    private fun animeKeys(row: JSONObject): List<String> {
        val type = normalizeSubjectType(row.text("subjectType"))
        val year = row.airYear()
        return buildList {
            row.externalIdentity()?.let { identity -> add(identity.key) }
            row.normalizedText("title")?.let { title ->
                if (year != null) add("title:$type:$year:$title")
                add("title:$type:*:$title")
            }
            row.normalizedText("originalTitle")?.let { title ->
                if (year != null) add("original:$type:$year:$title")
                add("original:$type:*:$title")
            }
        }
    }

    private fun characterKeys(row: JSONObject): List<String> {
        return buildList {
            row.positiveLong("bgmId")?.let { add("bgm:$it") }
            row.positiveLong("anilistId")?.let { add("anilist:$it") }
            row.normalizedText("nameCn")?.let { add("name:$it") }
            row.normalizedText("name")?.let { add("name:$it") }
        }.distinct()
    }

    private fun characterGroupKeys(row: JSONObject): List<String> {
        return buildList {
            row.normalizedText("communityId")?.let { add("community:$it") }
            row.normalizedText("shareCode")?.let { add("share:$it") }
            row.normalizedText("name")?.let { add("name:$it") }
        }.distinct()
    }

    private fun canMatchAnime(local: JSONObject, incoming: JSONObject): Boolean {
        val localIdentity = local.externalIdentity()
        val incomingIdentity = incoming.externalIdentity()
        if (localIdentity != null && incomingIdentity != null) {
            return localIdentity.matches(incomingIdentity)
        }
        val localType = normalizeSubjectType(local.text("subjectType"))
        val incomingType = normalizeSubjectType(incoming.text("subjectType"))
        if (localType != incomingType) return false
        val localYear = local.airYear()
        val incomingYear = incoming.airYear()
        return localYear == null || incomingYear == null || localYear == incomingYear
    }

    private fun canMatchCharacter(local: JSONObject, incoming: JSONObject): Boolean {
        val localId = local.positiveLong("bgmId")
        val incomingId = incoming.positiveLong("bgmId")
        val localAniList = local.positiveLong("anilistId")
        val incomingAniList = incoming.positiveLong("anilistId")
        return (localId == null || incomingId == null || localId == incomingId) &&
            (localAniList == null || incomingAniList == null || localAniList == incomingAniList)
    }

    private fun canMatchCharacterGroup(local: JSONObject, incoming: JSONObject): Boolean {
        val localCommunityId = local.normalizedText("communityId")
        val incomingCommunityId = incoming.normalizedText("communityId")
        if (localCommunityId != null && incomingCommunityId != null) {
            return localCommunityId == incomingCommunityId
        }
        val localShareCode = local.normalizedText("shareCode")
        val incomingShareCode = incoming.normalizedText("shareCode")
        return localShareCode == null || incomingShareCode == null || localShareCode == incomingShareCode
    }

    private fun JSONObject.normalizeAnimeValues() {
        val rawSubjectType = text("subjectType")
        put("subjectType", normalizeSubjectType(rawSubjectType))
        if (text("seriesRole") !in com.animeow.app.data.local.SERIES_ROLES || positiveLong("seriesId") == null) put("seriesRole", JSONObject.NULL)
        put("memo", com.animeow.app.data.local.normalizeAnimeMemo(text("memo")) ?: JSONObject.NULL)
        normalizeOptionalInt("bookReadVolumes", 0..10000)
        normalizeOptionalInt("bookReadChapters", 0..10000)
        listOf("watchedEpisodes", "totalEpisodes", "tvEpisodes", "spEpisodes").forEach { field ->
            normalizeRequiredInt(field, default = 0, range = 0..Int.MAX_VALUE)
        }
        normalizeAnimeRating()
        normalizeReminder()
        normalizeAnimeCompletion()
    }

    private fun JSONObject.normalizeAnimeCompletion() {
        if (text("status")?.trim() != "看完") return
        val total = nonNegativeInt("totalEpisodes")
        if (total > 0) put("watchedEpisodes", total)
    }

    private fun JSONObject.normalizeAnimeRating() {
        val grade = normalizeAnimeRatingGrade(text("ratingGrade"))
        val score = nullableInt("rating")?.takeIf { it in 1..100 }
        when {
            grade != null -> {
                put("ratingGrade", grade)
                put("rating", JSONObject.NULL)
            }
            score != null -> {
                put("rating", score)
                put("ratingGrade", JSONObject.NULL)
            }
            else -> {
                put("rating", JSONObject.NULL)
                put("ratingGrade", JSONObject.NULL)
            }
        }
    }

    private fun JSONObject.mergeAnimeRatingFrom(incoming: JSONObject) {
        if (hasAnimeRating() || !incoming.hasAnimeRating()) return
        incoming.text("ratingGrade")?.let { grade ->
            put("ratingGrade", grade)
            put("rating", JSONObject.NULL)
            return
        }
        incoming.nullableInt("rating")?.let { score ->
            put("rating", score)
            put("ratingGrade", JSONObject.NULL)
        }
    }

    private fun JSONObject.hasAnimeRating(): Boolean =
        normalizeAnimeRatingGrade(text("ratingGrade")) != null || nullableInt("rating") in 1..100

    private fun JSONObject.normalizeReminder() {
        val reminder = reminder()
        if (reminder == null) {
            put("reminderDay", JSONObject.NULL)
            put("reminderTime", JSONObject.NULL)
        } else {
            put("reminderDay", reminder.day)
            put("reminderTime", reminder.time.toString())
        }
    }

    private fun JSONObject.mergeReminderFrom(incoming: JSONObject) {
        if (reminder() != null) return
        val reminder = incoming.reminder() ?: return
        put("reminderDay", reminder.day)
        put("reminderTime", reminder.time.toString())
    }

    private fun JSONObject.reminder(): AnimeReminder? {
        val day = nullableInt("reminderDay")?.takeIf { it in 1..7 } ?: return null
        val time = text("reminderTime")?.let { value ->
            runCatching { LocalTime.parse(value) }.getOrNull()
        } ?: return null
        return AnimeReminder(day, time)
    }

    private fun JSONObject.mergeExternalIdentityFrom(incoming: JSONObject) {
        val localIdentity = externalIdentity()
        val incomingIdentity = incoming.externalIdentity()
        when {
            localIdentity != null -> {
                put("externalSource", localIdentity.source)
                put("externalId", localIdentity.id)
                if (
                    !hasMeaningful("externalUrl") &&
                    incomingIdentity != null &&
                    localIdentity.matches(incomingIdentity)
                ) {
                    incoming.text("externalUrl")?.let { put("externalUrl", it) }
                }
            }
            incomingIdentity != null -> {
                put("externalSource", incomingIdentity.source)
                put("externalId", incomingIdentity.id)
                put("externalUrl", incoming.text("externalUrl") ?: JSONObject.NULL)
            }
        }
    }

    private fun JSONObject.externalIdentity(): AnimeExternalIdentity? {
        val source = text("externalSource") ?: return null
        val id = text("externalId") ?: return null
        return AnimeExternalIdentity(
            source = source,
            id = id,
            normalizedSource = source.lowercase(Locale.ROOT),
            normalizedId = id.lowercase(Locale.ROOT),
        )
    }

    private fun JSONObject.airYear(): String? = text("airDate")
        ?.take(4)
        ?.takeIf { value -> value.length == 4 && value.all(Char::isDigit) }

    private fun JSONObject.normalizeCharacterNumbers() {
        normalizePositiveLong("bgmId")
        normalizeOptionalInt("birthYear", 1..9999)
        normalizeOptionalInt("birthMonth", 1..12)
        normalizeOptionalInt("birthDay", 1..31)
        normalizeOptionalInt("rating", 1..10)
    }

    private fun JSONObject.rows(table: String): MutableList<JSONObject> {
        val array = optJSONArray(table) ?: JSONArray()
        return MutableList(array.length()) { index -> JSONObject(array.getJSONObject(index).toString()) }
    }

    private fun JSONObject.fillMissingFrom(incoming: JSONObject, vararg fields: String) {
        fields.forEach { field ->
            if (!hasMeaningful(field) && incoming.hasMeaningful(field)) put(field, incoming.opt(field))
        }
    }

    private fun JSONObject.hasMeaningful(field: String): Boolean {
        if (!has(field) || isNull(field)) return false
        val value = opt(field)
        return value !is String || value.isNotBlank()
    }

    private fun JSONObject.remapRequired(field: String, mapping: Map<Long, Long>): Boolean {
        val mapped = nullableLong(field)?.let(mapping::get) ?: return false
        put(field, mapped)
        return true
    }

    private fun JSONObject.remapNullable(field: String, mapping: Map<Long, Long>): Boolean {
        if (!has(field) || isNull(field)) return true
        val current = nullableLong(field)
        if (current == null) {
            put(field, JSONObject.NULL)
            return true
        }
        val mapped = mapping[current]
        if (mapped == null) put(field, JSONObject.NULL) else put(field, mapped)
        return true
    }

    private fun JSONObject.normalizeTagBlocked() {
        when {
            !has("blocked") || isNull("blocked") -> put("blocked", 0)
            opt("blocked") is Boolean -> put("blocked", if (optBoolean("blocked")) 1 else 0)
            else -> normalizeRequiredInt("blocked", range = 0..1, default = 0)
        }
    }

    private fun JSONObject.normalizeRequiredInt(
        field: String,
        range: IntRange,
        default: Int? = null,
    ): Boolean {
        val value = nullableInt(field)?.takeIf { it in range }
        if (value != null) {
            put(field, value)
            return true
        }
        if (default != null) {
            put(field, default)
            return true
        }
        return false
    }

    private fun JSONObject.normalizeOptionalInt(field: String, range: IntRange) {
        val value = nullableInt(field)?.takeIf { it in range }
        put(field, value ?: JSONObject.NULL)
    }

    private fun JSONObject.normalizePositiveLong(field: String) {
        put(field, positiveLong(field) ?: JSONObject.NULL)
    }

    private fun JSONObject.putMaximumNonNegativeInt(field: String, incoming: JSONObject) {
        put(field, maxOf(nonNegativeInt(field), incoming.nonNegativeInt(field)))
    }

    private fun JSONObject.putMinimumNonNegativeInt(field: String, incoming: JSONObject) {
        val first = nullableInt(field)?.takeIf { it >= 0 }
        val second = incoming.nullableInt(field)?.takeIf { it >= 0 }
        put(field, listOfNotNull(first, second).minOrNull() ?: 0)
    }

    private fun JSONObject.putEarliest(field: String, incoming: JSONObject) {
        val first = text(field)
        val second = incoming.text(field)
        when {
            first.isNullOrBlank() && !second.isNullOrBlank() -> put(field, second)
            !first.isNullOrBlank() && !second.isNullOrBlank() -> put(field, minOf(first, second))
        }
    }

    private fun JSONObject.putLatest(field: String, incoming: JSONObject) {
        val first = text(field)
        val second = incoming.text(field)
        when {
            first.isNullOrBlank() && !second.isNullOrBlank() -> put(field, second)
            !first.isNullOrBlank() && !second.isNullOrBlank() -> put(field, maxOf(first, second))
        }
    }

    private fun JSONObject.text(field: String): String? =
        if (!has(field) || isNull(field)) null else optString(field).trim().takeIf(String::isNotEmpty)

    private fun JSONObject.normalizedText(field: String): String? = text(field)
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("\\s+"), " ")

    private fun JSONObject.nullableLong(field: String): Long? =
        if (!has(field) || isNull(field)) null else opt(field).toExactLong()

    private fun JSONObject.positiveLong(field: String): Long? = nullableLong(field)?.takeIf { it > 0 }

    private fun JSONObject.nullableInt(field: String): Int? = nullableLong(field)
        ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
        ?.toInt()

    private fun JSONObject.nonNegativeInt(field: String): Int = nullableInt(field)?.takeIf { it >= 0 } ?: 0

    private fun JSONObject.long(field: String): Long = nullableLong(field) ?: 0L

    private fun Any?.toExactLong(): Long? {
        val value = when (this) {
            is Byte, is Short, is Int, is Long -> return (this as Number).toLong()
            is Number, is String -> toString().trim()
            else -> return null
        }
        return runCatching { BigDecimal(value).longValueExact() }.getOrNull()
    }

    private fun allocateId(preferred: Long, usedIds: Set<Long>): Long {
        if (preferred > 0 && preferred !in usedIds) return preferred
        val maximum = usedIds.maxOrNull()
        if (maximum == null) return 1L
        if (maximum < Long.MAX_VALUE) return maximum + 1L
        var candidate = 1L
        while (candidate in usedIds) {
            check(candidate < Long.MAX_VALUE) { "合并记录失败，请稍后重试" }
            candidate += 1L
        }
        return candidate
    }

    private data class AnimeExternalIdentity(
        val source: String,
        val id: String,
        val normalizedSource: String,
        val normalizedId: String,
    ) {
        val key: String get() = "external:$normalizedSource:$normalizedId"

        fun matches(other: AnimeExternalIdentity): Boolean =
            normalizedSource == other.normalizedSource && normalizedId == other.normalizedId
    }

    private data class AnimeReminder(val day: Int, val time: LocalTime)
}
