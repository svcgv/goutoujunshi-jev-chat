package com.jev.probe.capture

/**
 * Decides whether the on-screen conversation is a DIFFERENT one than the one
 * currently tracked.
 *
 * A readable title change is a real switch. When the title cannot be read
 * (WeChat and other self-drawn windows) the confirmed key stands in for the
 * title, so a window that is already bound is NOT treated as new merely because
 * the title came back empty — that misjudgement cleared the binding and made the
 * app ask the user to bind again on every capture.
 */
internal object ConversationSwitch {
    fun isNewConversation(
        packageChanged: Boolean,
        trackedKey: String?,
        incomingKey: String?
    ): Boolean {
        if (packageChanged) return true
        // Nothing to compare yet (not bound, no readable title): not a switch.
        if (incomingKey.isNullOrBlank() || trackedKey.isNullOrBlank()) return false
        return incomingKey != trackedKey
    }

    /**
     * Should a window-state event drop an already-confirmed opaque binding?
     * Our own overlay taking focus, and IME windows, are not navigation.
     */
    fun invalidatesOpaqueBinding(
        eventPackage: String?,
        activePackage: String?,
        ownPackage: String,
        livePackage: String?,
        stillInChatWindow: Boolean
    ): Boolean {
        if (eventPackage.isNullOrBlank() || eventPackage == ownPackage) return false
        if (activePackage.isNullOrBlank() || eventPackage != activePackage) return false
        if (livePackage == activePackage && stillInChatWindow) return false
        return true
    }
}
