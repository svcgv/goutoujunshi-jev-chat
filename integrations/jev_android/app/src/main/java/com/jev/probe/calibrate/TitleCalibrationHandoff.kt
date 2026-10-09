package com.jev.probe.calibrate

import android.graphics.Bitmap

/**
 * Carries one screenshot from the accessibility service to the calibration
 * Activity. Bitmaps cannot go through an Intent (transaction size limits), so a
 * single in-process slot is used; it always holds at most one image and is
 * cleared as soon as the Activity consumes it.
 */
object TitleCalibrationHandoff {
    @Volatile private var bitmap: Bitmap? = null

    /** Package of the chat app the screenshot belongs to. */
    @Volatile var targetPackage: String = ""
        private set

    fun put(target: Bitmap, pkg: String) {
        bitmap?.recycle()
        bitmap = target
        targetPackage = pkg
    }

    /** Takes ownership of the pending image, or null when there is none. */
    fun take(): Bitmap? {
        val b = bitmap
        bitmap = null
        return b
    }

    fun peekPackage(): String = targetPackage

    fun clear() {
        bitmap?.recycle()
        bitmap = null
        targetPackage = ""
    }
}
