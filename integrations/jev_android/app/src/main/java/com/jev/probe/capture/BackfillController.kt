package com.jev.probe.capture

import com.jev.probe.core.Msg

/** One visible screen as read during a run. */
internal data class BackfillScreen(
    val messages: List<Msg>,
    val viewportTop: Int,
    val viewportBottom: Int
)

/**
 * Drives one automatic history backfill run.
 *
 * The run is a strict serial loop — scroll → settle → read → fold — that always
 * terminates on the target, the history top, a swipe cap or an unreadable screen.
 * All side effects (reading the screen, dispatching a gesture, scheduling the
 * next tick) are injected lambdas so the whole state machine can be unit-tested
 * on the JVM.
 *
 * Reaching the bottom is verified before collecting: while scrolling toward newer
 * messages we require two consecutive identical reads, so one lucky frame is
 * never mistaken for "already at the bottom".
 */
internal class BackfillController(
    private val options: BackfillOptions,
    private val postDelayed: (Long, () -> Unit) -> Unit,
    private val readScreen: () -> BackfillScreen?,
    private val scroll: (ScrollStep, () -> Unit) -> Unit,
    private val widthPx: () -> Int,
    private val running: () -> Boolean,
    private val progress: (BackfillState) -> Unit,
    private val onFinish: (BackfillResult) -> Unit
) {
    private val target = options.clampedTarget
    private val acc = BackfillAccumulator(target)

    private var phase = BackfillPhase.TO_BOTTOM
    private var swipes = 0
    private var stale = 0
    private var bottomSwipes = 0
    private var returnSwipes = 0
    private var lastSignature = ""
    private var lastArea: Pair<Int, Int>? = null
    private var finished = false
    private var sawGap = false
    private var readFailures = 0
    private var stopReason: BackfillStopReason = BackfillStopReason.HISTORY_TOP

    fun start() {
        if (finished) return
        phase = BackfillPhase.TO_BOTTOM
        emit()
        stepToBottom()
    }

    // ---- reach the bottom ------------------------------------------------

    private fun stepToBottom() {
        if (!running()) return cancel()
        if (bottomSwipes >= BackfillPlan.MAX_BOTTOM_SWIPES) {
            return complete(BackfillStopReason.BOTTOM_UNCONFIRMED)
        }
        val screen = readScreen() ?: return failRead { stepToBottom() }
        readFailures = 0
        val area = screen.viewportTop to screen.viewportBottom
        val sig = ScreenSignature.of(screen.messages)
        val stable = sig == lastSignature && area == lastArea
        lastSignature = sig
        lastArea = area
        if (stable && bottomSwipes > 0) return beginCollect(screen)

        val plan = BackfillScroll.plan(widthPx(), area.first, area.second,
            towardOlder = false, step = BackfillScroll.COLLECT_STEP)
            ?: return complete(BackfillStopReason.BOTTOM_UNCONFIRMED)
        bottomSwipes++
        scroll(plan) { schedule(SETTLE_MS) { stepToBottom() } }
    }

    // ---- collect older messages -----------------------------------------

    private fun beginCollect(screen: BackfillScreen) {
        phase = BackfillPhase.COLLECTING
        acc.fold(screen.messages)
        emit()
        if (!BackfillPlan.shouldContinue(acc.collected, target, swipes, stale)) {
            return complete(reasonForStop())
        }
        scrollOlder(BackfillScroll.COLLECT_STEP)
    }

    private fun collectStep() {
        if (!running()) return cancel()
        val screen = readScreen() ?: return failRead { collectStep() }
        readFailures = 0
        lastArea = screen.viewportTop to screen.viewportBottom

        acc.fold(screen.messages)
        if (acc.lastAdded > 0 && !acc.lastAnchored) sawGap = true
        val sig = ScreenSignature.of(screen.messages)
        val moved = sig != lastSignature
        lastSignature = sig
        stale = if (acc.lastAdded > 0 || (moved && acc.lastAnchored)) 0 else stale + 1
        emit()

        if (!BackfillPlan.shouldContinue(acc.collected, target, swipes, stale)) {
            return complete(reasonForStop())
        }
        // A gap gets one smaller re-read before we trust it.
        val step = if (!acc.lastAnchored && acc.lastAdded > 0) BackfillScroll.RECOVERY_STEP
                   else BackfillScroll.COLLECT_STEP
        scrollOlder(step)
    }

    private fun scrollOlder(step: Float) {
        if (!running()) return cancel()
        val area = lastArea ?: return complete(BackfillStopReason.UNREADABLE)
        val plan = BackfillScroll.plan(widthPx(), area.first, area.second, towardOlder = true, step = step)
            ?: return complete(BackfillStopReason.UNREADABLE)
        swipes++
        scroll(plan) { schedule(SETTLE_MS) { collectStep() } }
    }

    private fun reasonForStop(): BackfillStopReason = when {
        acc.collected >= target -> BackfillStopReason.TARGET_REACHED
        stale >= BackfillPlan.MAX_STALE_SCREENS -> BackfillStopReason.HISTORY_TOP
        swipes >= BackfillPlan.MAX_SWIPES -> BackfillStopReason.SCROLL_LIMIT
        else -> BackfillStopReason.HISTORY_TOP
    }

    // ---- return to the newest messages ----------------------------------

    private fun complete(reason: BackfillStopReason) {
        if (reason == BackfillStopReason.CANCELED) return cancel()
        // Already returning: a failure here must finish, not restart the return.
        if (phase == BackfillPhase.RETURNING) return build()
        stopReason = reason
        phase = BackfillPhase.RETURNING
        // Reset the stability baseline: the first return read is the same screen
        // we just collected from, so it must not count as "already at the bottom".
        lastSignature = ""
        lastArea = null
        emit()
        stepReturn()
    }

    private fun stepReturn() {
        if (!running()) return cancel()
        if (returnSwipes >= BackfillPlan.MAX_RETURN_SWIPES) return build()
        val screen = readScreen() ?: return failRead { stepReturn() }
        readFailures = 0
        val area = screen.viewportTop to screen.viewportBottom
        val sig = ScreenSignature.of(screen.messages)
        val stable = sig == lastSignature && area == lastArea
        lastSignature = sig
        lastArea = area
        if (stable && returnSwipes > 0) return build()
        val plan = BackfillScroll.plan(widthPx(), area.first, area.second,
            towardOlder = false, step = BackfillScroll.COLLECT_STEP) ?: return build()
        returnSwipes++
        scroll(plan) { schedule(SETTLE_MS) { stepReturn() } }
    }

    // ---- termination -----------------------------------------------------

    private fun build() {
        if (finished) return
        finished = true
        phase = BackfillPhase.DONE
        emit()
        val completeness = if (sawGap) MessageCompleteness.STITCHED else MessageCompleteness.COMPLETE
        val messages = acc.resultMessages().mapIndexed { i, m ->
            CollectedMessage(id = "m${i + 1}", side = m.side, text = m.text, completeness = completeness)
        }
        onFinish(BackfillResult(messages, stopReason, target))
    }

    private fun cancel() {
        if (finished) return
        finished = true
    }

    /**
     * A single unreadable frame is worth retrying; a screen that stays unreadable
     * (empty node tree, protected window, wrong window) must terminate the run
     * instead of rescheduling forever.
     */
    private fun failRead(again: () -> Unit) {
        if (!running()) return cancel()
        readFailures++
        if (readFailures > MAX_READ_FAILURES) {
            if (phase == BackfillPhase.COLLECTING) return complete(BackfillStopReason.UNREADABLE)
            return complete(BackfillStopReason.BOTTOM_UNCONFIRMED)
        }
        schedule(RETRY_MS, again)
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        postDelayed(delayMs) { if (running()) block() }
    }

    private fun emit() {
        progress(BackfillState(phase, acc.collected.coerceAtMost(target), 0, swipes, target))
    }

    companion object {
        const val SETTLE_MS = 350L
        const val RETRY_MS = 200L

        /** Consecutive unreadable frames tolerated before the run gives up. */
        const val MAX_READ_FAILURES = 10
    }
}
