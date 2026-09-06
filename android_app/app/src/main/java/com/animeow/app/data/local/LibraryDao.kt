package com.animeow.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query(
        """
        SELECT * FROM animes
        WHERE deletedAt IS NULL
        ORDER BY
            CASE status
                WHEN '在看' THEN 0
                WHEN '想看' THEN 1
                WHEN '未看' THEN 2
                WHEN '看完' THEN 3
                ELSE 4
            END,
            createdAt DESC,
            id DESC
        """,
    )
    fun observeAnimes(): Flow<List<AnimeEntity>>

    @Query("SELECT * FROM animes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id DESC")
    fun observeDeletedAnimes(): Flow<List<AnimeEntity>>

    @Query("SELECT * FROM animes WHERE id = :id LIMIT 1")
    suspend fun getAnime(id: Long): AnimeEntity?

    @Query("SELECT * FROM animes WHERE id IN (:ids)")
    suspend fun getAnimes(ids: List<Long>): List<AnimeEntity>

    @Query("SELECT * FROM animes WHERE deletedAt IS NULL")
    suspend fun getActiveAnimes(): List<AnimeEntity>

    @Query("SELECT * FROM animes")
    suspend fun getAllAnimesIncludingDeleted(): List<AnimeEntity>

    @Query(
        "SELECT * FROM animes WHERE externalSource = :source AND externalId = :externalId " +
            "AND deletedAt IS NULL LIMIT 1",
    )
    suspend fun getAnimeByExternalId(source: String, externalId: String): AnimeEntity?

    @Query(
        "SELECT * FROM animes WHERE externalSource = :source AND externalId = :externalId LIMIT 1",
    )
    suspend fun getAnimeByExternalIdIncludingDeleted(source: String, externalId: String): AnimeEntity?

    @Query("SELECT * FROM animes WHERE id = :id LIMIT 1")
    fun observeAnime(id: Long): Flow<AnimeEntity?>

    @Query("SELECT * FROM tags WHERE blocked = 0 ORDER BY name COLLATE NOCASE")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE blocked = 0 ORDER BY name COLLATE NOCASE")
    suspend fun getTags(): List<TagEntity>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    suspend fun getAllTags(): List<TagEntity>

    @Query("UPDATE tags SET blocked = :blocked WHERE id = :tagId")
    suspend fun setTagBlocked(tagId: Long, blocked: Boolean)

    @Query("SELECT * FROM watch_statuses ORDER BY sortOrder, id")
    fun observeWatchStatuses(): Flow<List<WatchStatusEntity>>

    @Query("SELECT * FROM watch_statuses ORDER BY sortOrder, id")
    suspend fun getWatchStatuses(): List<WatchStatusEntity>

    @Query("SELECT * FROM series ORDER BY name COLLATE NOCASE")
    fun observeSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :seriesId LIMIT 1")
    suspend fun getSeries(seriesId: Long): SeriesEntity?

    @Query("SELECT tagId FROM anime_tags WHERE animeId = :animeId")
    suspend fun getTagIdsForAnime(animeId: Long): List<Long>

    @Query("SELECT * FROM anime_tags")
    fun observeAnimeTags(): Flow<List<AnimeTagEntity>>

    @Query("SELECT * FROM anime_tags")
    suspend fun getAnimeTags(): List<AnimeTagEntity>

    @Query("SELECT * FROM anime_tags WHERE animeId = :animeId")
    suspend fun getAnimeTagsByAnimeId(animeId: Long): List<AnimeTagEntity>

    @Query("UPDATE animes SET watchedEpisodes = :progress WHERE id = :id")
    suspend fun updateAnimeProgress(id: Long, progress: Int)

    @Query("UPDATE animes SET reminderDay = NULL, reminderTime = NULL WHERE id = :animeId")
    suspend fun clearAnimeReminder(animeId: Long)

    @Query("UPDATE animes SET funRatingTier = :tier WHERE id = :animeId")
    suspend fun updateAnimeFunTier(animeId: Long, tier: String?)

    @Query("UPDATE animes SET funRatingTier = NULL WHERE deletedAt IS NULL AND subjectType = 'anime'")
    suspend fun clearAnimeFunTiers()

    @Query("SELECT COUNT(*) FROM animes WHERE deletedAt IS NULL")
    suspend fun animeCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiagnosticLogs(items: List<DiagnosticLogEntity>)

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getDiagnosticLogs(limit: Int = 500): List<DiagnosticLogEntity>

    @Query("DELETE FROM app_logs")
    suspend fun clearDiagnosticLogs()

    @Query("DELETE FROM app_logs WHERE timestamp < :cutoff")
    suspend fun clearDiagnosticLogsBefore(cutoff: String)

    @Query(
        "DELETE FROM app_logs WHERE id NOT IN (" +
            "SELECT id FROM app_logs ORDER BY timestamp DESC, id DESC LIMIT :limit)",
    )
    suspend fun pruneDiagnosticLogs(limit: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnime(item: AnimeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimes(items: List<AnimeEntity>)

    @Update
    suspend fun updateAnime(item: AnimeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(items: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(item: TagEntity): Long

    @Update
    suspend fun updateTag(item: TagEntity)

    @Query("SELECT * FROM tags WHERE id = :tagId LIMIT 1")
    suspend fun getTag(tagId: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Query(
        "INSERT OR IGNORE INTO anime_tags (animeId, tagId) " +
            "SELECT animeId, :targetTagId FROM anime_tags WHERE tagId = :sourceTagId",
    )
    suspend fun copyAnimeTagLinks(targetTagId: Long, sourceTagId: Long)

    @Query("DELETE FROM anime_tags WHERE tagId = :tagId")
    suspend fun clearAnimeTagsForTag(tagId: Long)

    @Query("SELECT animeId FROM anime_tags WHERE tagId = :tagId")
    suspend fun getAnimeIdsForTag(tagId: Long): List<Long>

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimeTags(items: List<AnimeTagEntity>)

    @Query("DELETE FROM anime_tags WHERE animeId = :animeId")
    suspend fun clearAnimeTagsForAnime(animeId: Long)

    @Query("DELETE FROM anime_tags WHERE animeId IN (:animeIds) AND tagId = :tagId")
    suspend fun removeTagFromAnimes(animeIds: List<Long>, tagId: Long)

    @Query("DELETE FROM watch_records WHERE animeId = :animeId")
    suspend fun clearWatchRecordsForAnime(animeId: Long)

    @Query("DELETE FROM anime_characters WHERE animeId = :animeId")
    suspend fun clearAnimeCharactersForAnime(animeId: Long)

    @Query("DELETE FROM character_group_works WHERE animeId = :animeId")
    suspend fun clearCharacterGroupWorksForAnime(animeId: Long)

    @Query("DELETE FROM animes WHERE id = :animeId")
    suspend fun deleteAnime(animeId: Long)

    @Query("UPDATE animes SET deletedAt = :deletedAt WHERE id = :animeId")
    suspend fun moveAnimeToTrash(animeId: Long, deletedAt: String)

    @Query("UPDATE animes SET deletedAt = NULL WHERE id = :animeId")
    suspend fun restoreAnime(animeId: Long)

    @Query(
        """
        UPDATE animes
        SET status = :status,
            watchedEpisodes = CASE
                WHEN TRIM(:status) = '看完' AND totalEpisodes > 0 THEN totalEpisodes
                ELSE watchedEpisodes
            END
        WHERE id IN (:animeIds)
        """,
    )
    suspend fun updateAnimeStatuses(animeIds: List<Long>, status: String)

    @Query("DELETE FROM anime_tags WHERE animeId IN (:animeIds)")
    suspend fun clearAnimeTagsForAnimes(animeIds: List<Long>)

    @Query("DELETE FROM watch_records WHERE animeId IN (:animeIds)")
    suspend fun clearWatchRecordsForAnimes(animeIds: List<Long>)

    @Query("DELETE FROM anime_characters WHERE animeId IN (:animeIds)")
    suspend fun clearAnimeCharactersForAnimes(animeIds: List<Long>)

    @Query("DELETE FROM character_group_works WHERE animeId IN (:animeIds)")
    suspend fun clearCharacterGroupWorksForAnimes(animeIds: List<Long>)

    @Query("DELETE FROM animes WHERE id IN (:animeIds)")
    suspend fun deleteAnimes(animeIds: List<Long>)

    @Query("UPDATE animes SET deletedAt = :deletedAt WHERE id IN (:animeIds)")
    suspend fun moveAnimesToTrash(animeIds: List<Long>, deletedAt: String)

    @Query("UPDATE animes SET synopsis = review, review = synopsis WHERE id = :animeId")
    suspend fun swapSynopsisAndReview(animeId: Long)

    @Query("UPDATE animes SET synopsis = review, review = synopsis WHERE id IN (:animeIds)")
    suspend fun swapSynopsisAndReviewBatch(animeIds: List<Long>)

    @Query(
        """
        INSERT OR IGNORE INTO anime_tags (animeId, tagId)
        SELECT :targetId, tagId FROM anime_tags WHERE animeId IN (:sourceIds)
        """,
    )
    suspend fun copyAnimeTags(targetId: Long, sourceIds: List<Long>)

    @Query(
        """
        INSERT OR IGNORE INTO anime_characters (animeId, characterId, roleName, sortOrder)
        SELECT :targetId, characterId, roleName, sortOrder
        FROM anime_characters WHERE animeId IN (:sourceIds)
        """,
    )
    suspend fun copyAnimeCharacters(targetId: Long, sourceIds: List<Long>)

    @Query(
        """
        INSERT OR IGNORE INTO character_group_works (groupId, animeId, sortOrder)
        SELECT groupId, :targetId, sortOrder
        FROM character_group_works WHERE animeId IN (:sourceIds)
        """,
    )
    suspend fun copyCharacterGroupWorks(targetId: Long, sourceIds: List<Long>)

    @Query(
        """
        INSERT INTO watch_records (animeId, episode, status, recordDate)
        SELECT :targetId, source.episode, source.status, source.recordDate
        FROM watch_records AS source
        WHERE source.animeId IN (:sourceIds)
          AND NOT EXISTS (
              SELECT 1 FROM watch_records AS target
              WHERE target.animeId = :targetId
                AND target.episode = source.episode
                AND IFNULL(target.recordDate, '') = IFNULL(source.recordDate, '')
          )
        """,
    )
    suspend fun copyWatchRecords(targetId: Long, sourceIds: List<Long>)

    @Query("DELETE FROM anime_tags WHERE animeId IN (SELECT id FROM animes WHERE deletedAt IS NOT NULL)")
    suspend fun clearAnimeTagsForTrash()

    @Query("DELETE FROM watch_records WHERE animeId IN (SELECT id FROM animes WHERE deletedAt IS NOT NULL)")
    suspend fun clearWatchRecordsForTrash()

    @Query("DELETE FROM anime_characters WHERE animeId IN (SELECT id FROM animes WHERE deletedAt IS NOT NULL)")
    suspend fun clearAnimeCharactersForTrash()

    @Query("DELETE FROM character_group_works WHERE animeId IN (SELECT id FROM animes WHERE deletedAt IS NOT NULL)")
    suspend fun clearCharacterGroupWorksForTrash()

    @Query("DELETE FROM animes WHERE deletedAt IS NOT NULL")
    suspend fun deleteTrashAnimes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(items: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSeriesItem(item: SeriesEntity): Long

    @Update
    suspend fun updateSeriesItem(item: SeriesEntity)

    @Query("UPDATE animes SET seriesId = NULL WHERE seriesId = :seriesId")
    suspend fun clearSeriesFromAnimes(seriesId: Long)

    @Query("UPDATE animes SET seriesId = :seriesId WHERE id IN (:animeIds)")
    suspend fun assignSeriesToAnimes(animeIds: List<Long>, seriesId: Long)

    @Query("UPDATE animes SET seriesId = NULL WHERE id IN (:animeIds)")
    suspend fun clearSeriesFromAnimes(animeIds: List<Long>)

    @Query("DELETE FROM series WHERE id = :seriesId")
    suspend fun deleteSeries(seriesId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchStatuses(items: List<WatchStatusEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWatchStatus(item: WatchStatusEntity): Long

    @Update
    suspend fun updateWatchStatus(item: WatchStatusEntity)

    @Query("SELECT * FROM watch_statuses WHERE id = :statusId LIMIT 1")
    suspend fun getWatchStatus(statusId: Long): WatchStatusEntity?

    @Query("SELECT * FROM watch_statuses WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getWatchStatusByName(name: String): WatchStatusEntity?

    @Query("UPDATE animes SET status = :newName WHERE status = :oldName")
    suspend fun renameAnimeStatus(oldName: String, newName: String)

    @Query("SELECT COUNT(*) FROM animes WHERE status = :statusName")
    suspend fun countAnimesUsingStatus(statusName: String): Int

    @Query("DELETE FROM watch_statuses WHERE id = :statusId")
    suspend fun deleteWatchStatus(statusId: Long)

    @Query("UPDATE watch_statuses SET sortOrder = :sortOrder WHERE id = :statusId")
    suspend fun updateWatchStatusSortOrder(statusId: Long, sortOrder: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchRecords(items: List<WatchRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWatchRecord(item: WatchRecordEntity): Long

    @Query("SELECT * FROM watch_records ORDER BY recordDate DESC, id DESC")
    fun observeWatchRecords(): Flow<List<WatchRecordEntity>>

    @Query("SELECT * FROM watch_records ORDER BY recordDate DESC, id DESC")
    suspend fun getWatchRecords(): List<WatchRecordEntity>

    @Query("DELETE FROM watch_records WHERE id = :recordId")
    suspend fun deleteWatchRecord(recordId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimeAnalysisRecords(items: List<AnimeAnalysisRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnimeAnalysisRecord(item: AnimeAnalysisRecordEntity): Long

    @Query("SELECT * FROM anime_analysis_records ORDER BY createdAt DESC, id DESC")
    fun observeAnimeAnalysisRecords(): Flow<List<AnimeAnalysisRecordEntity>>

    @Query("DELETE FROM anime_analysis_records WHERE id = :recordId")
    suspend fun deleteAnimeAnalysisRecord(recordId: Long)

    @Query(
        """
        SELECT c.*,
               COUNT(DISTINCT ac.animeId) AS workCount,
               COUNT(DISTINCT cr.id) AS relationCount,
               COUNT(DISTINCT ctl.tagId) AS tagCount
        FROM characters c
        LEFT JOIN anime_characters ac ON ac.characterId = c.id
        LEFT JOIN character_relations cr
          ON cr.sourceCharacterId = c.id OR cr.targetCharacterId = c.id
        LEFT JOIN character_tag_links ctl ON ctl.characterId = c.id
        GROUP BY c.id
        ORDER BY COALESCE(NULLIF(c.nameCn, ''), c.name) COLLATE NOCASE ASC
        """,
    )
    fun observeCharacterItems(): Flow<List<CharacterListItem>>

    @Query(
        """
        SELECT c.*,
               COUNT(DISTINCT ac.animeId) AS workCount,
               COUNT(DISTINCT cr.id) AS relationCount,
               COUNT(DISTINCT ctl.tagId) AS tagCount
        FROM characters c
        LEFT JOIN anime_characters ac ON ac.characterId = c.id
        LEFT JOIN character_relations cr
          ON cr.sourceCharacterId = c.id OR cr.targetCharacterId = c.id
        LEFT JOIN character_tag_links ctl ON ctl.characterId = c.id
        GROUP BY c.id
        ORDER BY COALESCE(NULLIF(c.nameCn, ''), c.name) COLLATE NOCASE ASC
        """,
    )
    suspend fun getCharacterItems(): List<CharacterListItem>

    @Query(
        """
        SELECT c.*,
               COUNT(DISTINCT ac.animeId) AS workCount,
               COUNT(DISTINCT cr.id) AS relationCount,
               COUNT(DISTINCT ctl.tagId) AS tagCount
        FROM characters c
        LEFT JOIN anime_characters ac ON ac.characterId = c.id
        LEFT JOIN character_relations cr
          ON cr.sourceCharacterId = c.id OR cr.targetCharacterId = c.id
        LEFT JOIN character_tag_links ctl ON ctl.characterId = c.id
        WHERE c.id = :characterId
        GROUP BY c.id
        LIMIT 1
        """,
    )
    fun observeCharacterItem(characterId: Long): Flow<CharacterListItem?>

    @Query("SELECT * FROM characters WHERE id = :characterId LIMIT 1")
    suspend fun getCharacter(characterId: Long): CharacterEntity?

    @Query("SELECT * FROM characters WHERE bgmId = :bgmId LIMIT 1")
    suspend fun getCharacterByBgmId(bgmId: Long): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCharacter(item: CharacterEntity): Long

    @Update
    suspend fun updateCharacter(item: CharacterEntity)

    @Query("DELETE FROM anime_characters WHERE characterId = :characterId")
    suspend fun clearAnimeCharactersForCharacter(characterId: Long)

    @Query("DELETE FROM character_tag_links WHERE characterId = :characterId")
    suspend fun clearCharacterTagLinksForCharacter(characterId: Long)

    @Query(
        "DELETE FROM character_relations " +
            "WHERE sourceCharacterId = :characterId OR targetCharacterId = :characterId",
    )
    suspend fun clearCharacterRelationsForCharacter(characterId: Long)

    @Query("DELETE FROM character_group_characters WHERE characterId = :characterId")
    suspend fun clearCharacterGroupLinksForCharacter(characterId: Long)

    @Query("DELETE FROM characters WHERE id = :characterId")
    suspend fun deleteCharacter(characterId: Long)

    @Query("SELECT * FROM characters WHERE id IN (:characterIds)")
    suspend fun getCharactersByIds(characterIds: List<Long>): List<CharacterEntity>

    @Query(
        """
        INSERT OR IGNORE INTO anime_characters (animeId, characterId, roleName, sortOrder)
        SELECT animeId, :targetCharacterId, roleName, sortOrder
        FROM anime_characters
        WHERE characterId IN (:sourceCharacterIds)
        """,
    )
    suspend fun copyWorksToCharacter(targetCharacterId: Long, sourceCharacterIds: List<Long>)

    @Query(
        """
        INSERT OR IGNORE INTO character_tag_links (characterId, tagId)
        SELECT :targetCharacterId, tagId
        FROM character_tag_links
        WHERE characterId IN (:sourceCharacterIds)
        """,
    )
    suspend fun copyTagsToCharacter(targetCharacterId: Long, sourceCharacterIds: List<Long>)

    @Query(
        """
        INSERT OR IGNORE INTO character_group_characters (groupId, characterId, roleName, sortOrder)
        SELECT groupId, :targetCharacterId, roleName, sortOrder
        FROM character_group_characters
        WHERE characterId IN (:sourceCharacterIds)
        """,
    )
    suspend fun copyGroupsToCharacter(targetCharacterId: Long, sourceCharacterIds: List<Long>)

    @Query(
        """
        SELECT * FROM character_relations
        WHERE sourceCharacterId IN (:characterIds) OR targetCharacterId IN (:characterIds)
        """,
    )
    suspend fun getRelationsForCharacters(characterIds: List<Long>): List<CharacterRelationEntity>

    @Query("SELECT * FROM character_tags ORDER BY name COLLATE NOCASE ASC")
    fun observeCharacterTags(): Flow<List<CharacterTagEntity>>

    @Query("SELECT * FROM character_tag_links")
    fun observeCharacterTagLinks(): Flow<List<CharacterTagLinkEntity>>

    @Query(
        """
        SELECT ct.*, COUNT(ctl.characterId) AS characterCount
        FROM character_tags ct
        LEFT JOIN character_tag_links ctl ON ctl.tagId = ct.id
        GROUP BY ct.id
        ORDER BY ct.name COLLATE NOCASE ASC
        """,
    )
    fun observeCharacterTagSummaries(): Flow<List<CharacterTagSummary>>

    @Query(
        """
        SELECT ct.*
        FROM character_tag_links ctl
        JOIN character_tags ct ON ct.id = ctl.tagId
        WHERE ctl.characterId = :characterId
        ORDER BY ct.name COLLATE NOCASE ASC
        """,
    )
    fun observeTagsForCharacter(characterId: Long): Flow<List<CharacterTagEntity>>

    @Query("SELECT * FROM character_tags WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getCharacterTagByName(name: String): CharacterTagEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCharacterTag(item: CharacterTagEntity): Long

    @Query("DELETE FROM character_tag_links WHERE characterId = :characterId")
    suspend fun clearTagsForCharacter(characterId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCharacterTagLink(item: CharacterTagLinkEntity)

    @Query("DELETE FROM character_tag_links WHERE tagId = :tagId")
    suspend fun clearCharacterTagLinksForTag(tagId: Long)

    @Query("DELETE FROM character_tags WHERE id = :tagId")
    suspend fun deleteCharacterTag(tagId: Long)

    @Query(
        """
        SELECT c.*, ac.roleName, ac.sortOrder AS memberSortOrder
        FROM anime_characters ac
        JOIN characters c ON c.id = ac.characterId
        WHERE ac.animeId = :animeId
        ORDER BY ac.sortOrder ASC, COALESCE(NULLIF(c.nameCn, ''), c.name) COLLATE NOCASE ASC
        """,
    )
    fun observeCharactersForAnime(animeId: Long): Flow<List<CharacterWithRole>>

    @Query(
        """
        SELECT a.*, ac.roleName, ac.sortOrder AS memberSortOrder
        FROM anime_characters ac
        JOIN animes a ON a.id = ac.animeId
        WHERE ac.characterId = :characterId
        ORDER BY ac.sortOrder ASC, a.title COLLATE NOCASE ASC
        """,
    )
    fun observeWorksForCharacter(characterId: Long): Flow<List<CharacterWorkItem>>

    @Query(
        """
        SELECT a.*, ac.roleName, ac.sortOrder AS memberSortOrder
        FROM anime_characters ac
        JOIN animes a ON a.id = ac.animeId
        WHERE ac.characterId = :fromCharacterId
          AND ac.animeId NOT IN (
              SELECT animeId FROM anime_characters WHERE characterId = :missingFromCharacterId
          )
        ORDER BY ac.sortOrder ASC, a.title COLLATE NOCASE ASC
        """,
    )
    suspend fun getWorksMissingFromCharacter(
        fromCharacterId: Long,
        missingFromCharacterId: Long,
    ): List<CharacterWorkItem>

    @Query(
        """
        SELECT * FROM animes
        WHERE deletedAt IS NULL
          AND id NOT IN (
              SELECT animeId FROM anime_characters WHERE characterId = :characterId
          )
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    suspend fun getLinkableWorks(characterId: Long): List<AnimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimeCharacter(item: AnimeCharacterEntity)

    @Query("DELETE FROM anime_characters WHERE animeId = :animeId AND characterId = :characterId")
    suspend fun deleteAnimeCharacter(animeId: Long, characterId: Long)

    @Query(
        """
        SELECT cr.*,
               other.id AS relatedCharacterId,
               other.bgmId AS relatedBgmId,
               other.name AS relatedName,
               other.nameCn AS relatedNameCn,
               other.imageUrl AS relatedImageUrl,
               other.gender AS relatedGender,
               other.summary AS relatedSummary,
               other.rating AS relatedRating
        FROM character_relations cr
        JOIN characters other
          ON other.id = CASE
              WHEN cr.sourceCharacterId = :characterId THEN cr.targetCharacterId
              ELSE cr.sourceCharacterId
          END
        WHERE cr.sourceCharacterId = :characterId OR cr.targetCharacterId = :characterId
        ORDER BY cr.updatedAt DESC, cr.id DESC
        """,
    )
    fun observeRelationsForCharacter(characterId: Long): Flow<List<CharacterRelationItem>>

    @Query(
        """
        SELECT * FROM character_relations
        WHERE sourceCharacterId = :firstId AND targetCharacterId = :secondId
        LIMIT 1
        """,
    )
    suspend fun getCharacterRelation(firstId: Long, secondId: Long): CharacterRelationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCharacterRelation(item: CharacterRelationEntity): Long

    @Update
    suspend fun updateCharacterRelation(item: CharacterRelationEntity)

    @Query("DELETE FROM character_relations WHERE id = :relationId")
    suspend fun deleteCharacterRelation(relationId: Long)

    @Query(
        """
        SELECT cg.*,
               COUNT(DISTINCT cgc.characterId) AS characterCount,
               COUNT(DISTINCT cgw.animeId) AS workCount
        FROM character_groups cg
        LEFT JOIN character_group_characters cgc ON cgc.groupId = cg.id
        LEFT JOIN character_group_works cgw ON cgw.groupId = cg.id
        GROUP BY cg.id
        ORDER BY cg.updatedAt DESC, cg.createdAt DESC, cg.id DESC
        """,
    )
    fun observeCharacterGroupSummaries(): Flow<List<CharacterGroupSummary>>

    @Query("SELECT * FROM character_groups WHERE id = :groupId LIMIT 1")
    fun observeCharacterGroup(groupId: Long): Flow<CharacterGroupEntity?>

    @Query("SELECT * FROM character_groups WHERE id = :groupId LIMIT 1")
    suspend fun getCharacterGroup(groupId: Long): CharacterGroupEntity?

    @Query("SELECT * FROM character_groups WHERE communityId = :communityId LIMIT 1")
    suspend fun getCharacterGroupByCommunityId(communityId: String): CharacterGroupEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCharacterGroup(item: CharacterGroupEntity): Long

    @Update
    suspend fun updateCharacterGroup(item: CharacterGroupEntity)

    @Query(
        """
        SELECT c.*, cgc.roleName, cgc.sortOrder AS memberSortOrder
        FROM character_group_characters cgc
        JOIN characters c ON c.id = cgc.characterId
        WHERE cgc.groupId = :groupId
        ORDER BY cgc.sortOrder ASC, COALESCE(NULLIF(c.nameCn, ''), c.name) COLLATE NOCASE ASC
        """,
    )
    fun observeCharacterGroupMembers(groupId: Long): Flow<List<CharacterGroupMember>>

    @Query(
        """
        SELECT c.*, cgc.roleName, cgc.sortOrder AS memberSortOrder
        FROM character_group_characters cgc
        JOIN characters c ON c.id = cgc.characterId
        WHERE cgc.groupId = :groupId
        ORDER BY cgc.sortOrder ASC, COALESCE(NULLIF(c.nameCn, ''), c.name) COLLATE NOCASE ASC
        """,
    )
    suspend fun getCharacterGroupMembers(groupId: Long): List<CharacterGroupMember>

    @Query(
        """
        SELECT a.*, cgw.sortOrder AS memberSortOrder
        FROM character_group_works cgw
        JOIN animes a ON a.id = cgw.animeId
        WHERE cgw.groupId = :groupId
        ORDER BY cgw.sortOrder ASC, a.title COLLATE NOCASE ASC
        """,
    )
    fun observeCharacterGroupWorks(groupId: Long): Flow<List<CharacterGroupWork>>

    @Query(
        """
        SELECT a.*, cgw.sortOrder AS memberSortOrder
        FROM character_group_works cgw
        JOIN animes a ON a.id = cgw.animeId
        WHERE cgw.groupId = :groupId
        ORDER BY cgw.sortOrder ASC, a.title COLLATE NOCASE ASC
        """,
    )
    suspend fun getCharacterGroupWorks(groupId: Long): List<CharacterGroupWork>

    @Query("DELETE FROM character_group_characters WHERE groupId = :groupId")
    suspend fun clearCharactersForGroup(groupId: Long)

    @Query("DELETE FROM character_group_works WHERE groupId = :groupId")
    suspend fun clearWorksForGroup(groupId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterGroupCharacter(item: CharacterGroupCharacterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterGroupWork(item: CharacterGroupWorkEntity)

    @Query("DELETE FROM character_groups WHERE id = :groupId")
    suspend fun deleteCharacterGroup(groupId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacters(items: List<CharacterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimeCharacters(items: List<AnimeCharacterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterRelations(items: List<CharacterRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterTags(items: List<CharacterTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterTagLinks(items: List<CharacterTagLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterGroups(items: List<CharacterGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterGroupCharacters(items: List<CharacterGroupCharacterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterGroupWorks(items: List<CharacterGroupWorkEntity>)

    @Query("DELETE FROM character_group_works")
    suspend fun clearCharacterGroupWorks()

    @Query("DELETE FROM character_group_characters")
    suspend fun clearCharacterGroupCharacters()

    @Query("DELETE FROM character_groups")
    suspend fun clearCharacterGroups()

    @Query("DELETE FROM character_tag_links")
    suspend fun clearCharacterTagLinks()

    @Query("DELETE FROM character_tags")
    suspend fun clearCharacterTags()

    @Query("DELETE FROM character_relations")
    suspend fun clearCharacterRelations()

    @Query("DELETE FROM anime_characters")
    suspend fun clearAnimeCharacters()

    @Query("DELETE FROM characters")
    suspend fun clearCharacters()

    @Query("DELETE FROM anime_analysis_records")
    suspend fun clearAnimeAnalysisRecords()

    @Query("DELETE FROM watch_records")
    suspend fun clearWatchRecords()

    @Query("DELETE FROM anime_tags")
    suspend fun clearAnimeTags()

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    @Query("DELETE FROM animes")
    suspend fun clearAnimes()

    @Query("DELETE FROM series")
    suspend fun clearSeries()

    @Query("DELETE FROM watch_statuses")
    suspend fun clearWatchStatuses()
}
