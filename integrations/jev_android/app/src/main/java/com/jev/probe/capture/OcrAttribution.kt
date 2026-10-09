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
     * Group OCR lines into messages the way the flat-screen fallback always did,
     * but give each group a side from where it sits.
     *
     * When the app hides even its bubble nodes there is no rectangle to match
     * against; the recognized text still carries the one signal that decides who
     * spoke: a left bubble is the other person, a right bubble is me. That is the
     * same rule the node path applies to bubble centres, so this is attribution,
     * not a guess at the speaker's identity.
     */
    fun groupByPosition(
        lines: List<OcrLine>,
        screenWidth: Int,
        edgeMargin: Int = (screenWidth * 0.10f).toInt()
    ): List<Msg> {
        if (screenWidth <= 0) return emptyList()
        val usable = lines.filter { it.text.isNotBlank() }.sortedBy { it.bounds.top }
        if (usable.isEmpty()) return emptyList()

        // Same vertical grouping rule as the geometry-free fallback: a gap wider
        // than a line separates two bubbles.
        val groups = ArrayList<MutableList<OcrLine>>()
        var current = ArrayList<OcrLine>()
        var prev: OcrLine? = null
        for (line in usable) {
            val previous = prev
            if (previous != null) {
                val gap = line.bounds.top - previous.bounds.bottom
                val lineHeight = maxOf(previous.bounds.bottom - previous.bounds.top, 1)
                if (gap > lineHeight * 1.2f) {
                    if (current.isNotEmpty()) { groups.add(current); current = ArrayList() }
                }
            }
            current.add(line)
            prev = line
        }
        if (current.isNotEmpty()) groups.add(current)

        return groups.map { group ->
            val text = group.joinToString(" ") { it.text.trim() }.trim()
            Msg(sideOf(group, screenWidth, edgeMargin), text)
        }
    }

    /**
     * Which side a group of lines hugs. Compared at the outer edges, because a
     * long bubble can reach past the middle of the screen.
     */
    private fun sideOf(group: List<OcrLine>, screenWidth: Int, edgeMargin: Int): String {
        val left = group.minOf { it.bounds.left }
        val right = group.maxOf { it.bounds.right }
        val distanceToLeft = kotlin.math.abs(left - edgeMargin)
        val distanceToRight = kotlin.math.abs((screenWidth - edgeMargin) - right)
        return if (distanceToRight < distanceToLeft) "me" else "other"
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
