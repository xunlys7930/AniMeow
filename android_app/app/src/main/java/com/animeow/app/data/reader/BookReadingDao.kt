package com.animeow.app.data.reader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookReadingDao {
    @Query("SELECT * FROM book_reading_states WHERE animeId = :animeId LIMIT 1")
    fun observeState(animeId: Long): Flow<BookReadingStateEntity?>

    @Query("SELECT * FROM book_reading_states WHERE animeId = :animeId LIMIT 1")
    suspend fun getState(animeId: Long): BookReadingStateEntity?

    @Query("SELECT * FROM book_reading_states WHERE animeId IN (:animeIds)")
    suspend fun getStates(animeIds: List<Long>): List<BookReadingStateEntity>

    @Query("SELECT * FROM book_reading_states")
    suspend fun getAllStates(): List<BookReadingStateEntity>

    @Query("SELECT * FROM book_reading_states")
    fun observeAllStates(): Flow<List<BookReadingStateEntity>>

    @Query(
        """
        SELECT s.* FROM book_reading_states s
        INNER JOIN animes a ON a.id = s.animeId
        WHERE a.deletedAt IS NULL
          AND (s.lastReadAt IS NOT NULL OR s.legadoLastReadAt IS NOT NULL OR s.localFileUri IS NOT NULL OR s.legadoBookUrl IS NOT NULL)
        ORDER BY COALESCE(s.lastReadAt, s.legadoLastReadAt, s.updatedAt) DESC
        LIMIT :limit
        """,
    )
    fun observeRecentStates(limit: Int = 20): Flow<List<BookReadingStateEntity>>

    @Query("SELECT * FROM book_reading_states WHERE legadoBookUrl = :bookUrl LIMIT 1")
    suspend fun getStateByLegadoUrl(bookUrl: String): BookReadingStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: BookReadingStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStates(states: List<BookReadingStateEntity>)

    @Query("DELETE FROM book_reading_states WHERE animeId = :animeId")
    suspend fun deleteStateForAnime(animeId: Long)

    @Query("DELETE FROM book_reading_states WHERE animeId IN (:animeIds)")
    suspend fun deleteStatesForAnimes(animeIds: List<Long>)

    @Query("DELETE FROM book_reading_states WHERE animeId IN (SELECT id FROM animes WHERE deletedAt IS NOT NULL)")
    suspend fun deleteStatesForTrash()

    @Query("SELECT * FROM book_bookmarks WHERE animeId = :animeId ORDER BY chapterIndex ASC, blockIndex ASC, id ASC")
    fun observeBookmarks(animeId: Long): Flow<List<BookBookmarkEntity>>

    @Query("SELECT * FROM book_bookmarks WHERE animeId = :animeId ORDER BY chapterIndex ASC, blockIndex ASC, id ASC")
    suspend fun getBookmarks(animeId: Long): List<BookBookmarkEntity>

    @Query("SELECT * FROM book_bookmarks WHERE animeId IN (:animeIds)")
    suspend fun getBookmarksForAnimes(animeIds: List<Long>): List<BookBookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookBookmarkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarks(bookmarks: List<BookBookmarkEntity>)

    @Query("DELETE FROM book_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM book_bookmarks WHERE animeId = :animeId")
    suspend fun deleteBookmarksForAnime(animeId: Long)

    @Query("DELETE FROM book_bookmarks WHERE animeId IN (:animeIds)")
    suspend fun deleteBookmarksForAnimes(animeIds: List<Long>)

    @Query("DELETE FROM book_bookmarks WHERE animeId IN (SELECT id FROM animes WHERE deletedAt IS NOT NULL)")
    suspend fun deleteBookmarksForTrash()
}
