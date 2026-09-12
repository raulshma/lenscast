package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the photo-aspect → ImageCapture bound mapping. Pure over
 * ints — android.util.Size stays behind the thin adapter the service calls.
 */
class PhotoAspectRatioPolicyTest {

    @Test
    fun `16x9 keeps the video resolution verbatim`() {
        assertEquals(1920 to 1080, PhotoAspectRatioPolicy.captureBound(PhotoAspectRatio.R16_9, 1920, 1080))
        assertEquals(1280 to 720, PhotoAspectRatioPolicy.captureBound(PhotoAspectRatio.R16_9, 1280, 720))
    }

    @Test
    fun `4x3 widens the same tier height to the 4x3 width`() {
        assertEquals(1440 to 1080, PhotoAspectRatioPolicy.captureBound(PhotoAspectRatio.R4_3, 1920, 1080))
        assertEquals(960 to 720, PhotoAspectRatioPolicy.captureBound(PhotoAspectRatio.R4_3, 1280, 720))
        // 2160p tier: 3840 × 2160 video → 2880 × 2160 photo.
        assertEquals(2880 to 2160, PhotoAspectRatioPolicy.captureBound(PhotoAspectRatio.R4_3, 3840, 2160))
    }

    @Test
    fun `odd 4x3 width rounds down to the even neighbor`() {
        // 720 * 4 / 3 = 960 (already even); force an odd product via 540p:
        // 540 * 4 / 3 = 720 — even again, so exercise evenWidth directly.
        assertEquals(960, PhotoAspectRatioPolicy.evenWidth(961))
        assertEquals(958, PhotoAspectRatioPolicy.evenWidth(959))
        assertEquals(4, PhotoAspectRatioPolicy.evenWidth(4))
    }

    @Test
    fun `ratio values are the exact aspect fractions`() {
        assertEquals(16f / 9f, PhotoAspectRatioPolicy.ratioOf(PhotoAspectRatio.R16_9), 1e-6f)
        assertEquals(4f / 3f, PhotoAspectRatioPolicy.ratioOf(PhotoAspectRatio.R4_3), 1e-6f)
    }
}
