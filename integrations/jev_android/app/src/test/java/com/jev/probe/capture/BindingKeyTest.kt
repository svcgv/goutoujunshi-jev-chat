package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A chat window whose title cannot be read (WeChat, and any app drawing its own
 * text) must still bind once and stay bound. Binding is keyed on the title the
 * user confirmed; when the live title is unreadable the confirmed name is the
 * lookup key, so the same window is not re-bound on every capture cycle.
 */
class BindingKeyTest {

    @Test fun readableTitleIsItsOwnBindingKey() {
        assertEquals("小雨", ConversationBindingKey.resolve("小雨", null))
    }

    @Test fun unreadableTitleFallsBackToTheConfirmedName() {
        assertEquals("小雨", ConversationBindingKey.resolve(null, "小雨"))
        assertEquals("小雨", ConversationBindingKey.resolve("", "小雨"))
        assertEquals("小雨", ConversationBindingKey.resolve("   ", "小雨"))
    }

    @Test fun transientTitleDoesNotBeatTheConfirmedName() {
        // "连接中…" must never become a binding key, even while a name is known.
        assertEquals("小雨", ConversationBindingKey.resolve("连接中…", "小雨"))
    }

    @Test fun nothingIsResolvedWhenNeitherIsKnown() {
        assertNull(ConversationBindingKey.resolve(null, null))
        assertNull(ConversationBindingKey.resolve("", null))
    }

    @Test fun aBoundOpaqueWindowKeepsMatchingItsOwnKey() {
        // Once confirmed, repeated reads with an unreadable title stay stable:
        // this is what stops the "bind again" loop.
        val confirmed = ConversationBindingKey.resolve(null, "小雨")
        assertNotNull(confirmed)
        repeat(3) {
            assertEquals(confirmed, ConversationBindingKey.resolve(null, "小雨"))
        }
    }

    @Test fun switchingToAReadableDifferentTitleDoesNotReuseTheOldName() {
        // A readable title always wins, so a different conversation cannot
        // inherit the previously confirmed opaque-window name.
        assertEquals("小美", ConversationBindingKey.resolve("小美", "小雨"))
    }
}
