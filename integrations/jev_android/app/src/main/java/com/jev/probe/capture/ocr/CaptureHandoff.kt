package com.jev.probe.capture.ocr

import android.graphics.Bitmap
import java.util.UUID

/** A generation-scoped in-process handoff; old consent dialogs cannot finish a newer request. */
object CaptureHandoff {
    var requestId: String = ""
        private set
    private var callback: ((Bitmap?, String?) -> Unit)? = null
    private var frameGuard: () -> Boolean = { false }
    fun begin(allowFrame: () -> Boolean = { true }, callback: (Bitmap?, String?) -> Unit): String {
        requestId = UUID.randomUUID().toString()
        this.callback = callback
        frameGuard = allowFrame
        return requestId
    }
    fun isCurrent(id: String?): Boolean = !id.isNullOrBlank() && id == requestId && callback != null
    fun canCapture(id: String?): Boolean = isCurrent(id) && frameGuard()
    fun cancel() { requestId = ""; callback = null; frameGuard = { false } }
    fun finish(id: String?, bitmap: Bitmap?, error: String? = null) {
        if (!isCurrent(id)) { bitmap?.recycle(); return }
        val target = callback
        cancel()
        target?.invoke(bitmap, error)
    }
}
