package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class ReviewedTranscriptTest {
    @Test fun preservesConfirmedSidesAndEmbeddedColons() {
        assertEquals(listOf(Msg("me", "问：周末有空吗"), Msg("other", "可以")),
            ReviewedTranscript.parse("我：问：周末有空吗\n对方：可以"))
    }
    @Test fun unconfirmedOcrCannotBeSavedOrSent() {
        for (raw in listOf("待确认：你好", "你好", "", "我：", "我：你好\n待确认：收到")) {
            try { ReviewedTranscript.parse(raw); fail("accepted unverified input") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
