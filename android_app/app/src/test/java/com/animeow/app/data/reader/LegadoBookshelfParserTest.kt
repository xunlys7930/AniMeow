package com.animeow.app.data.reader

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.reader.legado.LegadoBookshelfParser
import com.animeow.app.data.reader.legado.LegadoLanClient
import com.animeow.app.data.reader.legado.LegadoMatchKind
import com.animeow.app.data.reader.legado.LegadoWebDavClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class LegadoBookshelfParserTest {

    @Test
    fun parsesBookshelfJsonAndBackupZipWhileSkippingAudioEntries() {
        val json = """
            [
              {
                "bookUrl": "https://example.org/book/101",
                "name": "诡秘之主",
                "author": "作者：爱潜水的乌贼",
                "origin": "https://example.org",
                "originName": "测试书源",
                "durChapterIndex": 124,
                "durChapterTitle": "第一百二十五章 塔罗会",
                "durChapterPos": 380,
                "totalChapterNum": 1430,
                "durChapterTime": 1730000000000,
                "type": 0
              },
              {
                "bookUrl": "https://example.org/book/unread",
                "name": "未读新书",
                "author": "某作者",
                "durChapterIndex": 0,
                "durChapterPos": 0,
                "totalChapterNum": 200,
                "durChapterTime": 0,
                "type": 0
              },
              {
                "bookUrl": "https://example.org/audio/99",
                "name": "有声演播版",
                "author": "主播甲",
                "type": 1
              }
            ]
        """.trimIndent()

        val zipBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("bookSource.json"))
                zos.write("[]".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("bookshelf.json"))
                zos.write(json.toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val items = LegadoBookshelfParser.parseStream(ByteArrayInputStream(zipBytes), "backup2026-11-12.zip")
        assertEquals(2, items.size)

        val reading = items[0]
        assertEquals("诡秘之主", reading.name)
        assertEquals("爱潜水的乌贼", reading.author)
        assertFalse(reading.isUnread)
        assertEquals(125, reading.readChapters)

        val unread = items[1]
        assertTrue(unread.isUnread)
        assertEquals(0, unread.readChapters)
    }

    @Test
    fun isolatesAnimeItemsSameTitleDifferentAuthorsAndProtectsVolumeVsChapterMismatch() {
        val animeSameTitle = AnimeEntity(
            id = 10L,
            title = "无职转生",
            subjectType = "anime",
            status = "在看",
            watchedEpisodes = 12,
            totalEpisodes = 24,
        )
        val bangumiLightNovelVolumes = AnimeEntity(
            id = 20L,
            title = "无职转生",
            subjectType = "book",
            status = "在看",
            watchedEpisodes = 8,
            totalEpisodes = 26,
            externalSource = "bangumi",
            externalId = "101114",
        )
        val localNovelAuthorA = AnimeEntity(
            id = 30L,
            title = "星辰大海",
            subjectType = "book",
            status = "在看",
            studio = "作者甲",
            watchedEpisodes = 10,
            totalEpisodes = 100,
        )

        val legadoItems = LegadoBookshelfParser.parseJsonText(
            """
            {
              "isSuccess": true,
              "data": [
                {
                  "bookUrl": "https://src.example/mushoku",
                  "name": "《无职转生》",
                  "author": "理不尽な孫の手",
                  "durChapterIndex": 244,
                  "durChapterTitle": "第245话 决战前夜",
                  "durChapterPos": 120,
                  "totalChapterNum": 360,
                  "durChapterTime": 1731000000000
                },
                {
                  "bookUrl": "https://src.example/star-b",
                  "name": "星辰大海",
                  "author": "作者乙",
                  "durChapterIndex": 0,
                  "durChapterPos": 0,
                  "totalChapterNum": 80,
                  "durChapterTime": 0
                }
              ]
            }
            """.trimIndent(),
        )

        val previews = LegadoBookshelfParser.buildPreview(
            items = legadoItems,
            activeAnimes = listOf(animeSameTitle, bangumiLightNovelVolumes, localNovelAuthorA),
            existingStates = emptyMap(),
        )
        assertEquals(2, previews.size)

        // 1. Matches book #20 (never anime #10), detects 26 volumes vs 360 chapters mismatch -> syncProgressToMain = false!
        val mushoku = previews[0]
        assertEquals(LegadoMatchKind.TITLE_MATCH, mushoku.matchKind)
        assertEquals(20L, mushoku.matchedAnimeId)
        assertTrue(mushoku.unitMismatchProtected)
        assertFalse(mushoku.syncProgressToMain)
        assertEquals("在看", mushoku.targetStatus)

        // 2. "星辰大海" by 作者乙 does NOT auto-merge into 作者甲's book #30; creates new book with status "想看"!
        val starB = previews[1]
        assertEquals(LegadoMatchKind.CREATE_NEW, starB.matchKind)
        assertNull(starB.matchedAnimeId)
        assertEquals("想看", starB.targetStatus)
        assertTrue(starB.syncProgressToMain)
    }

    @Test
    fun enforcesWebDavHttpsLanPrivateIpAndParsesPropfindXml() {
        try {
            LegadoWebDavClient.validateWebDavEndpoint("http://dav.jianguoyun.com/dav/")
            fail("Public cleartext WebDAV must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("HTTPS"))
        }

        val validHttps = LegadoWebDavClient.validateWebDavEndpoint("https://dav.jianguoyun.com/dav/")
        assertEquals("https", validHttps.scheme)

        try {
            LegadoLanClient.normalizeLanEndpoint(LegadoLanConfig(host = "8.8.8.8", port = 1122))
            fail("Public IP must be rejected by LAN client")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("局域网"))
        }

        val (lanHost, lanPort) = LegadoLanClient.normalizeLanEndpoint(
            LegadoLanConfig(host = "http://192.168.1.25:1122/", port = 8080),
        )
        assertEquals("192.168.1.25", lanHost)
        assertEquals(1122, lanPort)

        val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/legado/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/legado/backup2026-11-01.zip</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/legado/backup2026-11-12.zip</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = LegadoWebDavClient.parsePropfindXml(ByteArrayInputStream(propfindXml.toByteArray()))
        val best = LegadoWebDavClient.selectBestEntry(entries)
        assertEquals("backup2026-11-12.zip", best?.fileName)
    }
}
