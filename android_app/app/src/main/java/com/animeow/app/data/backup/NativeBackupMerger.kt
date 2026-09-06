package com.animeow.app.data.backup

import com.animeow.app.data.local.normalizeAnimeRatingGrade
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
    ): JSONObject {
        val result = JSONObject(local.toString())
        if (includeCore) mergeCore(result, remote)
        if (includeAnalysis) mergeAnalysis(result, remote)
        return result
    }

    private fun mergeCore(result: JSONObject, remote: JSONObject) {
        mergeSimpleEntityTable(
            result,
            remote,
            table = "watch_statuses",
            keys = { row -> listOfNotNull(row.normalizedText("name")?.let { "name:$it" }) },
            prepareIncoming = { row ->
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
            mergeMatched = { local, incoming ->
                local.fillMissingFrom(incoming, "description", "customCoverUrl")
                local.putEarliest("createdAt", incoming)
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
                local.fillMissingFrom(incoming, "color", "blocked")
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
                local.fillMissingFrom(incoming, "roleName")
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
        local.normalizeAnimeValues()
        incoming.normalizeAnimeValues()
        local.fillMissingFrom(
            incoming,
            "coverUrl",
            "funRatingTier",
            "review",
            "seriesId",
            "airDate",
            "studio",
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
        listOf("watchedEpisodes", "totalEpisodes", "tvEpisodes", "spEpisodes").forEach { field ->
            local.putMaximumNonNegativeInt(field, incoming)
        }
        if (
            local.text("status").isNullOrBlank() ||
            local.text("status") == "未看" && incoming.nonNegativeInt("watchedEpisodes") > 0
        ) {
            incoming.text("status")?.let { local.put("status", it) }
        }
        local.putEarliest("createdAt", incoming)
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
            "nameCn",
            "imageUrl",
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
        return localId == null || incomingId == null || localId == incomingId
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
            check(candidate < Long.MAX_VALUE) { "无法为合并记录分配主键" }
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
