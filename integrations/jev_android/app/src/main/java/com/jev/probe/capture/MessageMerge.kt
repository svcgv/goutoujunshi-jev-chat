package com.jev.probe.capture

import com.jev.probe.core.Msg

/**
 * Merges successive screens of a chat into one ordered history.
 *
 * Screens overlap: scrolling up shows part of what was already read plus older
 * messages above it. Overlap is detected on (side, text) pairs and the newer
 * screen is spliced onto the end. With no overlap the screen is older content and
 * is prepended. Message text is never shortened — a long bubble stays intact.
 */
internal object MessageMerge {

    /** Result of folding one screen into the accumulated history. */
    data class Result(val messages: List<Msg>, val added: Int)

    fun merge(acc: List<Msg>, screen: List<Msg>): Result {
        if (screen.isEmpty()) return Result(acc, 0)
        if (acc.isEmpty()) return Result(screen, screen.size)

        val maxOverlap = minOf(acc.size, screen.size)
        var overlap = 0
        for (k in maxOverlap downTo 1) {
            if (suffixEqualsPrefix(acc, screen, k)) { overlap = k; break }
        }
        if (overlap > 0) {
            val tail = screen.drop(overlap)
            return Result(acc + tail, tail.size)
        }
        // No overlap at all: this screen is somewhere else in the history. Keep
        // only the lines we have not already seen, in front of what we have.
        val existing = acc.map { it.side to it.text }.toHashSet()
        val fresh = screen.filter { (it.side to it.text) !in existing }
        return Result(fresh + acc, fresh.size)
    }

    private fun suffixEqualsPrefix(acc: List<Msg>, screen: List<Msg>, k: Int): Boolean {
        val aStart = acc.size - k
        for (i in 0 until k) {
            if (acc[aStart + i].side != screen[i].side) return false
            if (acc[aStart + i].text != screen[i].text) return false
        }
        return true
    }
}
