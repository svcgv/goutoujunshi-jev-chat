package com.jev.probe.capture

import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillEngineTest {

    // ---- scroll planning -------------------------------------------------

    @Test fun towardOlderMovesTheFingerDownward() {
        val step = BackfillScroll.plan(1000, 200, 2000, towardOlder = true,
            step = BackfillScroll.COLLECT_STEP)!!
        assertTrue(step.endY > step.startY)
        assertTrue(step.startY >= 200)
        assertTrue(step.endY <= 2000)
    }

    @Test fun towardNewerMovesTheFingerUpward() {
        val step = BackfillScroll.plan(1000, 200, 2000, towardOlder = false,
            step = BackfillScroll.COLLECT_STEP)!!
        assertTrue(step.endY < step.startY)
        assertTrue(step.endY >= 200)
        assertTrue(step.startY <= 2000)
    }

    @Test fun aTinyMessageAreaProducesNoGesture() {
        assertNull(BackfillScroll.plan(1000, 200, 210, towardOlder = true, step = 0.5f))
        assertNull(BackfillScroll.plan(0, 200, 2000, towardOlder = true, step = 0.5f))
    }

    @Test fun stepIsClampedToASaneFraction() {
        val tiny = BackfillScroll.plan(1000, 200, 2000, true, 0.001f)!!
        val huge = BackfillScroll.plan(1000, 200, 2000, true, 5f)!!
        assertTrue(tiny.endY - tiny.startY >= 20)
        // clamp(0.80) of 1800 = 1440, but margin keeps it inside the area.
        assertTrue(huge.endY <= 2000 - 180)
    }

    // ---- screen signature ------------------------------------------------

    @Test fun identicalScreensShareASignature() {
        val a = listOf(Msg("other", "你好"), Msg("me", "在"))
        val b = listOf(Msg("other", "你好"), Msg("me", "在"))
        assertEquals(ScreenSignature.of(a), ScreenSignature.of(b))
    }

    @Test fun differentScreensDifferInSignature() {
        val a = listOf(Msg("other", "你好"))
        val b = listOf(Msg("other", "你好啊"))
        assertFalse(ScreenSignature.of(a) == ScreenSignature.of(b))
    }

    // ---- accumulator -----------------------------------------------------

    @Test fun accumulatorPrependsOlderScreensAndCountsAdded() {
        val acc = BackfillAccumulator(target = 50)
        acc.fold(listOf(Msg("other", "c"), Msg("me", "d")))
        assertEquals(2, acc.lastAdded)
        val r = acc.fold(listOf(Msg("other", "a"), Msg("me", "b"), Msg("other", "c"), Msg("me", "d")))
        assertEquals(2, r.added)
        assertEquals(listOf("a", "b", "c", "d"), acc.snapshot().map { it.text })
        assertTrue(acc.lastAnchored)
    }

    @Test fun resultMessagesKeepsOnlyTheNewestTarget() {
        val acc = BackfillAccumulator(target = 3)
        acc.fold((1..6).map { Msg(if (it % 2 == 0) "me" else "other", "m$it") })
        assertEquals(listOf("m4", "m5", "m6"), acc.resultMessages().map { it.text })
    }

    @Test fun aStaleScreenAddsNothing() {
        val acc = BackfillAccumulator(target = 50)
        acc.fold(listOf(Msg("other", "a"), Msg("me", "b")))
        val r = acc.fold(listOf(Msg("other", "a"), Msg("me", "b")))
        assertEquals(0, r.added)
        assertTrue(BackfillPlan.isStale(r.added))
    }

    @Test fun accumulatingAcceptsEachBubbleExactlyOnce() {
        // Repeated identical short lines must all survive.
        val acc = BackfillAccumulator(target = 50)
        acc.fold(listOf(Msg("other", "嗯"), Msg("me", "嗯")))
        acc.fold(listOf(Msg("other", "嗯"), Msg("me", "嗯"), Msg("other", "嗯"), Msg("me", "嗯")))
        assertNotNull(acc.snapshot())
        assertEquals(4, acc.snapshot().size)
    }
}
