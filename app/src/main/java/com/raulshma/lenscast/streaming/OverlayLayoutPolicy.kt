package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Pure pixel rect for masking zones and the overlay box. No Android types. */
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
 * Zero Android imports: kotlin.math, pure parsing, and java.text date
 * formatting only.
 *
 * Degenerate-input guards (documented, covered by tests): zero-size bitmaps
 * yield an empty rect; non-positive blur radii floor to the 0.05 scale (the
 * old inline formula produced +Inf, capped to 0.5 — no caller passes <= 0 in
 * practice); only #RRGGBB/#AARRGGBB hex parses, anything else (including
 * named colors) is null and the caller falls back.
 */
object OverlayLayoutPolicy {

    /** The text overlay box's inset from the frame edge, in pixels. */
    const val OVERLAY_MARGIN_PX = 16

    private val dateFormatCache = ConcurrentHashMap<String, SimpleDateFormat>()

    private val reusableDate = Date()

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

    /**
     * Which lines the text overlay renders, top to bottom: timestamp,
     * branding, status, custom text. Blank branding/custom text renders
     * nothing even when switched on, and the status line appears only when
     * clients are watching. The "N viewer(s)" pluralization here is the
     * overlay-side twin — [com.raulshma.lenscast.camera.model.CameraDashboardPolicy]
     * owns the dashboard's separate client pluralization, and the two must
     * not be merged.
     */
    fun buildOverlayLines(
        settings: OverlaySettings,
        clientCount: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val lines = mutableListOf<String>()

        if (settings.showTimestamp) {
            val formatter = dateFormatCache.getOrPut(settings.timestampFormat) {
                SimpleDateFormat(settings.timestampFormat, Locale.getDefault())
            }
            synchronized(reusableDate) {
                reusableDate.time = nowMs
                lines.add(formatter.format(reusableDate))
            }
        }

        if (settings.showBranding && settings.brandingText.isNotBlank()) {
            lines.add(settings.brandingText)
        }

        if (settings.showStatus) {
            val statusParts = mutableListOf<String>()
            if (clientCount > 0) statusParts.add("${clientCount} viewer${if (clientCount != 1) "s" else ""}")
            if (statusParts.isNotEmpty()) lines.add(statusParts.joinToString("  "))
        }

        if (settings.showCustomText && settings.customText.isNotBlank()) {
            lines.add(settings.customText)
        }

        return lines
    }

    /**
     * The pure position→rect math for the text overlay box: each corner
     * insets [OVERLAY_MARGIN_PX] from the frame edge. No clamping — an
     * overlay larger than the frame produces negative origins, exactly like
     * the inline version it replaced.
     */
    fun computeOverlayPosition(
        position: OverlayPosition,
        bitmapWidth: Int,
        bitmapHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int,
    ): PixelRect {
        val margin = OVERLAY_MARGIN_PX
        return when (position) {
            OverlayPosition.TOP_LEFT ->
                PixelRect(margin, margin, margin + overlayWidth, margin + overlayHeight)
            OverlayPosition.TOP_RIGHT ->
                PixelRect(
                    bitmapWidth - overlayWidth - margin,
                    margin,
                    bitmapWidth - margin,
                    margin + overlayHeight,
                )
            OverlayPosition.BOTTOM_LEFT ->
                PixelRect(
                    margin,
                    bitmapHeight - overlayHeight - margin,
                    margin + overlayWidth,
                    bitmapHeight - margin,
                )
            OverlayPosition.BOTTOM_RIGHT ->
                PixelRect(
                    bitmapWidth - overlayWidth - margin,
                    bitmapHeight - overlayHeight - margin,
                    bitmapWidth - margin,
                    bitmapHeight - margin,
                )
        }
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
