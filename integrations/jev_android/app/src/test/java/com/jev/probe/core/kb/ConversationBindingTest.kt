package com.jev.probe.core.kb

import com.jev.probe.core.Msg
import com.jev.probe.core.ChatSnapshot
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationBindingTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = KbStore(temp.root)
    private val messages = listOf(Msg("other", "你好"), Msg("me", "你好"))
    private fun bind(store: KbStore, id: String, app: String, title: String, remember: Boolean = true) {
        assertTrue(store.saveContact(Contact(id, title)))
        assertTrue(store.bind(ConversationBinding(app, title, id, remember)))
    }

    @Test fun exactBindingSurvivesReopenWithoutAliasGuessing() {
        val store = store()
        bind(store, "a", "wechat", "小雨")
        assertEquals("a", store().binding("小雨", "wechat")?.contactId)
        assertNull(store.binding("小雨", "qq"))
        assertNull(store.binding("小雨的新备注", "wechat"))
        assertNull(store.binding(null, "wechat"))
    }
    @Test fun duplicateBindingsAreAmbiguousAndNotAutoSelected() {
        assertNull(ConversationBindings.resolve(listOf(
            ConversationBinding("wechat", "同名", "a"), ConversationBinding("wechat", "同名", "b")), "wechat", "同名"))
    }
    @Test fun historyOnlyWrittenForOptedInBindingAndNeverAnotherContact() {
        val store = store()
        bind(store, "a", "wechat", "对象A")
        bind(store, "b", "wechat", "对象B", false)
        assertTrue(store.rememberReviewed("对象A", "wechat", messages))
        assertTrue(store.rememberReviewed("对象B", "wechat", messages))
        assertTrue(store.rememberReviewed("陌生窗口", "wechat", messages))
        assertEquals(2, store().logSize("a"))
        assertEquals(0, store.logSize("b"))
    }
    @Test fun repeatedScreensDeduplicateButDisjointReviewedNewScreenIsSaved() {
        val store = store()
        bind(store, "a", "wechat", "小雨")
        repeat(2) { store.rememberReviewed("小雨", "wechat", messages) }
        assertEquals(2, store.logSize("a"))
        store.rememberReviewed("小雨", "wechat", listOf(Msg("other", "明天有空吗")))
        assertEquals(3, store.logSize("a"))
    }
    @Test fun sameNameAcrossAppsIsIsolatedUntilExplicitlyBoundToSameId() {
        val store = store()
        bind(store, "a", "wechat", "小雨")
        bind(store, "b", "qq", "小雨")
        assertEquals("a", store.binding("小雨", "wechat")?.contactId)
        assertEquals("b", store.binding("小雨", "qq")?.contactId)
        assertTrue(store.bind(ConversationBinding("qq", "小雨", "a", true)))
        assertEquals("a", store.binding("小雨", "qq")?.contactId)
    }
    @Test fun unbindRetainsHistoryButClearAndDeleteRemoveIt() {
        val store = store()
        bind(store, "a", "wechat", "小雨")
        store.rememberReviewed("小雨", "wechat", messages)
        assertTrue(store.unbind("小雨", "wechat"))
        assertNull(store.binding("小雨", "wechat"))
        assertEquals(2, store.logSize("a"))
        assertTrue(store.clearHistory("a"))
        assertEquals(0, store().logSize("a"))
        assertTrue(store.bind(ConversationBinding("wechat", "小雨", "a", true)))
        assertTrue(store.deleteContact("a"))
        assertTrue(store().bindings().isEmpty())
    }
    @Test fun malformedBindingsAreNotOverwritten() {
        val file = File(temp.root, "bindings.json")
        file.writeText("broken")
        val store = store()
        assertTrue(store.saveContact(Contact("a", "小雨")))
        assertFalse(store.bind(ConversationBinding("wechat", "小雨", "a")))
        assertEquals("broken", file.readText())
    }
    @Test fun unknownSpeakerCannotReachHistoryEvenIfCallerSkipsUiValidation() {
        val store = store()
        bind(store, "a", "wechat", "小雨")
        assertFalse(store.rememberReviewed("小雨", "wechat", listOf(Msg("unknown", "你好"))))
        assertEquals(0, store.logSize("a"))
    }
    @Test fun pausedBindingRetainsButDoesNotAppendHistory() {
        val store = store()
        bind(store, "a", "wechat", "小雨")
        store.rememberReviewed("小雨", "wechat", messages)
        store.bind(ConversationBinding("wechat", "小雨", "a", false))
        store.rememberReviewed("小雨", "wechat", listOf(Msg("other", "新消息")))
        assertEquals(2, store.logSize("a"))
    }
    @Test fun contextReadIsSideEffectFreeAndInjectsOnlyBoundOptedInHistory() {
        val store = store()
        bind(store, "a", "wechat", "小雨")
        store.rememberReviewed("小雨", "wechat", messages)
        val snapshot = ChatSnapshot("小雨", listOf(Msg("other", "最近怎么样")))
        repeat(2) {
            val context = ContextBuilder.build(store, snapshot, "wechat", true, 30)
            assertEquals("a", context.contact?.id)
            assertEquals(2, context.history.size)
            assertEquals(2, store.logSize("a"))
        }
        assertTrue(ContextBuilder.build(store, snapshot, "wechat", false, 30).history.isEmpty())
        assertNull(ContextBuilder.build(store, snapshot, "qq", true, 30).contact)
        store.bind(ConversationBinding("wechat", "小雨", "a", false))
        assertTrue(ContextBuilder.build(store, snapshot, "wechat", true, 30).history.isEmpty())
    }
    @Test fun historyCountAndBudgetAreAppliedWithoutDeletingStoredData() {
        val store = store()
        bind(store, "a", "wechat", "小雨")
        store.rememberReviewed("小雨", "wechat", (1..20).map { Msg("other", "$it" + "文本".repeat(100)) })
        val context = ContextBuilder.build(store, ChatSnapshot("小雨", emptyList()), "wechat", true, 3)
        assertEquals(3, context.history.size)
        assertEquals(20, store.logSize("a"))
    }
    @Test fun legacyContactsNeedExplicitBindingBeforeRecall() {
        val store = store()
        assertTrue(store.saveContact(Contact("a", "小雨", aliases = listOf("雨"))))
        assertNull(store.binding("小雨", "wechat"))
        assertNull(store.binding("雨", "wechat"))
    }
}
