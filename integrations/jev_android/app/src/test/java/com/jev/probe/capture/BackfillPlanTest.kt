package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backfill drives the chat UI, so it must always terminate: on the target, on a
 * swipe cap, or when scrolling stops producing new messages.
 */
class BackfillPlanTest {

    @Test fun defaultTargetIsFifty() {
        assertEquals(50, BackfillPlan.TARGET_MESSAGES)
    }

    @Test fun stopsOnceTheTargetIsReached() {
        assertFalse(BackfillPlan.shouldContinue(collected = 50))
        assertFalse(BackfillPlan.shouldContinue(collected = 120))
        assertTrue(BackfillPlan.shouldContinue(collected = 49))
    }

    @Test fun stopsAtTheSwipeCapEvenIfTheTargetIsUnmet() {
        assertFalse(BackfillPlan.shouldContinue(collected = 10, swipes = BackfillPlan.MAX_SWIPES))
        assertTrue(BackfillPlan.shouldContinue(collected = 10, swipes = BackfillPlan.MAX_SWIPES - 1))
    }

    @Test fun stopsWhenScrollingStopsProducingNewMessages() {
        assertFalse(BackfillPlan.shouldContinue(collected = 10, staleScreens = BackfillPlan.MAX_STALE_SCREENS))
        assertTrue(BackfillPlan.shouldContinue(collected = 10, staleScreens = BackfillPlan.MAX_STALE_SCREENS - 1))
    }

    @Test fun aScreenWithNoNewMessagesCountsAsStale() {
        assertTrue(BackfillPlan.isStale(0))
        assertTrue(BackfillPlan.isStale(-1))
        assertFalse(BackfillPlan.isStale(1))
    }

    @Test fun aCustomTargetIsHonoured() {
        assertFalse(BackfillPlan.shouldContinue(collected = 20, target = 20))
        assertTrue(BackfillPlan.shouldContinue(collected = 19, target = 20))
    }

    @Test fun targetIsClampedToSupportedRange() {
        assertEquals(BackfillPlan.MIN_TARGET, BackfillPlan.clampTarget(0))
        assertEquals(BackfillPlan.MIN_TARGET, BackfillPlan.clampTarget(-5))
        assertEquals(BackfillPlan.MAX_TARGET, BackfillPlan.clampTarget(999))
        assertEquals(50, BackfillPlan.clampTarget(50))
    }

    @Test fun progressIsHumanReadable() {
        assertTrue(BackfillPlan.progress(35).contains("35"))
        assertTrue(BackfillPlan.progress(35).contains("50"))
    }
}
