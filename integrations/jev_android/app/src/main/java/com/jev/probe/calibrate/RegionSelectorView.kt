package com.jev.probe.calibrate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.jev.probe.capture.TitleRegion

/**
 * Shows a screenshot and lets the user drag a rectangle over the conversation
 * title. The box is reported in image fractions so it can be stored per app and
 * reused at a different capture size.
 */
class RegionSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Image size in pixels. */
    private var imageWidth = 0
    private var imageHeight = 0

    /** Current selection in image pixels. */
    private var selLeft = 0f
    private var selTop = 0f
    private var selRight = 0f
    private var selBottom = 0f

    private var dragging = false
    private var startX = 0f
    private var startY = 0f

    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val clear = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
    private val border = Paint().apply {
        color = Color.parseColor("#FF3B30"); style = Paint.Style.STROKE
        strokeWidth = 6f; isAntiAlias = true
    }
    private val handle = Paint().apply { color = Color.parseColor("#FF3B30"); isAntiAlias = true }

    /** Where the image is drawn inside this view: left, top, scale. */
    private var drawLeft = 0f
    private var drawTop = 0f
    private var scale = 1f

    fun setImageSize(width: Int, height: Int) {
        imageWidth = width; imageHeight = height
        val initial = TitleRegion.initialSelection(width, height)
        selLeft = initial.left * width; selTop = initial.top * height
        selRight = initial.right * width; selBottom = initial.bottom * height
        requestLayout(); invalidate()
    }

    /** The current selection, or null when it is too small to be meaningful. */
    fun selectedRegion(): TitleRegion? =
        TitleRegion.fromPixels(
            selLeft.toInt(), selTop.toInt(), selRight.toInt(), selBottom.toInt(),
            imageWidth, imageHeight)

    fun setRegion(region: TitleRegion?) {
        val r = region ?: return
        selLeft = r.left * imageWidth; selTop = r.top * imageHeight
        selRight = r.right * imageWidth; selBottom = r.bottom * imageHeight
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (imageWidth <= 0 || imageHeight <= 0) return
        // Fit the image inside the view, preserving aspect ratio.
        scale = minOf(w.toFloat() / imageWidth, h.toFloat() / imageHeight)
        drawLeft = (w - imageWidth * scale) / 2f
        drawTop = (h - imageHeight * scale) / 2f
    }

    private fun toImageY(viewY: Float) = ((viewY - drawTop) / scale).coerceIn(0f, imageHeight.toFloat())
    private fun toImageX(viewX: Float) = ((viewX - drawLeft) / scale).coerceIn(0f, imageWidth.toFloat())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = toImageX(event.x); val y = toImageY(event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; startX = x; startY = y
                selLeft = x; selTop = y; selRight = x; selBottom = y
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                selLeft = minOf(startX, x); selRight = maxOf(startX, x)
                selTop = minOf(startY, y); selBottom = maxOf(startY, y)
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; invalidate(); return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth <= 0) return
        val w = imageWidth * scale
        val h = imageHeight * scale
        // Dim everything, then punch a hole over the selection so the title stays
        // visible while choosing.
        canvas.drawRect(drawLeft, drawTop, drawLeft + w, drawTop + h, dim)
        val sx = drawLeft + selLeft * scale
        val sy = drawTop + selTop * scale
        val ex = drawLeft + selRight * scale
        val ey = drawTop + selBottom * scale
        canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(drawLeft, drawTop, drawLeft + w, drawTop + h, dim)
        canvas.drawRect(sx, sy, ex, ey, clear)
        canvas.restore()
        canvas.drawRect(RectF(sx, sy, ex, ey), border)
        val r = 12f
        canvas.drawCircle(sx, sy, r, handle)
        canvas.drawCircle(ex, ey, r, handle)
    }
}
