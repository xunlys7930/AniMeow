package com.animeow.app.ui.anime

import com.animeow.app.data.bangumi.EpisodeNumberMetadata
import com.animeow.app.data.bangumi.EpisodeNumbering
import org.junit.Assert.*
import org.junit.Test

class EpisodeProgressInputTest {
    private val numbering = EpisodeNumbering.fromEpisodes((129..180).map {
        EpisodeNumberMetadata(it.toLong(), 0, it.toDouble())
    }, 52)!!

    @Test fun continuousInputAlwaysSavesSeasonCount() {
        assertEquals(47, resolveEpisodeProgressInput("175", 52, true, numbering))
        assertEquals(48, resolveEpisodeProgressInput("176", 52, true, numbering))
        assertEquals(52, resolveEpisodeProgressInput("180", 52, true, numbering))
        assertEquals(1, resolveEpisodeProgressInput("129", 52, true, numbering))
        for (text in listOf("0", "47", "128", "181", "", "999999999999")) {
            assertNull(resolveEpisodeProgressInput(text, 52, true, numbering))
        }
    }

    @Test fun legacyInputAndZeroRemainUnchangedAndMetadataMustMatch() {
        assertEquals(47, resolveEpisodeProgressInput("47", 52, false, numbering))
        assertEquals(0, resolveEpisodeProgressInput("0", 52, false, numbering))
        assertNull(resolveEpisodeProgressInput("175", 52, false, numbering))
        assertEquals(175, resolveEpisodeProgressInput("175", 0, false, null))
        assertNull(resolveEpisodeProgressInput("175", 51, true, numbering))
        assertNull(resolveEpisodeProgressInput("175", 52, true, null))
    }
}
