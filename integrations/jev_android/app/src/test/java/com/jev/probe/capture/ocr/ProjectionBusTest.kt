package com.jev.probe.capture.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionBusTest {

    @Test fun requestsAreRefusedWhenNoSessionIsLive() {
        ProjectionFrameBus.clear()
        assertFalse(ProjectionFrameBus.isActive())
        var error: String? = null
        ProjectionFrameBus.request { _, e -> error = e }
        assertTrue(error != null)
    }

    @Test fun aPublishedSessionAnswersExactlyOnce() {
        ProjectionFrameBus.publish { cb -> cb(null, "stub") }
        assertTrue(ProjectionFrameBus.isActive())
        var calls = 0
        ProjectionFrameBus.request { _, _ -> calls++ }
        assertEquals(1, calls)
        ProjectionFrameBus.clear()
        assertFalse(ProjectionFrameBus.isActive())
    }

    @Test fun clearStopsServingFurtherFrames() {
        ProjectionFrameBus.publish { cb -> cb(null, null) }
        ProjectionFrameBus.clear()
        var error: String? = "unset"
        ProjectionFrameBus.request { _, e -> error = e }
        assertTrue(error != null)
    }

    @Test fun aThrowingRequesterIsReportedRatherThanPropagated() {
        ProjectionFrameBus.publish { _ -> throw IllegalStateException("boom") }
        var error: String? = null
        ProjectionFrameBus.request { _, e -> error = e }
        assertTrue(error != null)
        ProjectionFrameBus.clear()
    }
}

class ProjectionSessionHandoffTest {

    @Test fun readyFiresTheCallbackOnce() {
        var ok: Boolean? = null
        val id = ProjectionSessionHandoff.begin { success, _ -> ok = success }
        assertTrue(ProjectionSessionHandoff.isCurrent(id))
        ProjectionSessionHandoff.ready(id)
        assertEquals(true, ok)
        assertFalse(ProjectionSessionHandoff.isCurrent(id))
    }

    @Test fun aStaleGenerationCannotFinishANewerOne() {
        val first = ProjectionSessionHandoff.begin { _, _ -> }
        val second = ProjectionSessionHandoff.begin { _, _ -> }
        assertFalse(ProjectionSessionHandoff.isCurrent(first))
        assertTrue(ProjectionSessionHandoff.isCurrent(second))
        ProjectionSessionHandoff.fail(first, "stale")
        assertTrue(ProjectionSessionHandoff.isCurrent(second))
        ProjectionSessionHandoff.ready(second)
    }

    @Test fun failReportsTheReason() {
        var error: String? = null
        var ok: Boolean? = null
        val id = ProjectionSessionHandoff.begin { success, e -> ok = success; error = e }
        ProjectionSessionHandoff.fail(id, "declined")
        assertEquals(false, ok)
        assertEquals("declined", error)
    }
}
