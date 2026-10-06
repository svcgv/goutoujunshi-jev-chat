package com.jev.probe.capture.ocr

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class CaptureHandoffTest {
    @After fun clear() { CaptureHandoff.cancel() }
    @Test fun oldConsentCannotCompleteNewConversationCapture() {
        var oldCalls = 0; var newCalls = 0
        val old = CaptureHandoff.begin { _, _ -> oldCalls++ }
        val next = CaptureHandoff.begin { _, _ -> newCalls++ }
        CaptureHandoff.finish(old, null, "cancelled")
        assertEquals(0, oldCalls); assertEquals(0, newCalls)
        assertTrue(CaptureHandoff.isCurrent(next))
        CaptureHandoff.finish(next, null, "done")
        assertEquals(1, newCalls)
        CaptureHandoff.finish(next, null, "late")
        assertEquals(1, newCalls)
    }
    @Test fun frameMustBeFromTheUserSelectedWindow() {
        var sameWindow = true
        val id = CaptureHandoff.begin(allowFrame = { sameWindow }) { _, _ -> }
        assertTrue(CaptureHandoff.canCapture(id))
        sameWindow = false
        assertFalse(CaptureHandoff.canCapture(id))
        assertFalse(CaptureHandoff.canCapture("old"))
    }
    @Test fun disableOrWindowSwitchCancelsPendingCapture() {
        var calls = 0
        val id = CaptureHandoff.begin { _, _ -> calls++ }
        CaptureHandoff.cancel()
        CaptureHandoff.finish(id, null)
        assertEquals(0, calls)
        assertFalse(CaptureHandoff.isCurrent(id))
    }
}
