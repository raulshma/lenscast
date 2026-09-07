package com.raulshma.lenscast.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The media viewer's zoom/pan thresholds and clamps, with the historical
 * values locked: double-tap 1.2f/2.5f, pinch clamp 1..5.
 */
class ViewerZoomPolicyTest {

    // ── Double tap ──

    @Test
    fun `double tap from the fit scale zooms in to the target`() {
        val next = ViewerZoomPolicy.onDoubleTap(currentScale = 1f)
        assertEquals(ViewerZoomPolicy.DOUBLE_TAP_SCALE, next.scale)
        assertEquals(0f, next.offsetX)
        assertEquals(0f, next.offsetY)
    }

    @Test
    fun `double tap at the threshold still zooms in`() {
        val next = ViewerZoomPolicy.onDoubleTap(currentScale = ViewerZoomPolicy.DOUBLE_TAP_RESET_THRESHOLD)
        assertEquals(ViewerZoomPolicy.DOUBLE_TAP_SCALE, next.scale)
    }

    @Test
    fun `double tap past the threshold resets to identity including pan`() {
        val next = ViewerZoomPolicy.onDoubleTap(currentScale = ViewerZoomPolicy.DOUBLE_TAP_RESET_THRESHOLD + 0.01f)
        assertEquals(ViewerZoomPolicy.MIN_SCALE, next.scale)
        assertEquals(0f, next.offsetX)
        assertEquals(0f, next.offsetY)
    }

    // ── Pinch scale clamps ──

    @Test
    fun `pinch multiplies the scale and stays within 1 to 5`() {
        val zoomedIn = ViewerZoomPolicy.onTransform(
            currentScale = 2f, currentOffsetX = 0f, currentOffsetY = 0f,
            zoom = 2f, panX = 0f, panY = 0f, viewportWidth = 1000f, viewportHeight = 2000f,
        )
        assertEquals(4f, zoomedIn.scale)

        val clampedHigh = ViewerZoomPolicy.onTransform(
            currentScale = 4f, currentOffsetX = 0f, currentOffsetY = 0f,
            zoom = 4f, panX = 0f, panY = 0f, viewportWidth = 1000f, viewportHeight = 2000f,
        )
        assertEquals(ViewerZoomPolicy.MAX_SCALE, clampedHigh.scale)

        val clampedLow = ViewerZoomPolicy.onTransform(
            currentScale = 1f, currentOffsetX = 0f, currentOffsetY = 0f,
            zoom = 0.5f, panX = 0f, panY = 0f, viewportWidth = 1000f, viewportHeight = 2000f,
        )
        assertEquals(ViewerZoomPolicy.MIN_SCALE, clampedLow.scale)
    }

    @Test
    fun `at the fit scale the pan is pinned to center`() {
        val next = ViewerZoomPolicy.onTransform(
            currentScale = 1f, currentOffsetX = 40f, currentOffsetY = -40f,
            zoom = 1f, panX = 10f, panY = 10f, viewportWidth = 1000f, viewportHeight = 2000f,
        )
        assertEquals(1f, next.scale)
        assertEquals(0f, next.offsetX)
        assertEquals(0f, next.offsetY)
    }

    @Test
    fun `pan accumulates and clamps to half the scaled overflow per axis`() {
        // Viewport 1000 wide at scale 3 → maxX = 1000 * 2 / 2 = 1000.
        val next = ViewerZoomPolicy.onTransform(
            currentScale = 3f, currentOffsetX = 900f, currentOffsetY = 0f,
            zoom = 1f, panX = 500f, panY = 0f, viewportWidth = 1000f, viewportHeight = 2000f,
        )
        assertEquals(1000f, next.offsetX)
        assertEquals(0f, next.offsetY)

        // Viewport 2000 tall at scale 3 → maxY = 2000; a pan past it clamps symmetrically.
        val clampedLow = ViewerZoomPolicy.onTransform(
            currentScale = 3f, currentOffsetX = 0f, currentOffsetY = -900f,
            zoom = 1f, panX = 0f, panY = -1500f, viewportWidth = 1000f, viewportHeight = 2000f,
        )
        assertEquals(-2000f, clampedLow.offsetY)
    }

    @Test
    fun `zero viewport never produces pan room`() {
        val next = ViewerZoomPolicy.onTransform(
            currentScale = 3f, currentOffsetX = 100f, currentOffsetY = 100f,
            zoom = 1f, panX = 50f, panY = 50f, viewportWidth = 0f, viewportHeight = 0f,
        )
        assertEquals(0f, next.offsetX)
        assertEquals(0f, next.offsetY)
    }
}
