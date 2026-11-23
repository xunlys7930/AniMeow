package com.animeow.app.data.reader

import com.animeow.app.data.reader.ebook.BookFileHasher
import com.animeow.app.data.reader.ebook.EbookBlock
import com.animeow.app.data.reader.ebook.EbookFormat
import com.animeow.app.data.reader.ebook.EpubBookParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class EpubBookParserTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("animeow-epub-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun parsesEpubMetadataTocHtmlEntitiesIllustrationsAndBlocksRemoteImages() {
        val epubFile = File(tempDir, "sample_light_novel.epub")
        val pngStub = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
        )
        writeZip(
            epubFile,
            mapOf(
                "META-INF/container.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().toByteArray(),
                "OEBPS/content.opf" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>仰望半月的夜空</dc:title>
                        <dc:creator>桥本纺</dc:creator>
                        <dc:description>医院屋顶与星空的轻小说故事。</dc:description>
                        <meta name="cover" content="cover-img"/>
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="cover-img" href="Images/cover.png" media-type="image/png"/>
                        <item id="illust-1" href="Images/illust1.png" media-type="image/png"/>
                        <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine toc="ncx">
                        <itemref idref="ch1"/>
                      </spine>
                    </package>
                """.trimIndent().toByteArray(),
                "OEBPS/toc.ncx" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <navMap>
                        <navPoint id="navPoint-1" playOrder="1">
                          <navLabel><text>第一章 屋顶的邂逅</text></navLabel>
                          <content src="Text/ch1.xhtml"/>
                        </navPoint>
                      </navMap>
                    </ncx>
                """.trimIndent().toByteArray(),
                "OEBPS/Images/cover.png" to pngStub,
                "OEBPS/Images/illust1.png" to pngStub,
                "OEBPS/Text/ch1.xhtml" to """
                    <?xml version="1.0" encoding="utf-8"?>
                    <!DOCTYPE html>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink">
                    <head><title>Ignored Head Title</title><style>body { color: black; }</style></head>
                    <body>
                      <h1>第一章&nbsp;屋顶的邂逅</h1>
                      <p>她翻开书页&mdash;&mdash;微风拂过窗台。</p>
                      <div><img src="../Images/illust1.png" alt="彩页插图"/></div>
                      <svg xmlns="http://www.w3.org/2000/svg"><image xlink:href="../Images/cover.png"/></svg>
                      <p><img src="https://tracker.example.com/pixel.png" alt="远程追踪图"/></p>
                    </body>
                    </html>
                """.trimIndent().toByteArray(),
            ),
        )

        val catalog = EpubBookParser.parseCatalog(epubFile, fallbackTitle = "默认书名")
        assertEquals(EbookFormat.EPUB, catalog.format)
        assertEquals("仰望半月的夜空", catalog.title)
        assertEquals("桥本纺", catalog.author)
        assertEquals("医院屋顶与星空的轻小说故事。", catalog.synopsis)
        assertEquals("png", catalog.coverExtension)
        assertArrayEquals(pngStub, catalog.coverBytes)
        assertEquals(1, catalog.chapters.size)
        assertEquals("第一章 屋顶的邂逅", catalog.chapters[0].title)

        val fingerprint = BookFileHasher.computeFingerprint(epubFile)
        val blocks = EpubBookParser.loadChapterBlocks(epubFile, fingerprint, catalog.chapters[0], tempDir)

        val paragraphs = blocks.filterIsInstance<EbookBlock.Paragraph>()
        val images = blocks.filterIsInstance<EbookBlock.Illustration>()

        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs[0].isHeading)
        assertTrue(paragraphs[0].text.contains("第一章"))
        assertEquals("她翻开书页——微风拂过窗台。", paragraphs[1].text)

        // Local <img> + SVG <image> extracted, remote https:// tracker blocked
        assertEquals(2, images.size)
        assertEquals("彩页插图", images[0].altText)
        assertTrue(File(images[0].cacheFilePath).isFile)
        assertTrue(File(images[1].cacheFilePath).isFile)
    }

    @Test
    fun rejectsZipPathTraversalEntriesInEpub() {
        val maliciousFile = File(tempDir, "evil.epub")
        writeZip(
            maliciousFile,
            mapOf(
                "../escape.txt" to "pwn".toByteArray(),
                "META-INF/container.xml" to "<container/>".toByteArray(),
            ),
        )
        try {
            EpubBookParser.parseCatalog(maliciousFile, fallbackTitle = "evil")
            fail("Expected zip traversal entry to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    private fun writeZip(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }
}
