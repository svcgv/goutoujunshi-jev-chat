package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListEndSignalTest {

    @Test fun theLastReportedRowMeansTheBottomIsVisible() {
        assertEquals(true, ListEndSignal.from(rowCount = 40, maxVisibleRowEnd = 40))
        assertEquals(true, ListEndSignal.from(rowCount = 40, maxVisibleRowEnd = 41))
    }

    @Test fun aLowerRowEndMeansMoreMessagesFollow() {
        assertEquals(false, ListEndSignal.from(rowCount = 40, maxVisibleRowEnd = 30))
    }

    @Test fun missingMetadataYieldsNoAnswer() {
        assertNull(ListEndSignal.from(rowCount = null, maxVisibleRowEnd = 10))
        assertNull(ListEndSignal.from(rowCount = 40, maxVisibleRowEnd = null))
        assertNull(ListEndSignal.from(rowCount = 0, maxVisibleRowEnd = 0))
        assertNull(ListEndSignal.from(rowCount = 40, maxVisibleRowEnd = -1))
    }
}
