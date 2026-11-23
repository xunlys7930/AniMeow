package com.animeow.app.data

import androidx.room.*
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.util.withDataTransferLock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.combine

enum class MediaSeriesKind(val label: String, internal val workTable: String) {
    GAME("游戏", "games"), AUDIO("音声", "audio_works")
}

@Entity(tableName = "media_series", indices = [Index(value = ["syncId"], unique = true)])
data class MediaSeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val kind: String,
    val name: String,
    val updatedAt: String = Instant.now().toString(),
    val deletedAt: String? = null,
)

// A nullable membership is a portable detach record; works keep their own independent identity.
@Entity(tableName = "media_series_members", indices = [Index(value = ["kind", "workSyncId"], unique = true), Index("seriesId")],
    foreignKeys = [ForeignKey(entity = MediaSeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.SET_NULL)])
data class MediaSeriesMember(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val workSyncId: String,
    val seriesId: Long?,
    val sortOrder: Int,
    val updatedAt: String = Instant.now().toString(),
)

data class MediaSeriesWork(val syncId: String, val title: String, val updatedAt: String,
    val source: String? = null, val externalId: String? = null)
data class MediaSeriesSnapshot(val series: List<MediaSeriesEntity>, val members: List<MediaSeriesMember>)

fun mediaSeriesMembersVersion(members: List<MediaSeriesMember>): String {
    val text = members.sortedBy { it.id }.joinToString("") { member ->
        listOf(member.id, member.kind, member.workSyncId, member.seriesId, member.sortOrder, member.updatedAt)
            .joinToString("") { value -> value.toString().let { "${it.length}:$it" } }
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

@Dao
interface MediaSeriesDao {
    @Query("SELECT * FROM media_series WHERE kind = :kind AND deletedAt IS NULL ORDER BY name, id")
    fun observeSeries(kind: String): kotlinx.coroutines.flow.Flow<List<MediaSeriesEntity>>
    @Query("SELECT * FROM media_series_members WHERE kind = :kind ORDER BY sortOrder, id")
    fun observeMembers(kind: String): kotlinx.coroutines.flow.Flow<List<MediaSeriesMember>>
    @Query("SELECT * FROM media_series WHERE kind = :kind AND deletedAt IS NULL ORDER BY name, id")
    suspend fun series(kind: String): List<MediaSeriesEntity>
    @Query("SELECT * FROM media_series_members WHERE kind = :kind ORDER BY sortOrder, id")
    suspend fun members(kind: String): List<MediaSeriesMember>
    @Query("SELECT * FROM media_series WHERE id = :id") suspend fun get(id: Long): MediaSeriesEntity?
    @Insert suspend fun insert(series: MediaSeriesEntity): Long
    @Update suspend fun update(series: MediaSeriesEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(member: MediaSeriesMember)
}

class MediaSeriesRepository(private val database: AniMeowDatabase, private val kind: MediaSeriesKind) {
    private val dao = database.mediaSeriesDao()
    val snapshot = combine(dao.observeSeries(kind.name), dao.observeMembers(kind.name), ::MediaSeriesSnapshot)

    suspend fun save(previous: MediaSeriesEntity?, expectedMembersVersion: String,
        works: List<MediaSeriesWork>, name: String, orderedSyncIds: List<String>): Long = withDataTransferLock {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 100) { "系列名称请填写 1–100 字" }
        require(orderedSyncIds.distinct().size == orderedSyncIds.size) { "系列成员不能重复" }
        database.withTransaction {
            val members = checkedMembers(expectedMembersVersion)
            previous?.let { checkedSeries(it) }
            require(orderedSyncIds.all { sync -> works.any { it.syncId == sync } ||
                members.any { it.workSyncId == sync && previous != null && it.seriesId == previous.id } }) { "请选择已有作品" }
            require(dao.series(kind.name).none { it.id != previous?.id && it.name == cleanName }) { "已有同名系列，请使用其他名称" }
            val affected = members.filter { previous != null && it.seriesId == previous.id }.map { it.workSyncId }.toSet() + orderedSyncIds
            checkWorks(works, affected)
            val now = Instant.now().toString()
            val id = if (previous == null) dao.insert(MediaSeriesEntity(kind = kind.name, name = cleanName, updatedAt = now))
                else { dao.update(previous.copy(name = cleanName, updatedAt = now)); previous.id }
            val byWork = members.associateBy { it.workSyncId }
            members.filter { it.seriesId == id && it.workSyncId !in orderedSyncIds }.forEach {
                dao.put(it.copy(seriesId = null, sortOrder = 0, updatedAt = now))
            }
            orderedSyncIds.forEachIndexed { index, syncId ->
                val old = byWork[syncId]
                if (old?.seriesId != id || old.sortOrder != index) dao.put(MediaSeriesMember(
                    id = old?.id ?: 0, kind = kind.name, workSyncId = syncId, seriesId = id, sortOrder = index, updatedAt = now))
            }
            id
        }
    }

    suspend fun delete(series: MediaSeriesEntity, expectedMembersVersion: String) = withDataTransferLock {
        database.withTransaction {
            checkedSeries(series)
            val members = checkedMembers(expectedMembersVersion)
            val now = Instant.now().toString()
            members.filter { it.seriesId == series.id }.forEach { dao.put(it.copy(seriesId = null, sortOrder = 0, updatedAt = now)) }
            dao.update(series.copy(deletedAt = now, updatedAt = now))
        }
    }

    private suspend fun checkedSeries(series: MediaSeriesEntity) {
        check(series.kind == kind.name && dao.get(series.id) == series && series.deletedAt == null) { "系列已变化，请重新打开" }
    }
    private suspend fun checkedMembers(expected: String): List<MediaSeriesMember> {
        val current = dao.members(kind.name)
        check(mediaSeriesMembersVersion(current) == expected) { "系列归属已变化，请重新打开" }
        return current
    }
    private fun checkWorks(expected: List<MediaSeriesWork>, affected: Set<String>) {
        val versions = expected.associateBy { it.syncId }
        database.openHelper.writableDatabase.query("SELECT syncId, updatedAt FROM ${kind.workTable} WHERE deletedAt IS NULL").use { cursor ->
            val actual = mutableMapOf<String, String>()
            while (cursor.moveToNext()) actual[cursor.getString(0)] = cursor.getString(1)
            // Deleted members may stay hidden until their work is restored; never manufacture works.
            check(affected.all { sync -> versions[sync]?.let { actual[sync] == it.updatedAt } ?: (sync !in actual) }) { "作品记录已变化，请重新打开系列" }
            require(affected.filter { it in versions }.all { it in actual }) { "作品已不存在，请重新打开系列" }
        }
    }
}
