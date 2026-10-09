package com.jev.probe.capture.ocr

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*

/**
 * System screen capture with two modes:
 *
 * - **one-shot** (default): a single frame per user consent, then the projection
 *   is released. This is the screenshot used by "截屏识别一次".
 * - **session** (`session=true`): the projection stays alive for the duration of
 *   one history backfill run and serves frames on demand through
 *   [ProjectionFrameBus]. It stops on [ACTION_STOP], on consent revocation, or
 *   when the caller tears the run down.
 *
 * The session keeps at most [MAX_IMAGES] unacquired frames so the producer can
 * never buffer without bound; a request drains stale frames first, then waits a
 * short grace period for the post-scroll frame to land.
 */
class ProjectionCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var completed = false
    private var requestId: String? = null
    private var session = false

    /** Pending session frame request; at most one at a time. */
    private var pendingFrame: ((Bitmap?, String?) -> Unit)? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (session) finishSession()
            else complete(null, "已取消截屏")
            return START_NOT_STICKY
        }
        if (projection != null || completed) return START_NOT_STICKY
        session = intent?.getBooleanExtra("session", false) == true
        requestId = intent?.getStringExtra("requestId")
        if (session) {
            if (!ProjectionSessionHandoff.isCurrent(requestId)) { stopSelf(); return START_NOT_STICKY }
        } else if (!CaptureHandoff.isCurrent(requestId)) {
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "屏幕捕获", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (session) "正在补录会话历史" else "正在识别本次聊天画面")
            .setContentText(if (session) "仅在本次补录中按需取帧，完成后自动停止" else "仅捕获一帧，完成后自动停止")
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>("resultData") ?: error("no consent")
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_CANCELED), data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (session) {
                        failPending("屏幕捕获已停止")
                        ProjectionSessionHandoff.fail(requestId, "屏幕捕获已停止")
                        clearBus()
                    } else {
                        complete(null, "屏幕捕获已停止，请重试或导入截图")
                    }
                }
            }, main)
            if (session) {
                // Let the consent activity disappear, then bring up the display.
                main.postDelayed({
                    if (createReader()) {
                        ProjectionFrameBus.publish { cb -> serveFrame(cb) }
                        ProjectionSessionHandoff.ready(requestId)
                    } else {
                        ProjectionSessionHandoff.fail(requestId, "无法创建屏幕捕获会话")
                        finishSession()
                    }
                }, SESSION_SETUP_DELAY_MS)
            } else {
                main.postDelayed({ captureOneShot() }, SESSION_SETUP_DELAY_MS)
                main.postDelayed({ complete(null, "系统截屏超时；受保护窗口请改用允许的手动截图") }, ONE_SHOT_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            if (session) { ProjectionSessionHandoff.fail(requestId, "系统截屏失败，请重新授权"); finishSession() }
            else complete(null, "系统截屏失败，请重新授权或导入截图")
        }
        return START_NOT_STICKY
    }

    // -------------------------------------------------------------- one shot

    private fun captureOneShot() {
        if (completed || !CaptureHandoff.isCurrent(requestId)) { complete(null, "本轮已取消"); return }
        if (!CaptureHandoff.canCapture(requestId)) { complete(null, "聊天窗口未恢复或已切换，请回到原会话重试"); return }
        if (!createReader()) return complete(null, "无法创建屏幕捕获；请重新授权")
        reader?.setOnImageAvailableListener({ source ->
            val bitmap = decode(source)
            complete(bitmap, if (bitmap == null) "截屏不可读取，可能受系统保护" else null)
        }, main)
    }

    private fun createReader(): Boolean = try {
        val dm = resources.displayMetrics
        val imageReader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, MAX_IMAGES)
        reader = imageReader
        display = projection?.createVirtualDisplay("Goutou capture", dm.widthPixels, dm.heightPixels,
            dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, main)
        display != null
    } catch (_: Exception) {
        false
    }

    // --------------------------------------------------------------- session

    /**
     * Answer one frame request. Stale frames are drained first so the bitmap
     * reflects the screen after the caller's scroll settled.
     */
    private fun serveFrame(cb: (Bitmap?, String?) -> Unit) {
        if (!session || projection == null || reader == null) { cb(null, "系统截屏会话已结束"); return }
        pendingFrame?.invoke(null, "新的取帧请求已替换上一次")
        pendingFrame = cb
        drainStale()
        main.postDelayed({ acquireFresh() }, FRAME_GRACE_MS)
    }

    private fun drainStale() {
        val r = reader ?: return
        runCatching {
            while (true) {
                val img = r.acquireLatestImage() ?: break
                img.close()
            }
        }
    }

    private fun acquireFresh() {
        val cb = pendingFrame ?: return
        val r = reader
        if (r == null) { pendingFrame = null; cb(null, "系统截屏会话已结束"); return }
        val bitmap = decodeFrom(r)
        pendingFrame = null
        cb(bitmap, if (bitmap == null) "截屏不可读取，可能受系统保护" else null)
    }

    private fun decodeFrom(r: ImageReader): Bitmap? {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return null
        return decodeImage(image)
    }

    private fun decode(source: ImageReader): Bitmap? {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return null
        return decodeImage(image)
    }

    private fun decodeImage(image: Image): Bitmap? {
        var bitmap: Bitmap? = null
        var padded: Bitmap? = null
        try {
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        } catch (_: Exception) {
            bitmap?.recycle(); bitmap = null
        } finally {
            image.close()
            if (padded !== bitmap) padded?.recycle()
        }
        return bitmap
    }

    private fun failPending(error: String) {
        val cb = pendingFrame ?: return
        pendingFrame = null
        cb(null, error)
    }

    private fun finishSession() {
        if (completed) return
        completed = true
        failPending("系统截屏会话已结束")
        clearBus()
        releaseResources()
        ProjectionSessionHandoff.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearBus() {
        ProjectionFrameBus.clear()
    }

    // ------------------------------------------------------------- lifecycle

    private fun complete(bitmap: Bitmap?, error: String?) {
        if (completed) { bitmap?.recycle(); return }
        completed = true
        main.removeCallbacksAndMessages(null)
        releaseResources()
        CaptureHandoff.finish(requestId, bitmap, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
    }

    override fun onDestroy() {
        if (!completed) {
            if (session) finishSession()
            else complete(null, "屏幕捕获已取消")
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.jev.probe.capture.ocr.STOP"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 2
        private const val MAX_IMAGES = 2
        private const val SESSION_SETUP_DELAY_MS = 700L
        private const val ONE_SHOT_TIMEOUT_MS = 10_000L
        private const val FRAME_GRACE_MS = 120L

        fun stop(context: android.content.Context) {
            runCatching {
                context.startService(Intent(context, ProjectionCaptureService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
