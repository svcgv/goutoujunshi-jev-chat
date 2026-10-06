package com.jev.probe.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelPlacementTest {
    @Test fun opensToTheRightWhenThereIsRoom() {
        // bubble at x=40 on a 1080 wide screen, 316 panel, 8 margin -> 40+52+8
        assertEquals(100 to 200, PanelPlacement.place(40, 200, 52, 316, 600, 1080, 1920, 8))
    }

    @Test fun flipsToTheLeftNearTheRightEdge() {
        // No room on the right, so the panel sits just left of the bubble.
        assertEquals(1000 - 316 - 8 to 200,
            PanelPlacement.place(1000, 200, 52, 316, 600, 1080, 1920, 8))
    }

    @Test fun clampsVerticallyAndNeverGoesOffScreen() {
        val (x, y) = PanelPlacement.place(900, 1900, 52, 316, 600, 1080, 1920, 8)
        assertEquals(900 - 316 - 8, x)
        assertEquals(1920 - 8 - 600, y)
    }

    @Test fun bubblePositionIsUnchangedByPlacement() {
        // Placement is a pure function of the bubble position; it must not move it.
        val (x1, _) = PanelPlacement.place(300, 100, 52, 316, 400, 1080, 1920, 8)
        val (x2, _) = PanelPlacement.place(300, 100, 52, 316, 400, 1080, 1920, 8)
        assertEquals(x1, x2)
    }
}
