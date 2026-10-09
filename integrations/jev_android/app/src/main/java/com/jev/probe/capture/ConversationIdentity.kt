package com.jev.probe.capture

/**
 * Whether the live window is still the conversation we started working with.
 *
 * Identity is title-based, but WeChat and other self-drawn UIs often expose no
 * title at all. In that case the name the user confirmed for this window stands
 * in for the missing title on BOTH sides of the comparison — otherwise a bound,
 * unreadable window can never match itself and every analysis fails with
 * "cannot confirm the chat identity".
 */
internal object ConversationIdentity {
    fun matches(expectedApp: String, expectedTitle: String?, liveApp: String?, liveTitle: String?,
                group: Boolean, manuallyConfirmedTitle: String? = null): Boolean {
        if (group || expectedApp.isBlank() || expectedApp != liveApp) return false
        val confirmed = manuallyConfirmedTitle?.trim().orEmpty()
        // An unreadable title falls back to the confirmed name on either side.
        val expected = expectedTitle?.trim().orEmpty().ifEmpty { confirmed }
        val live = liveTitle?.trim().orEmpty().ifEmpty { confirmed }
        if (expected.isEmpty()) return false
        return expected == live
    }
}
