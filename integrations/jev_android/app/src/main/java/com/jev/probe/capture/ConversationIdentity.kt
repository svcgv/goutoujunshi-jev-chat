package com.jev.probe.capture

/** Title-only identity cannot distinguish same-name accounts. Never reuse missing titles. */
internal object ConversationIdentity {
    fun matches(expectedApp: String, expectedTitle: String?, liveApp: String?, liveTitle: String?,
                group: Boolean, manuallyConfirmedTitle: String? = null): Boolean {
        if (group || expectedApp.isBlank() || expectedApp != liveApp || expectedTitle.isNullOrBlank()) return false
        if (liveTitle.isNullOrBlank()) {
            // The window's title is unreadable (WeChat and other self-drawn UIs).
            // Accept it when the confirmed name that owns this session is the
            // same window's name — otherwise a bound window would never match
            // again and the analysis would be dropped silently.
            val confirmed = manuallyConfirmedTitle?.trim().orEmpty()
            return confirmed.isNotEmpty() && confirmed == expectedTitle.trim()
        }
        return liveTitle == expectedTitle
    }
}
