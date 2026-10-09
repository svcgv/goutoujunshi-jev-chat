package com.jev.probe.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSwitchTest {

    @Test fun unreadableTitleDoesNotLookLikeANewConversation() {
        // The binding loop: tracked key "小雨", incoming title null -> NOT a switch.
        assertFalse(ConversationSwitch.isNewConversation(
            packageChanged = false, trackedKey = "小雨", incomingKey = null))
    }

    @Test fun aDifferentReadableTitleIsASwitch() {
        assertTrue(ConversationSwitch.isNewConversation(
            packageChanged = false, trackedKey = "小雨", incomingKey = "小美"))
    }

    @Test fun sameKeyIsNotASwitch() {
        assertFalse(ConversationSwitch.isNewConversation(
            packageChanged = false, trackedKey = "小雨", incomingKey = "小雨"))
    }

    @Test fun switchingAppIsAlwaysASwitch() {
        assertTrue(ConversationSwitch.isNewConversation(
            packageChanged = true, trackedKey = "小雨", incomingKey = null))
    }

    @Test fun noTrackedKeyIsNotASwitch() {
        assertFalse(ConversationSwitch.isNewConversation(
            packageChanged = false, trackedKey = null, incomingKey = "小雨"))
    }

    @Test fun ourOwnOverlayAndImeDoNotDropTheBinding() {
        // Overlay focus change: event is ours.
        assertFalse(ConversationSwitch.invalidatesOpaqueBinding(
            eventPackage = "com.goutoujunshi.chat", activePackage = "com.tencent.mm",
            ownPackage = "com.goutoujunshi.chat", livePackage = "com.tencent.mm",
            stillInChatWindow = true))
        // Keyboard window: not the active chat package.
        assertFalse(ConversationSwitch.invalidatesOpaqueBinding(
            eventPackage = "com.tencent.wetype", activePackage = "com.tencent.mm",
            ownPackage = "com.goutoujunshi.chat", livePackage = "com.tencent.mm",
            stillInChatWindow = true))
    }

    @Test fun stayingInTheSameChatWindowKeepsTheBinding() {
        assertFalse(ConversationSwitch.invalidatesOpaqueBinding(
            eventPackage = "com.tencent.mm", activePackage = "com.tencent.mm",
            ownPackage = "com.goutoujunshi.chat", livePackage = "com.tencent.mm",
            stillInChatWindow = true))
    }

    @Test fun messagesDecideWhenTitlesAreUnreadable() {
        // WeChat gives no title. Two different chats therefore look identical to a
        // title-only comparison, and the previous conversation was kept. The
        // messages on screen must break the tie.
        assertTrue(ConversationSwitch.isDifferentByMessages(
            trackedMessages = listOf("你好", "吃了吗"),
            incomingMessages = listOf("在吗", "周末有空吗")))
        assertFalse(ConversationSwitch.isDifferentByMessages(
            trackedMessages = listOf("你好", "吃了吗"),
            incomingMessages = listOf("你好", "吃了吗")))
    }

    @Test fun partialOverlapIsTheSameConversation() {
        // A new message arrives; most of the screen is unchanged.
        assertFalse(ConversationSwitch.isDifferentByMessages(
            trackedMessages = listOf("你好", "吃了吗", "刚看到"),
            incomingMessages = listOf("你好", "吃了吗", "刚看到", "在忙吗")))
    }

    @Test fun aCompletelyDifferentScreenIsASwitch() {
        assertTrue(ConversationSwitch.isDifferentByMessages(
            trackedMessages = listOf("你好", "吃了吗", "刚看到"),
            incomingMessages = listOf("文档发我", "好的", "收到")))
    }

    @Test fun emptyInputsNeverClaimASwitch() {
        assertFalse(ConversationSwitch.isDifferentByMessages(emptyList(), listOf("你好")))
        assertFalse(ConversationSwitch.isDifferentByMessages(listOf("你好"), emptyList()))
        assertFalse(ConversationSwitch.isDifferentByMessages(emptyList(), emptyList()))
    }

    @Test fun leavingTheChatWindowInvalidatesTheBinding() {
        assertTrue(ConversationSwitch.invalidatesOpaqueBinding(
            eventPackage = "com.tencent.mm", activePackage = "com.tencent.mm",
            ownPackage = "com.goutoujunshi.chat", livePackage = "com.tencent.mm",
            stillInChatWindow = false))
    }
}
