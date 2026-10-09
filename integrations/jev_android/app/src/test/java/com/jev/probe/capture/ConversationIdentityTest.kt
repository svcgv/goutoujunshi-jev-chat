package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class ConversationIdentityTest {
    @Test fun knownWindowRestoresOnlyItsOwnIdentity() {
        assertTrue(ConversationIdentity.matches("wechat", "A", "wechat", "A", false))
        assertFalse(ConversationIdentity.matches("wechat", "A", "wechat", "B", false))
        assertFalse(ConversationIdentity.matches("wechat", "A", "qq", "A", false))
    }
    @Test fun opaqueWindowRequiresExplicitConfirmationForThisRound() {
        assertFalse(ConversationIdentity.matches("wechat", "A", "wechat", null, false))
        assertTrue(ConversationIdentity.matches("wechat", "A", "wechat", null, false, "A"))
        assertFalse(ConversationIdentity.matches("wechat", "A", "wechat", null, false, "B"))
    }

    @Test fun boundOpaqueWindowStaysValidForAnalysis() {
        // Regression: after binding, the snapshot title is the confirmed name but
        // the live title is still unreadable. This must stay valid, otherwise the
        // analysis is dropped silently and the panel appears to hang.
        assertTrue(ConversationIdentity.matches("wechat", "小雨", "wechat", null, false, "小雨"))
        assertTrue(ConversationIdentity.matches("wechat", "小雨", "wechat", "", false, "小雨"))
        assertTrue(ConversationIdentity.matches("wechat", "小雨", "wechat", "   ", false, "小雨"))
    }

    @Test fun aDifferentConfirmedNameStillDoesNotMatch() {
        assertFalse(ConversationIdentity.matches("wechat", "小雨", "wechat", null, false, "小美"))
    }

    @Test fun readableDifferentTitleStillWinsOverTheConfirmedName() {
        // A readable title is authoritative: another conversation cannot inherit it.
        assertFalse(ConversationIdentity.matches("wechat", "小雨", "wechat", "小美", false, "小雨"))
    }
    @Test fun groupOrBlankIdentityCannotBeAnalyzed() {
        assertFalse(ConversationIdentity.matches("wechat", "A", "wechat", "A", true, "A"))
        assertFalse(ConversationIdentity.matches("wechat", null, "wechat", null, false))
    }
}
