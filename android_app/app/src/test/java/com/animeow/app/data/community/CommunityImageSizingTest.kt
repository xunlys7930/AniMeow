package com.animeow.app.data.community

import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityImageSizingTest {
    @Test
    fun sampleSizeUsesPowerOfTwoWithoutOverShrinking() {
        assertEquals(1, CommunityImageSizing.sampleSize(1600, 900, 1600))
        assertEquals(2, CommunityImageSizing.sampleSize(4000, 3000, 1600))
        assertEquals(4, CommunityImageSizing.sampleSize(8000, 6000, 1600))
    }

    @Test
    fun targetSizePreservesAspectRatio() {
        assertEquals(1600 to 1200, CommunityImageSizing.targetSize(4000, 3000, 1600))
        assertEquals(900 to 1600, CommunityImageSizing.targetSize(1800, 3200, 1600))
        assertEquals(800 to 600, CommunityImageSizing.targetSize(800, 600, 1600))
    }
}
