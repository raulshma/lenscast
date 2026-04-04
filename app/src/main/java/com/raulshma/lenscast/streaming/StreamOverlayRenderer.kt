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
import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import java.text.SimpleDateFormat
import java.util.Locale

object StreamOverlayRenderer {

    private const val TAG = "StreamOverlayRenderer"
    private const val REFERENCE_WIDTH = 1920f

    private val dateFormatCache = java.util.concurrent.ConcurrentHashMap<String, SimpleDateFormat>()

    private val reusableDate = java.util.Date()
    private val reusableRect = android.graphics.Rect()

    fun applyOverlay(
        bitmap: Bitmap,
        settings: OverlaySettings,
        clientCount: Int = 0,
        isRecording: Boolean = false,
    ): Bitmap {
        if (!settings.enabled && !settings.maskingEnabled) return bitmap

        return try {
            val scale = bitmap.width / REFERENCE_WIDTH
            val fontSize = (settings.fontSize * scale).coerceAtLeast(12f).toInt()
            val padding = (settings.padding * scale).coerceAtLeast(2f).toInt()
            val lineHeight = (settings.lineHeight * scale).coerceAtLeast(1f).toInt()

            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)

            if (settings.maskingEnabled && settings.maskingZones.isNotEmpty()) {
                applyMaskingZones(canvas, bitmap, settings.maskingZones, bitmap.width, bitmap.height)
            }

            if (settings.enabled) {
                applyTextOverlay(canvas, settings, clientCount, isRecording, scale, fontSize, padding, lineHeight)
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (zone in zones) {
            if (!zone.enabled) continue

            val left = (zone.x * bitmapWidth).toInt().coerceIn(0, bitmapWidth)
            val top = (zone.y * bitmapHeight).toInt().coerceIn(0, bitmapHeight)
            val right = ((zone.x + zone.width) * bitmapWidth).toInt().coerceIn(0, bitmapWidth)
            val bottom = ((zone.y + zone.height) * bitmapHeight).toInt().coerceIn(0, bitmapHeight)

            if (right <= left || bottom <= top) continue

            when (zone.type) {
                MaskingType.BLACKOUT -> {
                    paint.color = Color.BLACK
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(Rect(left, top, right, bottom), paint)
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

        val safePixelSize = pixelSize.coerceAtLeast(1)
        val smallWidth = maxOf(1, regionWidth / safePixelSize)
        val smallHeight = maxOf(1, regionHeight / safePixelSize)

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
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            canvas.drawRect(Rect(left, top, right, bottom), paint)
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

        val scaleFactor = (1f / (radius * 0.5f)).coerceIn(0.05f, 0.5f)
        val smallWidth = maxOf(1, (regionWidth * scaleFactor).toInt())
        val smallHeight = maxOf(1, (regionHeight * scaleFactor).toInt())

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
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            canvas.drawRect(Rect(left, top, right, bottom), paint)
        }
    }

    private fun applyTextOverlay(
        canvas: Canvas,
        settings: OverlaySettings,
        clientCount: Int,
        isRecording: Boolean,
        scale: Float,
        fontSize: Int,
        padding: Int,
        lineHeight: Int,
    ) {
        val lines = buildOverlayLines(settings, clientCount, isRecording)
        if (lines.isEmpty()) return

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(settings.textColor, Color.WHITE)
            textSize = fontSize.toFloat()
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(settings.backgroundColor, Color.parseColor("#80000000"))
            style = Paint.Style.FILL
        }

        var maxWidth = 0
        var totalHeight = 0
        synchronized(reusableRect) {
            for ((i, line) in lines.withIndex()) {
                textPaint.getTextBounds(line, 0, line.length, reusableRect)
                if (reusableRect.width() > maxWidth) maxWidth = reusableRect.width()
                totalHeight += reusableRect.height()
                if (i < lines.size - 1) totalHeight += lineHeight
            }
        }
        totalHeight += padding * 2
        val bgWidth = maxWidth + padding * 2

        val position = computeOverlayPosition(
            settings.position,
            canvas.width,
            canvas.height,
            bgWidth,
            totalHeight,
        )

        canvas.drawRoundRect(
            RectF(
                position.left.toFloat(),
                position.top.toFloat(),
                (position.left + bgWidth).toFloat(),
                (position.top + totalHeight).toFloat(),
            ),
            4f * scale,
            4f * scale,
            bgPaint,
        )

        var y = position.top + padding
        synchronized(reusableRect) {
            for (line in lines) {
                textPaint.getTextBounds(line, 0, line.length, reusableRect)
                y += reusableRect.height()
                canvas.drawText(line, (position.left + padding).toFloat(), y.toFloat(), textPaint)
                y += lineHeight
            }
        }
    }

    private fun buildOverlayLines(
        settings: OverlaySettings,
        clientCount: Int,
        isRecording: Boolean,
    ): List<String> {
        val lines = mutableListOf<String>()

        if (settings.showTimestamp) {
            val formatter = getDateFormat(settings.timestampFormat)
            synchronized(reusableDate) {
                reusableDate.time = System.currentTimeMillis()
                lines.add(formatter.format(reusableDate))
            }
        }

        if (settings.showBranding && settings.brandingText.isNotBlank()) {
            lines.add(settings.brandingText)
        }

        if (settings.showStatus) {
            val statusParts = mutableListOf<String>()
            if (isRecording) statusParts.add("REC")
            if (clientCount > 0) statusParts.add("${clientCount} viewer${if (clientCount != 1) "s" else ""}")
            if (statusParts.isNotEmpty()) lines.add(statusParts.joinToString("  "))
        }

        if (settings.showCustomText && settings.customText.isNotBlank()) {
            lines.add(settings.customText)
        }

        return lines
    }

    private fun getDateFormat(pattern: String): SimpleDateFormat {
        return dateFormatCache.getOrPut(pattern) {
            SimpleDateFormat(pattern, Locale.getDefault())
        }
    }

    private fun computeOverlayPosition(
        position: OverlayPosition,
        bitmapWidth: Int,
        bitmapHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int,
    ): android.graphics.Rect {
        val margin = 16
        return when (position) {
            OverlayPosition.TOP_LEFT ->
                android.graphics.Rect(margin, margin, margin + overlayWidth, margin + overlayHeight)
            OverlayPosition.TOP_RIGHT ->
                android.graphics.Rect(
                    bitmapWidth - overlayWidth - margin,
                    margin,
                    bitmapWidth - margin,
                    margin + overlayHeight,
                )
            OverlayPosition.BOTTOM_LEFT ->
                android.graphics.Rect(
                    margin,
                    bitmapHeight - overlayHeight - margin,
                    margin + overlayWidth,
                    bitmapHeight - margin,
                )
            OverlayPosition.BOTTOM_RIGHT ->
                android.graphics.Rect(
                    bitmapWidth - overlayWidth - margin,
                    bitmapHeight - overlayHeight - margin,
                    bitmapWidth - margin,
                    bitmapHeight - margin,
                )
        }
    }

    private fun parseColor(hex: String, fallback: Int): Int {
        return runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
    }
}
