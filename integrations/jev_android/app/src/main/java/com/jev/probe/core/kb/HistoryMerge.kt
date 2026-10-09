package com.jev.probe.core.kb

/**
 * Inserts a freshly back-filled interval into a contact's saved history.
 *
 * Backfill walks toward OLDER messages, so a new batch is expected to sit before
 * (or overlap the start of) what is already stored. Unlike the append path used
 * for live screens, this never assumes the batch is newer and never rewrites the
 * whole timeline by set membership — repeated short lines stay distinct.
 *
 * When no contiguous overlap can be proven the batch cannot be placed on the main
 * timeline; the caller stores it as an independent fragment instead of guessing.
 */
internal object HistoryMerge {

    data class Result(
        /** Merged timeline, oldest-first. */
        val entries: List<LogEntry>,
        /** Rows actually inserted (0 when the batch was already present). */
        val inserted: Int,
        /** False when continuity could not be proven (independent fragment). */
        val anchored: Boolean
    )

    fun insert(existing: List<LogEntry>, older: List<LogEntry>): Result {
        if (older.isEmpty()) return Result(existing, 0, true)
        if (existing.isEmpty()) return Result(older, older.size, true)

        // A. The exact batch is already stored: idempotent re-run.
        if (indexOfSlice(existing, older) >= 0) return Result(existing, 0, true)

        // B. older's tail overlaps existing's head → older content goes in front.
        for (k in minOf(older.size, existing.size) downTo 1) {
            if (matches(older, older.size - k, existing, 0, k)) {
                val head = older.take(older.size - k)
                return Result(head + existing, head.size, true)
            }
        }

        // C. existing's tail overlaps older's head → older content goes after.
        for (k in minOf(older.size, existing.size) downTo 1) {
            if (matches(existing, existing.size - k, older, 0, k)) {
                val tail = older.drop(k)
                return Result(existing + tail, tail.size, true)
            }
        }

        // D. No proven continuity: hand back an independent fragment.
        return Result(older + existing, older.size, false)
    }

    private fun indexOfSlice(haystack: List<LogEntry>, needle: List<LogEntry>): Int {
        if (needle.size > haystack.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (!same(haystack[start + i], needle[i])) continue@outer
            }
            return start
        }
        return -1
    }

    private fun matches(a: List<LogEntry>, aStart: Int, b: List<LogEntry>, bStart: Int, k: Int): Boolean {
        for (i in 0 until k) if (!same(a[aStart + i], b[bStart + i])) return false
        return true
    }

    private fun same(a: LogEntry, b: LogEntry): Boolean = a.side == b.side && a.text == b.text
}
