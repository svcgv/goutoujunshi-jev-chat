package com.jev.probe.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayVisibilityTest {
    @Test fun panelIsClosedUntilTheUserExpandsIt() {
        // Browsing/switching chats leaves the panel collapsed: no auto popup.
        assertFalse(OverlayVisibility.panelVisible(expanded = false, hiddenForShot = false))
        assertTrue(OverlayVisibility.panelVisible(expanded = true, hiddenForShot = false))
    }

    @Test fun aCollapsedPanelStaysCollapsedEvenAfterARestore() {
        // Restoring after a screenshot must not open a panel the user never opened.
        assertFalse(OverlayVisibility.panelVisible(expanded = false, hiddenForShot = false))
    }

    @Test fun screenshotsHideBothSurfacesAndRestoreTheUserChoice() {
        assertFalse(OverlayVisibility.bubbleVisible(hiddenForShot = true))
        assertTrue(OverlayVisibility.bubbleVisible(hiddenForShot = false))
        assertFalse(OverlayVisibility.panelVisible(expanded = true, hiddenForShot = true))
        assertTrue(OverlayVisibility.panelVisible(expanded = true, hiddenForShot = false))
    }
}
