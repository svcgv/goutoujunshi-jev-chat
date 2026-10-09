package com.jev.probe.capture

import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end state machine over a scripted chat: the controller must reach the
 * bottom, walk toward older messages, stop on the target, and always terminate.
 */
class BackfillControllerTest {

    /** Runs posted ticks synchronously so the loop completes inside the test. */
    private class FakeScheduler {
        private val tasks = ArrayDeque<() -> Unit>()
        fun post(delayMs: Long, block: () -> Unit) { tasks.addLast(block) }
        fun runAll(limit: Int = 20_000) {
            var n = 0
            while (tasks.isNotEmpty() && n < limit) { tasks.removeFirst().invoke(); n++ }
        }
    }

    /** A scrollable conversation: a window of [window] messages out of [total]. */
    private class FakeChat(val total: Int, val window: Int, var end: Int,
                           val reportsListEnd: Boolean = false) {
        var newerScrolls = 0
            private set

        fun screen(): BackfillScreen {
            val to = end.coerceIn(minOf(window, total), total)
            val from = (to - window).coerceAtLeast(0)
            val msgs = (from until to).map { Msg(if (it % 2 == 0) "other" else "me", "m$it") }
            val atEnd = if (reportsListEnd) to >= total else null
            return BackfillScreen(msgs, 100, 1100, atEnd)
        }
        fun scroll(towardOlder: Boolean, move: Int) {
            if (!towardOlder) newerScrolls++
            val step = maxOf(1, move)
            end = if (towardOlder) (end - step).coerceAtLeast(minOf(window, total))
                  else (end + step).coerceAtMost(total)
        }
    }

    private class Run(val result: BackfillResult, val finalPhase: BackfillPhase, val progress: List<BackfillState>)

    private fun run(chat: FakeChat, target: Int): Run {
        val sched = FakeScheduler()
        var out: BackfillResult? = null
        val states = ArrayList<BackfillState>()
        var phase = BackfillPhase.IDLE
        val controller = BackfillController(
            options = BackfillOptions(target),
            postDelayed = sched::post,
            requestScreen = { cb -> cb(BackfillRead.Screen(chat.screen())) },
            scroll = { step, done ->
                // Translate the planned finger travel into whole messages.
                val areaHeight = 1000
                val travel = kotlin.math.abs(step.endY - step.startY)
                val move = maxOf(1, chat.window * travel / areaHeight)
                chat.scroll(towardOlder = step.endY > step.startY, move = move)
                done()
            },
            widthPx = { 1000 },
            running = { out == null },
            progress = { states.add(it); phase = it.phase },
            onFinish = { out = it })
        controller.start()
        sched.runAll()
        return Run(out!!, phase, states)
    }

    @Test fun reachesTheBottomThenCollectsTheNewestTarget() {
        // Start in the middle of a long conversation.
        val chat = FakeChat(total = 200, window = 10, end = 40)
        val run = run(chat, target = 50)
        assertEquals(BackfillStopReason.TARGET_REACHED, run.result.stopReason)
        assertEquals(50, run.result.messages.size)
        assertEquals("m150", run.result.messages.first().text)
        assertEquals("m199", run.result.messages.last().text)
        assertEquals(BackfillPhase.DONE, run.finalPhase)
        // It must have returned to the newest messages before finishing.
        assertTrue(chat.end >= 190)
        assertTrue(run.result.summary().contains("50/50"))
    }

    @Test fun aShortConversationStopsAtTheHistoryTop() {
        val chat = FakeChat(total = 8, window = 10, end = 8)
        val run = run(chat, target = 50)
        assertEquals(BackfillStopReason.HISTORY_TOP, run.result.stopReason)
        assertEquals(8, run.result.messages.size)
        assertEquals("m0", run.result.messages.first().text)
        assertTrue(run.result.summary().contains("顶部"))
    }

    @Test fun progressReportsTheBottomThenCollectingPhases() {
        val chat = FakeChat(total = 120, window = 10, end = 100)
        val run = run(chat, target = 50)
        assertTrue(run.progress.any { it.phase == BackfillPhase.TO_BOTTOM })
        assertTrue(run.progress.any { it.phase == BackfillPhase.COLLECTING })
        assertTrue(run.progress.any { it.phase == BackfillPhase.RETURNING })
    }

    @Test fun anUnreadableScreenStopsInsteadOfLoopingForever() {
        val sched = FakeScheduler()
        var out: BackfillResult? = null
        val controller = BackfillController(
            options = BackfillOptions(50),
            postDelayed = sched::post,
            requestScreen = { cb -> cb(BackfillRead.Unreadable) },
            scroll = { _, done -> done() },
            widthPx = { 1000 },
            running = { out == null },
            progress = {},
            onFinish = { out = it })
        controller.start()
        sched.runAll(limit = 5_000)
        assertNotNull("the run must terminate when no screen is readable", out)
        assertEquals(BackfillStopReason.BOTTOM_UNCONFIRMED, out!!.stopReason)
        assertTrue(out!!.messages.isEmpty())
    }

    @Test fun anUnreadableScreenDuringCollectionStopsWithUnreadable() {
        val sched = FakeScheduler()
        val chat = FakeChat(total = 100, window = 10, end = 100)
        var out: BackfillResult? = null
        var reads = 0
        val controller = BackfillController(
            options = BackfillOptions(50),
            postDelayed = sched::post,
            requestScreen = { cb ->
                reads++
                // Reach the bottom (needs two identical reads), then go blind.
                cb(if (reads <= 4) BackfillRead.Screen(chat.screen()) else BackfillRead.Unreadable)
            },
            scroll = { _, done -> done() },
            widthPx = { 1000 },
            running = { out == null },
            progress = {},
            onFinish = { out = it })
        controller.start()
        sched.runAll(limit = 5_000)
        assertNotNull(out)
        assertEquals(BackfillStopReason.UNREADABLE, out!!.stopReason)
    }

    @Test fun aReportedLastRowSkipsTheBottomProbingScrolls() {
        // Already at the very bottom, and the list reports it: the run must reach
        // the COLLECTING phase without any scroll toward newer first. (The return
        // phase legitimately scrolls toward newer again afterwards.)
        val chat = FakeChat(total = 80, window = 10, end = 80, reportsListEnd = true)
        val sched = FakeScheduler()
        var out: BackfillResult? = null
        var newerScrollsWhenCollecting: Int? = null
        val controller = BackfillController(
            options = BackfillOptions(50),
            postDelayed = sched::post,
            requestScreen = { cb -> cb(BackfillRead.Screen(chat.screen())) },
            scroll = { step, done ->
                val travel = kotlin.math.abs(step.endY - step.startY)
                chat.scroll(towardOlder = step.endY > step.startY,
                    move = maxOf(1, chat.window * travel / 1000))
                done()
            },
            widthPx = { 1000 },
            running = { out == null },
            progress = { st ->
                if (st.phase == BackfillPhase.COLLECTING && newerScrollsWhenCollecting == null) {
                    newerScrollsWhenCollecting = chat.newerScrolls
                }
            },
            onFinish = { out = it })
        controller.start()
        sched.runAll()
        assertEquals(0, newerScrollsWhenCollecting)
        assertEquals(BackfillStopReason.TARGET_REACHED, out!!.stopReason)
        assertEquals(50, out!!.messages.size)
        assertEquals("m79", out!!.messages.last().text)
    }

    @Test fun anIdentityChangeStopsTheRunImmediately() {
        val sched = FakeScheduler()
        var out: BackfillResult? = null
        var reads = 0
        val controller = BackfillController(
            options = BackfillOptions(50),
            postDelayed = sched::post,
            requestScreen = { cb ->
                reads++
                cb(BackfillRead.IdentityChanged)
            },
            scroll = { _, done -> done() },
            widthPx = { 1000 },
            running = { out == null },
            progress = {},
            onFinish = { out = it })
        controller.start()
        sched.runAll()
        assertEquals(BackfillStopReason.IDENTITY_CHANGED, out!!.stopReason)
        // No retry loop: a switch is terminal, not a transient read failure. The
        // return leg may check once more, but never the 10-deep unreadable retry.
        assertTrue("expected a bounded number of reads, got $reads", reads <= 2)
    }

    @Test fun anObstructedFrameIsWaitedOutThenCollected() {
        val sched = FakeScheduler()
        val chat = FakeChat(total = 80, window = 10, end = 80, reportsListEnd = true)
        var out: BackfillResult? = null
        var occlusions = 3
        val controller = BackfillController(
            options = BackfillOptions(50),
            postDelayed = sched::post,
            requestScreen = { cb ->
                if (occlusions-- > 0) cb(BackfillRead.Occluded)
                else cb(BackfillRead.Screen(chat.screen()))
            },
            scroll = { step, done ->
                val travel = kotlin.math.abs(step.endY - step.startY)
                chat.scroll(towardOlder = step.endY > step.startY,
                    move = maxOf(1, chat.window * travel / 1000))
                done()
            },
            widthPx = { 1000 },
            running = { out == null },
            progress = {},
            onFinish = { out = it })
        controller.start()
        sched.runAll()
        assertEquals(BackfillStopReason.TARGET_REACHED, out!!.stopReason)
        assertEquals(50, out!!.messages.size)
    }

    @Test fun persistentObstructionEventuallyStopsTheRun() {
        val sched = FakeScheduler()
        var out: BackfillResult? = null
        val controller = BackfillController(
            options = BackfillOptions(50),
            postDelayed = sched::post,
            requestScreen = { cb -> cb(BackfillRead.Occluded) },
            scroll = { _, done -> done() },
            widthPx = { 1000 },
            running = { out == null },
            progress = {},
            onFinish = { out = it })
        controller.start()
        sched.runAll(limit = 5000)
        assertNotNull(out)
        assertEquals(BackfillStopReason.BOTTOM_UNCONFIRMED, out!!.stopReason)
    }
}
