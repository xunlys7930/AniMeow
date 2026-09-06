package com.animeow.app.data.importer

import com.animeow.app.data.local.AnimeAnalysisRecordEntity
import com.animeow.app.data.local.AnimeCharacterEntity
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterGroupCharacterEntity
import com.animeow.app.data.local.CharacterGroupEntity
import com.animeow.app.data.local.CharacterGroupWorkEntity
import com.animeow.app.data.local.CharacterRelationEntity
import com.animeow.app.data.local.CharacterTagEntity
import com.animeow.app.data.local.CharacterTagLinkEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.legacyAnimeRatingToStored
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.normalizeSubjectType
import java.time.LocalTime

internal object LegacySnapshotMapper {
    fun map(
        sourceSchemaVersion: Int,
        tables: Map<String, List<Map<String, Any?>>>,
    ): LegacyLibrarySnapshot {
        val series = tables.rows("series").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            SeriesEntity(
                id = id,
                name = row.string("name").orEmpty().ifBlank { "未命名系列 #$id" },
                description = row.string("description"),
                customCoverUrl = row.string("custom_cover_url"),
                createdAt = row.string("created_at"),
            )
        }
        val validSeriesIds = series.mapTo(mutableSetOf()) { it.id }

        val animes = tables.rows("animes").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            val totalEpisodes = row.int("total_episodes").orZero().coerceAtLeast(0)
            val normalizedStatus = normalizeStatus(row.string("status"))
            val ratingGrade = normalizeAnimeRatingGrade(row.string("rating_grade"))
            val legacyProgress = row.int("watched_episodes").orZero().coerceAtLeast(0)
            val rawReminderDay = row.int("reminder_day")?.takeIf { it in 1..7 }
            val rawReminderTime = row.string("reminder_time")?.trim()?.takeIf { value ->
                value.isNotEmpty() && runCatching { LocalTime.parse(value) }.isSuccess
            }
            val hasValidReminder = rawReminderDay != null && rawReminderTime != null
            val rawSubjectType = row.string("subject_type")
            val watchedEpisodes = if (
                normalizedStatus == "看完" &&
                totalEpisodes > 0 &&
                legacyProgress < totalEpisodes
            ) {
                totalEpisodes
            } else {
                legacyProgress
            }

            AnimeEntity(
                id = id,
                title = row.string("title").orEmpty().ifBlank { "未命名作品 #$id" },
                coverUrl = row.string("cover_url"),
                status = normalizedStatus,
                rating = if (ratingGrade == null) legacyAnimeRatingToStored(row["rating"]) else null,
                ratingGrade = ratingGrade,
                funRatingTier = row.string("fun_rating_tier"),
                review = row.string("review"),
                seriesId = row.long("series_id")?.takeIf(validSeriesIds::contains),
                createdAt = row.string("created_at"),
                airDate = row.string("air_date"),
                studio = row.string("studio"),
                watchStartDate = row.string("watch_start_date"),
                watchFinishDate = row.string("watch_finish_date"),
                watchedEpisodes = watchedEpisodes,
                totalEpisodes = totalEpisodes,
                tvEpisodes = row.int("tv_episodes").orZero().coerceAtLeast(0),
                spEpisodes = row.int("sp_episodes").orZero().coerceAtLeast(0),
                subjectType = normalizeSubjectType(rawSubjectType),
                reminderDay = rawReminderDay.takeIf { hasValidReminder },
                reminderTime = rawReminderTime.takeIf { hasValidReminder },
                mediaFormat = row.string("media_format")?.trim()?.takeIf(String::isNotEmpty),
            )
        }
        val animeIds = animes.mapTo(mutableSetOf()) { it.id }

        val tags = tables.rows("tags").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            val name = row.string("name").orEmpty().trim()
            if (name.isEmpty()) return@mapNotNull null
            TagEntity(
                id = id,
                name = name,
                color = row.long("color"),
            )
        }
        val tagIds = tags.mapTo(mutableSetOf()) { it.id }

        val animeTags = tables.rows("anime_tags").mapNotNull { row ->
            val animeId = row.positiveLong("anime_id") ?: return@mapNotNull null
            val tagId = row.positiveLong("tag_id") ?: return@mapNotNull null
            if (animeId !in animeIds || tagId !in tagIds) return@mapNotNull null
            AnimeTagEntity(animeId = animeId, tagId = tagId)
        }.distinct()

        val watchStatuses = buildWatchStatuses(
            rows = tables.rows("watch_statuses"),
            animeStatuses = animes.map { it.status },
        )

        val watchRecords = tables.rows("watch_records").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            val animeId = row.positiveLong("anime_id") ?: return@mapNotNull null
            if (animeId !in animeIds) return@mapNotNull null
            WatchRecordEntity(
                id = id,
                animeId = animeId,
                episode = row.int("episode").orZero().coerceAtLeast(0),
                status = row.string("status"),
                recordDate = row.string("record_date"),
            )
        }

        val analysisRecords = tables.rows("anime_analysis_records").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            AnimeAnalysisRecordEntity(
                id = id,
                serverRecordId = row.positiveLong("server_record_id"),
                userId = row.positiveLong("user_id"),
                username = row.string("username"),
                model = row.string("model"),
                analysis = row.string("analysis"),
                statsJson = row.string("stats_json"),
                createdAt = row.string("created_at"),
            )
        }

        val characters = tables.rows("characters").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            CharacterEntity(
                id = id,
                bgmId = row.positiveLong("bgm_id"),
                name = row.string("name").orEmpty().ifBlank { "未命名角色 #$id" },
                nameCn = row.string("name_cn"),
                imageUrl = row.string("image_url"),
                summary = row.string("summary"),
                gender = row.string("gender"),
                birthYear = row.int("birth_year")?.takeIf { it in 1..9999 },
                birthMonth = row.int("birth_mon")?.takeIf { it in 1..12 },
                birthDay = row.int("birth_day")?.takeIf { it in 1..31 },
                bloodType = row.string("blood_type"),
                infoboxJson = row.string("infobox_json"),
                rating = row.int("rating")?.coerceIn(1, 10),
                review = row.string("review"),
                updatedAt = row.string("updated_at"),
            )
        }
        val characterIds = characters.mapTo(mutableSetOf()) { it.id }

        val animeCharacters = tables.rows("anime_characters").mapNotNull { row ->
            val animeId = row.positiveLong("anime_id") ?: return@mapNotNull null
            val characterId = row.positiveLong("character_id") ?: return@mapNotNull null
            if (animeId !in animeIds || characterId !in characterIds) return@mapNotNull null
            AnimeCharacterEntity(
                animeId = animeId,
                characterId = characterId,
                roleName = row.string("role_name"),
                sortOrder = row.int("sort_order").orZero().coerceAtLeast(0),
            )
        }.distinct()

        val characterRelations = tables.rows("character_relations").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            val sourceId = row.positiveLong("source_character_id") ?: return@mapNotNull null
            val targetId = row.positiveLong("target_character_id") ?: return@mapNotNull null
            if (sourceId !in characterIds || targetId !in characterIds || sourceId == targetId) {
                return@mapNotNull null
            }
            CharacterRelationEntity(
                id = id,
                sourceCharacterId = minOf(sourceId, targetId),
                targetCharacterId = maxOf(sourceId, targetId),
                relationType = row.string("relation_type").orEmpty().ifBlank { "关联" },
                note = row.string("note"),
                strength = (row.int("strength") ?: 3).coerceIn(1, 5),
                createdAt = row.string("created_at"),
                updatedAt = row.string("updated_at"),
            )
        }.distinctBy { it.sourceCharacterId to it.targetCharacterId }

        val characterTags = tables.rows("character_tags").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            val name = row.string("name").orEmpty().trim()
            if (name.isEmpty()) return@mapNotNull null
            CharacterTagEntity(
                id = id,
                name = name,
                color = row.long("color"),
                createdAt = row.string("created_at"),
            )
        }
        val characterTagIds = characterTags.mapTo(mutableSetOf()) { it.id }

        val characterTagLinks = tables.rows("character_tag_links").mapNotNull { row ->
            val characterId = row.positiveLong("character_id") ?: return@mapNotNull null
            val tagId = row.positiveLong("tag_id") ?: return@mapNotNull null
            if (characterId !in characterIds || tagId !in characterTagIds) return@mapNotNull null
            CharacterTagLinkEntity(characterId = characterId, tagId = tagId)
        }.distinct()

        val characterGroups = tables.rows("character_groups").mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            CharacterGroupEntity(
                id = id,
                name = row.string("name").orEmpty().ifBlank { "未命名角色组 #$id" },
                description = row.string("description"),
                coverUrl = row.string("cover_url"),
                communityId = row.string("community_id"),
                shareCode = row.string("share_code"),
                source = row.string("source").orEmpty().ifBlank { "local" },
                isPublic = row.boolean("is_public"),
                extraJson = row.string("extra_json"),
                createdAt = row.string("created_at"),
                updatedAt = row.string("updated_at"),
            )
        }
        val groupIds = characterGroups.mapTo(mutableSetOf()) { it.id }

        val groupCharacters = tables.rows("character_group_characters").mapNotNull { row ->
            val groupId = row.positiveLong("group_id") ?: return@mapNotNull null
            val characterId = row.positiveLong("character_id") ?: return@mapNotNull null
            if (groupId !in groupIds || characterId !in characterIds) return@mapNotNull null
            CharacterGroupCharacterEntity(
                groupId = groupId,
                characterId = characterId,
                roleName = row.string("role_name"),
                sortOrder = row.int("sort_order").orZero().coerceAtLeast(0),
            )
        }.distinct()

        val groupWorks = tables.rows("character_group_works").mapNotNull { row ->
            val groupId = row.positiveLong("group_id") ?: return@mapNotNull null
            val animeId = row.positiveLong("anime_id") ?: return@mapNotNull null
            if (groupId !in groupIds || animeId !in animeIds) return@mapNotNull null
            CharacterGroupWorkEntity(
                groupId = groupId,
                animeId = animeId,
                sortOrder = row.int("sort_order").orZero().coerceAtLeast(0),
            )
        }.distinct()

        return LegacyLibrarySnapshot(
            sourceSchemaVersion = sourceSchemaVersion,
            animes = animes,
            tags = tags,
            animeTags = animeTags,
            series = series,
            watchStatuses = watchStatuses,
            watchRecords = watchRecords,
            animeAnalysisRecords = analysisRecords,
            characters = characters,
            animeCharacters = animeCharacters,
            characterRelations = characterRelations,
            characterTags = characterTags,
            characterTagLinks = characterTagLinks,
            characterGroups = characterGroups,
            characterGroupCharacters = groupCharacters,
            characterGroupWorks = groupWorks,
        )
    }

    private fun buildWatchStatuses(
        rows: List<Map<String, Any?>>,
        animeStatuses: List<String>,
    ): List<WatchStatusEntity> {
        val imported = rows.mapNotNull { row ->
            val id = row.positiveLong("id") ?: return@mapNotNull null
            val name = normalizeStatus(row.string("name"))
            if (name.isBlank()) return@mapNotNull null
            WatchStatusEntity(
                id = id,
                name = name,
                color = row.long("color") ?: DEFAULT_STATUS_COLOR,
                sortOrder = row.int("sort_order").orZero().coerceAtLeast(0),
            )
        }.distinctBy { it.name }

        val result = if (imported.isEmpty()) defaultStatuses().toMutableList() else imported.toMutableList()
        var nextId = (result.maxOfOrNull { it.id } ?: 0L) + 1L
        animeStatuses.distinct().filter { status ->
            status.isNotBlank() && result.none { it.name == status }
        }.forEach { status ->
            result += WatchStatusEntity(
                id = nextId++,
                name = status,
                color = DEFAULT_STATUS_COLOR,
                sortOrder = result.size,
            )
        }
        return result.sortedBy { it.sortOrder }
    }

    private fun defaultStatuses(): List<WatchStatusEntity> = listOf(
        WatchStatusEntity(1, "在看", 0xFF2196F3, 0),
        WatchStatusEntity(2, "看完", 0xFF4CAF50, 1),
        WatchStatusEntity(3, "未看", 0xFFFF9800, 2),
        WatchStatusEntity(4, "弃坑", 0xFFF44336, 3),
    )

    internal fun normalizeStatus(value: String?): String {
        val status = value.orEmpty().trim()
        return MOJIBAKE_STATUS_FIXES[status] ?: status.ifBlank { "未看" }
    }

    private fun Map<String, List<Map<String, Any?>>>.rows(
        tableName: String,
    ): List<Map<String, Any?>> = get(tableName).orEmpty()

    private fun Map<String, Any?>.string(key: String): String? =
        get(key)?.toString()?.takeUnless { it == "null" }

    private fun Map<String, Any?>.long(key: String): Long? {
        val value = get(key) ?: return null
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            else -> value.toString().toLongOrNull()
        }
    }

    private fun Map<String, Any?>.positiveLong(key: String): Long? = long(key)?.takeIf { it > 0L }

    private fun Map<String, Any?>.int(key: String): Int? = long(key)
        ?.takeIf { it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong() }
        ?.toInt()

    private fun Map<String, Any?>.boolean(key: String): Boolean {
        val value = get(key) ?: return false
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().equals("true", ignoreCase = true) || value.toString() == "1"
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private const val DEFAULT_STATUS_COLOR = 0xFF9E9E9E

    private val MOJIBAKE_STATUS_FIXES = mapOf(
        "\u9366\u3127\u6E45" to "在看",
        "\u942A\u5B2A\u756C" to "看完",
        "\u93C8\uE046\u6E45" to "未看",
        "\u5BEE\u51A8\u6F59" to "弃坑",
        "\u934F\u3129\u503C" to "全部",
    )
}
