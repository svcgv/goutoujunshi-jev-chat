package com.jev.probe.capture

/**
 * Recognises a chat message composer from node properties alone.
 *
 * Some chat apps (WeChat among them) draw their own input field and do not always
 * report `isEditable`. Relying on that single flag meant the composer was never
 * found and "填入" degraded to copying to the clipboard. Structure — class name,
 * a known input id, interactivity — is a more reliable signal.
 */
internal object ComposerShape {

    /** The composer sits in the lower part of the screen. */
    fun isInComposerRegion(centerY: Int, screenHeight: Int): Boolean =
        centerY >= screenHeight * 0.35f

    fun isComposer(className: String?, viewId: String?, enabled: Boolean, visible: Boolean,
                   clickable: Boolean, focusable: Boolean, editable: Boolean,
                   screenHeight: Int, centerY: Int = Int.MAX_VALUE): Boolean {
        if (!enabled || !visible) return false
        if (editable) return true
        val cls = className.orEmpty()
        val id = viewId.orEmpty()
        val looksEditable = cls.contains("EditText", ignoreCase = true) || id.endsWith(":id/bkk")
        if (!looksEditable) return false
        if (!(clickable || focusable)) return false
        return centerY == Int.MAX_VALUE || isInComposerRegion(centerY, screenHeight)
    }
}
