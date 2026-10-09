package com.jev.probe.capture

import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.Msg

/**
 * Gives OCR text a speaker by looking at WHERE it sits.
 *
 * The node tree already tells us each bubble's rectangle and which side of the
 * screen it hugs — that is how the normal path knows who said what. When the
 * app hides its message text we fall back to OCR, and the old fallback threw
 * that geometry away: every line came back "待确认" and the user had to label
 * dozens of rows by hand before analysis was allowed.
 *
 * Here each recognized line is assigned to the bubble whose rectangle contains
 * it, so the OCR path keeps the same speaker attribution the tree would have
 * given.
 */
internal object OcrAttribution {

    /**
     * @return one [Msg] per bubble that received text, oldest (topmost) first;
     *         null when nothing could be assigned, so the caller can fall back
     *         to the geometry-free grouping.
     */
    fun assign(lines: List<OcrLine>, bubbles: List<BubbleRect>): List<Msg>? {
        if (lines.isEmpty() || bubbles.isEmpty()) return null
        val ordered = bubbles.sortedBy { it.rect.top }
        val perBubble = LinkedHashMap<Int, MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.bounds.top }) {
            val owner = ownerOf(line, ordered) ?: continue
            perBubble.getOrPut(owner) { ArrayList() }.add(line)
        }
        if (perBubble.isEmpty()) return null
        return ordered.mapIndexedNotNull { index, bubble ->
            val rows = perBubble[index] ?: return@mapIndexedNotNull null
            val text = rows.sortedBy { it.bounds.top }
                .joinToString(" ") { it.text.trim() }
                .trim()
            text.takeIf { it.isNotEmpty() }?.let { Msg(bubble.side, it) }
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * The smallest bubble that contains this line's vertical centre, so a line
     * never gets claimed by a large neighbour that merely overlaps it.
     */
    private fun ownerOf(line: OcrLine, ordered: List<BubbleRect>): Int? {
        val centre = (line.bounds.top + line.bounds.bottom) / 2
        var best: Int? = null
        var bestHeight = Int.MAX_VALUE
        for ((index, bubble) in ordered.withIndex()) {
            val r = bubble.rect
            val height = r.bottom - r.top
            if (height <= 0) continue
            if (centre < r.top || centre > r.bottom) continue
            if (height < bestHeight) {
                bestHeight = height
                best = index
            }
        }
        return best
    }
}
