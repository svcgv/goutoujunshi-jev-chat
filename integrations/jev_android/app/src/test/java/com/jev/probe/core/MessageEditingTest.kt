package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageEditingTest {

    @Test fun splitKeepsBothHalvesAndTrimsTheSeam() {
        val (a, b) = MessageEditing.split("前半 后半", 3)!!
        assertEquals("前半", a)
        assertEquals("后半", b)
    }

    @Test fun splitKeepsInternalNewlines() {
        // Cursor sits on the newline after the second paragraph.
        val (a, b) = MessageEditing.split("第一段\n第二段\n第三段", 7)!!
        assertEquals("第一段\n第二段", a)
        assertEquals("第三段", b)
    }

    @Test fun splittingAtEitherEdgeIsRejected() {
        assertNull(MessageEditing.split("abc", 0))
        assertNull(MessageEditing.split("abc", 3))
        assertNull(MessageEditing.split("abc", 99))
    }

    @Test fun splittingWhereOneHalfIsBlankIsRejected() {
        assertNull(MessageEditing.split("   abc", 3))
    }

    @Test fun mergeJoinsAdjacentMessagesWithOneSeparatingNewline() {
        assertEquals("上半\n下半", MessageEditing.merge("上半", "下半"))
        // Existing trailing/leading newlines are normalised to exactly one seam.
        assertEquals("上半\n下半", MessageEditing.merge("上半\n", "\n下半"))
        // A paragraph break inside one side survives.
        assertEquals("上半\n\n中间\n下半", MessageEditing.merge("上半\n\n中间", "\n下半"))
        assertEquals("只有一段", MessageEditing.merge("", "只有一段"))
        assertEquals("只有一段", MessageEditing.merge("只有一段", "   "))
    }

    @Test fun removeAtDropsExactlyOneMessage() {
        val msgs = listOf(Msg("me", "a"), Msg("other", "b"), Msg("me", "c"))
        assertEquals(listOf("a", "c"), MessageEditing.removeAt(msgs, 1).map { it.text })
        assertEquals(msgs, MessageEditing.removeAt(msgs, 9))
        assertEquals(msgs, MessageEditing.removeAt(msgs, -1))
    }

    @Test fun removingARepeatedMessageLeavesTheOthersIntact() {
        val msgs = listOf(Msg("other", "嗯"), Msg("me", "嗯"), Msg("other", "嗯"))
        assertEquals(2, MessageEditing.removeAt(msgs, 1).size)
        assertEquals(listOf("other", "other"), MessageEditing.removeAt(msgs, 1).map { it.side })
    }
}
