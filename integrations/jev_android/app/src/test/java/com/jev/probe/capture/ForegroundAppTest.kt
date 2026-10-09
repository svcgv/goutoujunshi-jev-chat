package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppTest {

    private val own = "com.goutoujunshi.chat"
    private val adapters = setOf("com.tencent.mm", "com.tencent.mobileqq")

    @Test fun ourOwnOverlayIsNotAForeignApp() {
        val kind = ForegroundApp.classify(own, own, adapters)
        assertEquals(ForegroundApp.Kind.OwnApp, kind)
        // Regression: tapping 填入 collapsed the panel, our own package was seen
        // as foreground, and the run was cancelled so nothing was ever filled.
        assertFalse(ForegroundApp.shouldCancelWork(kind))
        assertFalse(ForegroundApp.shouldHideOverlay(kind))
    }

    @Test fun theChatAppKeepsTheRoundAlive() {
        val kind = ForegroundApp.classify("com.tencent.mm", own, adapters)
        assertEquals(ForegroundApp.Kind.ChatApp, kind)
        assertFalse(ForegroundApp.shouldCancelWork(kind))
        assertFalse(ForegroundApp.shouldHideOverlay(kind))
    }

    @Test fun anUnadaptedChatAppIsNotTreatedAsForeign() {
        val kind = ForegroundApp.classify("com.other.chat", own, adapters)
        assertEquals(ForegroundApp.Kind.Foreign, kind)
    }

    @Test fun aRealForeignAppStillCancels() {
        val kind = ForegroundApp.classify("com.android.settings", own, adapters)
        assertTrue(ForegroundApp.shouldCancelWork(kind))
        assertTrue(ForegroundApp.shouldHideOverlay(kind))
    }

    @Test fun unknownForegroundDoesNotCancel() {
        val kind = ForegroundApp.classify(null, own, adapters)
        assertFalse(ForegroundApp.shouldCancelWork(kind))
    }
}
