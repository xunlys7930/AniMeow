package com.animeow.app.data.reader.ebook

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

enum class EbookFormat(val label: String) {
    TXT("TXT"),
    EPUB("EPUB"),
    ;

    companion object {
        fun detect(fileName: String?, headerBytes: ByteArray): EbookFormat {
            if (
                headerBytes.size >= 4 &&
                headerBytes[0] == 0x50.toByte() &&
                headerBytes[1] == 0x4B.toByte() &&
                (headerBytes[2] == 0x03.toByte() || headerBytes[2] == 0x05.toByte())
            ) {
                return EPUB
            }
            val lower = fileName?.lowercase(Locale.ROOT).orEmpty()
            return if (lower.endsWith(".epub")) EPUB else TXT
        }
    }
}

data class EbookChapterRef(
    val index: Int,
    val title: String,
    /** EPUB 内相对路径（如 OEBPS/Text/ch01.xhtml），TXT 为空 */
    val entryPath: String = "",
    /** TXT 归一化缓存文件字节起点 */
    val byteStart: Long = 0L,
    /** TXT 归一化缓存文件字节长度 */
    val byteLength: Int = 0,
    val anchor: String? = null,
)

sealed interface EbookBlock {
    data class Paragraph(
        val text: String,
        val isHeading: Boolean = false,
    ) : EbookBlock

    data class Illustration(
        val cacheFilePath: String,
        val altText: String? = null,
    ) : EbookBlock
}

data class ParsedEbookCatalog(
    val format: EbookFormat,
    val title: String,
    val author: String? = null,
    val synopsis: String? = null,
    val coverBytes: ByteArray? = null,
    val coverExtension: String? = null,
    val detectedCharset: String? = null,
    val chapters: List<EbookChapterRef>,
    val workingFile: File,
)

object BookFileHasher {
    private const val SAMPLE_BYTES = 64 * 1024
    const val MAX_EBOOK_FILE_BYTES = 150L * 1024 * 1024

    /**
     * 基于「文件总长度 + 头部 64KB + 尾部 64KB」计算快速 SHA-256 指纹：
     * 即使 50 MB 的长篇 TXT 也能在毫秒级完成指纹校验，文件移动后重新选择即可恢复精确阅读进度。
     */
    fun computeFingerprint(file: File): String {
        val length = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putLong(length).array())
        RandomAccessFile(file, "r").use { raf ->
            val headSize = minOf(length, SAMPLE_BYTES.toLong()).toInt()
            if (headSize > 0) {
                val head = ByteArray(headSize)
                raf.seek(0L)
                raf.readFully(head)
                digest.update(head)
            }
            if (length > SAMPLE_BYTES) {
                val tailStart = maxOf(SAMPLE_BYTES.toLong(), length - SAMPLE_BYTES)
                val tailSize = (length - tailStart).toInt()
                if (tailSize > 0) {
                    val tail = ByteArray(tailSize)
                    raf.seek(tailStart)
                    raf.readFully(tail)
                    digest.update(tail)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    fun copyStreamToCache(
        input: InputStream,
        cacheDir: File,
        extensionHint: String,
        maxBytes: Long = MAX_EBOOK_FILE_BYTES,
    ): Pair<File, String> {
        val booksDir = File(cacheDir, "ebook_cache/books").apply { mkdirs() }
        val tempFile = File(booksDir, "staging_${System.nanoTime()}.tmp")
        var total = 0L
        try {
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "电子书文件体积超过上限（150 MB），请分卷后导入" }
                    output.write(buffer, 0, read)
                }
            }
            require(total > 0L) { "所选电子书文件为空" }
            val fingerprint = computeFingerprint(tempFile)
            val cleanExt = extensionHint.lowercase(Locale.ROOT).takeIf { it in setOf("epub", "txt") } ?: "dat"
            val target = File(booksDir, "$fingerprint.$cleanExt")
            if (target.isFile && target.length() == tempFile.length()) {
                tempFile.delete()
            } else {
                if (target.exists()) target.delete()
                if (!tempFile.renameTo(target)) {
                    tempFile.copyTo(target, overwrite = true)
                    tempFile.delete()
                }
            }
            return target to fingerprint
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }
}
