package com.jev.probe.capture.ocr

import java.util.UUID

/**
 * Generation-scoped handshake for starting a multi-frame system-capture session.
 *
 * Separate from [CaptureHandoff] (which transfers one bitmap): here the consent
 * activity only needs to tell the caller whether the session came up. The frames
 * themselves travel through [ProjectionFrameBus].
 */
object ProjectionSessionHandoff {
    var requestId: String = ""
        private set
    private var callback: ((Boolean, String?) -> Unit)? = null

    fun begin(callback: (Boolean, String?) -> Unit): String {
        requestId = UUID.randomUUID().toString()
        this.callback = callback
        return requestId
    }

    fun isCurrent(id: String?): Boolean = !id.isNullOrBlank() && id == requestId && callback != null

    fun cancel() { requestId = ""; callback = null }

    fun ready(id: String?) {
        if (!isCurrent(id)) return
        val target = callback
        cancel()
        target?.invoke(true, null)
    }

    fun fail(id: String?, error: String?) {
        if (!isCurrent(id)) return
        val target = callback
        cancel()
        target?.invoke(false, error)
    }
}
