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
    @Test fun groupOrBlankIdentityCannotBeAnalyzed() {
        assertFalse(ConversationIdentity.matches("wechat", "A", "wechat", "A", true, "A"))
        assertFalse(ConversationIdentity.matches("wechat", null, "wechat", null, false))
    }
}
