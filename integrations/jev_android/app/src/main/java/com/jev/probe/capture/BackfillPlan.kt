package com.jev.probe.capture

/**
 * How a manual history backfill proceeds.
 *
 * The user taps once; the app swipes up repeatedly, reading each screen, until it
 * has collected the target number of messages or runs out of history. This is
 * deliberately bounded: it drives the chat UI, so it must stop on a fixed target,
 * on stagnation, or when the user cancels — never scroll endlessly.
 */
internal object BackfillPlan {

    /** Default target for one backfill run. */
    const val TARGET_MESSAGES = 80

    /** Hard cap on swipes so a stuck screen cannot loop forever. */
    const val MAX_SWIPES = 60

    /** Stop after this many consecutive screens that add nothing new. */
    const val MAX_STALE_SCREENS = 3

    /**
     * Whether to keep scrolling.
     *
     * @param collected messages gathered so far in THIS run.
     * @param target how many are wanted.
     * @param swipes swipes already performed.
     * @param staleScreens consecutive screens that produced no new messages.
     */
    fun shouldContinue(collected: Int, target: Int = TARGET_MESSAGES,
                      swipes: Int = 0, staleScreens: Int = 0): Boolean {
        if (collected >= target) return false
        if (swipes >= MAX_SWIPES) return false
        if (staleScreens >= MAX_STALE_SCREENS) return false
        return true
    }

    /** True when a screen contributed nothing new, i.e. we have reached the top. */
    fun isStale(newMessages: Int): Boolean = newMessages <= 0

    /** Progress text for the panel. */
    fun progress(collected: Int, target: Int = TARGET_MESSAGES): String =
        "补录中 $collected/$target"
}
