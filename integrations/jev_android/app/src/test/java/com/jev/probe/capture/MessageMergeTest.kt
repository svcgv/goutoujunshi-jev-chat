package com.jev.probe.capture

import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Backfill reads overlapping screens; merging must produce one ordered history
 * without duplicating what was already seen.
 */
class MessageMergeTest {

    private fun m(vararg t: String) = t.mapIndexed { i, s -> Msg(if (i % 2 == 0) "other" else "me", s) }

    @Test fun anEmptyAccumulatorTakesTheWholeScreen() {
        val screen = m("a", "b")
        val r = MessageMerge.merge(emptyList(), screen)
        assertEquals(screen, r.messages)
        assertEquals(2, r.added)
    }

    @Test fun anOverlappingScreenAppendsOnlyTheNewTail() {
        val acc = m("a", "b", "c")
        val screen = m("c", "d", "e")
        val r = MessageMerge.merge(acc, screen)
        assertEquals(listOf("a", "b", "c", "d", "e"), r.messages.map { it.text })
        assertEquals(2, r.added)
    }

    @Test fun anIdenticalScreenAddsNothing() {
        val acc = m("a", "b", "c")
        val r = MessageMerge.merge(acc, m("a", "b", "c"))
        assertEquals(3, r.messages.size)
        assertEquals(0, r.added)
    }

    @Test fun anEntirelyOlderScreenIsPrepended() {
        val acc = m("c", "d")
        val r = MessageMerge.merge(acc, m("a", "b"))
        assertEquals(listOf("a", "b", "c", "d"), r.messages.map { it.text })
        assertEquals(2, r.added)
    }

    @Test fun duplicateTextWithDifferentSidesIsNotTreatedAsTheSameMessage() {
        val acc = listOf(Msg("me", "在吗"))
        val r = MessageMerge.merge(acc, listOf(Msg("other", "在吗")))
        assertEquals(2, r.messages.size)
    }

    @Test fun aFullOverlapAddsNothing() {
        val acc = m("a", "b")
        val r = MessageMerge.merge(acc, m("a", "b", "c").subList(0, 2))
        assertEquals(0, r.added)
    }
}
