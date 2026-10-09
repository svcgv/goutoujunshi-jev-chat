package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WeChat exposes no conversation title at all, so a title-based binding can never
 * be re-found after a restart. The fingerprint of recent messages is what
 * re-identifies the same conversation.
 */
class ConversationFingerprintTest {

    private fun binding(id: String, vararg msgs: String) = ConversationBinding(
        app = "com.tencent.mm", title = id, contactId = id, remember = true,
        fingerprint = ConversationFingerprints.of(msgs.toList()))

    @Test fun theSameConversationIsFoundAgainFromItsMessages() {
        val rows = listOf(
            binding("a", "你好", "吃了吗", "刚看到，吃过了，你呢"),
            binding("b", "在吗", "周末一起爬山吗", "我看看时间"))
        val found = ConversationFingerprints.match(rows, "com.tencent.mm",
            listOf("你好", "吃了吗", "刚看到，吃过了，你呢", "明天呢"))
        assertEquals("a", found?.contactId)
    }

    @Test fun anUnrelatedConversationDoesNotMatch() {
        val rows = listOf(binding("a", "你好", "吃了吗", "刚看到，吃过了，你呢"))
        assertNull(ConversationFingerprints.match(rows, "com.tencent.mm",
            listOf("项目进度怎么样了", "记得发我文档")))
    }

    @Test fun aTieBetweenTwoContactsIsRefusedRatherThanGuessed() {
        val rows = listOf(
            binding("a", "你好", "在吗"),
            binding("b", "你好", "在吗"))
        assertNull(ConversationFingerprints.match(rows, "com.tencent.mm", listOf("你好", "在吗")))
    }

    @Test fun aDifferentAppNeverMatches() {
        val rows = listOf(binding("a", "你好", "在吗"))
        assertNull(ConversationFingerprints.match(rows, "com.tencent.mobileqq", listOf("你好", "在吗")))
    }

    @Test fun shortOverlapIsNotEnoughToIdentifySomeone() {
        val rows = listOf(binding("a", "你好", "吃了吗"))
        assertNull(ConversationFingerprints.match(rows, "com.tencent.mm", listOf("你好", "别的")))
    }

    @Test fun fingerprintIgnoresCaseWhitespaceAndDuplicates() {
        val fp = ConversationFingerprints.of(listOf("  你好 ", "你好", "", "  ", "HELLO"))
        assertEquals(listOf("你好", "hello"), fp)
    }

    @Test fun fingerprintIsCappedAndKeepsTheMostRecentMessages() {
        val many = (1..20).map { "消息$it" }
        val fp = ConversationFingerprints.of(many)
        assertEquals(ConversationBindings.FINGERPRINT_SIZE, fp.size)
        assertEquals("消息20", fp.last())
    }

    @Test fun bindingsWithoutAFingerprintAreIgnored() {
        val rows = listOf(ConversationBinding("com.tencent.mm", "小雨", "a", true, emptyList()))
        assertNull(ConversationFingerprints.match(rows, "com.tencent.mm", listOf("你好", "在吗")))
    }
}
