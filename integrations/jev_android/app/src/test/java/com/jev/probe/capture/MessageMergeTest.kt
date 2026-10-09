package com.jev.probe.capture

import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backfill reads overlapping screens while scrolling toward OLDER messages;
 * merging must produce one ordered history without duplicating what was seen and
 * without folding distinct repeated short lines together.
 */
class MessageMergeTest {

    private fun m(vararg t: String) = t.mapIndexed { i, s -> Msg(if (i % 2 == 0) "other" else "me", s) }

    private fun merge(acc: List<Msg>, screen: List<Msg>) = MessageMerge.prepend(acc, screen)

    @Test fun anEmptyAccumulatorTakesTheWholeScreen() {
        val screen = m("a", "b")
        val r = merge(emptyList(), screen)
        assertEquals(screen, r.messages)
        assertEquals(2, r.added)
        assertTrue(r.anchored)
    }

    @Test fun anEmptyScreenChangesNothing() {
        val acc = m("a", "b")
        val r = merge(acc, emptyList())
        assertEquals(acc, r.messages)
        assertEquals(0, r.added)
    }

    @Test fun anOverlappingScreenPrependsOnlyTheOlderHead() {
        // acc holds c,d,e,f; scrolling up reveals a,b,c,d → only a,b are new.
        val acc = m("c", "d", "e", "f")
        val screen = m("a", "b", "c", "d")
        val r = merge(acc, screen)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), r.messages.map { it.text })
        assertEquals(2, r.added)
        assertTrue(r.anchored)
    }

    @Test fun anIdenticalScreenAddsNothing() {
        val acc = m("a", "b", "c")
        val r = merge(acc, m("a", "b", "c"))
        assertEquals(acc.map { it.text }, r.messages.map { it.text })
        assertEquals(0, r.added)
        assertTrue(r.anchored)
    }

    @Test fun repeatedShortLinesWithinAScreenAreKept() {
        val screen = m("嗯", "好的", "嗯", "好的", "嗯")
        val r = merge(emptyList(), screen)
        assertEquals(5, r.messages.size)
        assertEquals(5, r.added)
    }

    @Test fun aLongBubbleSplitAcrossScreensIsStitchedIntoOne() {
        val acc = listOf(
            Msg("other", "这是一条很长的消息，前半段在这里"),
            Msg("me", "收到"))
        // Scrolling up shows older content plus the TOP half of the same long bubble.
        val screen = listOf(
            Msg("other", "开场白"),
            Msg("other", "这是一条很长的"))
        val r = merge(acc, screen)
        assertEquals(listOf("开场白", "这是一条很长的消息，前半段在这里", "收到"),
            r.messages.map { it.text })
        assertEquals(1, r.added)
        assertTrue(r.anchored)
    }

    @Test fun aGrowingBubbleKeepsTheFullerText() {
        val acc = listOf(Msg("other", "前半"))
        val screen = listOf(Msg("other", "前半后半"))
        val r = merge(acc, screen)
        assertEquals(listOf("前半后半"), r.messages.map { it.text })
        assertTrue(r.anchored)
    }

    @Test fun duplicateTextWithDifferentSidesIsNotTreatedAsTheSameMessage() {
        val acc = listOf(Msg("me", "在吗"))
        val r = merge(acc, listOf(Msg("other", "在吗")))
        assertEquals(2, r.messages.size)
    }

    @Test fun anUnrelatedScreenIsReportedAsAGapRatherThanGuessed() {
        val acc = m("c", "d", "e")
        val screen = m("x", "y", "z")
        val r = merge(acc, screen)
        // No reliable anchor: keep every new row but flag the gap.
        assertFalse(r.anchored)
        assertEquals(6, r.messages.size)
    }

    @Test fun aContiguousRunOfDuplicateShortLinesStillAnchors() {
        // acc ends with c,d; the next screen re-shows c,d as its tail.
        val acc = listOf(Msg("other", "嗯"), Msg("me", "嗯"), Msg("other", "在吗"), Msg("me", "好的"))
        val screen = listOf(Msg("other", "在吗"), Msg("me", "好的"))
        val r = merge(acc, screen)
        assertEquals(acc.map { it.text }, r.messages.map { it.text })
        assertTrue(r.anchored)
    }
}
