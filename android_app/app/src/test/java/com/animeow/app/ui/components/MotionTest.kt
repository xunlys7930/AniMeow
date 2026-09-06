package com.animeow.app.ui.components

import com.animeow.app.ui.theme.MotionLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {
    @Test
    fun durationScalingMatchesTheSelectedMotionLevel() {
        assertEquals(300, scaledMotionDurationMillis(300, MotionLevel.FULL))
        assertEquals(165, scaledMotionDurationMillis(300, MotionLevel.REDUCED))
        assertEquals(0, scaledMotionDurationMillis(300, MotionLevel.NONE))
    }

    @Test
    fun durationScalingNeverReturnsANegativeDuration() {
        assertEquals(0, scaledMotionDurationMillis(-300, MotionLevel.FULL))
    }
}
