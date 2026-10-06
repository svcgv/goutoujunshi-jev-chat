package com.jev.probe.overlay

import org.junit.Assert.*
import org.junit.Test

class BubblePositionTest {
    @Test fun freePositionDoesNotSnapToEdge() {
        assertEquals(350 to 400, BubblePosition.clamp(350, 400, 1080, 1920, 52, 8))
    }
    @Test fun restoreClampsAfterRotationOrSmallerDisplay() {
        assertEquals(420 to 740, BubblePosition.clamp(900, 1600, 480, 800, 52, 8))
    }
    @Test fun negativeOrTinyBoundsNeverThrowOrHideBubble() {
        assertEquals(8 to 8, BubblePosition.clamp(-1, -1, 1080, 1920, 52, 8))
        assertEquals(0 to 0, BubblePosition.clamp(100, 100, 20, 20, 52, 8))
    }
}
