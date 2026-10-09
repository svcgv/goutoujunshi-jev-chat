package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The title area is chosen by the user, so the model must round-trip whatever
 * they drew and reject degenerate selections rather than silently cropping to
 * something meaningless.
 */
class TitleRegionTest {

    @Test fun pixelsRoundTripThroughFractions() {
        val region = TitleRegion.fromPixels(100, 150, 700, 260, 1216, 2640)!!
        val px = region.pixelsFor(1216, 2640)!!
        assertEquals(100, px[0]); assertEquals(150, px[1])
        assertEquals(700, px[2]); assertEquals(260, px[3])
    }

    @Test fun theSameFractionWorksAtAnotherResolution() {
        val region = TitleRegion.fromPixels(100, 150, 700, 260, 1216, 2640)!!
        val px = region.pixelsFor(608, 1320)!!
        assertEquals(50, px[0]); assertEquals(75, px[1])
        assertEquals(350, px[2]); assertEquals(130, px[3])
    }

    @Test fun encodeDecodeIsStable() {
        val region = TitleRegion.fromPixels(10, 20, 300, 90, 1216, 2640)!!
        assertEquals(region, TitleRegion.decode(region.encode()))
    }

    @Test fun aDegenerateBoxIsRejected() {
        assertNull(TitleRegion.fromPixels(100, 100, 100, 100, 1216, 2640))
        assertNull(TitleRegion.fromPixels(100, 100, 101, 101, 1216, 2640))
    }

    @Test fun outOfRangeBoxesAreClamped() {
        val region = TitleRegion.fromPixels(-500, -500, 99999, 99999, 1216, 2640)!!
        assertTrue(region.isValid())
        assertEquals(0f, region.left, 0.0001f)
        assertEquals(1f, region.right, 0.0001f)
    }

    @Test fun malformedStoredValuesAreIgnored() {
        assertNull(TitleRegion.decode(null))
        assertNull(TitleRegion.decode(""))
        assertNull(TitleRegion.decode("1,2,3"))
        assertNull(TitleRegion.decode("a,b,c,d"))
        assertNull(TitleRegion.decode("0.5,0.5,0.1,0.6"))
    }

    @Test fun aRegionSmallerThanTheMinimumCannotBeUsed() {
        val tiny = TitleRegion(0.1f, 0.1f, 0.1001f, 0.9f)
        assertFalse(tiny.isValid())
        assertNull(tiny.pixelsFor(1216, 2640))
    }

    @Test fun theInitialSelectionIsUsableAndNearTheTop() {
        val s = TitleRegion.initialSelection(1216, 2640)
        assertTrue(s.isValid())
        assertTrue(s.top < 0.2f)
        assertNotNull(s.pixelsFor(1216, 2640))
    }

    @Test fun aSavedRegionSurvivesDifferentImageSizes() {
        val saved = TitleRegion.fromPixels(200, 120, 900, 260, 1216, 2640)!!
        val restored = TitleRegion.decode(saved.encode())!!
        assertNotNull(restored.pixelsFor(1080, 2400))
    }
}
