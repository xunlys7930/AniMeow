package com.animeow.app.data.reader

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍（轻小说／网文）本地电子书阅读状态与《开源阅读》（Legado）同步进度。
 *
 * 与 `animes` 主表 1:1 关联（仅 `subjectType = "book"` 作品使用）：
 * - [localFileUri] 为设备私有 SAF `content://` URI，原生备份与云同步导出时自动置空剥离；
 * - [fileFingerprint] 保存文件采样哈希，文件移动后重绑同一文件可无损恢复阅读位置；
 * - [syncProgressToMain] 控制章节进度是否同步到 `animes.watchedEpisodes`（当检测到本地按“卷”计而开源阅读按“章”计时默认关闭，避免冲掉卷数进度）。
 */
@Entity(
    tableName = "book_reading_states",
    indices = [
        Index(value = ["legadoBookUrl"]),
        Index(value = ["lastReadAt"]),
    ],
)
data class BookReadingStateEntity(
    @PrimaryKey
    val animeId: Long,
    val localFileUri: String? = null,
    val localFileName: String? = null,
    /** "TXT" | "EPUB" */
    val fileFormat: String? = null,
    val fileFingerprint: String? = null,
    val charsetName: String? = null,
    val chapterIndex: Int = 0,
    val chapterTitle: String? = null,
    val totalChapters: Int = 0,
    val blockIndex: Int = 0,
    val charOffset: Int = 0,
    /** 全书阅读千分比进度 0..1000 */
    val progressPermille: Int = 0,
    val lastReadAt: String? = null,
    val legadoBookUrl: String? = null,
    val legadoOriginName: String? = null,
    val legadoAuthor: String? = null,
    val legadoChapterIndex: Int? = null,
    val legadoChapterTitle: String? = null,
    val legadoTotalChapters: Int? = null,
    val legadoLastReadAt: String? = null,
    val syncProgressToMain: Boolean = true,
    val updatedAt: String,
) {
    val hasLocalBook: Boolean
        get() = !localFileUri.isNullOrBlank()

    val hasLegadoLink: Boolean
        get() = !legadoBookUrl.isNullOrBlank()
}

@Entity(
    tableName = "book_bookmarks",
    indices = [Index(value = ["animeId"])],
)
data class BookBookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val animeId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val blockIndex: Int = 0,
    val charOffset: Int = 0,
    val snippet: String,
    val note: String? = null,
    val createdAt: String,
)
