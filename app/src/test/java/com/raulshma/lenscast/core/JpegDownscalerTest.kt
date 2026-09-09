package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure math of the shared JPEG downscale ladder ([JpegDownscaler]);
 * the decode/encode half needs Android bitmaps and stays device-bound.
 */
class JpegDownscalerTest {

    @Test
    fun `a source well over the target picks the largest power-of-two that still reaches it`() {
        // 3840 wide: 3840/2=1920, /4=960, /8=480 < 640 → sample 4 for a 640 target.
        assertEquals(4, JpegDownscaler.sampleSizeFor(3840, 2160, 640))
        // 1920 wide: /2=960, /4=480 < 640 → sample 2.
        assertEquals(2, JpegDownscaler.sampleSizeFor(1920, 1080, 640))
    }

    @Test
    fun `a source just over the target still halves when the halved size reaches it`() {
        // 1280 wide: 1280/2 = 640 >= 640 → sample 2 (decodes at exactly the
        // target); 1280/4 = 320 < 640 stops further doubling.
        assertEquals(2, JpegDownscaler.sampleSizeFor(1280, 720, 640))
        assertEquals(2, JpegDownscaler.sampleSizeFor(1024, 768, 512))
    }

    @Test
    fun `the longest side drives the decision - portrait sources included`() {
        assertEquals(2, JpegDownscaler.sampleSizeFor(720, 1280, 512))
        // 640 < 700, so the portrait source never halves for this target.
        assertEquals(1, JpegDownscaler.sampleSizeFor(720, 1280, 700))
    }

    @Test
    fun `thumbnail targets of 512 map the common camera sizes`() {
        assertEquals(8, JpegDownscaler.sampleSizeFor(4096, 3072, 512))
        assertEquals(4, JpegDownscaler.sampleSizeFor(2048, 1536, 512))
        assertEquals(2, JpegDownscaler.sampleSizeFor(1024, 768, 512))
        assertEquals(1, JpegDownscaler.sampleSizeFor(512, 512, 512))
    }

    @Test
    fun `tiny targets keep doubling - the pure math has no ladder cap`() {
        // The 32-sample cap is the downscale ladder's, not this function's.
        assertEquals(256, JpegDownscaler.sampleSizeFor(4096, 4096, 16))
    }

    @Test
    fun `downscale rejects missing input without touching a decoder`() {
        assertNull(JpegDownscaler.downscale(null, targetMaxPx = 512, quality = 75))
        assertNull(JpegDownscaler.downscale(ByteArray(0), targetMaxPx = 512, quality = 75))
    }
}
