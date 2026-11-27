package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure orientation mapping ([EncodedOrientationPolicy]): landscape tiers swap
 * to portrait on 90°/270° and keep landscape on 0°/180°.
 */
class EncodedOrientationPolicyTest {

    @Test
    fun `portrait rotation detects quarter turns including wrapped angles`() {
        assertFalse(EncodedOrientationPolicy.isPortraitRotation(0))
        assertFalse(EncodedOrientationPolicy.isPortraitRotation(180))
        assertFalse(EncodedOrientationPolicy.isPortraitRotation(360))
        assertFalse(EncodedOrientationPolicy.isPortraitRotation(-180))

        assertTrue(EncodedOrientationPolicy.isPortraitRotation(90))
        assertTrue(EncodedOrientationPolicy.isPortraitRotation(270))
        assertTrue(EncodedOrientationPolicy.isPortraitRotation(-90))
        assertTrue(EncodedOrientationPolicy.isPortraitRotation(-270))
        assertTrue(EncodedOrientationPolicy.isPortraitRotation(450))
    }

    @Test
    fun `all rtsp resolutions swap dimensions in portrait and preserve them in landscape`() {
        for (res in RtspResolution.entries) {
            assertEquals(
                res.width to res.height,
                EncodedOrientationPolicy.orientedDimensions(res.width, res.height, 0),
            )
            assertEquals(
                res.width to res.height,
                EncodedOrientationPolicy.orientedDimensions(res.width, res.height, 180),
            )
            assertEquals(
                res.height to res.width,
                EncodedOrientationPolicy.orientedDimensions(res.width, res.height, 90),
            )
            assertEquals(
                res.height to res.width,
                EncodedOrientationPolicy.orientedDimensions(res.width, res.height, 270),
            )
        }
    }

    @Test
    fun `sub-stream dimensions swap to 480x640 in portrait`() {
        assertEquals(
            480 to 640,
            EncodedOrientationPolicy.orientedDimensions(
                StreamDefaults.RTSP_SUB_VIDEO_WIDTH,
                StreamDefaults.RTSP_SUB_VIDEO_HEIGHT,
                90,
            ),
        )
        assertEquals(
            640 to 480,
            EncodedOrientationPolicy.orientedDimensions(
                StreamDefaults.RTSP_SUB_VIDEO_WIDTH,
                StreamDefaults.RTSP_SUB_VIDEO_HEIGHT,
                0,
            ),
        )
    }
}
