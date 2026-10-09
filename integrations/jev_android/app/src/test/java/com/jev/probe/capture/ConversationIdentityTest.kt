package com.jev.probe.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun boundOpaqueWindowStaysValidForAnalysis() {
        assertTrue(ConversationIdentity.matches("wechat", "小雨", "wechat", null, false, "小雨"))
        assertTrue(ConversationIdentity.matches("wechat", "小雨", "wechat", "", false, "小雨"))
        assertTrue(ConversationIdentity.matches("wechat", "小雨", "wechat", "   ", false, "小雨"))
    }

    @Test fun aDifferentConfirmedNameStillDoesNotMatch() {
        assertFalse(ConversationIdentity.matches("wechat", "小雨", "wechat", null, false, "小美"))
    }

    @Test fun readableDifferentTitleStillWinsOverTheConfirmedName() {
        assertFalse(ConversationIdentity.matches("wechat", "小雨", "wechat", "小美", false, "小雨"))
    }

    @Test fun screenshotPathHasNoTitleAtAllAndMustStillMatch() {
        // Regression: the OCR path builds a snapshot whose title is null because
        // WeChat exposes none. Comparing it against the same window (also null)
        // must succeed, using the confirmed name, or the analysis is refused.
        assertTrue(ConversationIdentity.matches(
            "com.tencent.mm", null, "com.tencent.mm", null, false, "星月九"))
        assertTrue(ConversationIdentity.matches(
            "com.tencent.mm", null, "com.tencent.mm", "星月九", false, "星月九"))
        assertTrue(ConversationIdentity.matches(
            "com.tencent.mm", "星月九", "com.tencent.mm", null, false, "星月九"))
    }

    @Test fun aConflictingReadableTitleStillBlocksTheWrongChat() {
        // Even with a confirmed name, a readable title for another chat wins.
        assertFalse(ConversationIdentity.matches(
            "com.tencent.mm", null, "com.tencent.mm", "小美", false, "星月九"))
    }
}
