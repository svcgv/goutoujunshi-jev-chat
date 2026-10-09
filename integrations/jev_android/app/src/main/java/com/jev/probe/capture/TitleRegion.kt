package com.jev.probe.capture

/**
 * The area of a chat screenshot that holds the conversation title, chosen by the
 * user by dragging a box over a real screenshot.
 *
 * Stored as fractions of the captured image (0..1) rather than pixels so the same
 * selection keeps working when the window is captured at a different size, in a
 * different orientation, or on another device. Nothing here is hard-coded: with
 * no saved region the title is simply not read from pixels.
 */
data class TitleRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isValid(): Boolean =
        left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
            right - left >= MIN_SIDE && bottom - top >= MIN_SIDE

    /** Pixel bounds for an image of this size, or null when the region is unusable. */
    fun pixelsFor(imageWidth: Int, imageHeight: Int): IntArray? {
        if (imageWidth <= 0 || imageHeight <= 0 || !isValid()) return null
        val l = (left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val t = (top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val r = (right * imageWidth).toInt().coerceIn(l + 1, imageWidth)
        val b = (bottom * imageHeight).toInt().coerceIn(t + 1, imageHeight)
        if (r - l < MIN_PIXELS || b - t < MIN_PIXELS) return null
        return intArrayOf(l, t, r, b)
    }

    fun encode(): String = "$left,$top,$right,$bottom"

    companion object {
        const val MIN_SIDE = 0.01f
        const val MIN_PIXELS = 8

        fun decode(raw: String?): TitleRegion? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(',')
            if (parts.size != 4) return null
            val values = parts.map { it.trim().toFloatOrNull() ?: return null }
            return TitleRegion(values[0], values[1], values[2], values[3]).takeIf { it.isValid() }
        }

        fun fromPixels(x1: Int, y1: Int, x2: Int, y2: Int, imageWidth: Int, imageHeight: Int): TitleRegion? {
            if (imageWidth <= 0 || imageHeight <= 0) return null
            val l = minOf(x1, x2).coerceIn(0, imageWidth)
            val r = maxOf(x1, x2).coerceIn(0, imageWidth)
            val t = minOf(y1, y2).coerceIn(0, imageHeight)
            val b = maxOf(y1, y2).coerceIn(0, imageHeight)
            val region = TitleRegion(
                left = l.toFloat() / imageWidth,
                top = t.toFloat() / imageHeight,
                right = r.toFloat() / imageWidth,
                bottom = b.toFloat() / imageHeight)
            return region.takeIf { it.isValid() }
        }

        /** A centred band used only as the initial drag box during calibration. */
        fun initialSelection(imageWidth: Int, imageHeight: Int): TitleRegion =
            fromPixels(
                (imageWidth * 0.20f).toInt(), (imageHeight * 0.045f).toInt(),
                (imageWidth * 0.80f).toInt(), (imageHeight * 0.105f).toInt(),
                imageWidth, imageHeight)!!
    }
}

/**
 * Title areas keyed by chat app package, so WeChat and QQ can be calibrated
 * independently — their title bars differ in height and padding, and a single
 * shared region would misread at least one of them.
 */
object TitleRegions {

    /** Encodes per-app regions as `pkg=left,top,right,bottom` separated by `;`. */
    fun encode(regions: Map<String, TitleRegion>): String =
        regions.entries
            .filter { it.key.isNotBlank() && it.value.isValid() }
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value.encode()}" }

    fun decode(raw: String?): Map<String, TitleRegion> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, TitleRegion>()
        raw.split(';').forEach { entry ->
            val pkg = entry.substringBefore('=', "").trim()
            val value = entry.substringAfter('=', "")
            if (pkg.isEmpty() || value.isEmpty()) return@forEach
            TitleRegion.decode(value)?.let { out[pkg] = it }
        }
        return out
    }

    fun with(regions: Map<String, TitleRegion>, pkg: String, region: TitleRegion?): Map<String, TitleRegion> {
        if (pkg.isBlank()) return regions
        val next = LinkedHashMap(regions)
        if (region == null || !region.isValid()) next.remove(pkg) else next[pkg] = region
        return next
    }
}
