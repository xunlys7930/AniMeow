package com.animeow.app.data.games

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

val gameStatuses = listOf("想玩", "在玩", "已玩", "搁置", "弃坑")

@Entity(tableName = "games", indices = [Index(value = ["syncId"], unique = true), Index(value = ["bangumiId"], unique = true), Index(value = ["vndbId"], unique = true)])
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val bangumiId: Long? = null,
    val vndbId: String? = null,
    val title: String = "",
    val originalTitle: String = "",
    val coverUrl: String = "",
    val synopsis: String = "",
    val releaseDate: String = "",
    val platforms: String = "",
    val developer: String = "",
    val publisher: String = "",
    val genre: String = "",
    val score: Double = 0.0,
    val status: String = "想玩",
    val playMinutes: Long = 0,
    val selectedPlatform: String = "",
    val startedOn: String = "",
    val finishedOn: String = "",
    val review: String = "",
    val rating: Int = 0,
    val updatedAt: String = Instant.now().toString(),
    val deletedAt: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "''") val tags: String = "",
)

@Dao
interface GameDao {
    @Query("SELECT * FROM games WHERE deletedAt IS NULL ORDER BY updatedAt DESC, id DESC")
    fun observe(): Flow<List<GameEntity>>
    @Query("SELECT * FROM games WHERE id = :id") suspend fun get(id: Long): GameEntity?
    @Query("SELECT * FROM games WHERE bangumiId = :id") suspend fun byBangumiId(id: Long): GameEntity?
    @Query("SELECT * FROM games WHERE vndbId = :id") suspend fun byVndbId(id: String): GameEntity?
    @Query("SELECT * FROM games WHERE deletedAt IS NULL ORDER BY updatedAt DESC") suspend fun all(): List<GameEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(game: GameEntity): Long
    @androidx.room.Update suspend fun update(game: GameEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enqueue(row: GamePendingSync)
    @Query("SELECT * FROM game_pending_sync WHERE ownerUserId = :owner AND bangumiUserId = :binding ORDER BY updatedAt")
    suspend fun pending(owner: Long, binding: Long): List<GamePendingSync>
    @Query("DELETE FROM game_pending_sync WHERE gameId = :id AND ownerUserId = :owner AND bangumiUserId = :binding AND updatedAt = :version")
    suspend fun acknowledge(id: Long, owner: Long, binding: Long, version: String)
    @Query("SELECT COUNT(*) FROM game_pending_sync") fun observePendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM game_pending_sync") suspend fun pendingCount(): Int
    @Query("UPDATE game_pending_sync SET updatedAt = :version WHERE gameId = :id AND gameSyncId = :syncId AND updatedAt = :previous")
    suspend fun rebasePending(id: Long, syncId: String, previous: String, version: String)
}

// Queue identity is device-local and deliberately excluded from portable backups.
@Entity(tableName = "game_pending_sync", primaryKeys = ["gameId", "ownerUserId", "bangumiUserId"])
data class GamePendingSync(
    val gameId: Long, val gameSyncId: String, val subjectId: Long, val ownerUserId: Long, val bangumiUserId: Long,
    val collectionType: Int?, val rate: Int?, val updatedAt: String,
)

enum class GameLayout(val label: String) {
    GRID("封面网格"), RECORD("继续记录"), LIST("紧凑清单");
    companion object { fun fromStorage(value: String?) = entries.firstOrNull { it.name == value } ?: GRID }
}

fun effectiveGameColumns(saved: Int, widthDp: Float, fontScale: Float): Int {
    val preferred = saved.coerceIn(2, 6)
    val minColWidth = (64f * fontScale.coerceAtLeast(1f)).coerceAtLeast(56f)
    val maxFit = ((widthDp + 10) / (minColWidth + 10)).toInt().coerceAtLeast(2)
    return preferred.coerceAtMost(maxFit)
}

fun gamePlayTime(minutes: Long): String = "${minutes.coerceAtLeast(0) / 60}小时 ${minutes.coerceAtLeast(0) % 60}分"
fun gameCollectionType(status: String): Int = gameStatuses.indexOf(status).let { index -> listOf(1, 3, 2, 4, 5).getOrElse(index) { 1 } }
fun gameStatusFromCollection(type: Int): String = when (type) { 2 -> "已玩"; 3 -> "在玩"; 4 -> "搁置"; 5 -> "弃坑"; else -> "想玩" }

internal fun gameSyncChanges(previous: GameEntity?, game: GameEntity, pending: GamePendingSync?, statusEnabled: Boolean, ratingEnabled: Boolean): Pair<Int?, Int?> =
    (if (statusEnabled && previous?.status != game.status) gameCollectionType(game.status) else pending?.collectionType) to
        (if (ratingEnabled && previous?.rating != game.rating && (previous != null || game.rating > 0)) game.rating else pending?.rate)

val GameEntity.catalogKey: String get() = bangumiId?.let { "bgm:$it" } ?: vndbId?.let { "vndb:$it" } ?: "local:$syncId"
internal val GameEntity.needsMetadata: Boolean get() = synopsis.isBlank() || coverUrl.isBlank() ||
    originalTitle.isBlank() || developer.isBlank() || releaseDate.isBlank()
val GameEntity.catalogSource: String get() = if (vndbId != null) "VNDB" else if (bangumiId != null) "Bangumi" else "手动记录"
