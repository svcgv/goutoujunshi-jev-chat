package com.jev.probe.overlay

/**
 * Places the panel beside the bubble: to its right when that fits, otherwise to
 * its left, otherwise clamped on screen. The bubble itself never moves.
 */
internal object PanelPlacement {
    fun place(bubbleX: Int, bubbleY: Int, bubbleSize: Int, panelW: Int, panelH: Int,
              screenW: Int, screenH: Int, margin: Int): Pair<Int, Int> {
        val maxX = (screenW - panelW - margin).coerceAtLeast(margin)
        val maxY = (screenH - panelH - margin).coerceAtLeast(margin)
        val right = bubbleX + bubbleSize + margin
        val left = bubbleX - panelW - margin
        val x = when {
            right <= maxX -> right
            left >= margin -> left
            else -> bubbleX
        }
        return x.coerceIn(margin, maxX) to bubbleY.coerceIn(margin, maxY)
    }
}
