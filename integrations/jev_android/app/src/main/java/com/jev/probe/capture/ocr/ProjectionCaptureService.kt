package com.jev.probe.capture.ocr

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*

/** One frame per system consent; releases the projection immediately afterward. */
class ProjectionCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var completed = false
    private var requestId: String? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null || completed) return START_NOT_STICKY
        requestId = intent?.getStringExtra("requestId")
        if (!CaptureHandoff.isCurrent(requestId)) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("capture", "单次屏幕捕获", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("正在识别本次聊天画面")
            .setContentText("仅捕获一帧，完成后自动停止").build()
        try {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>("resultData") ?: error("no consent")
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_CANCELED), data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { complete(null, "屏幕捕获已停止，请重试或导入截图") }
            }, main)
            // Let the permission activity disappear before creating the one-shot display.
            main.postDelayed({ captureFrame() }, 700)
            main.postDelayed({ complete(null, "系统截屏超时；受保护窗口请改用允许的手动截图") }, 10000)
        } catch (_: Exception) { complete(null, "系统截屏失败，请重新授权或导入截图") }
        return START_NOT_STICKY
    }

    private fun captureFrame() {
        if (completed || !CaptureHandoff.isCurrent(requestId)) { complete(null, "本轮已取消"); return }
        if (!CaptureHandoff.canCapture(requestId)) { complete(null, "聊天窗口未恢复或已切换，请回到原会话重试"); return }
        try {
            val dm = resources.displayMetrics
            val imageReader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                var bitmap: Bitmap? = null
                var padded: Bitmap? = null
                try {
                    val plane = image.planes[0]
                    val paddedWidth = plane.rowStride / plane.pixelStride
                    padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                } catch (_: Exception) { bitmap?.recycle(); bitmap = null }
                finally { image.close(); if (padded !== bitmap) padded?.recycle() }
                complete(bitmap, if (bitmap == null) "截屏不可读取，可能受系统保护" else null)
            }, main)
            display = projection!!.createVirtualDisplay("Goutou single capture", dm.widthPixels, dm.heightPixels,
                dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, main)
        } catch (_: Exception) { complete(null, "无法创建屏幕捕获；请重新授权") }
    }

    private fun complete(bitmap: Bitmap?, error: String?) {
        if (completed) { bitmap?.recycle(); return }
        completed = true
        main.removeCallbacksAndMessages(null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        CaptureHandoff.finish(requestId, bitmap, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!completed) complete(null, "屏幕捕获已取消")
        super.onDestroy()
    }
}
