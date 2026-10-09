package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The title search band must not collapse when the message list is scrolled
 * under the action bar — that silently hid the conversation title.
 */
class ActionBarSearchTest {

    private val screen = 2640
    private val band = (screen * 0.14f).toInt()   // 369

    @Test fun theBandIsTheTopOfTheScreen() {
        assertEquals(band, actionBarSearchBottom(screen))
    }

    @Test fun theBandDoesNotDependOnAnyBubblePosition() {
        // Regression: the band used to be min(firstBubbleTop, band), so a list
        // scrolled under the action bar collapsed it to nothing and the title
        // disappeared even though it was visible on screen.
        assertEquals(band, actionBarSearchBottom(screen))
        assertTrue(actionBarSearchBottom(screen) > 0)
    }

    @Test fun theBandIsAlwaysUsable() {
        assertTrue(actionBarSearchBottom(2640) > 0)
        assertTrue(actionBarSearchBottom(1080) > 0)
        assertTrue(actionBarSearchBottom(1) >= 0)
    }
}
