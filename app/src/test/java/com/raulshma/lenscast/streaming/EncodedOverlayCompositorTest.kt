package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The encoded-path overlay compositor: render caching (re-rasterize only
 * when the rendered content can have changed), per-size slots, and the
 * shared-frame copy rule — all through a fake rasterizer, no Android
 * graphics.
 */
class EncodedOverlayCompositorTest {

    private class FakeRasterizer : EncodedOverlayCompositor.Rasterizer {
        var renderCount = 0
        val renderedForWidths = mutableListOf<Int>()

        override fun render(
            settings: OverlaySettings,
            targetWidth: Int,
            nowMs: Long,
        ): EncodedOverlayCompositor.YuvaStrip {
            renderCount++
            renderedForWidths.add(targetWidth)
            // A fixed 4×2 fully-opaque Y=200 block.
            val strip = ByteArray(4 * 2 * 4)
            for (i in 0 until 4 * 2) {
                strip[i * 4] = 200.toByte()
                strip[i * 4 + 1] = 54
                strip[i * 4 + 2] = 242.toByte()
                strip[i * 4 + 3] = 0xFF.toByte()
            }
            return EncodedOverlayCompositor.YuvaStrip(strip, 4, 2)
        }
    }

    private fun settings(
        enabled: Boolean = true,
        position: OverlayPosition = OverlayPosition.TOP_LEFT,
    ) = OverlaySettings(enabled = enabled, showTimestamp = true, position = position)

    private fun nv21Frame(width: Int, height: Int): ByteArray = ByteArray(width * height * 3 / 2)

    @Test
    fun `disabled overlay short-circuits without rendering or touching the frame`() {
        val rasterizer = FakeRasterizer()
        val compositor = EncodedOverlayCompositor(rasterizer)
        compositor.setOverlaySettings(settings(enabled = false))
        val frame = nv21Frame(64, 64)

        val result = compositor.applyTo(frame, 64, 64, nowMs = 0)

        assertSame(frame, result)
        assertEquals(0, rasterizer.renderCount)
        assertArrayEquals(nv21Frame(64, 64), frame)
    }

    @Test
    fun `a timestamp re-renders exactly on the second boundary`() {
        val rasterizer = FakeRasterizer()
        val compositor = EncodedOverlayCompositor(rasterizer)
        compositor.setOverlaySettings(settings())

        compositor.applyTo(nv21Frame(64, 64), 64, 64, nowMs = 0)
        assertEquals(1, rasterizer.renderCount)

        // The same second: cached strip, no re-render.
        compositor.applyTo(nv21Frame(64, 64), 64, 64, nowMs = 500)
        compositor.applyTo(nv21Frame(64, 64), 64, 64, nowMs = 999)
        assertEquals(1, rasterizer.renderCount)

        // The next second: new timestamp text → re-render.
        compositor.applyTo(nv21Frame(64, 64), 64, 64, nowMs = 1000)
        assertEquals(2, rasterizer.renderCount)
    }

    @Test
    fun `a settings swap re-renders even when the lines are identical`() {
        val rasterizer = FakeRasterizer()
        val compositor = EncodedOverlayCompositor(rasterizer)
        compositor.setOverlaySettings(settings())
        compositor.applyTo(nv21Frame(64, 64), 64, 64, nowMs = 0)
        assertEquals(1, rasterizer.renderCount)

        // Same lines (timestamp unchanged), different position → re-render.
        compositor.setOverlaySettings(settings(position = OverlayPosition.BOTTOM_RIGHT))
        compositor.applyTo(nv21Frame(64, 64), 64, 64, nowMs = 0)
        assertEquals(2, rasterizer.renderCount)
    }

    @Test
    fun `each target size keeps its own cached strip`() {
        val rasterizer = FakeRasterizer()
        val compositor = EncodedOverlayCompositor(rasterizer)
        compositor.setOverlaySettings(settings())

        compositor.applyTo(nv21Frame(64, 64), 64, 64, nowMs = 0)
        compositor.applyTo(nv21Frame(640, 480), 640, 480, nowMs = 0)
        assertEquals(2, rasterizer.renderCount)
        assertEquals(listOf(64, 640), rasterizer.renderedForWidths)

        // Revisiting either size rides its own cache.
        compositor.applyTo(nv21Frame(64, 64), 64, 64, nowMs = 0)
        compositor.applyTo(nv21Frame(640, 480), 640, 480, nowMs = 0)
        assertEquals(2, rasterizer.renderCount)
    }

    @Test
    fun `blend lands at the corner margin`() {
        val rasterizer = FakeRasterizer()
        val compositor = EncodedOverlayCompositor(rasterizer)
        compositor.setOverlaySettings(settings())
        val frame = nv21Frame(64, 64)

        compositor.applyTo(frame, 64, 64, nowMs = 0)

        assertEquals(200, frame[16 * 64 + 16].toInt() and 0xFF)
        assertEquals(0, frame[16 * 64 + 15].toInt() and 0xFF)
    }

    @Test
    fun `a shared frame is blended into a reusable copy and never mutated`() {
        val rasterizer = FakeRasterizer()
        val compositor = EncodedOverlayCompositor(rasterizer)
        compositor.setOverlaySettings(settings())
        val cameraFrame = nv21Frame(64, 64)

        val first = compositor.applyTo(cameraFrame, 64, 64, nowMs = 0, frameShared = true)
        val second = compositor.applyTo(cameraFrame, 64, 64, nowMs = 0, frameShared = true)

        assertNotEquals(cameraFrame, first)
        // The camera array stays clean for the MJPEG/WHIP/WHEP taps…
        assertArrayEquals(nv21Frame(64, 64), cameraFrame)
        // …the blend landed in the copy…
        assertEquals(200, first[16 * 64 + 16].toInt() and 0xFF)
        // …and the per-size scratch is reused, not reallocated per frame.
        assertSame(first, second)
    }

    @Test
    fun `an owned frame is blended in place`() {
        val rasterizer = FakeRasterizer()
        val compositor = EncodedOverlayCompositor(rasterizer)
        compositor.setOverlaySettings(settings())
        val frame = nv21Frame(64, 64)

        val result = compositor.applyTo(frame, 64, 64, nowMs = 0, frameShared = false)

        assertSame(frame, result)
        assertEquals(200, frame[16 * 64 + 16].toInt() and 0xFF)
    }
}
