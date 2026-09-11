package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCapturePlanTest {

    private fun config(
        jpegQuality: Int = PhotoCapturePlan.PHOTO_JPEG_QUALITY_DEFAULT,
        maximizeQuality: Boolean = false,
        rawRequested: Boolean = false,
    ) = PhotoCapturePlan.PhotoCaptureConfig(jpegQuality, maximizeQuality, rawRequested)

    // ── the quality clamp ──

    @Test
    fun `the quality clamps into the persistence bounds`() {
        assertEquals(60, PhotoCapturePlan.clampedQuality(59))
        assertEquals(60, PhotoCapturePlan.clampedQuality(60))
        assertEquals(90, PhotoCapturePlan.clampedQuality(90))
        assertEquals(100, PhotoCapturePlan.clampedQuality(100))
        assertEquals(100, PhotoCapturePlan.clampedQuality(101))
    }

    @Test
    fun `the bounds and default are the spec's`() {
        assertEquals(60, PhotoCapturePlan.PHOTO_JPEG_QUALITY_MIN)
        assertEquals(100, PhotoCapturePlan.PHOTO_JPEG_QUALITY_MAX)
        assertEquals(90, PhotoCapturePlan.PHOTO_JPEG_QUALITY_DEFAULT)
    }

    // ── the capability fold ──

    @Test
    fun `a raw request on a capable camera passes through`() {
        val effective = PhotoCapturePlan.effective(config(rawRequested = true), rawCaptureSupported = true)

        assertTrue(effective.rawRequested)
    }

    @Test
    fun `a raw request folds back to jpeg on an incapable camera`() {
        val effective = PhotoCapturePlan.effective(config(rawRequested = true), rawCaptureSupported = false)

        assertFalse(effective.rawRequested)
    }

    @Test
    fun `the fold clamps the quality too`() {
        val effective = PhotoCapturePlan.effective(config(jpegQuality = 500), rawCaptureSupported = false)

        assertEquals(100, effective.jpegQuality)
    }

    // ── the builder translations ──

    @Test
    fun `the output format follows the effective raw request`() {
        assertEquals(
            PhotoCapturePlan.PhotoOutputFormat.JPEG,
            PhotoCapturePlan.outputFormat(PhotoCapturePlan.effective(config(), rawCaptureSupported = true)),
        )
        assertEquals(
            PhotoCapturePlan.PhotoOutputFormat.RAW_JPEG,
            PhotoCapturePlan.outputFormat(
                PhotoCapturePlan.effective(config(rawRequested = true), rawCaptureSupported = true)
            ),
        )
    }

    @Test
    fun `maximize quality buys a quality capture mode at the cost of latency`() {
        assertEquals(
            PhotoCapturePlan.CaptureMode.MINIMIZE_LATENCY,
            PhotoCapturePlan.captureMode(config(maximizeQuality = false)),
        )
        assertEquals(
            PhotoCapturePlan.CaptureMode.MAXIMIZE_QUALITY,
            PhotoCapturePlan.captureMode(config(maximizeQuality = true)),
        )
    }

    // ── the rebind verdict ──

    @Test
    fun `no bound config is not a rebind - the next natural bind picks the config up`() {
        assertFalse(PhotoCapturePlan.needsRebind(previous = null, next = config(rawRequested = true)))
    }

    @Test
    fun `an unchanged config is not a rebind`() {
        assertFalse(PhotoCapturePlan.needsRebind(previous = config(), next = config()))
    }

    @Test
    fun `any builder-visible change rebinds`() {
        assertTrue(PhotoCapturePlan.needsRebind(previous = config(jpegQuality = 90), next = config(jpegQuality = 95)))
        assertTrue(PhotoCapturePlan.needsRebind(previous = config(), next = config(maximizeQuality = true)))
        assertTrue(PhotoCapturePlan.needsRebind(previous = config(), next = config(rawRequested = true)))
    }
}
