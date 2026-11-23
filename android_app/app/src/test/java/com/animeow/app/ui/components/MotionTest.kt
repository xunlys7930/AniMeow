package com.animeow.app.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import com.animeow.app.ui.theme.MotionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun fadeThroughSpecDistinguishesFullReducedAndNone() {
        val full = motionFadeThroughSpec(MotionLevel.FULL)
        val reduced = motionFadeThroughSpec(MotionLevel.REDUCED)
        val none = motionFadeThroughSpec(MotionLevel.NONE)

        assertEquals(EnterTransition.None, none.targetContentEnter)
        assertEquals(ExitTransition.None, none.initialContentExit)
        assertNotEquals(EnterTransition.None, reduced.targetContentEnter)
        assertNotEquals(ExitTransition.None, reduced.initialContentExit)
        assertNotEquals(full.targetContentEnter, reduced.targetContentEnter)
        assertNotEquals(full.initialContentExit, reduced.initialContentExit)
    }

    @Test
    fun slideContentSpecDistinguishesFullReducedAndNone() {
        val full = motionSlideContentSpec(MotionLevel.FULL, forward = true)
        val reduced = motionSlideContentSpec(MotionLevel.REDUCED, forward = true)
        val none = motionSlideContentSpec(MotionLevel.NONE, forward = true)

        assertEquals(EnterTransition.None, none.targetContentEnter)
        assertEquals(ExitTransition.None, none.initialContentExit)
        assertNotEquals(EnterTransition.None, reduced.targetContentEnter)
        assertNotEquals(ExitTransition.None, reduced.initialContentExit)
        assertNotEquals(full.targetContentEnter, reduced.targetContentEnter)
        assertNotEquals(full.initialContentExit, reduced.initialContentExit)
    }

    @Test
    fun buildMotionItemSpecsDistinguishesFullReducedAndNone() {
        val full = buildMotionItemSpecs(MotionLevel.FULL, enableFade = true, enablePlacement = true)
        val reduced = buildMotionItemSpecs(MotionLevel.REDUCED, enableFade = true, enablePlacement = true)
        val none = buildMotionItemSpecs(MotionLevel.NONE, enableFade = true, enablePlacement = true)

        assertNull(none.fadeInSpec)
        assertNull(none.placementSpec)
        assertNull(none.fadeOutSpec)

        assertTrue(reduced.fadeInSpec is TweenSpec)
        assertEquals(99, (reduced.fadeInSpec as TweenSpec).durationMillis)
        assertTrue(reduced.fadeOutSpec is TweenSpec)
        assertEquals(66, (reduced.fadeOutSpec as TweenSpec).durationMillis)
        assertTrue(reduced.placementSpec is TweenSpec)
        assertEquals(121, (reduced.placementSpec as TweenSpec).durationMillis)

        assertTrue(full.fadeInSpec is TweenSpec)
        assertEquals(180, (full.fadeInSpec as TweenSpec).durationMillis)
        assertTrue(full.fadeOutSpec is TweenSpec)
        assertEquals(120, (full.fadeOutSpec as TweenSpec).durationMillis)
        assertTrue(full.placementSpec is SpringSpec)
    }
}
