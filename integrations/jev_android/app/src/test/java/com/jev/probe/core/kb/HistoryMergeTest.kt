package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMergeTest {

    private fun e(side: String, text: String) = LogEntry(side, text, 0L, "app")

    @Test fun anEmptyTimelineTakesTheWholeBatch() {
        val r = HistoryMerge.insert(emptyList(), listOf(e("other", "a"), e("me", "b")))
        assertEquals(2, r.inserted)
        assertTrue(r.anchored)
    }

    @Test fun overlappingOlderBatchIsPrepended() {
        val existing = listOf(e("other", "c"), e("me", "d"), e("other", "e"))
        val older = listOf(e("other", "a"), e("me", "b"), e("other", "c"), e("me", "d"))
        val r = HistoryMerge.insert(existing, older)
        assertEquals(listOf("a", "b", "c", "d", "e"), r.entries.map { it.text })
        assertEquals(2, r.inserted)
        assertTrue(r.anchored)
    }

    @Test fun reinsertingTheSameBatchIsIdempotent() {
        val existing = listOf(e("other", "a"), e("me", "b"))
        val r = HistoryMerge.insert(existing, listOf(e("other", "a"), e("me", "b")))
        assertEquals(0, r.inserted)
        assertTrue(r.anchored)
        assertEquals(2, r.entries.size)
    }

    @Test fun repeatedShortLinesAreKeptAsDistinctRows() {
        // existing already holds a run of "嗯"; the older batch prepends one more
        // and re-shows the same run. Nothing may be folded together.
        val existing = listOf(e("other", "嗯"), e("me", "好的"), e("other", "嗯"), e("me", "在吗"))
        val older = listOf(e("me", "嗯"), e("other", "嗯"), e("me", "好的"), e("other", "嗯"))
        val r = HistoryMerge.insert(existing, older)
        assertTrue(r.anchored)
        assertEquals(1, r.inserted)
        assertEquals(listOf("嗯", "嗯", "好的", "嗯", "在吗"), r.entries.map { it.text })
    }

    @Test fun aDisjointBatchIsReportedAsUnanchored() {
        val existing = listOf(e("other", "c"), e("me", "d"))
        val older = listOf(e("other", "x"), e("me", "y"))
        val r = HistoryMerge.insert(existing, older)
        assertFalse(r.anchored)
        assertEquals(4, r.entries.size)
    }
}
