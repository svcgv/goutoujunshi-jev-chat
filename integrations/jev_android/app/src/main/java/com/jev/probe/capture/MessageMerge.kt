package com.jev.probe.capture

import com.jev.probe.core.Msg

/**
 * Merges successive screens of a chat into one ordered history while scrolling
 * TOWARD OLDER messages.
 *
 * Each screen is oldest-first (top of the screen → bottom). Scrolling up shows
 * older content on top plus an overlap with what we already hold at the bottom.
 * So the fold matches the new screen's *suffix* against the accumulator's
 * *prefix* and prepends whatever is genuinely new.
 *
 * Deliberately NOT a set-based de-duplicator: repeated short lines ("嗯"、"好的")
 * are distinct messages and must each survive. Anchoring requires either a
 * contiguous run of messages or a single long, unique line — never a bare text
 * match.
 */
internal object MessageMerge {

    /** Result of folding one screen into the accumulated history. */
    data class Result(
        /** Oldest-first merged history. */
        val messages: List<Msg>,
        /** How many messages were actually added by this screen. */
        val added: Int,
        /**
         * True when the screen could be aligned to what we already had. False
         * means we could not prove continuity, so the caller records a gap.
         */
        val anchored: Boolean
    )

    /**
     * Fold [screen] into [acc] after scrolling toward older messages.
     *
     * @param acc   history gathered so far, oldest-first.
     * @param screen the newest read screen, oldest-first.
     */
    fun prepend(acc: List<Msg>, screen: List<Msg>): Result {
        if (screen.isEmpty()) return Result(acc, 0, acc.isEmpty())
        if (acc.isEmpty()) return Result(screen, screen.size, true)

        // 1. Exact sequence overlap: screen's tail equals acc's head.
        for (k in minOf(screen.size, acc.size) downTo 1) {
            if (suffixEqualsPrefix(screen, acc, k)) {
                val older = screen.dropLast(k)
                return Result(older + acc, older.size, true)
            }
        }

        // 2. Long-bubble stitch: the newest fragment on this screen and the
        //    oldest fragment we hold are two pieces of the same bubble.
        val stitched = stitchFragments(screen.last(), acc.first())
        if (stitched != null) {
            val merged = screen.dropLast(1) + stitched + acc.drop(1)
            return Result(merged, screen.size - 1, true)
        }

        // 3. Any reliable anchor (a contiguous run, or one long unique line).
        val anchor = findAnchor(screen, acc)
        if (anchor != null) {
            val older = screen.take(anchor.screenIndex)
            return Result(older + acc, older.size, true)
        }

        // 4. No anchor: keep every row (never set-dedupe) but flag the gap, so
        //    the caller can shrink the step and re-read once before giving up.
        return Result(screen + acc, screen.size, false)
    }

    private fun suffixEqualsPrefix(acc: List<Msg>, screen: List<Msg>, k: Int): Boolean {
        val aStart = acc.size - k
        for (i in 0 until k) {
            if (acc[aStart + i].side != screen[i].side) return false
            if (acc[aStart + i].text != screen[i].text) return false
        }
        return true
    }

    /**
     * Two fragments of the same bubble share a non-trivial textual overlap at
     * their junction. Merge them at the longest such overlap; return null when
     * there is nothing convincing to join.
     */
    private fun stitchFragments(a: Msg, b: Msg): Msg? {
        if (a.side != b.side) return null
        val left = a.text
        val right = b.text
        if (left.isEmpty() || right.isEmpty()) return null
        if (left == right) return null // handled by exact overlap above

        // Longest suffix of `left` that is a prefix of `right` (the overlap
        // region when `left` is the older fragment and `right` the newer one).
        stitchAt(left, right)?.let { return Msg(a.side, it) }
        // Same, in the other direction.
        stitchAt(right, left)?.let { return Msg(a.side, it) }
        // Strict containment with no explicit overlap: keep the fuller text.
        if (right.length > left.length && beginsOrEnds(right, left)) return Msg(a.side, right)
        if (left.length > right.length && beginsOrEnds(left, right)) return Msg(a.side, left)
        return null
    }

    /** `older` + `newer` joined at the longest suffix/prefix overlap, or null. */
    private fun stitchAt(older: String, newer: String): String? {
        for (k in minOf(older.length, newer.length) downTo OVERLAP_MIN) {
            if (older.regionMatches(older.length - k, newer, 0, k, ignoreCase = false)) {
                return older + newer.substring(k)
            }
        }
        return null
    }

    private fun beginsOrEnds(full: String, part: String): Boolean =
        full.startsWith(part) || full.endsWith(part)

    private data class Anchor(val screenIndex: Int, val accIndex: Int, val run: Int)

    private fun findAnchor(screen: List<Msg>, acc: List<Msg>): Anchor? {
        var best: Anchor? = null
        for (s in screen.indices) {
            for (a in acc.indices) {
                if (screen[s].side != acc[a].side || screen[s].text != acc[a].text) continue
                var run = 0
                while (s + run < screen.size && a + run < acc.size &&
                    screen[s + run].side == acc[a + run].side &&
                    screen[s + run].text == acc[a + run].text) run++
                val candidate = Anchor(s, a, run)
                if (run >= 2) return candidate
                if (run == 1 && isLongUnique(screen, s) && isLongUnique(acc, a)) {
                    if (best == null) best = candidate
                }
            }
        }
        return best
    }

    private fun isLongUnique(rows: List<Msg>, index: Int): Boolean {
        val row = rows[index]
        if (row.text.length < UNIQUE_MIN_LEN) return false
        return rows.count { it.side == row.side && it.text == row.text } == 1
    }

    /** Minimum characters that must agree at a fragment junction. */
    private const val OVERLAP_MIN = 4

    /** A lone anchor line must be at least this long to be trusted. */
    private const val UNIQUE_MIN_LEN = 6
}
