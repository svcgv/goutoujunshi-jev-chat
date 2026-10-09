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

    @Test fun leavingTheChatWindowInvalidatesTheBinding() {
        assertTrue(ConversationSwitch.invalidatesOpaqueBinding(
            eventPackage = "com.tencent.mm", activePackage = "com.tencent.mm",
            ownPackage = "com.goutoujunshi.chat", livePackage = "com.tencent.mm",
            stillInChatWindow = false))
    }
}
