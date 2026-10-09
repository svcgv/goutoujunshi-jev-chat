package com.jev.probe.capture

import com.jev.probe.core.Msg

/** A geometric plan for one directional scroll gesture inside the message area. */
internal data class ScrollStep(
    val startX: Int,
    val startY: Int,
    val endY: Int,
    val durationMs: Long
)

/**
 * Plans the finger travel for one scroll step.
 *
 * "Toward older" means revealing messages above the current viewport: the finger
 * moves DOWN. "Toward newer" moves the finger UP. Both stay inside the message
 * area so a gesture can never land on the title bar, the composer or the
 * keyboard. Pure geometry, so it can be unit-tested without a device.
 */
internal object BackfillScroll {

    /** Fraction of the message-area height a normal collect step travels. */
    const val COLLECT_STEP = 0.55f

    /** A smaller step used to re-align a long bubble or recover from a gap. */
    const val RECOVERY_STEP = 0.30f

    /** Gesture duration; long enough for the list to treat it as a drag. */
    const val DURATION_MS = 320L

    fun plan(width: Int, areaTop: Int, areaBottom: Int,
             towardOlder: Boolean, step: Float): ScrollStep? {
        val height = areaBottom - areaTop
        if (width <= 0 || height <= 40) return null
        val x = width / 2
        val margin = (height * 0.10f).toInt().coerceAtLeast(8)
        val travel = (height * step.coerceIn(0.10f, 0.80f)).toInt().coerceAtLeast(20)
        return if (towardOlder) {
            val y0 = areaTop + margin
            val y1 = (y0 + travel).coerceAtMost(areaBottom - margin)
            if (y1 <= y0) null else ScrollStep(x, y0, y1, DURATION_MS)
        } else {
            val y0 = areaBottom - margin
            val y1 = (y0 - travel).coerceAtLeast(areaTop + margin)
            if (y1 >= y0) null else ScrollStep(x, y0, y1, DURATION_MS)
        }
    }
}

/**
 * A cheap fingerprint of one read screen. Two equal fingerprints mean the
 * viewport did not change, which is how a stuck scroll is detected without
 * trusting the gesture callback.
 */
internal object ScreenSignature {
    fun of(rows: List<Msg>): String = buildString(rows.size * 16) {
        for (row in rows) {
            append(row.side).append('\u0001').append(row.text.length)
                .append('.').append(row.text).append('\u0002')
        }
    }
}

/**
 * Folds successive screen reads into one ordered, deduplicated history.
 *
 * Not thread-safe by design: the coordinator drives it from the main thread, one
 * screen at a time (scroll → settle → read → fold).
 */
internal class BackfillAccumulator(val target: Int) {

    private var merged: List<Msg> = emptyList()

    /** Messages added by the most recent fold. */
    var lastAdded: Int = 0
        private set

    /** Whether the most recent fold could be aligned to what we already had. */
    var lastAnchored: Boolean = true
        private set

    /** Everything gathered so far, oldest-first. */
    val collected: Int get() = merged.size

    fun snapshot(): List<Msg> = merged

    fun fold(screen: List<Msg>): MessageMerge.Result {
        val r = MessageMerge.prepend(merged, screen)
        merged = r.messages
        lastAdded = r.added
        lastAnchored = r.anchored
        return r
    }

    /**
     * The most recent [target] messages, oldest-first. A long bubble that
     * straddled the target boundary may have pulled in extra older rows; they
     * are dropped here so the delivered window is exactly the newest [target].
     */
    fun resultMessages(): List<Msg> =
        if (merged.size <= target) merged else merged.takeLast(target)
}
