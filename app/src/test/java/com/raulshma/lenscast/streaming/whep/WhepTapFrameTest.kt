package com.raulshma.lenscast.streaming.whep

import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.streaming.EncodedOverlayCompositor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The WHEP tap's stamp contract: viewers see the same burned-in overlay as
 * every other output (the preview showed clean video while RTSP/HLS were
 * stamped), and the camera's shared analysis array stays clean for the
 * other taps. Pure NV21 through a fake rasterizer — the libwebrtc transport
 * around [WhepServer.prepareTapFrame] stays device-only.
 */
class WhepTapFrameTest {

    private class FakeRasterizer : EncodedOverlayCompositor.Rasterizer {
        var renderCount = 0

        override fun render(
            settings: OverlaySettings,
            targetWidth: Int,
            nowMs: Long,
        ): EncodedOverlayCompositor.YuvaStrip {
            renderCount++
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

    private fun compositor(enabled: Boolean): EncodedOverlayCompositor =
        EncodedOverlayCompositor(FakeRasterizer()).apply {
            setOverlaySettings(OverlaySettings(enabled = enabled, showTimestamp = true))
        }

    @Test
    fun `the tap frame carries the burned-in overlay`() {
        val cameraFrame = ByteArray(64 * 64 * 3 / 2)
        val tap = WhepServer.prepareTapFrame(cameraFrame, 64, 64, rotation = 0, compositor(true), nowMs = 0)

        assertEquals(64, tap.width)
        assertEquals(64, tap.height)
        assertEquals(200, tap.nv21[16 * 64 + 16].toInt() and 0xFF)
        // The camera array feeds the other taps clean.
        assertNotSame(cameraFrame, tap.nv21)
        assertArrayEquals(ByteArray(cameraFrame.size), cameraFrame)
    }

    @Test
    fun `rotation swaps dimensions and still stamps`() {
        val cameraFrame = ByteArray(64 * 32 * 3 / 2)
        val tap = WhepServer.prepareTapFrame(cameraFrame, 64, 32, rotation = 90, compositor(true), nowMs = 0)

        assertEquals(32, tap.width)
        assertEquals(64, tap.height)
        assertEquals(200, tap.nv21[16 * 32 + 16].toInt() and 0xFF)
        assertArrayEquals(ByteArray(cameraFrame.size), cameraFrame)
    }

    @Test
    fun `a disabled overlay passes a clean private copy`() {
        val cameraFrame = ByteArray(64 * 64 * 3 / 2) { (it % 251).toByte() }
        val tap = WhepServer.prepareTapFrame(cameraFrame, 64, 64, rotation = 0, compositor(false), nowMs = 0)

        assertNotSame(cameraFrame, tap.nv21)
        assertArrayEquals(cameraFrame, tap.nv21)
    }
}