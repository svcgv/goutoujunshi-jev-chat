package com.jev.probe.capture

/**
 * Bounded plan for one automatic history backfill run.
 *
 * The run drives the chat UI itself (scroll → settle → read → merge), so it must
 * always terminate: on the target, on a swipe cap, or when scrolling stops
 * producing anything new. It starts from the bottom of the conversation and
 * walks toward older messages.
 */
internal object BackfillPlan {

    /** Default target for one backfill run. */
    const val TARGET_MESSAGES = 50

    /** The start panel lets the user pick any value in this range. */
    const val MIN_TARGET = 10
    const val MAX_TARGET = 100

    /** Hard cap on scrolls while collecting older messages. */
    const val MAX_SWIPES = 200

    /** Hard cap on scrolls used to reach the bottom before collecting. */
    const val MAX_BOTTOM_SWIPES = 100

    /** Hard cap on scrolls used to return to the bottom afterwards. */
    const val MAX_RETURN_SWIPES = 100

    /** Stop after this many consecutive screens that add nothing new. */
    const val MAX_STALE_SCREENS = 3

    fun clampTarget(n: Int): Int = n.coerceIn(MIN_TARGET, MAX_TARGET)

    /**
     * Whether to keep scrolling for older messages.
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
