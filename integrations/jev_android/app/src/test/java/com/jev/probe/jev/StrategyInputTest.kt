package com.jev.probe.jev

import com.jev.probe.core.*
import com.jev.probe.core.kb.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class StrategyInputTest {
    private val snapshot = ChatSnapshot("同事甲", listOf(Msg("other", "这周比较忙")))
    private val context = ChatContext(
        Contact("id", "同事甲", relationship = "同事", stage = "了解中", goal = "自然接话", notes = "正在出差"),
        listOf(LogEntry("other", "等我回去再约", 0, "fixture.app")),
        listOf(Note("note", "安排", "不要催促")))

    @Test fun judgmentAndRankingPayloadIncludeContactStageGoalNotesAndHistory() {
        val payload = StrategyInput.build(snapshot, "伴侣", context, 30)
        assertEquals("同事", payload.getString("relationship"))
        val background = payload.getString("background")
        assertTrue(background.contains("了解中"))
        assertTrue(background.contains("自然接话"))
        assertTrue(background.contains("正在出差"))
        assertTrue(background.contains("不要催促"))
        assertEquals("等我回去再约", payload.getJSONArray("history").getJSONObject(0).getString("text"))
    }

    @Test fun zeroHistoryLimitOmitsHistoryButKeepsContactBackground() {
        val payload = StrategyInput.build(snapshot, "伴侣", context, 0)
        assertFalse(payload.has("history"))
        assertTrue(payload.has("background"))
    }

    @Test fun missingContactUsesGlobalRelationshipWithoutInventingContext() {
        val payload = StrategyInput.build(snapshot, "朋友", null, 30)
        assertEquals("朋友", payload.getString("relationship"))
        assertFalse(payload.has("background"))
        assertFalse(payload.has("history"))
    }

    @Test fun preparedTranscriptReplacesTheTruncatedArrayAndCarriesTheNotice() {
        val long = Msg("other", "长".repeat(1200))
        val snap = ChatSnapshot("同事甲", listOf(long))
        val prepared = ContextPreparer(TokenBudget(contextWindow = 4096)).prepare(
            primary = snap.messages, history = emptyList(), overheadTokens = 0,
            render = ConversationPayload::render, summarize = { it.take(20) })
        val payload = StrategyInput.build(snap, "同事", null, 30, prepared)
        // The transcript is a single string, so it is not clipped per message.
        assertTrue(payload.getString("transcript").contains(long.text))
        assertEquals(false, payload.getBoolean("compressed"))
    }

    @Test fun legacyShapeIsKeptWhenNoPreparedTranscriptIsSupplied() {
        val payload = StrategyInput.build(snapshot, "伴侣", context, 30)
        assertTrue(payload.get("transcript") is JSONArray)
    }
}
