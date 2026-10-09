package com.jev.probe.coach

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachSafetyTest {
    private fun snap(vararg rows: Msg) = ChatSnapshot("对象", rows.toList())

    @Test fun explicitStopRemainsActiveEvenAfterUserReplies() {
        val chat = snap(Msg("other", "不要再联系我了"), Msg("me", "我知道，但我想说最后一句"))
        assertTrue(CoachSafety.standingBoundary(chat))
    }

    @Test fun oneBusyDayIsNotTreatedAsRelationshipRejection() {
        val chat = snap(Msg("other", "今天有点忙，改天吧"))
        assertFalse(CoachSafety.standingBoundary(chat))
    }

    @Test fun storedBoundaryPreventsResetThroughNewChat() {
        assertTrue(CoachSafety.standingBoundary(snap(Msg("other", "你好")),
            "事件 boundary：对象明确要求停止联系"))
    }

    @Test fun threatAndSuicideCoercionAreSafetySignals() {
        assertTrue(CoachSafety.safetySignal(snap(Msg("other", "你不出来我就去你单位堵你"))))
        assertTrue(CoachSafety.safetySignal(snap(), "对方说我不复合就去死"))
    }

    @Test fun onlyTemporaryLeaveRemainsAndLegacyValuesCollapse() {
        assertEquals(EndChatMode.TEMPORARY_LEAVE, EndChatMode.fromWire(null))
        assertEquals(EndChatMode.TEMPORARY_LEAVE, EndChatMode.fromWire("end_turn"))
        assertEquals(EndChatMode.TEMPORARY_LEAVE, EndChatMode.fromWire("reduce_investment"))
        assertEquals(EndChatMode.TEMPORARY_LEAVE, EndChatMode.fromWire("end_relationship"))
        assertEquals(1, EndChatMode.entries.size)
        assertEquals("暂时离开会话", EndChatMode.TEMPORARY_LEAVE.label)
    }

    @Test fun ordinaryConflictIsNotASafetyEscalation() {
        assertFalse(CoachSafety.safetySignal(snap(Msg("other", "我现在很生气，先别说话"))))
    }
}
