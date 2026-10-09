package com.jev.probe.jev

import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContextPreparerTest {

    private val render: (List<Msg>) -> String = { rows ->
        rows.joinToString("\n") { (if (it.side == "me") "我" else "对方") + "：" + it.text }
    }

    private fun shortenTo(maxChars: Int): (String) -> String = { s -> s.take(maxChars) }

    private fun msg(i: Int, text: String) = Msg(if (i % 2 == 0) "other" else "me", text)

    @Test fun aShortConversationGoesOutVerbatim() {
        val preparer = ContextPreparer(TokenBudget(contextWindow = 8192))
        val msgs = (1..5).map { msg(it, "第 $it 条消息") }
        val p = preparer.prepare(msgs, emptyList(), overheadTokens = 50, render = render,
            summarize = shortenTo(20))
        assertFalse(p.compressed)
        assertEquals(5, p.verbatimMessages)
        assertEquals(0, p.compressedMessages)
        assertEquals(0, p.calls)
        assertTrue(p.text.contains("第 5 条消息"))
        assertEquals(null, p.notice())
    }

    @Test fun aLongConversationIsCompressedAndReportsIt() {
        val preparer = ContextPreparer(TokenBudget(contextWindow = 2000, reservedOutput = 400, safetyFraction = 0.0))
        val msgs = (1..80).map { msg(it, "这是第 $it 条比较长的消息，用于触发压缩路径。") }
        val p = preparer.prepare(msgs, emptyList(), overheadTokens = 20, render = render,
            summarize = shortenTo(24))
        assertTrue(p.compressed)
        assertEquals(80, p.totalMessages)
        assertTrue(p.verbatimMessages > 0)
        assertTrue(p.compressedMessages > 0)
        assertEquals(80, p.verbatimMessages + p.compressedMessages)
        assertTrue(p.calls > 0)
        assertNotNull(p.notice())
        assertTrue(p.notice()!!.contains("80"))
    }

    @Test fun theNewestMessagesStayVerbatim() {
        val preparer = ContextPreparer(TokenBudget(contextWindow = 800, reservedOutput = 200, safetyFraction = 0.0))
        val msgs = (1..60).map { msg(it, "这是编号 $it 的一条需要被压缩的消息内容") }
        val p = preparer.prepare(msgs, emptyList(), overheadTokens = 10, render = render,
            summarize = shortenTo(16))
        assertTrue(p.compressed)
        // The very last message must still appear verbatim in the payload.
        assertTrue(p.text.contains("这是编号 60 的一条需要被压缩的消息内容"))
    }

    @Test fun fixedOverheadBeyondBudgetFailsFast() {
        val preparer = ContextPreparer(TokenBudget(contextWindow = 300, reservedOutput = 200, safetyFraction = 0.0))
        try {
            preparer.prepare(listOf(msg(1, "你好")), emptyList(), overheadTokens = 1000,
                render = render, summarize = shortenTo(10))
            fail("accepted a request whose fixed prompt already exceeds the budget")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun extraHistoryIsDroppedBeforeTheReviewedWindow() {
        val preparer = ContextPreparer(TokenBudget(contextWindow = 900, reservedOutput = 300, safetyFraction = 0.0))
        val primary = (1..6).map { msg(it, "本轮第 $it 条") }
        val history = (1..40).map { msg(it, "很久以前第 $it 条很长的历史消息内容") }
        val p = preparer.prepare(primary, history, overheadTokens = 10, render = render,
            summarize = shortenTo(12))
        assertTrue(p.compressed)
        // The reviewed window is the primary content; history is what gets cut.
        assertTrue(p.text.contains("本轮第 6 条"))
    }

    @Test fun aSingleOverlongMessageIsSplitRatherThanDropped() {
        val preparer = ContextPreparer(TokenBudget(contextWindow = 800, reservedOutput = 200, safetyFraction = 0.0))
        val huge = msg(1, "长".repeat(2000))
        val p = preparer.prepare(listOf(huge), emptyList(), overheadTokens = 10, render = render,
            summarize = shortenTo(30))
        assertTrue(p.totalMessages >= 1)
        assertTrue(p.text.isNotEmpty())
    }

    @Test fun tokenEstimatorCountsCjkAtAboutOneTokenPerChar() {
        val t = TokenEstimator.estimate("你好世界")
        assertTrue("expected a few tokens, got $t", t in 4..8)
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test fun budgetReservesOutputAndSafety() {
        val b = TokenBudget(contextWindow = 1000, reservedOutput = 200, safetyFraction = 0.10)
        assertEquals(720, b.usableInput)
    }
}
