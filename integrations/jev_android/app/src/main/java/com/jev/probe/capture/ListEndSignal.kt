package com.jev.probe.capture

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Whether the newest message is the last visible row of the conversation list.
 *
 * Some chat lists expose accessibility collection metadata (rowCount plus each
 * row's index). When it is there, it is a *definitive* answer to "are we at the
 * bottom?" and is worth much more than guessing from repeated screens. When it
 * is absent — WeChat's custom list usually omits it — the caller falls back to
 * the stable-read heuristic.
 */
internal object ListEndSignal {

    /**
     * Pure combination of the two collection values, kept separate from node
     * traversal so it can be unit-tested.
     *
     * @return true when the last row is visible, false when more rows follow,
     *         null when the app does not report enough information.
     */
    fun from(rowCount: Int?, maxVisibleRowEnd: Int?): Boolean? {
        if (rowCount == null || rowCount <= 0) return null
        if (maxVisibleRowEnd == null || maxVisibleRowEnd < 0) return null
        return maxVisibleRowEnd >= rowCount
    }

    /** Walk the tree for collection metadata; null when nothing usable is found. */
    fun detect(root: AccessibilityNodeInfo): Boolean? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val info = runCatching { node.collectionInfo }.getOrNull()
            if (info != null && info.rowCount > 0) {
                val end = maxVisibleRowEnd(node)
                from(info.rowCount, end)?.let { return it }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    /** Highest visible row end index inside [container], or null if unreported. */
    private fun maxVisibleRowEnd(container: AccessibilityNodeInfo): Int? {
        var best: Int? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(container)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val item = runCatching { node.collectionItemInfo }.getOrNull()
            if (item != null) {
                val end = item.rowIndex + maxOf(1, item.rowSpan)
                if (best == null || end > best!!) best = end
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }
}
