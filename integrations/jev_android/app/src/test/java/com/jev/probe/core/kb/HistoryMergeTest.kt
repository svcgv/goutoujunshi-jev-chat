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

/**
 * A backfill reads a WIDER window than the few lines saved earlier, so the
 * stored rows usually sit inside the new batch — which must not make the merge
 * treat the batch as older and push them to the end.
 */
class HistoryMergeWideningTest {

    private fun e(text: String) = LogEntry("other", text, 0L, "app")

    @Test fun aBatchThatContainsTheStoredTimelineReplacesItInOrder() {
        val existing = listOf(e("m4"), e("m5"))                        // saved last time
        val batch = listOf(e("m1"), e("m2"), e("m3"), e("m4"), e("m5"), e("m6")) // backfill now
        val r = HistoryMerge.insert(existing, batch)
        assertTrue(r.anchored)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"), r.entries.map { it.text })
        assertEquals(4, r.inserted)
    }

    @Test fun theOlderStoredRowsNeverEndUpLast() {
        val existing = listOf(e("a"), e("b"))
        val batch = listOf(e("a"), e("b"), e("c"))
        val r = HistoryMerge.insert(existing, batch)
        assertEquals("c", r.entries.last().text)
        assertTrue(r.anchored)
    }

    @Test fun aBatchStillPrependingOlderHistoryKeepsWorking() {
        // The batch is genuinely older content that merely overlaps the start.
        val existing = listOf(e("c"), e("d"), e("e"))
        val batch = listOf(e("a"), e("b"), e("c"), e("d"))
        val r = HistoryMerge.insert(existing, batch)
        assertEquals(listOf("a", "b", "c", "d", "e"), r.entries.map { it.text })
        assertTrue(r.anchored)
    }
}
