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

    @Test fun blockFormatRoundTripsMultiLineMessages() {
        val messages = listOf(
            Msg("other", "第一段\n\n第二段，后面还有：冒号"),
            Msg("me", "好的"),
            Msg("other", "收尾"))
        val encoded = ReviewedTranscript.encode(messages)
        assertEquals(messages, ReviewedTranscript.parseBlocks(encoded))
    }

    @Test fun blockFormatKeepsRepeatedShortLinesDistinct() {
        val messages = listOf(Msg("other", "嗯"), Msg("me", "嗯"), Msg("other", "嗯"))
        assertEquals(messages, ReviewedTranscript.parseBlocks(ReviewedTranscript.encode(messages)))
    }

    @Test fun blockFormatRejectsAnUnmarkedBlock() {
        try {
            ReviewedTranscript.parseBlocks("我：你好${ReviewedTranscript.SEP}没有标记")
            fail("accepted an unmarked block")
        } catch (_: IllegalArgumentException) { }
    }
}
