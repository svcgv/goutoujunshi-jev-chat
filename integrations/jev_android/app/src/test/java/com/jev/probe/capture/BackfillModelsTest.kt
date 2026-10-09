package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillModelsTest {

    private fun msg(id: Int, text: String, c: MessageCompleteness = MessageCompleteness.COMPLETE) =
        CollectedMessage("m$id", "other", text, c)

    @Test fun optionsClampTheTargetIntoRange() {
        assertEquals(BackfillPlan.MIN_TARGET, BackfillOptions(1).clampedTarget)
        assertEquals(BackfillPlan.MAX_TARGET, BackfillOptions(500).clampedTarget)
        assertEquals(50, BackfillOptions().clampedTarget)
    }

    @Test fun progressTextDistinguishesPhases() {
        assertTrue(BackfillState(BackfillPhase.TO_BOTTOM, 0, 0, 0, 50).progressText().contains("底部"))
        assertTrue(BackfillState(BackfillPhase.COLLECTING, 12, 2, 3, 50).progressText().contains("12/50"))
        assertTrue(BackfillState(BackfillPhase.COLLECTING, 12, 2, 3, 50).progressText().contains("2"))
        assertTrue(BackfillState(BackfillPhase.RETURNING, 50, 0, 40, 50).progressText().contains("50/50"))
    }

    @Test fun onlyCompleteMessagesAvoidAttention() {
        assertFalse(MessageCompleteness.COMPLETE.needsAttention)
        assertTrue(MessageCompleteness.STITCHED.needsAttention)
        assertTrue(MessageCompleteness.PARTIAL.needsAttention)
        assertTrue(MessageCompleteness.CONFLICT.needsAttention)
        assertTrue(MessageCompleteness.UNKNOWN.needsAttention)
    }

    @Test fun resultCountsIncompleteMessages() {
        val r = BackfillResult(
            messages = listOf(
                msg(1, "a"),
                msg(2, "b", MessageCompleteness.PARTIAL),
                msg(3, "c", MessageCompleteness.CONFLICT)),
            stopReason = BackfillStopReason.TARGET_REACHED,
            target = 50)
        assertEquals(2, r.incompleteCount)
        assertTrue(r.hasGap)
        assertTrue(r.reachedTarget)
        assertTrue(r.summary().contains("3/50"))
    }

    @Test fun reachingHistoryTopIsNotReportedAsTargetReached() {
        val r = BackfillResult(listOf(msg(1, "a")), BackfillStopReason.HISTORY_TOP, 50)
        assertFalse(r.reachedTarget)
        assertTrue(r.summary().contains("顶部"))
    }
}
