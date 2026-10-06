package com.jev.probe.overlay

/** Coordinates are relative to the available overlay area, without edge snapping. */
internal object BubblePosition {
    fun clamp(x: Int, y: Int, width: Int, height: Int, size: Int, margin: Int): Pair<Int, Int> {
        val maxX = (width - size - margin).coerceAtLeast(0)
        val maxY = (height - size - margin).coerceAtLeast(0)
        return x.coerceIn(margin.coerceAtMost(maxX), maxX) to
            y.coerceIn(margin.coerceAtMost(maxY), maxY)
    }
}
