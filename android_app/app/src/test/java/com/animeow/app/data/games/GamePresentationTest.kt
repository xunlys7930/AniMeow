package com.animeow.app.data.games

import org.junit.Assert.*
import org.junit.Test
import com.animeow.app.data.audio.audioLookupCode

class GamePresentationTest {
    @Test fun ratingsPlatformsAndOriginalLanguageHaveReadableLabels() {
        assertEquals("6.73", gameScore(6.729999999999999))
        assertEquals("7.4", gameScore(7.4))
        assertEquals("", gameScore(Double.NaN))
        assertEquals("Windows / Android / Nintendo Switch", gamePlatforms("win / and / swi"))
        assertNotNull(gameSynopsisNotice(GameEntity(synopsis = "English description")))
        assertNull(gameSynopsisNotice(GameEntity(synopsis = "已核实的中文简介")))
    }
    @Test fun crossSourceCollectionMatchingNeverCollapsesSequelsOrDifferentReleaseDates() {
        val bgm = GameEntity(bangumiId = 256658, title = "夏娃年代记2", originalTitle = "Evenicle -イブニクル- 2", releaseDate = "2019-02-22")
        val vn = bgm.copy(bangumiId = null, vndbId = "v23742", originalTitle = "イブニクル２")
        assertTrue(sameCatalogGame(bgm, vn))
        assertFalse(sameCatalogGame(bgm, vn.copy(originalTitle = "イブニクル")))
        assertFalse(sameCatalogGame(bgm, vn.copy(originalTitle = "イブニクル2ランス版")))
        assertFalse(sameCatalogGame(bgm, vn.copy(originalTitle = "イブニクル2 Deluxe")))
        assertFalse(sameCatalogGame(bgm, vn.copy(releaseDate = "2020-01-01")))
    }
    @Test fun audioLookupAcceptsCodesAndWorkLinksAndExplainsInvalidInputsBeforeAnyRequest() {
        assertEquals("RJ01570739", audioLookupCode("ｒｊ０１５７０７３９"))
        assertEquals("RJ01570739", audioLookupCode("https://www.dlsite.com/home/work/=/product_id/RJ01570739.html?locale=zh_CN"))
        for (bad in listOf("", "作品名称", "RJ123", "https://evil.test/RJ01570739.html", "https://www.dlsite.com@evil.test/RJ01570739.html")) assertNull(audioLookupCode(bad))
    }
}
