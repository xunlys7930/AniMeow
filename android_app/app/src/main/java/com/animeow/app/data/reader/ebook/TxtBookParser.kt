package com.animeow.app.data.reader.ebook

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TxtBookParser {
    val SUPPORTED_CHARSETS = listOf("AUTO", "UTF-8", "GB18030", "UTF-16LE", "UTF-16BE", "Big5")

    private const val SAMPLE_SIZE = 64 * 1024
    private const val MAX_SINGLE_SECTION_BYTES = 24 * 1024
    private const val MAX_HEADING_LENGTH = 68

    private val CHAPTER_HEADING_REGEX = Regex(
        "^\\s*(" +
            "序章.*|序言.*|楔子.*|引子.*|前言.*|尾声.*|后记.*|终章.*|番外.*|" +
            "(?:第\\s*[零一二三四五六七八九十百千万两0-9]+\\s*[卷部篇集册]\\s*)?" +
            "第\\s*[零一二三四五六七八九十百千万两0-9]+\\s*[章节回话幕折篇].*|" +
            "(?:Volume|Vol\\.?)\\s*\\d+.*|" +
            "Chapter\\s+\\d+.*" +
            ")\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun parseCatalog(
        sourceFile: File,
        cacheDir: File,
        fallbackTitle: String,
        fingerprint: String = BookFileHasher.computeFingerprint(sourceFile),
        charsetOverride: String? = null,
    ): ParsedEbookCatalog {
        val (charset, bomSkipBytes) = resolveCharset(sourceFile, charsetOverride)
        val normalizedDir = File(cacheDir, "ebook_cache/txt").apply { mkdirs() }
        val safeCharsetTag = charset.name().replace(Regex("[^A-Za-z0-9_-]"), "_")
        val normalizedFile = File(normalizedDir, "${fingerprint}_${safeCharsetTag}.utf8")

        val chapters = indexAndTranscode(
            sourceFile = sourceFile,
            bomSkipBytes = bomSkipBytes,
            charset = charset,
            normalizedOutput = normalizedFile,
        )
        return ParsedEbookCatalog(
            format = EbookFormat.TXT,
            title = fallbackTitle.trim().ifEmpty { "未命名文本" },
            detectedCharset = charset.name(),
            chapters = chapters,
            workingFile = normalizedFile,
        )
    }

    fun loadChapterBlocks(normalizedUtf8File: File, chapter: EbookChapterRef): List<EbookBlock> {
        if (chapter.byteLength <= 0 || !normalizedUtf8File.isFile) {
            return listOf(EbookBlock.Paragraph("本章暂无正文内容"))
        }
        val bytes = ByteArray(chapter.byteLength)
        RandomAccessFile(normalizedUtf8File, "r").use { raf ->
            val maxReadable = (raf.length() - chapter.byteStart).coerceAtLeast(0L).toInt()
            val toRead = minOf(chapter.byteLength, maxReadable)
            if (toRead <= 0) return listOf(EbookBlock.Paragraph("本章暂无正文内容"))
            raf.seek(chapter.byteStart)
            raf.readFully(bytes, 0, toRead)
        }
        val text = bytes.toString(Charsets.UTF_8)
        val blocks = ArrayList<EbookBlock>()
        val lines = text.lines()
        lines.forEachIndexed { idx, rawLine ->
            val cleaned = rawLine.trim('\r', ' ', '\t', '\u3000')
            if (cleaned.isEmpty()) return@forEachIndexed
            val isHeading = idx == 0 && cleaned == chapter.title.trim()
            blocks += EbookBlock.Paragraph(text = cleaned, isHeading = isHeading)
        }
        if (blocks.isEmpty()) {
            blocks += EbookBlock.Paragraph(chapter.title.ifBlank { "正文" }, isHeading = true)
        }
        return blocks
    }

    internal fun resolveCharset(file: File, charsetOverride: String?): Pair<Charset, Int> {
        val sampleSize = minOf(file.length(), SAMPLE_SIZE.toLong()).toInt()
        val sample = ByteArray(sampleSize)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < sampleSize) {
                val n = input.read(sample, offset, sampleSize - offset)
                if (n < 0) break
                offset += n
            }
        }
        val bomSkip = when {
            sample.size >= 3 &&
                sample[0] == 0xEF.toByte() &&
                sample[1] == 0xBB.toByte() &&
                sample[2] == 0xBF.toByte() -> 3 to Charsets.UTF_8
            sample.size >= 2 &&
                sample[0] == 0xFF.toByte() &&
                sample[1] == 0xFE.toByte() -> 2 to Charsets.UTF_16LE
            sample.size >= 2 &&
                sample[0] == 0xFE.toByte() &&
                sample[1] == 0xFF.toByte() -> 2 to Charsets.UTF_16BE
            else -> null
        }

        val cleanOverride = charsetOverride?.trim()?.takeIf { it.isNotEmpty() && !it.equals("AUTO", ignoreCase = true) }
        if (cleanOverride != null) {
            val chosen = runCatching { Charset.forName(cleanOverride) }.getOrDefault(Charsets.UTF_8)
            val skip = if (bomSkip?.second == chosen) bomSkip.first else 0
            return chosen to skip
        }
        if (bomSkip != null) return bomSkip.second to bomSkip.first

        // 若无 BOM，但偶数字节出现大量 NUL，识别为 UTF-16
        if (sample.size >= 16) {
            var evenZero = 0
            var oddZero = 0
            val pairs = minOf(sample.size / 2, 256)
            for (i in 0 until pairs) {
                if (sample[i * 2] == 0.toByte()) evenZero++
                if (sample[i * 2 + 1] == 0.toByte()) oddZero++
            }
            if (oddZero > pairs / 3 && evenZero < pairs / 20) return Charsets.UTF_16LE to 0
            if (evenZero > pairs / 3 && oddZero < pairs / 20) return Charsets.UTF_16BE to 0
        }

        if (isValidUtf8Sample(sample)) {
            return Charsets.UTF_8 to 0
        }
        val gbCharset = runCatching { Charset.forName("GB18030") }
            .recoverCatching { Charset.forName("GBK") }
            .getOrDefault(Charsets.UTF_8)
        return gbCharset to 0
    }

    private fun isValidUtf8Sample(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return true
        // 截去采样末尾可能被 64KB 边界截断的半个多字节 UTF-8 字符
        var validLen = sample.size
        var trailingContinuation = 0
        while (validLen > 0 && trailingContinuation < 3 && (sample[validLen - 1].toInt() and 0xC0) == 0x80) {
            validLen--
            trailingContinuation++
        }
        if (validLen > 0) {
            val lead = sample[validLen - 1].toInt() and 0xFF
            val expectedTotal = when {
                (lead and 0x80) == 0 -> 1
                (lead and 0xE0) == 0xC0 -> 2
                (lead and 0xF0) == 0xE0 -> 3
                (lead and 0xF8) == 0xF0 -> 4
                else -> 99
            }
            if (1 + trailingContinuation < expectedTotal) {
                validLen--
            } else {
                validLen += trailingContinuation
            }
        }
        if (validLen <= 0) return true
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(sample, 0, validLen)) }.isSuccess
    }

    private fun indexAndTranscode(
        sourceFile: File,
        bomSkipBytes: Int,
        charset: Charset,
        normalizedOutput: File,
    ): List<EbookChapterRef> {
        val chapters = ArrayList<EbookChapterRef>()
        var currentTitle = "开始"
        var currentStart = 0L
        var currentBytes = 0
        var hasExplicitHeading = false
        var virtualPartCounter = 1
        var writtenOffset = 0L

        fun flushChapter() {
            if (currentBytes <= 0) return
            chapters += EbookChapterRef(
                index = chapters.size,
                title = currentTitle,
                byteStart = currentStart,
                byteLength = currentBytes,
            )
            currentStart = writtenOffset
            currentBytes = 0
        }

        FileInputStream(sourceFile).use { rawIn ->
            if (bomSkipBytes > 0) {
                var skipped = 0L
                while (skipped < bomSkipBytes) {
                    val s = rawIn.skip((bomSkipBytes - skipped).toLong())
                    if (s <= 0) break
                    skipped += s
                }
            }
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            BufferedReader(InputStreamReader(rawIn, decoder)).use { reader ->
                BufferedOutputStream(FileOutputStream(normalizedOutput)).use { out ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim('\uFEFF', ' ', '\t', '\u3000')
                        val isHeading = trimmed.length in 1..MAX_HEADING_LENGTH && CHAPTER_HEADING_REGEX.matches(trimmed)

                        if (isHeading) {
                            flushChapter()
                            hasExplicitHeading = true
                            currentTitle = trimmed
                            virtualPartCounter = 1
                        } else if (currentBytes >= MAX_SINGLE_SECTION_BYTES && trimmed.isNotEmpty()) {
                            // 对无章节标识或单章超长文本按段落边界切分虚拟小节，保证低内存与流畅滚动
                            val baseTitle = if (!hasExplicitHeading && chapters.isEmpty()) {
                                currentTitle = "第 1 节"
                                flushChapter()
                                virtualPartCounter = 2
                                "第 $virtualPartCounter 节"
                            } else if (!hasExplicitHeading) {
                                flushChapter()
                                virtualPartCounter++
                                "第 $virtualPartCounter 节"
                            } else {
                                val base = currentTitle.substringBefore("（续 ")
                                flushChapter()
                                virtualPartCounter++
                                "$base（续 $virtualPartCounter）"
                            }
                            currentTitle = baseTitle
                        }

                        if (trimmed.isEmpty() && currentBytes == 0) continue
                        val lineBytes = (trimmed + "\n").toByteArray(Charsets.UTF_8)
                        out.write(lineBytes)
                        writtenOffset += lineBytes.size
                        currentBytes += lineBytes.size
                    }
                    flushChapter()
                }
            }
        }

        if (chapters.isEmpty()) {
            chapters += EbookChapterRef(index = 0, title = "第 1 节", byteStart = 0L, byteLength = 0)
        } else if (!hasExplicitHeading && chapters.size == 1 && chapters[0].title == "开始") {
            chapters[0] = chapters[0].copy(title = "全文")
        }
        return chapters
    }
}
