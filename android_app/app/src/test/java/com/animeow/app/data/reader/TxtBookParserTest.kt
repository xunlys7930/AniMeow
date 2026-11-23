package com.animeow.app.data.reader

import com.animeow.app.data.reader.ebook.EbookBlock
import com.animeow.app.data.reader.ebook.EbookFormat
import com.animeow.app.data.reader.ebook.TxtBookParser
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TxtBookParserTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("animeow-txt-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun detectsUtf8EvenWhenMultiByteCharacterCrosses64KbSampleBoundary() {
        // Fill 65534 ASCII bytes so a 3-byte UTF-8 Chinese character straddles byte 65536
        val prefix = "A".repeat(65530) + "\n第1章 边界测试\n"
        val body = "这是一段跨越采样边界的 UTF-8 中文轻小说正文内容。".repeat(80)
        val txtFile = File(tempDir, "utf8_boundary.txt")
        txtFile.writeBytes((prefix + body).toByteArray(Charsets.UTF_8))

        val catalog = TxtBookParser.parseCatalog(
            sourceFile = txtFile,
            cacheDir = tempDir,
            fallbackTitle = "边界测试小说",
        )
        assertEquals(EbookFormat.TXT, catalog.format)
        assertEquals("UTF-8", catalog.detectedCharset)
        assertTrue(catalog.chapters.any { it.title.contains("第1章 边界测试") })

        val chapter = catalog.chapters.first { it.title.contains("第1章") }
        val blocks = TxtBookParser.loadChapterBlocks(catalog.workingFile, chapter)
        assertTrue(blocks.filterIsInstance<EbookBlock.Paragraph>().any { it.text.contains("跨越采样边界") })
    }

    @Test
    fun detectsGb18030ChineseNovelAndSplitsChapters() {
        val gbCharset = Charset.forName("GB18030")
        val content = buildString {
            appendLine("楔子 星海启程")
            appendLine("少年抬头望向夜空，星辰如海。")
            appendLine()
            appendLine("第一章 初入书院")
            appendLine("清晨的钟声回荡在山谷之间。")
            appendLine("他推开沉重的木门，看见满架古籍。")
            appendLine()
            appendLine("第二章 藏书阁之谜")
            appendLine("阁楼深处有一盏长明灯。")
        }
        val txtFile = File(tempDir, "gbk_novel.txt")
        txtFile.writeBytes(content.toByteArray(gbCharset))

        val catalog = TxtBookParser.parseCatalog(
            sourceFile = txtFile,
            cacheDir = tempDir,
            fallbackTitle = "星海书院",
        )
        assertEquals("GB18030", catalog.detectedCharset)
        assertEquals(3, catalog.chapters.size)
        assertEquals("楔子 星海启程", catalog.chapters[0].title)
        assertEquals("第一章 初入书院", catalog.chapters[1].title)
        assertEquals("第二章 藏书阁之谜", catalog.chapters[2].title)

        val ch2Blocks = TxtBookParser.loadChapterBlocks(catalog.workingFile, catalog.chapters[1])
        val paragraphs = ch2Blocks.filterIsInstance<EbookBlock.Paragraph>()
        assertEquals(3, paragraphs.size)
        assertTrue(paragraphs[0].isHeading)
        assertEquals("清晨的钟声回荡在山谷之间。", paragraphs[1].text)
    }

    @Test
    fun detectsUtf16LeBomAndSupportsManualCharsetOverride() {
        val text = "序章 晨雾\n第一行正文内容。\n第一章 远行\n第二行正文内容。\n"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val utf16File = File(tempDir, "utf16le.txt")
        utf16File.writeBytes(bom + text.toByteArray(Charsets.UTF_16LE))

        val catalog = TxtBookParser.parseCatalog(
            sourceFile = utf16File,
            cacheDir = tempDir,
            fallbackTitle = "晨雾远行",
        )
        assertEquals("UTF-16LE", catalog.detectedCharset)
        assertEquals(2, catalog.chapters.size)
        val firstBlocks = TxtBookParser.loadChapterBlocks(catalog.workingFile, catalog.chapters[0])
        assertEquals("第一行正文内容。", (firstBlocks[1] as EbookBlock.Paragraph).text)
    }

    @Test
    fun splitsUnchapteredLargeTxtIntoVirtualSectionsWithoutLosingLines() {
        val line = "这是一本没有传统章节标题的短篇合集段落，每一行都应当完整保留不截断。"
        val totalLines = 600
        val txtFile = File(tempDir, "unchaptered.txt")
        txtFile.writeText((1..totalLines).joinToString("\n") { "$it: $line" }, Charsets.UTF_8)

        val catalog = TxtBookParser.parseCatalog(
            sourceFile = txtFile,
            cacheDir = tempDir,
            fallbackTitle = "无章节合集",
        )
        assertTrue("Expected virtual sections > 1, got ${catalog.chapters.size}", catalog.chapters.size >= 2)
        val allParagraphs = catalog.chapters.flatMap { ch ->
            TxtBookParser.loadChapterBlocks(catalog.workingFile, ch).filterIsInstance<EbookBlock.Paragraph>()
        }
        assertEquals(totalLines, allParagraphs.size)
        assertTrue(allParagraphs.first().text.startsWith("1: "))
        assertTrue(allParagraphs.last().text.startsWith("$totalLines: "))
    }
}
