package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGuardTest {

    @Test fun anUncoveredBandHasNoOcclusion() {
        assertEquals(0f, FrameOcclusion.coverage(VSpan(200, 1200), emptyList()), 0.0001f)
        assertFalse(FrameOcclusion.isOccluded(VSpan(200, 1200), emptyList()))
    }

    @Test fun aWindowOutsideTheBandDoesNotCount() {
        assertEquals(0f, FrameOcclusion.coverage(VSpan(200, 1200), listOf(VSpan(0, 150))), 0.0001f)
    }

    @Test fun overlappingWindowsAreMergedBeforeCounting() {
        // 300..500 and 400..600 overlap → 300..600 = 300px of a 1000px band.
        val c = FrameOcclusion.coverage(VSpan(0, 1000), listOf(VSpan(300, 500), VSpan(400, 600)))
        assertEquals(0.30f, c, 0.0001f)
    }

    @Test fun aSmallBannerIsBelowTheDefaultThreshold() {
        val band = VSpan(0, 1000)
        assertFalse(FrameOcclusion.isOccluded(band, listOf(VSpan(0, 100))))
    }

    @Test fun aFullShadeIsOccluding() {
        val band = VSpan(0, 1000)
        assertTrue(FrameOcclusion.isOccluded(band, listOf(VSpan(0, 1000))))
    }

    @Test fun aFloatingKeyboardOverTheBandIsOccluding() {
        // A floating keyboard covering the lower 40% of the messages.
        val band = VSpan(200, 1200)
        assertTrue(FrameOcclusion.isOccluded(band, listOf(VSpan(800, 1400))))
    }

    @Test fun aWindowPushingUpFromBelowIsHarmless() {
        // The classic docked keyboard: below the composer, outside the band.
        val band = VSpan(200, 1200)
        assertFalse(FrameOcclusion.isOccluded(band, listOf(VSpan(1200, 2000))))
    }
}
