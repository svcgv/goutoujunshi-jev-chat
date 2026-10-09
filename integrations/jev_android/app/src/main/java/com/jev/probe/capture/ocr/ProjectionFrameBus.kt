package com.jev.probe.capture.ocr

import android.graphics.Bitmap

/**
 * Process-wide handle to a live system-capture session.
 *
 * `ProjectionCaptureService` normally takes exactly one frame per user consent.
 * For history backfill it can instead stay alive for the duration of one run and
 * serve a frame on demand. This bus is the only channel between that service and
 * the accessibility service that drives the scrolling.
 *
 * Only one session exists at a time; [publish] replaces whatever was there and
 * [clear] tears it down. Requests are answered exactly once on the main thread.
 */
object ProjectionFrameBus {

    private var requester: ((Bitmap?, String?) -> Unit) -> Unit =
        { cb -> cb(null, "没有进行中的系统截屏会话") }

    @Volatile private var active = false

    fun isActive(): Boolean = active

    fun publish(requestFrame: ((Bitmap?, String?) -> Unit) -> Unit) {
        requester = requestFrame
        active = true
    }

    fun clear() {
        active = false
        requester = { cb -> cb(null, "系统截屏会话已结束") }
    }

    /** Ask for the newest frame. [cb] runs once with a bitmap or an error sentence. */
    fun request(cb: (Bitmap?, String?) -> Unit) {
        if (!active) { cb(null, "没有进行中的系统截屏会话"); return }
        runCatching { requester(cb) }.onFailure { cb(null, "系统截屏取帧失败：${it.javaClass.simpleName}") }
    }
}
