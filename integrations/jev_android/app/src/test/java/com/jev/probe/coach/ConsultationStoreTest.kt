package com.jev.probe.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConsultationStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = ConsultationStore(temp.root)
    private fun turn(text: String) = StoredTurn("问题", text, CoachTask.CONSULT, 1L)

    @Test fun defaultOffDoesNotPersistTranscript() {
        val s = store()
        assertFalse(s.enabled())
        assertTrue(s.saveTurn("session", "obj-a", "小雨", turn("回答")))
        assertTrue(s.list().isEmpty())
    }

    @Test fun enabledStoreSavesAndReloadsTurns() {
        val s = store(); assertTrue(s.setEnabled(true))
        assertTrue(s.saveTurn("session", "obj-a", "小雨", turn("第一轮")))
        assertTrue(s.saveTurn("session", "obj-a", "小雨", turn("第二轮")))
        val loaded = ConsultationStore(temp.root).get("session")!!
        assertEquals(2, loaded.turns.size)
        assertEquals("第二轮", loaded.turns.last().assistant)
    }

    @Test fun clearKeepsOptInButDeletesTranscript() {
        val s = store(); s.setEnabled(true); s.saveTurn("a", null, "咨询", turn("回答"))
        assertTrue(s.clear())
        assertTrue(s.enabled())
        assertTrue(s.list().isEmpty())
    }
}
