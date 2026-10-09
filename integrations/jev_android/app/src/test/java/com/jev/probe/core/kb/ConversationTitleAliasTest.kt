package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat app lets the user rename a contact or edit a remark, so the title a
 * conversation was bound under can change. These tests pin the alias behaviour
 * that keeps such a conversation resolvable by title.
 */
class ConversationTitleAliasTest {

    private fun row(title: String, aliases: List<String> = emptyList()) =
        ConversationBinding("com.tencent.mm", title, "contact-1", remember = true,
            fingerprint = listOf("a", "b"), titleAliases = aliases)

    @Test fun aBindingResolvesByItsPrimaryTitle() {
        val rows = listOf(row("星月九"))
        assertEquals("contact-1", ConversationBindings.resolve(rows, "com.tencent.mm", "星月九")?.contactId)
    }

    @Test fun aBindingAlsoResolvesByAnyAlias() {
        val rows = listOf(row("星月九", aliases = listOf("f-星月九")))
        assertEquals("contact-1", ConversationBindings.resolve(rows, "com.tencent.mm", "f-星月九")?.contactId)
        assertTrue(rows[0].matchesTitle("f-星月九"))
        assertTrue(rows[0].matchesTitle("星月九"))
        assertFalse(rows[0].matchesTitle("别人"))
    }

    @Test fun anUnknownTitleStillDoesNotResolve() {
        val rows = listOf(row("星月九", aliases = listOf("f-星月九")))
        assertNull(ConversationBindings.resolve(rows, "com.tencent.mm", "小刚"))
        assertNull(ConversationBindings.resolve(rows, "com.tencent.mobileqq", "f-星月九"))
    }

    @Test fun withTitleAliasRecordsANewTitleOnce() {
        val rows = listOf(row("星月九"))
        val updated = ConversationBindings.withTitleAlias(rows, "com.tencent.mm", "星月九", "f-星月九")
        assertNotNull(updated)
        assertEquals(listOf("f-星月九"), updated!![0].titleAliases)
        // Primary title is untouched: the alias is an addition, not a rename.
        assertEquals("星月九", updated[0].title)
    }

    @Test fun withTitleAliasIsIdempotent() {
        val rows = listOf(row("星月九", aliases = listOf("f-星月九")))
        assertNull(ConversationBindings.withTitleAlias(rows, "com.tencent.mm", "星月九", "f-星月九"))
        assertNull(ConversationBindings.withTitleAlias(rows, "com.tencent.mm", "星月九", " 星月九 "))
    }

    @Test fun withTitleAliasIgnoresAnUnknownBinding() {
        val rows = listOf(row("星月九"))
        assertNull(ConversationBindings.withTitleAlias(rows, "com.tencent.mm", "查无此人", "f-x"))
        assertNull(ConversationBindings.withTitleAlias(rows, "com.tencent.mobileqq", "星月九", "f-x"))
    }

    @Test fun aliasesSurviveAnEncodeDecodeRoundTrip() {
        val rows = listOf(row("星月九", aliases = listOf("f-星月九", "星月九同学")))
        val decoded = ConversationBindings.decode(ConversationBindings.encode(rows).toString())
        assertEquals(rows, decoded)
        assertEquals("contact-1", ConversationBindings.resolve(decoded, "com.tencent.mm", "星月九同学")?.contactId)
    }

    @Test fun aLegacyRowWithoutAliasesStillDecodes() {
        val legacy = """[{"app":"com.tencent.mm","title":"星月九","contactId":"c1","remember":true,"fingerprint":["a"]}]"""
        val decoded = ConversationBindings.decode(legacy)
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].titleAliases.isEmpty())
        assertEquals("c1", ConversationBindings.resolve(decoded, "com.tencent.mm", "星月九")?.contactId)
    }

    @Test fun titlesListsThePrimaryTitleFirst() {
        assertEquals(listOf("星月九", "f-星月九"), row("星月九", listOf("f-星月九")).titles())
    }
}

/**
 * The fingerprint is what identifies a window whose title is hidden, so it has
 * to survive the conversation moving on.
 */
class BindingFingerprintGrowthTest {

    @Test fun newDistinctiveLinesAreAdded() {
        val grown = ConversationBindings.expandedFingerprint(
            existing = listOf("周末一起吃饭"),
            live = listOf("周末一起吃饭", "我明天出差回来"))
        assertEquals(listOf("周末一起吃饭", "我明天出差回来"), grown)
    }

    @Test fun genericShortLinesAreNeverRecorded() {
        // Everyone says these; recording them would make two chats look alike.
        assertNull(ConversationBindings.expandedFingerprint(
            existing = listOf("周末一起吃饭"), live = listOf("嗯", "好的", "哈哈", "在吗")))
    }

    @Test fun nothingNewYieldsNoWrite() {
        assertNull(ConversationBindings.expandedFingerprint(
            existing = listOf("周末一起吃饭"), live = listOf("周末一起吃饭")))
        assertNull(ConversationBindings.expandedFingerprint(existing = emptyList(), live = emptyList()))
    }

    @Test fun existingEntriesAreKeptAndOrderPreserved() {
        val grown = ConversationBindings.expandedFingerprint(
            existing = listOf("第一条比较长的消息", "第二条比较长的消息"),
            live = listOf("第三条比较长的消息"))
        assertEquals(listOf("第一条比较长的消息", "第二条比较长的消息", "第三条比较长的消息"), grown)
    }

    @Test fun theFingerprintIsCappedSoItCannotGrowForever() {
        val existing = (1..24).map { "历史消息内容编号$it" }
        val grown = ConversationBindings.expandedFingerprint(existing, listOf("一条全新的比较长的消息"))!!
        assertEquals(24, grown.size)
        assertTrue(grown.last().contains("全新的"))
    }

    @Test fun aLargerFingerprintStillResolvesTheRightConversationAfterScrolling() {
        // Bound when only the newest lines were visible…
        val atBind = ConversationBindings.fingerprintOf(listOf("周末一起吃饭", "我明天出差回来"))
        // …then the user scrolls to older messages and we learn them too.
        val grown = ConversationBindings.expandedFingerprint(
            atBind, listOf("上次说的那个地方不错", "下周找个时间吧"))!!
        val row = ConversationBinding("com.tencent.mm", "星月九", "c1",
            remember = true, fingerprint = grown)
        // Scrolled far away: only the older lines are on screen now.
        val live = listOf("上次说的那个地方不错", "下周找个时间吧")
        assertEquals("c1",
            ConversationBindings.resolveByFingerprint(listOf(row), "com.tencent.mm", live)?.contactId)
    }
}

/**
 * When the title is hidden and the short fingerprint has scrolled out of view,
 * the contact's stored history is the corpus that still identifies the chat.
 */
class BindingHistoryMatchTest {

    private val a = ConversationBinding("com.tencent.mm", "星月九", "c1", remember = true)
    private val b = ConversationBinding("com.tencent.mm", "小刚", "c2", remember = true)

    private val historyA = listOf("周末一起吃饭", "我明天出差回来", "上次那个地方不错")
    private val historyB = listOf("项目文档发我", "会议改到周五", "预算表看到了")

    private fun resolve(live: List<String>) = ConversationBindings.resolveByCorpus(
        listOf(a to historyA, b to historyB), "com.tencent.mm", live)

    @Test fun theContactWhoseHistoryOverlapsIsChosen() {
        assertEquals("c1", resolve(listOf("我明天出差回来", "上次那个地方不错"))?.contactId)
        assertEquals("c2", resolve(listOf("会议改到周五", "预算表看到了"))?.contactId)
    }

    @Test fun oneMatchingLineIsNotEnough() {
        assertNull(resolve(listOf("我明天出差回来")))
    }

    @Test fun aTieIsRefusedRatherThanGuessed() {
        val tied = listOf(a to listOf("共同的一句话"), b to listOf("共同的一句话"))
        assertNull(ConversationBindings.resolveByCorpus(tied, "com.tencent.mm",
            listOf("共同的一句话", "另一句话")))
    }

    @Test fun anotherAppNeverMatches() {
        assertNull(ConversationBindings.resolveByCorpus(
            listOf(a to historyA), "com.tencent.mobileqq", listOf("我明天出差回来", "上次那个地方不错")))
    }

    @Test fun matchingIsCaseAndWhitespaceInsensitive() {
        val c = ConversationBinding("com.tencent.mm", "X", "c3", remember = true)
        assertEquals("c3", ConversationBindings.resolveByCorpus(
            listOf(c to listOf("Hello World", "See You")), "com.tencent.mm",
            listOf("  hello world  ", "see you"))?.contactId)
    }
}
