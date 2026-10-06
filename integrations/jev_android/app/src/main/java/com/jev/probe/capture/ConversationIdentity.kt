package com.jev.probe.capture

/** Title-only identity cannot distinguish same-name accounts. Never reuse missing titles. */
internal object ConversationIdentity {
    fun matches(expectedApp: String, expectedTitle: String?, liveApp: String?, liveTitle: String?,
                group: Boolean, manuallyConfirmedTitle: String? = null): Boolean {
        if (group || expectedApp.isBlank() || expectedApp != liveApp || expectedTitle.isNullOrBlank()) return false
        return if (liveTitle.isNullOrBlank()) manuallyConfirmedTitle == expectedTitle
               else liveTitle == expectedTitle
    }
}
