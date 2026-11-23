package com.animeow.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "animes",
    indices = [Index(value = ["externalSource", "externalId"], unique = true)],
)
data class AnimeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val coverUrl: String? = null,
    val status: String = "想看",
    val rating: Int? = null,
    val ratingGrade: String? = null,
    val funRatingTier: String? = null,
    val review: String? = null,
    val seriesId: Long? = null,
    val createdAt: String? = null,
    val airDate: String? = null,
    val studio: String? = null,
    val watchStartDate: String? = null,
    val watchFinishDate: String? = null,
    val watchedEpisodes: Int = 0,
    val totalEpisodes: Int = 0,
    val tvEpisodes: Int = 0,
    val spEpisodes: Int = 0,
    val subjectType: String = "anime",
    val reminderDay: Int? = null,
    val reminderTime: String? = null,
    val deletedAt: String? = null,
    val externalSource: String? = null,
    val externalId: String? = null,
    val externalUrl: String? = null,
    val originalTitle: String? = null,
    val synopsis: String? = null,
    val mediaFormat: String? = null,
    val broadcastDay: Int? = null,
    val broadcastTime: String? = null,
    val pinnedAt: Long? = null,
    val mergedSourcesJson: String? = null,
    val voiceActors: String? = null,
    val coverOrientation: String? = null,
    val statusChangedAt: Long? = null,
    val sourceRatingJson: String? = null,
    val bookReadVolumes: Int? = null,
    val bookReadChapters: Int? = null,
    val seriesRole: String? = null,
    val memo: String? = null,
    val updatedAt: Long? = null,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val color: Long? = null,
    val blocked: Boolean = false,
    val sortOrder: Int? = null,
)

@Entity(
    tableName = "anime_tags",
    primaryKeys = ["animeId", "tagId"],
    indices = [Index("tagId")],
)
data class AnimeTagEntity(
    val animeId: Long,
    val tagId: Long,
)

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val customCoverUrl: String? = null,
    val createdAt: String? = null,
    val linkedMediaJson: String? = null,
)

@Entity(
    tableName = "watch_statuses",
    indices = [Index(value = ["name"], unique = true)],
)
data class WatchStatusEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val color: Long,
    val sortOrder: Int,
)

@Entity(
    tableName = "watch_records",
    indices = [Index("animeId")],
)
data class WatchRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val animeId: Long,
    val episode: Int,
    val status: String? = null,
    val recordDate: String? = null,
)

data class LatestWatchRecord(val animeId: Long, val recordDate: String?, val id: Long)

@Entity(tableName = "anime_analysis_records")
data class AnimeAnalysisRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serverRecordId: Long? = null,
    val userId: Long? = null,
    val username: String? = null,
    val model: String? = null,
    val analysis: String? = null,
    val statsJson: String? = null,
    val createdAt: String? = null,
)

@Entity(
    tableName = "characters",
    indices = [Index(value = ["bgmId"], unique = true), Index(value = ["anilistId"], unique = true)],
)
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bgmId: Long? = null,
    val anilistId: Long? = null,
    val name: String,
    val nameCn: String? = null,
    val imageUrl: String? = null,
    val imageDisplayMode: String? = null,
    val summary: String? = null,
    val gender: String? = null,
    val birthYear: Int? = null,
    val birthMonth: Int? = null,
    val birthDay: Int? = null,
    val bloodType: String? = null,
    val infoboxJson: String? = null,
    val rating: Int? = null,
    val review: String? = null,
    val updatedAt: String? = null,
)

@Entity(
    tableName = "anime_characters",
    primaryKeys = ["animeId", "characterId"],
    indices = [Index("characterId")],
)
data class AnimeCharacterEntity(
    val animeId: Long,
    val characterId: Long,
    val roleName: String? = null,
    val sortOrder: Int = 0,
    val voiceActor: String? = null,
)

@Entity(
    tableName = "character_relations",
    indices = [Index("sourceCharacterId"), Index("targetCharacterId")],
)
data class CharacterRelationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceCharacterId: Long,
    val targetCharacterId: Long,
    val relationType: String = "关联",
    // NULL keeps legacy symmetric wording; empty text explicitly leaves this end unnamed.
    val sourceRole: String? = null,
    val targetRole: String? = null,
    val note: String? = null,
    val strength: Int = 3,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    fun roleOf(characterId: Long): String = when (characterId) {
        sourceCharacterId -> sourceRole ?: relationType
        targetCharacterId -> targetRole ?: relationType
        else -> error("角色不属于此关系")
    }
}

@Entity(
    tableName = "character_tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class CharacterTagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val color: Long? = null,
    val createdAt: String? = null,
)

@Entity(
    tableName = "character_tag_links",
    primaryKeys = ["characterId", "tagId"],
    indices = [Index("tagId")],
)
data class CharacterTagLinkEntity(
    val characterId: Long,
    val tagId: Long,
)

@Entity(tableName = "character_groups")
data class CharacterGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val communityId: String? = null,
    val shareCode: String? = null,
    val source: String = "local",
    val isPublic: Boolean = false,
    val extraJson: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Entity(
    tableName = "character_group_characters",
    primaryKeys = ["groupId", "characterId"],
    indices = [Index("characterId")],
)
data class CharacterGroupCharacterEntity(
    val groupId: Long,
    val characterId: Long,
    val roleName: String? = null,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "character_group_works",
    primaryKeys = ["groupId", "animeId"],
    indices = [Index("animeId")],
)
data class CharacterGroupWorkEntity(
    val groupId: Long,
    val animeId: Long,
    val sortOrder: Int = 0,
)

/**
 * Bangumi 双向同步 P0 的本地待同步队列（Local-First）。
 * 同一 (animeId, kind) 合并为一行：episodes 为增量（可相互抵消），status/rating 后写覆盖。
 */
@Entity(
    tableName = "bangumi_pending_sync",
    primaryKeys = ["animeId", "kind"],
    indices = [Index("subjectId")],
)
data class BangumiPendingSyncEntity(
    val animeId: Long,
    val subjectId: Long,
    /** episodes | status | rating，见 BangumiSyncKind */
    val kind: String,
    val payloadJson: String,
    val createdAt: String,
    val attempts: Int = 0,
)

@Entity(tableName = "app_logs", indices = [Index("timestamp")])
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val level: String = "operation",
    val message: String,
    @androidx.room.ColumnInfo(name = "stack_trace")
    val stackTrace: String? = null,
    val timestamp: String,
)

/**
 * 用户为单集补充的资料（标题、时长、简介、评价、评分）。
 *
 * `episode` 始终是本季集数（1 起），与 `watchedEpisodes`、`watch_records` 的存储含义一致；
 * 连续集号只在界面换算展示，不写入本表。`rating` 与作品评分同口径，存十分位（1..100）。
 */
@Entity(
    tableName = "episode_details",
    primaryKeys = ["animeId", "episode"],
)
data class EpisodeDetailEntity(
    val animeId: Long,
    val episode: Int,
    val title: String? = null,
    val durationMinutes: Int? = null,
    val synopsis: String? = null,
    val review: String? = null,
    val rating: Int? = null,
    val updatedAt: String? = null,
)

data class EpisodeSearchTitle(val animeId: Long, val episode: Int, val title: String)
