package com.animeow.app.ui.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackSeekingTest {
    @Test fun lanczosKernelKeepsPixelCentersAndZerosAtIntegerNeighboursAndOutsideSupport() {
        assertEquals(1f, playbackLanczosWeight(0f), 0.00001f)
        assertEquals(0f, playbackLanczosWeight(1f), 0.00001f)
        assertEquals(0f, playbackLanczosWeight(-3f), 0f)
        assertEquals(0f, playbackLanczosWeight(4f), 0f)
        assertEquals(0.6079271f, playbackLanczosWeight(0.5f), 0.00001f)
        assertEquals(playbackLanczosWeight(0.5f), playbackLanczosWeight(-0.5f), 0f)
    }
    @Test fun spatialUpscalePreservesOrientationCapsOutputAndNeverDownscalesOriginal() {
        assertEquals(1440 to 960, playbackUpscaleSize(720, 480))
        assertEquals(3840 to 2160, playbackUpscaleSize(1920, 1080))
        assertEquals(2160 to 3840, playbackUpscaleSize(1080, 1920))
        assertEquals(3840 to 2160, playbackUpscaleSize(3840, 2160))
        assertEquals(7680 to 4320, playbackUpscaleSize(7680, 4320))
    }
    @Test fun dragIsBoundedAndUnknownDurationDoesNotInventASeekRange() {
        assertEquals(100000L, playbackDragTarget(90000, 390f, 390, 100000))
        assertEquals(0L, playbackDragTarget(5000, -390f, 390, 100000))
        assertEquals(5000L, playbackDragTarget(5000, 390f, 390, 0))
        assertEquals(5000L, playbackDragTarget(5000, 390f, 0, 100000))
        assertEquals(190000L, playbackDragTarget(10000, 390f, 390, 3600000))
    }
}
