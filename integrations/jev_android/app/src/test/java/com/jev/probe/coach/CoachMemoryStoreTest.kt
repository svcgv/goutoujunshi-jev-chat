package com.jev.probe.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoachMemoryStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = CoachMemoryStore(temp.root)

    private fun update(field: String = "stage", value: String = "互相了解",
                       scope: MemoryScope = MemoryScope.RELATIONSHIP,
                       subject: String = "obj-a", occurred: String = "") =
        MemoryUpdate(scope, subject, field, value, occurredAt = occurred)

    @Test fun disabledMemoryRejectsWrites() {
        val result = store().apply(listOf(update()))
        assertEquals(0, result.saved)
        assertTrue(result.reason.contains("尚未同意"))
    }

    @Test fun enabledMemoryPersistsAndLoads() {
        val s = store()
        assertTrue(s.enable())
        assertEquals(1, s.apply(listOf(update())).saved)
        assertEquals(1, CoachMemoryStore(temp.root).list("obj-a").size)
    }

    @Test fun sameStableFieldUpdatesInsteadOfAccumulating() {
        val s = store(); s.enable()
        s.apply(listOf(update(value = "刚认识")))
        s.apply(listOf(update(value = "互相了解")))
        val rows = s.list("obj-a")
        assertEquals(1, rows.size)
        assertEquals("互相了解", rows.single().value)
    }

    @Test fun pauseStopsAutomaticWritesAndResumeRestoresThem() {
        val s = store(); s.enable(); assertTrue(s.pause())
        assertEquals(0, s.apply(listOf(update())).saved)
        assertTrue(s.resume())
        assertEquals(1, s.apply(listOf(update())).saved)
    }

    @Test fun undoRestoresPreviousValue() {
        val s = store(); s.enable()
        s.apply(listOf(update(value = "刚认识")))
        s.apply(listOf(update(value = "暧昧")))
        assertTrue(s.undo())
        assertEquals("刚认识", s.list("obj-a").single().value)
    }

    @Test fun eventAndHypothesisCapsAreEnforcedPerSubject() {
        val s = store(); s.enable()
        repeat(25) { i -> s.apply(listOf(update("event_$i", "事件 $i", MemoryScope.EVENT,
            occurred = "2026-10-${(i % 9) + 1}"))) }
        repeat(8) { i -> s.apply(listOf(update("hyp_$i", "暂定 $i", MemoryScope.HYPOTHESIS))) }
        assertEquals(20, s.list("obj-a").count { it.scope == MemoryScope.EVENT })
        assertEquals(5, s.list("obj-a").count { it.scope == MemoryScope.HYPOTHESIS })
    }

    @Test fun valuesAreLimitedToTwoHundredCharacters() {
        val s = store(); s.enable()
        s.apply(listOf(update(value = "长".repeat(260))))
        assertEquals(200, s.list("obj-a").single().value.length)
    }

    @Test fun recallIncludesUserButNotAnotherObject() {
        val s = store(); s.enable()
        s.apply(listOf(update("mbti", "INTJ", MemoryScope.USER, "user"),
            update("stage", "暧昧", MemoryScope.RELATIONSHIP, "obj-a"),
            update("stage", "稳定", MemoryScope.RELATIONSHIP, "obj-b")))
        val text = s.contextFor("obj-a")
        assertTrue(text.contains("INTJ"))
        assertTrue(text.contains("暧昧"))
        assertFalse(text.contains("稳定"))
    }

    @Test fun disableKeepsDataButRevokeDeleteRemovesIt() {
        val s = store(); s.enable(); s.apply(listOf(update()))
        assertTrue(s.revoke(false))
        assertFalse(s.status().enabled)
        assertEquals(1, s.status().items.size)
        assertTrue(s.revoke(true))
        assertTrue(s.status().items.isEmpty())
    }

    @Test fun correctChangesValueAndRecordsSource() {
        val s = store(); s.enable(); s.apply(listOf(update()))
        val id = s.list("obj-a").single().id
        assertTrue(s.correct(id, "已确认恋爱"))
        assertEquals("已确认恋爱", s.list("obj-a").single().value)
        assertEquals("user_correction", s.list("obj-a").single().sourceType)
    }
}
