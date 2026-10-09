package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Crop bounds come from the live node tree, not from percentages: a fixed band
 * cut real messages off on tall screens.
 */
class CaptureRegionTest {

    @Test fun usesTheRealBubbleToComposerViewport() {
        // Bubble starts at 302, composer at 2503 on a 2640 tall screen.
        val region = CaptureRegionCalculator.compute(2640, firstBubbleTop = 302, composerTop = 2503)
        assertEquals(302, region.top)
        assertEquals(2503, region.bottom)
        assertEquals(2201, region.height)
    }

    @Test fun aMissingCoordinateMeansReadEverything() {
        // Without both edges we cannot bound the messages, so nothing is cut.
        val noComposer = CaptureRegionCalculator.compute(2640, firstBubbleTop = 302, composerTop = null)
        assertEquals(0, noComposer.top)
        assertEquals(2640, noComposer.bottom)

        val noBubble = CaptureRegionCalculator.compute(2640, firstBubbleTop = null, composerTop = 2503)
        assertEquals(0, noBubble.top)
        assertEquals(2640, noBubble.bottom)
    }

    @Test fun nothingKnownMeansNoCroppingAtAll() {
        val region = CaptureRegionCalculator.compute(2640, firstBubbleTop = null, composerTop = null)
        assertEquals(0, region.top)
        assertEquals(2640, region.bottom)
    }

    @Test fun aComposerAboveTheFirstBubbleFallsBackToTheWholeImage() {
        val region = CaptureRegionCalculator.compute(2640, firstBubbleTop = 2000, composerTop = 300)
        assertEquals(0, region.top)
        assertEquals(2640, region.bottom)
    }

    @Test fun outOfRangeCoordinatesAreIgnored() {
        val region = CaptureRegionCalculator.compute(2640, firstBubbleTop = -5, composerTop = 99999)
        assertEquals(0, region.top)
        assertEquals(2640, region.bottom)
    }

    @Test fun aKeyboardSqueezedViewportStillWorks() {
        // Keyboard up: bubbles start lower and the composer sits much higher.
        val region = CaptureRegionCalculator.compute(2640, firstBubbleTop = 900, composerTop = 1400)
        assertEquals(900, region.top)
        assertEquals(1400, region.bottom)
    }
}
