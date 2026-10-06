package com.jev.probe.overlay

/**
 * Decides which overlay surfaces are visible.
 *
 * The panel is user-driven only: it is visible exactly when the user has it
 * expanded. Switching chats, landing on a group, or any background observation
 * must never open it — only an explicit tap does.
 */
internal object OverlayVisibility {
    fun bubbleVisible(hiddenForShot: Boolean): Boolean = !hiddenForShot

    fun panelVisible(expanded: Boolean, hiddenForShot: Boolean): Boolean =
        expanded && !hiddenForShot
}
