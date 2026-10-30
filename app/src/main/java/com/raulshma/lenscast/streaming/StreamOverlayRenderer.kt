package com.raulshma.lenscast.streaming

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.OverlaySettings

object StreamOverlayRenderer {

    private const val TAG = "StreamOverlayRenderer"

    private val reusableRect = android.graphics.Rect()

    // Reusable Paint objects to avoid per-frame allocations
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun applyOverlay(
        bitmap: Bitmap,
        settings: OverlaySettings,
        clientCount: Int = 0,
    ): Bitmap {
        if (!settings.enabled && !settings.maskingEnabled) return bitmap

        return try {
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)

            if (settings.maskingEnabled && settings.maskingZones.isNotEmpty()) {
                applyMaskingZones(canvas, bitmap, settings.maskingZones, bitmap.width, bitmap.height)
            }

            if (settings.enabled) {
                drawTextOverlay(canvas, settings, clientCount, System.currentTimeMillis())
            }

            mutableBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply overlay", e)
            bitmap
        }
    }

    private fun applyMaskingZones(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        zones: List<MaskingZone>,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ) {
        for (zone in zones) {
            if (!zone.enabled) continue

            val rect = OverlayLayoutPolicy.zoneToPixels(zone, bitmapWidth, bitmapHeight)
            if (rect.isEmpty) continue

            val left = rect.left
            val top = rect.top
            val right = rect.right
            val bottom = rect.bottom

            when (zone.type) {
                MaskingType.BLACKOUT -> {
                    maskPaint.color = Color.BLACK
                    canvas.drawRect(Rect(left, top, right, bottom), maskPaint)
                }

                MaskingType.PIXELATE -> {
                    applyPixelate(canvas, sourceBitmap, left, top, right, bottom, zone.pixelateSize)
                }

                MaskingType.BLUR -> {
                    applyBlur(canvas, sourceBitmap, left, top, right, bottom, zone.blurRadius)
                }
            }
        }
    }

    private fun applyPixelate(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        pixelSize: Int,
    ) {
        val regionWidth = right - left
        val regionHeight = bottom - top
        if (regionWidth <= 0 || regionHeight <= 0) return

        val (smallWidth, smallHeight) =
            OverlayLayoutPolicy.pixelateDownscale(regionWidth, regionHeight, pixelSize)

        try {
            val regionBitmap = Bitmap.createBitmap(sourceBitmap, left, top, regionWidth, regionHeight)
            val scaledDown = Bitmap.createScaledBitmap(regionBitmap, smallWidth, smallHeight, false)
            val scaledUp = Bitmap.createScaledBitmap(scaledDown, regionWidth, regionHeight, false)

            canvas.drawBitmap(scaledUp, left.toFloat(), top.toFloat(), null)

            regionBitmap.recycle()
            scaledDown.recycle()
            scaledUp.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Pixelate failed, falling back to blackout", e)
            maskPaint.color = Color.BLACK
            canvas.drawRect(Rect(left, top, right, bottom), maskPaint)
        }
    }

    private fun applyBlur(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Float,
    ) {
        val regionWidth = right - left
        val regionHeight = bottom - top
        if (regionWidth <= 0 || regionHeight <= 0) return

        val (smallWidth, smallHeight) =
            OverlayLayoutPolicy.blurDownscale(regionWidth, regionHeight, radius)

        try {
            val regionBitmap = Bitmap.createBitmap(sourceBitmap, left, top, regionWidth, regionHeight)
            val scaledDown = Bitmap.createScaledBitmap(regionBitmap, smallWidth, smallHeight, true)
            val scaledUp = Bitmap.createScaledBitmap(scaledDown, regionWidth, regionHeight, true)

            canvas.drawBitmap(scaledUp, left.toFloat(), top.toFloat(), null)

            regionBitmap.recycle()
            scaledDown.recycle()
            scaledUp.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Blur failed, falling back to blackout", e)
            maskPaint.color = Color.BLACK
            canvas.drawRect(Rect(left, top, right, bottom), maskPaint)
        }
    }

    /**
     * The text overlay box rasterized standalone: exactly the size
     * [OverlayLayoutPolicy.buildOverlayLines] content measures to, background
     * round-rect and all lines drawn from origin (0,0), scaled relative to
     * [targetWidth] like the frame it is meant for. Shared by both outputs —
     * the MJPEG path draws it onto the frame canvas at the computed position,
     * the encoded path converts it to a YUVA strip and blends it into NV21 —
     * so a timestamp looks identical on either stream. Returns null when
     * nothing renders (overlay disabled, or no lines); the caller recycles
     * the bitmap.
     */
    fun renderTextOverlayBitmap(
        settings: OverlaySettings,
        targetWidth: Int,
        clientCount: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): Bitmap? {
        if (!settings.enabled) return null
        val lines = OverlayLayoutPolicy.buildOverlayLines(settings, clientCount, nowMs)
        if (lines.isEmpty()) return null

        val scale = targetWidth / OverlayLayoutPolicy.REFERENCE_WIDTH
        val fontSize = (settings.fontSize * scale).coerceAtLeast(12f).toInt()
        val padding = (settings.padding * scale).coerceAtLeast(2f).toInt()
        val lineHeight = (settings.lineHeight * scale).coerceAtLeast(1f).toInt()

        textPaint.color = parseColor(settings.textColor, Color.WHITE)
        textPaint.textSize = fontSize.toFloat()
        bgPaint.color = parseColor(settings.backgroundColor, Color.parseColor("#80000000"))

        return synchronized(reusableRect) {
            var maxWidth = 0
            var totalHeight = 0
            for ((i, line) in lines.withIndex()) {
                textPaint.getTextBounds(line, 0, line.length, reusableRect)
                if (reusableRect.width() > maxWidth) maxWidth = reusableRect.width()
                totalHeight += reusableRect.height()
                if (i < lines.size - 1) totalHeight += lineHeight
            }
            totalHeight += padding * 2
            val bgWidth = maxWidth + padding * 2

            val box = Bitmap.createBitmap(bgWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(box)
            canvas.drawRoundRect(
                RectF(0f, 0f, bgWidth.toFloat(), totalHeight.toFloat()),
                4f * scale,
                4f * scale,
                bgPaint,
            )
            var y = padding
            for (line in lines) {
                textPaint.getTextBounds(line, 0, line.length, reusableRect)
                y += reusableRect.height()
                canvas.drawText(line, padding.toFloat(), y.toFloat(), textPaint)
                y += lineHeight
            }
            box
        }
    }

    /** The MJPEG path: draw the rendered box at the pure position math's rect. */
    private fun drawTextOverlay(
        canvas: Canvas,
        settings: OverlaySettings,
        clientCount: Int,
        nowMs: Long,
    ) {
        val box = renderTextOverlayBitmap(settings, canvas.width, clientCount, nowMs) ?: return
        val position = OverlayLayoutPolicy.computeOverlayPosition(
            settings.position,
            canvas.width,
            canvas.height,
            box.width,
            box.height,
        )
        canvas.drawBitmap(box, position.left.toFloat(), position.top.toFloat(), null)
        box.recycle()
    }

    private fun parseColor(hex: String, fallback: Int): Int {
        return OverlayLayoutPolicy.parseColorOrNull(hex)?.toInt() ?: fallback
    }
}
