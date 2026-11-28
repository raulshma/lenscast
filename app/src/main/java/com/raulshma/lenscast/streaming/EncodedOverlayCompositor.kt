package com.raulshma.lenscast.streaming

import android.graphics.Bitmap
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.YuvConverter

/**
 * Privacy masks and burned-in text for NV21 outputs (the twin of the MJPEG
 * pipeline's [StreamOverlayRenderer] pass). Zones are painted in order before
 * the cached text strip, so a later blur cannot reveal pixels hidden by an
 * earlier blackout. The shared encode uses this for RTSP, HLS, WS video, RTMP
 * and SRT; WHIP and WHEP each own a compositor for their separate libwebrtc
 * paths.
 *
 * Text is rasterized (Android graphics) and converted to Y,U,V,A (BT.601 studio
 * swing) only when its rendered content can have changed — a new second on the
 * timestamp, a settings update, a different target size. Between renders the
 * cached strip is alpha-blended by [YuvConverter.blendNv21Overlay]. With both
 * masking and text disabled the class is one volatile read.
 *
 * Settings are volatile. Strip and shared-frame scratch slots are touched only
 * by their owner's frame path, so they need no synchronization.
 */
internal class EncodedOverlayCompositor(
    private val rasterizer: Rasterizer = AndroidRasterizer,
    private val illuminanceProvider: () -> Int? = { null },
) {

    /**
     * The rasterization seam: settings in, blendable strip out (null when
     * nothing renders). Production goes through the shared text-box bitmap;
     * JVM tests substitute fakes that count renders and emit fixed strips.
     */
    fun interface Rasterizer {
        fun render(settings: OverlaySettings, targetWidth: Int, nowMs: Long): YuvaStrip?

        /** Client-count-aware variant for outputs that display viewer count (e.g. MJPEG). */
        fun renderWithClients(
            settings: OverlaySettings,
            targetWidth: Int,
            nowMs: Long,
            clientCount: Int,
            illuminanceLux: Int? = null,
        ): YuvaStrip? = render(settings, targetWidth, nowMs)
    }

    /** Interleaved Y,U,V,A bytes (one per pixel), exactly [argbToYuvaStrip]'s layout. */
    class YuvaStrip(val data: ByteArray, val width: Int, val height: Int)

    /**
     * The production rasterizer: the shared [StreamOverlayRenderer] text-box
     * bitmap, converted to the strip format once per render. Android-graphics
     * bound — the unit tests exercise the compositor through fake
     * rasterizers instead.
     */
    object AndroidRasterizer : Rasterizer {
        override fun render(settings: OverlaySettings, targetWidth: Int, nowMs: Long): YuvaStrip? =
            renderWithClients(settings, targetWidth, nowMs, ENCODED_VIEWER_COUNT)

        override fun renderWithClients(
            settings: OverlaySettings,
            targetWidth: Int,
            nowMs: Long,
            clientCount: Int,
            illuminanceLux: Int?,
        ): YuvaStrip? {
            val box = StreamOverlayRenderer.renderTextOverlayBitmap(
                settings,
                targetWidth,
                clientCount,
                nowMs,
                illuminanceLux,
            ) ?: return null
            val width = box.width
            val height = box.height
            val pixels = IntArray(width * height)
            box.getPixels(pixels, 0, width, 0, 0, width, height)
            box.recycle()
            return YuvaStrip(YuvConverter.argbToYuvaStrip(pixels, width, height), width, height)
        }
    }

    @Volatile private var settings: OverlaySettings = OverlaySettings()

    /** The Settings Applier's route in — the next pushed frame re-renders. */
    fun setOverlaySettings(newSettings: OverlaySettings) {
        settings = newSettings
    }

    fun overlayEnabled(): Boolean = OverlayLayoutPolicy.overlayPassNeeded(settings)

    // Per-target-size cache. Keyed on the packed frame size — encode sizes
    // are few (main + the 640×480 sub, plus transient resolution changes),
    // and the map is cleared once a settings churn leaves more than a
    // handful of stale slots behind.
    private val slots = HashMap<Long, Slot>()

    private class Slot {
        var lines: List<String> = emptyList()
        var settings: OverlaySettings? = null
        var strip: YuvaStrip? = null
        var position: PixelRect? = null
        var scratch: ByteArray? = null
    }

    /**
     * One camera frame at its final output size. Returns [frame] when neither
     * masks nor text apply. Otherwise paints masks first, then text, mutating
     * an owned array in place or a per-size copy when [frameShared] marks the
     * camera array as still aliased by other consumers.
     */
    fun applyTo(
        frame: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        nowMs: Long,
        frameShared: Boolean = false,
        clientCount: Int = ENCODED_VIEWER_COUNT,
    ): ByteArray {
        val current = settings
        if (!OverlayLayoutPolicy.overlayPassNeeded(current)) return frame

        var target = frame
        val masking = current.maskingEnabled && current.maskingZones.any { it.enabled }
        if (masking) {
            val slot = slotFor(frameWidth, frameHeight)
            if (frameShared) target = copyIntoSlot(frame, slot)
            applyMaskingZones(target, frameWidth, frameHeight, current.maskingZones)
        }
        if (!current.enabled) return target

        // The rendered-content dirty check: the line list is what actually
        // shows, so a timestamp re-render happens exactly on its second
        // boundary (the default format) and nothing else re-rasterizes.
        val illuminanceLux = if (current.showIlluminance) illuminanceProvider() else null
        val lines = OverlayLayoutPolicy.buildOverlayLines(current, clientCount, nowMs, illuminanceLux)
        val slot = slotFor(frameWidth, frameHeight)
        if (lines != slot.lines || current !== slot.settings) {
            slot.lines = lines
            slot.settings = current
            slot.strip = rasterizer.renderWithClients(current, frameWidth, nowMs, clientCount, illuminanceLux)
            slot.position = slot.strip?.let {
                OverlayLayoutPolicy.computeOverlayPosition(
                    current.position,
                    frameWidth,
                    frameHeight,
                    it.width,
                    it.height,
                )
            }
        }
        val strip = slot.strip ?: return target
        val rect = slot.position ?: return target

        if (target === frame && frameShared) target = copyIntoSlot(frame, slot)
        YuvConverter.blendNv21Overlay(
            target,
            frameWidth,
            frameHeight,
            strip.data,
            strip.width,
            strip.height,
            rect.left,
            rect.top,
        )
        return target
    }

    private fun slotFor(width: Int, height: Int): Slot {
        if (slots.size > SLOT_CACHE_MAX) slots.clear()
        return slots.getOrPut(cacheKey(width, height)) { Slot() }
    }

    private fun copyIntoSlot(frame: ByteArray, slot: Slot): ByteArray {
        val scratch = slot.scratch?.takeIf { it.size >= frame.size } ?: ByteArray(frame.size)
        slot.scratch = scratch
        System.arraycopy(frame, 0, scratch, 0, frame.size)
        return scratch
    }

    private fun applyMaskingZones(
        frame: ByteArray,
        width: Int,
        height: Int,
        zones: List<MaskingZone>,
    ) {
        for (zone in zones) {
            if (!zone.enabled) continue
            val rect = OverlayLayoutPolicy.zoneToPixels(zone, width, height)
            if (rect.isEmpty) continue
            when (zone.type) {
                MaskingType.BLACKOUT -> fillNv21Region(frame, width, height, rect)
                MaskingType.PIXELATE, MaskingType.BLUR -> {
                    val (smallWidth, smallHeight) = if (zone.type == MaskingType.PIXELATE) {
                        OverlayLayoutPolicy.pixelateDownscale(
                            rect.right - rect.left,
                            rect.bottom - rect.top,
                            zone.pixelateSize,
                        )
                    } else {
                        OverlayLayoutPolicy.blurDownscale(
                            rect.right - rect.left,
                            rect.bottom - rect.top,
                            zone.blurRadius,
                        )
                    }
                    resampleNv21Region(
                        frame,
                        width,
                        height,
                        rect,
                        smallWidth,
                        smallHeight,
                        smooth = zone.type == MaskingType.BLUR,
                    )
                }
            }
        }
    }

    private fun fillNv21Region(frame: ByteArray, width: Int, height: Int, rect: PixelRect) {
        for (y in rect.top until rect.bottom) {
            val row = y * width
            for (x in rect.left until rect.right) frame[row + x] = 0
        }
        val chromaStart = width * height
        val left = rect.left / 2
        val right = (rect.right + 1) / 2
        val top = rect.top / 2
        val bottom = (rect.bottom + 1) / 2
        for (cy in top until bottom) {
            val row = chromaStart + cy * width
            for (cx in left until right) {
                frame[row + cx * 2] = 128.toByte()
                frame[row + cx * 2 + 1] = 128.toByte()
            }
        }
    }

    /**
     * Samples the region as it exists now, then paints the reduced result back
     * over it. Because zones run in list order, a later blur/pixelate samples
     * earlier masks instead of bringing original camera pixels back.
     */
    private fun resampleNv21Region(
        frame: ByteArray,
        width: Int,
        height: Int,
        rect: PixelRect,
        smallWidth: Int,
        smallHeight: Int,
        smooth: Boolean,
    ) {
        val regionWidth = rect.right - rect.left
        val regionHeight = rect.bottom - rect.top
        val smallLuma = ByteArray(smallWidth * smallHeight)
        for (sy in 0 until smallHeight) {
            val sourceTop = rect.top + sy * regionHeight / smallHeight
            val sourceBottom = maxOf(sourceTop + 1, rect.top + (sy + 1) * regionHeight / smallHeight)
            for (sx in 0 until smallWidth) {
                val sourceLeft = rect.left + sx * regionWidth / smallWidth
                val sourceRight = maxOf(sourceLeft + 1, rect.left + (sx + 1) * regionWidth / smallWidth)
                val index = sy * smallWidth + sx
                smallLuma[index] = if (smooth) {
                    averageRegion(frame, width, sourceLeft, sourceTop, sourceRight, sourceBottom, 1)
                } else {
                    frame[sourceTop * width + sourceLeft]
                }
            }
        }

        val chromaLeft = rect.left / 2
        val chromaRight = (rect.right + 1) / 2
        val chromaTop = rect.top / 2
        val chromaBottom = (rect.bottom + 1) / 2
        val chromaRegionWidth = chromaRight - chromaLeft
        val chromaRegionHeight = chromaBottom - chromaTop
        val smallChromaWidth = (smallWidth + 1) / 2
        val smallChromaHeight = (smallHeight + 1) / 2
        val smallChroma = ByteArray(smallChromaWidth * smallChromaHeight * 2)
        val chromaStart = width * height
        for (sy in 0 until smallChromaHeight) {
            val sourceTop = chromaTop + sy * chromaRegionHeight / smallChromaHeight
            val sourceBottom = maxOf(sourceTop + 1, chromaTop + (sy + 1) * chromaRegionHeight / smallChromaHeight)
            for (sx in 0 until smallChromaWidth) {
                val sourceLeft = chromaLeft + sx * chromaRegionWidth / smallChromaWidth
                val sourceRight = maxOf(sourceLeft + 1, chromaLeft + (sx + 1) * chromaRegionWidth / smallChromaWidth)
                val sourceIndex = chromaStart + sourceTop * width + sourceLeft * 2
                val targetIndex = (sy * smallChromaWidth + sx) * 2
                if (smooth) {
                    smallChroma[targetIndex] = averageRegion(
                        frame,
                        width,
                        sourceLeft * 2,
                        sourceTop,
                        sourceRight * 2,
                        sourceBottom,
                        2,
                        chromaStart,
                    )
                    smallChroma[targetIndex + 1] = averageRegion(
                        frame,
                        width,
                        sourceLeft * 2 + 1,
                        sourceTop,
                        sourceRight * 2 + 1,
                        sourceBottom,
                        2,
                        chromaStart,
                    )
                } else {
                    smallChroma[targetIndex] = frame[sourceIndex]
                    smallChroma[targetIndex + 1] = frame[sourceIndex + 1]
                }
            }
        }

        for (y in rect.top until rect.bottom) {
            val sourceY = (y - rect.top) * smallHeight / regionHeight
            val nextY = (sourceY + 1).coerceAtMost(smallHeight - 1)
            val yFraction = if (smooth) {
                (y - rect.top) * smallHeight.toFloat() / regionHeight - sourceY
            } else {
                0f
            }
            val row = y * width
            for (x in rect.left until rect.right) {
                val sourceX = (x - rect.left) * smallWidth / regionWidth
                val nextX = (sourceX + 1).coerceAtMost(smallWidth - 1)
                val xFraction = if (smooth) {
                    (x - rect.left) * smallWidth.toFloat() / regionWidth - sourceX
                } else {
                    0f
                }
                frame[row + x] = interpolateByte(
                    smallLuma,
                    sourceY * smallWidth + sourceX,
                    sourceY * smallWidth + nextX,
                    nextY * smallWidth + sourceX,
                    nextY * smallWidth + nextX,
                    xFraction,
                    yFraction,
                )
            }
        }

        for (cy in chromaTop until chromaBottom) {
            val sourceY = (cy - chromaTop) * smallChromaHeight / chromaRegionHeight
            val nextY = (sourceY + 1).coerceAtMost(smallChromaHeight - 1)
            val yFraction = if (smooth) {
                (cy - chromaTop) * smallChromaHeight.toFloat() / chromaRegionHeight - sourceY
            } else {
                0f
            }
            for (cx in chromaLeft until chromaRight) {
                val sourceX = (cx - chromaLeft) * smallChromaWidth / chromaRegionWidth
                val nextX = (sourceX + 1).coerceAtMost(smallChromaWidth - 1)
                val xFraction = if (smooth) {
                    (cx - chromaLeft) * smallChromaWidth.toFloat() / chromaRegionWidth - sourceX
                } else {
                    0f
                }
                val targetIndex = chromaStart + cy * width + cx * 2
                val a = (sourceY * smallChromaWidth + sourceX) * 2
                val b = (sourceY * smallChromaWidth + nextX) * 2
                val c = (nextY * smallChromaWidth + sourceX) * 2
                val d = (nextY * smallChromaWidth + nextX) * 2
                frame[targetIndex] = interpolateByte(smallChroma, a, b, c, d, xFraction, yFraction)
                frame[targetIndex + 1] = interpolateByte(smallChroma, a + 1, b + 1, c + 1, d + 1, xFraction, yFraction)
            }
        }
    }

    private fun averageRegion(
        frame: ByteArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        step: Int,
        rowOffset: Int = 0,
    ): Byte {
        var sum = 0
        var count = 0
        for (y in top until bottom) {
            val row = rowOffset + y * stride
            var x = left
            while (x < right) {
                sum += frame[row + x].toInt() and 0xFF
                count++
                x += step
            }
        }
        return (sum / count).toByte()
    }

    private fun interpolateByte(
        samples: ByteArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        xFraction: Float,
        yFraction: Float,
    ): Byte {
        if (xFraction == 0f && yFraction == 0f) return samples[a]
        val top = samples[a].toInt() + ((samples[b].toInt() - samples[a].toInt()) * xFraction).toInt()
        val bottom = samples[c].toInt() + ((samples[d].toInt() - samples[c].toInt()) * xFraction).toInt()
        return (top + ((bottom - top) * yFraction).toInt()).toByte()
    }

    private fun cacheKey(width: Int, height: Int): Long =
        (width.toLong() shl 32) or (height.toLong() and 0xFFFFFFFFL)

    companion object {
        /**
         * The viewer count the encoded overlay's status line renders with —
         * zero, so the line is suppressed on the encoded outputs until real
         * RTSP/WS viewer counts are wired through (the MJPEG count would be
         * wrong on an RTSP stream).
         */
        const val ENCODED_VIEWER_COUNT = 0

        private const val SLOT_CACHE_MAX = 4
    }
}
