package com.jev.probe.capture

/** A vertical span in screen coordinates. */
internal data class VSpan(val top: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/**
 * Which part of the message band is covered by other windows.
 *
 * A keyboard that pushes the composer up is harmless (the adapter's viewport
 * already excludes it), but a floating keyboard, a notification shade or a
 * heads-up banner can sit ON TOP of the messages. Merging an occluded frame
 * would silently corrupt the transcript, so those frames are skipped.
 */
internal object FrameOcclusion {

    /** Anything covering at least this share of the message band is "occluded". */
    const val DEFAULT_THRESHOLD = 0.20f

    /** Fraction of [band] covered by the union of [others], clamped to 0..1. */
    fun coverage(band: VSpan, others: List<VSpan>): Float {
        if (band.height <= 0) return 0f
        val clipped = others
            .map { VSpan(maxOf(band.top, it.top), minOf(band.bottom, it.bottom)) }
            .filter { it.height > 0 }
            .sortedBy { it.top }
        if (clipped.isEmpty()) return 0f
        var covered = 0
        var start = clipped[0].top
        var end = clipped[0].bottom
        for (i in 1 until clipped.size) {
            val s = clipped[i].top
            val e = clipped[i].bottom
            if (s > end) {
                covered += end - start
                start = s; end = e
            } else if (e > end) {
                end = e
            }
        }
        covered += end - start
        return (covered.toFloat() / band.height).coerceIn(0f, 1f)
    }

    fun isOccluded(band: VSpan, others: List<VSpan>, threshold: Float = DEFAULT_THRESHOLD): Boolean =
        coverage(band, others) >= threshold
}
