package com.jev.probe.capture

/**
 * Classifies the window reported by a WINDOW_STATE_CHANGED event.
 *
 * The overlay panel is itself a window belonging to this app. When it takes or
 * releases focus (for example while the user taps "填入" and the panel collapses)
 * the service can observe its own package as the active window. Treating that as
 * "the user left the chat app" cancelled the in-flight fill, cleared the analysed
 * conversation, and hid the panel — so the draft was never written and the next
 * tap showed a blank slate.
 */
internal object ForegroundApp {

    sealed interface Kind {
        /** Our own overlay/settings: keep the current round and the panel. */
        data object OwnApp : Kind
        /** The chat app we are working with (or one we do not adapt). */
        data object ChatApp : Kind
        /** A different, unrelated app: the user really left the conversation. */
        data object Foreign : Kind
    }

    fun classify(foregroundPackage: String?, ownPackage: String, adapterPackages: Set<String>): Kind =
        when {
            foregroundPackage.isNullOrBlank() -> Kind.ChatApp
            foregroundPackage == ownPackage -> Kind.OwnApp
            foregroundPackage in adapterPackages -> Kind.ChatApp
            else -> Kind.Foreign
        }

    /** Our own windows and the chat app itself must never cancel the run. */
    fun shouldCancelWork(kind: Kind): Boolean = kind == Kind.Foreign

    /** Only a genuinely foreign app should take the panel away. */
    fun shouldHideOverlay(kind: Kind): Boolean = kind == Kind.Foreign
}
