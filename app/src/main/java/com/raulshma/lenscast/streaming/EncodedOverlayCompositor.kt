package com.raulshma.lenscast.streaming

import android.graphics.Bitmap
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.YuvConverter

/**
 * The burned-in text overlay for the encoded path (the twin of the MJPEG
 * pipeline's [StreamOverlayRenderer] pass): blends the configured overlay
 * lines into the NV21 frames the [EncodedStreamHub] feeds its MediaCodec
 * encoders, so RTSP (and every output riding the same shared encode — HLS,
 * WS video, RTMP, SRT) shows the same timestamp/branding/custom text the
 * web stream does. WHIP/WHEP encode separately and are not stamped here.
 *
 * The design keeps the per-frame cost at a small-region blend: the overlay
 * text is rasterized (Android graphics) and converted to Y,U,V,A (BT.601
 * studio swing) only when its rendered content can have changed — a new
 * second on the timestamp, a settings update, a different target size.
 * Between renders the cached strip is alpha-blended into the frame by the
 * pure [YuvConverter.blendNv21Overlay]; a strip pixel with alpha 0 touches
 * nothing. With the overlay disabled the whole class is one volatile read.
 *
 * State model: the overlay settings are written by the Settings Applier
 * thread ([setOverlaySettings], volatile like the hub's other retained
 * setters); the strip cache lives in per-size slots touched only from the
 * frame path (the camera analysis thread `pushFrame` runs on), so no slot
 * synchronization is needed.
 */
internal class EncodedOverlayCompositor(
    private val rasterizer: Rasterizer = AndroidRasterizer,
) {

    /**
     * The rasterization seam: settings in, blendable strip out (null when
     * nothing renders). Production goes through the shared text-box bitmap;
     * JVM tests substitute fakes that count renders and emit fixed strips.
     */
    fun interface Rasterizer {
        fun render(settings: OverlaySettings, targetWidth: Int, nowMs: Long): YuvaStrip?
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
        override fun render(settings: OverlaySettings, targetWidth: Int, nowMs: Long): YuvaStrip? {
            val box = StreamOverlayRenderer.renderTextOverlayBitmap(
                settings,
                targetWidth,
                ENCODED_VIEWER_COUNT,
                nowMs,
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

    fun overlayEnabled(): Boolean = settings.enabled

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
     * One camera frame at its final encode size. Returns the array to feed
     * the encoder: [frame] itself when the blend does not apply, otherwise
     * [frame] mutated in place — or, when [frameShared] marks the array as
     * still aliased by other consumers (the camera's own analysis array),
     * a per-size reusable copy blended instead, so the MJPEG pipeline and
     * the WHIP/WHEP taps keep reading clean pixels.
     */
    fun applyTo(
        frame: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        nowMs: Long,
        frameShared: Boolean = false,
    ): ByteArray {
        val current = settings
        if (!current.enabled) return frame

        // The rendered-content dirty check: the line list is what actually
        // shows, so a timestamp re-render happens exactly on its second
        // boundary (the default format) and nothing else re-rasterizes.
        val lines = OverlayLayoutPolicy.buildOverlayLines(current, ENCODED_VIEWER_COUNT, nowMs)
        if (slots.size > SLOT_CACHE_MAX) slots.clear()
        val slot = slots.getOrPut(cacheKey(frameWidth, frameHeight)) { Slot() }
        if (lines != slot.lines || current !== slot.settings) {
            slot.lines = lines
            slot.settings = current
            slot.strip = rasterizer.render(current, frameWidth, nowMs)
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
        val strip = slot.strip ?: return frame
        val rect = slot.position ?: return frame

        var target = frame
        if (frameShared) {
            val scratch = slot.scratch?.takeIf { it.size >= frame.size } ?: ByteArray(frame.size)
            slot.scratch = scratch
            System.arraycopy(frame, 0, scratch, 0, frame.size)
            target = scratch
        }
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
