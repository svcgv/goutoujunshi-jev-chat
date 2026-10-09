package com.jev.probe

import android.app.Activity
import android.content.Intent
import android.graphics.ImageDecoder
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.media.projection.MediaProjectionConfig
import com.jev.probe.capture.ocr.CaptureHandoff
import com.jev.probe.capture.ocr.ProjectionCaptureService
import com.jev.probe.capture.ocr.ProjectionSessionHandoff

/**
 * Visible system consent / document picker, launched only from an explicit user action.
 *
 * Three modes:
 * - import  : document picker → one bitmap
 * - one-shot: MediaProjection consent → exactly one frame
 * - session : MediaProjection consent → a live session that serves frames while a
 *             backfill run drives the chat
 */
class CapturePermissionActivity : Activity() {
    private val requestId get() = intent.getStringExtra("requestId")
    private val session get() = intent.getBooleanExtra("session", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = if (session) ProjectionSessionHandoff.isCurrent(requestId)
                      else CaptureHandoff.isCurrent(requestId)
        if (!current) { finish(); return }
        if (savedInstanceState != null) return
        try {
            val request = when {
                intent.getBooleanExtra("import", false) ->
                    Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                else -> {
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    else manager.createScreenCaptureIntent()
                }
            }
            startActivityForResult(request, if (intent.getBooleanExtra("import", false)) 2 else 1)
        } catch (_: Exception) {
            fail("无法打开系统授权或图片选择器")
            finish()
        }
    }

    @Deprecated("Activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            fail(if (session) "已取消系统截屏授权" else "已取消截屏或导入")
            finish(); return
        }
        if (requestCode == 1) {
            if (session && !ProjectionSessionHandoff.isCurrent(requestId)) { finish(); return }
            if (!session && !CaptureHandoff.isCurrent(requestId)) { finish(); return }
            try {
                startForegroundService(Intent(this, ProjectionCaptureService::class.java)
                    .putExtra("requestId", requestId)
                    .putExtra("resultCode", resultCode)
                    .putExtra("resultData", data)
                    .putExtra("session", session))
            } catch (e: Exception) {
                fail("系统不允许启动截屏：${e.javaClass.simpleName}")
            }
            finish()
        } else {
            val uri = data.data
            finish()
            if (!CaptureHandoff.isCurrent(requestId)) return
            Thread {
                val bitmap = try {
                    require(uri != null)
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                        val scale = minOf(1.0, 2400.0 / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } catch (_: Exception) { null }
                runOnUiThread { CaptureHandoff.finish(requestId, bitmap, if (bitmap == null) "图片无法读取，请选择普通聊天截图" else null) }
            }.start()
        }
    }

    private fun fail(message: String) {
        if (session) ProjectionSessionHandoff.fail(requestId, message)
        else CaptureHandoff.finish(requestId, null, message)
    }
}
