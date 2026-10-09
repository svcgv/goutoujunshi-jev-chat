package com.jev.probe.overlay

/**
 * Which way out a read-only detail page offers.
 *
 * The history page can be opened BEFORE any analysis exists, so a button that
 * only renders an existing judgment is a dead end there — tapping it did
 * nothing at all. The page must always offer a way back.
 */
internal object DetailBackAction {

    enum class Kind {
        /** A judgment exists: go back to the candidate replies. */
        BACK_TO_REPLIES,

        /** Nothing to render: use the caller's way out. */
        CALLBACK,

        /** Nothing to render and no way out was provided: hide the button. */
        NONE
    }

    fun forPage(hasJudgment: Boolean, hasCallback: Boolean): Kind = when {
        hasJudgment -> Kind.BACK_TO_REPLIES
        hasCallback -> Kind.CALLBACK
        else -> Kind.NONE
    }
}
