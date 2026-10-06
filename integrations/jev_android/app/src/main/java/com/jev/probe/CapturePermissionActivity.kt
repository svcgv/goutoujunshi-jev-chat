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

/** Visible system consent / document picker, launched only from an explicit user action. */
class CapturePermissionActivity : Activity() {
    private val requestId get() = intent.getStringExtra("requestId")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CaptureHandoff.isCurrent(requestId)) { finish(); return }
        if (savedInstanceState != null) return
        try {
            val request = if (intent.getBooleanExtra("import", false))
                Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            else {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            }
            startActivityForResult(request, if (intent.getBooleanExtra("import", false)) 2 else 1)
        } catch (_: Exception) {
            CaptureHandoff.finish(requestId, null, "无法打开系统授权或图片选择器")
            finish()
        }
    }

    @Deprecated("Activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null || !CaptureHandoff.isCurrent(requestId)) {
            CaptureHandoff.finish(requestId, null, "已取消截屏或导入")
            finish(); return
        }
        if (requestCode == 1) {
            try {
                startForegroundService(Intent(this, ProjectionCaptureService::class.java)
                    .putExtra("requestId", requestId).putExtra("resultCode", resultCode).putExtra("resultData", data))
            } catch (_: Exception) { CaptureHandoff.finish(requestId, null, "系统不允许启动截屏，请重试或导入截图") }
            finish()
        } else {
            val uri = data.data
            finish()
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
}
