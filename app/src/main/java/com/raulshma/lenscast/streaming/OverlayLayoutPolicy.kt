package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.MaskingZone

/** Pure pixel rect for masking zones. No Android types. */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isEmpty: Boolean
        get() = right <= left || bottom <= top
}

/**
 * Pure overlay/masking math extracted verbatim from StreamOverlayRenderer.
 * Zero Android imports: kotlin.math + pure parsing only.
 *
 * Degenerate-input guards (documented, covered by tests): zero-size bitmaps
 * yield an empty rect; non-positive blur radii floor to the 0.05 scale (the
 * old inline formula produced +Inf, capped to 0.5 — no caller passes <= 0 in
 * practice); only #RRGGBB/#AARRGGBB hex parses, anything else (including
 * named colors) is null and the caller falls back.
 */
object OverlayLayoutPolicy {

    fun zoneToPixels(
        zone: MaskingZone,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): PixelRect {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return PixelRect(0, 0, 0, 0)
        val left = (zone.x * bitmapWidth).toInt().coerceIn(0, bitmapWidth)
        val top = (zone.y * bitmapHeight).toInt().coerceIn(0, bitmapHeight)
        val right = ((zone.x + zone.width) * bitmapWidth).toInt().coerceIn(0, bitmapWidth)
        val bottom = ((zone.y + zone.height) * bitmapHeight).toInt().coerceIn(0, bitmapHeight)
        return PixelRect(left, top, right, bottom)
    }

    fun pixelateDownscale(
        regionW: Int,
        regionH: Int,
        pixelSize: Int,
    ): Pair<Int, Int> {
        val safePixelSize = pixelSize.coerceAtLeast(1)
        val smallWidth = maxOf(1, regionW / safePixelSize)
        val smallHeight = maxOf(1, regionH / safePixelSize)
        return smallWidth to smallHeight
    }

    fun blurDownscale(
        regionW: Int,
        regionH: Int,
        blurRadius: Float,
    ): Pair<Int, Int> {
        val scaleFactor = if (blurRadius <= 0f) {
            0.05f
        } else {
            (1f / (blurRadius * 0.5f)).coerceIn(0.05f, 0.5f)
        }
        val smallWidth = maxOf(1, (regionW * scaleFactor).toInt())
        val smallHeight = maxOf(1, (regionH * scaleFactor).toInt())
        return smallWidth to smallHeight
    }

    /**
     * Pure `#RRGGBB` / `#AARRGGBB` parse (leading '#' optional).
     * Returns the unsigned 32-bit ARGB value as Long, or null when invalid.
     */
    fun parseColorOrNull(hex: String): Long? {
        val clean = if (hex.startsWith("#")) hex.substring(1) else hex
        if (clean.length != 6 && clean.length != 8) return null
        if (!clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        val parsed = clean.toLong(16)
        return if (clean.length == 6) parsed or 0xFF000000L else parsed and 0xFFFFFFFFL
    }
}
