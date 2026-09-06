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
    val status: String = "未看",
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
    indices = [Index(value = ["bgmId"], unique = true)],
)
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bgmId: Long? = null,
    val name: String,
    val nameCn: String? = null,
    val imageUrl: String? = null,
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
    val note: String? = null,
    val strength: Int = 3,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

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
