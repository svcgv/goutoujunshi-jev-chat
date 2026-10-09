package com.jev.probe.capture

/** The vertical slice of a chat screenshot that holds messages, in image coordinates. */
internal data class CaptureRegion(val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/**
 * Chooses what part of a chat screenshot to read.
 *
 * The old rule was a fixed band (top 12% / bottom 84%) chosen to skip the status
 * bar and the input box on an imagined "average" phone. On a tall screen that
 * band cut off real messages above the composer; on a short one it ate into the
 * action bar. Instead we use the coordinates the node tree already gives us:
 * from the first message bubble down to the composer.
 *
 * When a coordinate is unknown we do NOT guess — that edge is simply left
 * uncropped, so nothing that might be a message is thrown away.
 */
internal object CaptureRegionCalculator {

    fun compute(imageHeight: Int, firstBubbleTop: Int?, composerTop: Int?): CaptureRegion {
        if (imageHeight <= 0) return CaptureRegion(0, 0)
        val whole = CaptureRegion(0, imageHeight)

        val top = firstBubbleTop?.takeIf { it in 0 until imageHeight } ?: return whole
        val bottom = composerTop?.takeIf { it in 0..imageHeight } ?: return whole

        // The composer must sit below the first bubble. If the tree says
        // otherwise, its coordinates cannot be trusted: read the whole image
        // rather than crop away messages on the strength of a bad reading.
        if (bottom <= top) return whole
        if (bottom - top < imageHeight / 10) return whole
        return CaptureRegion(top, bottom)
    }
}
