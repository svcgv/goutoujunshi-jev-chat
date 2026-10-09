package com.jev.probe.calibrate

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.capture.ChatTitle
import com.jev.probe.capture.TitleRegion
import com.jev.probe.capture.TitleRegions
import com.jev.probe.core.Prefs

/**
 * Lets the user drag a box over a real chat screenshot to mark where the
 * conversation title is, and saves that box for the app it was captured from.
 *
 * The title is the identity we match contacts by, but WeChat (and others) draw
 * the bar themselves so the text is invisible to accessibility — it can only be
 * read from pixels, which means the region has to come from the user rather than
 * from a guess at some fixed height.
 */
class TitleCalibrationActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var selector: RegionSelectorView
    private lateinit var status: TextView
    private var shot: Bitmap? = null
    private var pkg: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pkg = TitleCalibrationHandoff.peekPackage()
        shot = TitleCalibrationHandoff.take()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(12), dp(20), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "框选会话标题"
            setTextColor(Color.WHITE); textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val hint = TextView(this).apply {
            text = "拖动框选聊天页顶部显示对方昵称的区域。" +
                "已按当前 App 分别保存：微信和 QQ 可以设置不同的位置。"
            setTextColor(Color.parseColor("#9CA3AF")); textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(hint)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        shot?.let { image.setImageBitmap(it) }
        frame.addView(image)
        selector = RegionSelectorView(this)
        frame.addView(selector)
        root.addView(frame)

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#E5E7EB")); textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
            text = existingSummary()
        }
        root.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(action("测试识别", accent = false) { testRead() })
        buttons.addView(action("保存", accent = true) { save() })
        buttons.addView(action("清除", accent = false) { clear() })
        buttons.addView(action("取消", accent = false) { finish() })
        root.addView(buttons)

        setContentView(root)

        shot?.let { selector.setImageSize(it.width, it.height) }
        TitleRegions.decode(prefs.titleRegions)[pkg]?.let { selector.setRegion(it) }

        if (shot == null) {
            Toast.makeText(this, "没有可用的截图，请回到聊天窗口重试", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun existingSummary(): String {
        if (pkg.isBlank()) return "未识别到当前聊天 App"
        val saved = TitleRegions.decode(prefs.titleRegions)[pkg]
        return if (saved == null) "当前 App 尚未设置标题区域"
        else "当前 App 已设置：${"%.1f".format(saved.top * 100)}% – ${"%.1f".format(saved.bottom * 100)}% 高度"
    }

    private fun save() {
        val region = selector.selectedRegion()
        if (region == null) {
            Toast.makeText(this, "框选区域太小，请重新拖动", Toast.LENGTH_SHORT).show(); return
        }
        val next = TitleRegions.with(TitleRegions.decode(prefs.titleRegions), pkg, region)
        prefs.titleRegions = TitleRegions.encode(next)
        status.text = "已保存（$pkg）"
        Toast.makeText(this, "已保存标题区域", Toast.LENGTH_SHORT).show()
    }

    private fun clear() {
        val next = TitleRegions.with(TitleRegions.decode(prefs.titleRegions), pkg, null)
        prefs.titleRegions = TitleRegions.encode(next)
        status.text = "已清除当前 App 的标题区域"
    }

    /** Reads the selected area so the user can see whether it captures the name. */
    private fun testRead() {
        val region = selector.selectedRegion()
        val bmp = shot
        if (region == null || bmp == null) {
            Toast.makeText(this, "请先框选区域", Toast.LENGTH_SHORT).show(); return
        }
        val px = region.pixelsFor(bmp.width, bmp.height) ?: run {
            Toast.makeText(this, "框选区域太小", Toast.LENGTH_SHORT).show(); return
        }
        status.text = "识别中…"
        val mlKit = com.jev.probe.capture.ocr.MlKitOcr()
        mlKit.recognize(bmp, android.graphics.Rect(px[0], px[1], px[2], px[3])) { lines ->
            val picked = ChatTitle.pick(lines.map { it.text to it.bounds.top })
            status.text = if (picked == null) "这一区域没有识别到昵称，请调整框选范围"
            else "识别到：$picked"
        }
    }

    private fun action(label: String, accent: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (accent) Color.WHITE else Color.parseColor("#E5E7EB"))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(if (accent) "#2B5245" else "#2A2E37"))
        }
        setPadding(dp(16), dp(11), dp(16), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        // The Activity owns the bitmap after taking it; release it here.
        shot?.let { if (!it.isRecycled) it.recycle() }
        shot = null
        super.onDestroy()
    }
}
