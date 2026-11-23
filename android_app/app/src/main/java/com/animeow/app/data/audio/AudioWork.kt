package com.animeow.app.data.audio

import androidx.room.*
import androidx.room.withTransaction
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.util.withDataTransferLock
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow

val audioStatuses = listOf("想听", "在听", "已听", "搁置", "弃坑")
val audioCategories = listOf("ASMR", "情景音声", "广播剧", "其他")

@Entity(tableName = "audio_works", indices = [Index(value = ["syncId"], unique = true)])
data class AudioWork(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val title: String = "",
    val coverUrl: String = "",
    val category: String = "其他",
    val voiceActors: String = "",
    val circle: String = "",
    val releaseDate: String = "",
    val durationSeconds: Long = 0,
    val workCode: String = "",
    val sourceUrl: String = "",
    val status: String = "想听",
    val listenedSeconds: Long = 0,
    val rating: Int = 0,
    val review: String = "",
    val tags: String = "",
    val updatedAt: String = Instant.now().toString(),
    val deletedAt: String? = null,
    @ColumnInfo(defaultValue = "''") val originalTitle: String = "",
)

@Dao
interface AudioWorkDao {
    @Query("SELECT * FROM audio_works WHERE deletedAt IS NULL ORDER BY updatedAt DESC, id DESC")
    fun observe(): Flow<List<AudioWork>>
    @Query("SELECT * FROM audio_works WHERE id = :id") suspend fun get(id: Long): AudioWork?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(work: AudioWork): Long
    @Update suspend fun update(work: AudioWork)
}

internal fun validAudioLink(value: String): Boolean = value.isBlank() || runCatching {
    val uri = URI(value)
    uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

internal fun normalizeAudioWork(work: AudioWork): AudioWork {
    val clean = work.copy(title = work.title.trim(), originalTitle = work.originalTitle.trim(), coverUrl = work.coverUrl.trim(), sourceUrl = work.sourceUrl.trim(),
        voiceActors = work.voiceActors.trim(), circle = work.circle.trim(), releaseDate = work.releaseDate.trim(),
        workCode = work.workCode.trim(), tags = work.tags.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct().joinToString("，"))
    require(clean.title.isNotBlank() && clean.title.length <= 200) { "请填写 200 字以内的作品名称" }
    require(clean.status in audioStatuses && clean.category in audioCategories) { "请选择有效的分类和收听状态" }
    require(clean.durationSeconds in 0..31_536_000 && clean.listenedSeconds in 0..31_536_000) { "时长超出允许范围" }
    require(clean.durationSeconds == 0L || clean.listenedSeconds <= clean.durationSeconds) { "已听时长不能超过作品总时长" }
    require(clean.rating in 0..10) { "评分应为 0–10，0 表示未评分" }
    require(clean.releaseDate.isBlank() || runCatching { LocalDate.parse(clean.releaseDate) }.isSuccess) { "发行日期请填写 YYYY-MM-DD" }
    require(validAudioLink(clean.sourceUrl) && validAudioLink(clean.coverUrl)) { "原站和封面链接请使用有效的 HTTP 或 HTTPS 地址" }
    require(clean.sourceUrl.length <= 2048 && clean.coverUrl.length <= 2048 && clean.review.length <= 10_000 &&
        clean.originalTitle.length <= 500 && clean.tags.length <= 1024 && clean.voiceActors.length <= 500 && clean.circle.length <= 200 && clean.workCode.length <= 100) { "填写的资料过长，请适当精简" }
    return clean
}

/** Blank means unknown; a bare number means minutes, with optional :seconds. */
internal fun parseAudioTime(text: String): Long? {
    if (text.isBlank()) return 0
    val match = Regex("^([0-9]{1,6})(?::([0-5][0-9]))?$").matchEntire(text.trim()) ?: return null
    val seconds = match.groupValues[1].toLong() * 60 + match.groupValues[2].ifBlank { "0" }.toLong()
    return seconds.takeIf { it <= 31_536_000 }
}

fun audioTime(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

internal fun applyAudioMetadata(work: AudioWork, metadata: com.animeow.app.data.remote.AudioMetadata, replace: Boolean): AudioWork {
    fun field(existing: String, incoming: String) = if (incoming.isNotBlank() && (replace || existing.isBlank())) incoming else existing
    return work.copy(title = field(work.title, metadata.title), originalTitle = field(work.originalTitle, metadata.originalTitle), coverUrl = field(work.coverUrl, metadata.coverUrl),
        voiceActors = field(work.voiceActors, metadata.voiceActors), circle = field(work.circle, metadata.circle),
        releaseDate = field(work.releaseDate, metadata.releaseDate), workCode = field(work.workCode, metadata.workCode),
        sourceUrl = field(work.sourceUrl, metadata.sourceUrl), tags = field(work.tags, metadata.tags))
}

class AudioWorkRepository(private val database: AniMeowDatabase) {
    private val dao = database.audioWorkDao()
    val works = dao.observe()
    suspend fun save(work: AudioWork): Long = withDataTransferLock {
        val clean = normalizeAudioWork(work).copy(updatedAt = Instant.now().toString())
        if (clean.id == 0L) dao.insert(clean) else {
            val previous = dao.get(clean.id) ?: error("作品已不存在，请重新打开")
            check(previous.deletedAt == null && previous.syncId == clean.syncId && previous.updatedAt == work.updatedAt) { "作品记录已变化，请重新打开" }
            dao.update(clean.copy(syncId = previous.syncId, deletedAt = null)); clean.id
        }
    }
    suspend fun delete(work: AudioWork) = withDataTransferLock {
        val previous = dao.get(work.id) ?: return@withDataTransferLock
        check(previous.syncId == work.syncId && previous.updatedAt == work.updatedAt) { "作品记录已变化，请重新打开" }
        val now = Instant.now().toString()
        dao.update(previous.copy(deletedAt = now, updatedAt = now))
    }

    suspend fun recordProgress(work: AudioWork, finish: Boolean): AudioProgressChange = withDataTransferLock {
        database.withTransaction {
            val current = dao.get(work.id)
            check(current == work && work.deletedAt == null) { "作品记录已变化，请重新打开" }
            val updated = quickAudioProgress(work, finish).copy(updatedAt = Instant.now().toString())
            dao.update(updated)
            AudioProgressChange(work, updated)
        }
    }

    suspend fun undoProgress(change: AudioProgressChange) = withDataTransferLock {
        database.withTransaction {
            check(dao.get(change.after.id) == change.after) { "记录已有新的修改，无法撤销，请在编辑页调整" }
            dao.update(change.before.copy(updatedAt = Instant.now().toString()))
        }
    }
}

data class AudioProgressChange(val before: AudioWork, val after: AudioWork)

internal fun quickAudioProgress(work: AudioWork, finish: Boolean): AudioWork {
    val limit = work.durationSeconds.takeIf { it > 0 } ?: 31_536_000L
    return work.copy(
        listenedSeconds = if (finish && work.durationSeconds > 0) work.durationSeconds else if (finish) work.listenedSeconds
            else (work.listenedSeconds + 300).coerceIn(0, limit),
        status = if (finish) "已听" else if (work.status.trim() in setOf("想听", "未听")) "在听" else work.status,
    )
}
