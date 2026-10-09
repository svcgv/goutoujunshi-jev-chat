package com.jev.probe.core

/**
 * Pure helpers for fixing up a reviewed transcript by hand.
 *
 * They exist so the multi-line review UI can offer split / merge / delete without
 * putting decision logic in the view layer, and so the behaviour is unit-tested.
 */
internal object MessageEditing {

    /**
     * Split [text] into two messages at [cursor] (the EditText selection).
     *
     * Returns null when the split would leave an empty half — splitting at the
     * very start or end of the text is a no-op the UI should ignore.
     */
    fun split(text: String, cursor: Int): Pair<String, String>? {
        if (cursor <= 0 || cursor >= text.length) return null
        val left = text.substring(0, cursor).trimEnd()
        val right = text.substring(cursor).trimStart()
        if (left.isEmpty() || right.isEmpty()) return null
        return left to right
    }

    /**
     * Join two adjacent messages of the same speaker. Internal newlines are kept:
     * a long bubble split across screens is re-joined without losing paragraphs.
     */
    fun merge(first: String, second: String): String {
        val a = first.trimEnd()
        val b = second.trimStart()
        return when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> a + "\n" + b
        }
    }

    /** Drop a message entirely; returns the list without the given index. */
    fun removeAt(messages: List<Msg>, index: Int): List<Msg> =
        if (index !in messages.indices) messages
        else messages.filterIndexed { i, _ -> i != index }
}
