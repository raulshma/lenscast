package com.raulshma.lenscast.streaming.whip

import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.streaming.EncodedOverlayCompositor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class WhipFramePreparationTest {

    private val rasterizer = EncodedOverlayCompositor.Rasterizer { _, _, _ -> null }

    @Test
    fun `WHIP stamps privacy masks without mutating the camera frame`() {
        val compositor = EncodedOverlayCompositor(rasterizer).apply {
            setOverlaySettings(
                OverlaySettings(
                    enabled = false,
                    maskingEnabled = true,
                    maskingZones = listOf(MaskingZone(x = 0.25f, y = 0.25f, width = 0.25f, height = 0.25f)),
                ),
            )
        }
        val cameraFrame = ByteArray(64 * 64 * 3 / 2) { 90.toByte() }

        val frame = WhipPublisher.prepareFrame(cameraFrame, 64, 64, rotation = 0, compositor, nowMs = 0)

        assertEquals(0, frame.nv21[20 * 64 + 20].toInt() and 0xFF)
        assertEquals(90, cameraFrame[20 * 64 + 20].toInt() and 0xFF)
        assertNotSame(cameraFrame, frame.nv21)
    }

    @Test
    fun `WHIP rotates before masking and preserves the camera frame`() {
        val compositor = EncodedOverlayCompositor(rasterizer).apply {
            setOverlaySettings(
                OverlaySettings(
                    enabled = false,
                    maskingEnabled = true,
                    maskingZones = listOf(MaskingZone(x = 0.25f, y = 0.25f, width = 0.25f, height = 0.25f)),
                ),
            )
        }
        val cameraFrame = ByteArray(64 * 32 * 3 / 2) { 90.toByte() }

        val frame = WhipPublisher.prepareFrame(cameraFrame, 64, 32, rotation = 90, compositor, nowMs = 0)

        assertEquals(32, frame.width)
        assertEquals(64, frame.height)
        assertEquals(0, frame.nv21[20 * 32 + 10].toInt() and 0xFF)
        assertArrayEquals(ByteArray(cameraFrame.size) { 90.toByte() }, cameraFrame)
    }
}
