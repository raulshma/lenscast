package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class SnapshotFrameStoreMaskingTest {

    private val rasterizer = EncodedOverlayCompositor.Rasterizer { _, _, _ -> null }

    @Test
    fun `detection fallback carries the current privacy mask without changing the retained frame`() {
        val compositor = EncodedOverlayCompositor(rasterizer).apply {
            setOverlaySettings(
                OverlaySettings(
                    enabled = false,
                    maskingEnabled = true,
                    maskingZones = listOf(MaskingZone(x = 0.25f, y = 0.25f, width = 0.25f, height = 0.25f)),
                ),
            )
        }
        val retained = ByteArray(64 * 64 * 3 / 2) { 100.toByte() }

        val prepared = SnapshotFrameStore.prepareFrame(retained, 64, 64, rotation = 0, compositor, nowMs = 0)

        assertEquals(0, prepared.nv21[20 * 64 + 20].toInt() and 0xFF)
        assertEquals(100, retained[20 * 64 + 20].toInt() and 0xFF)
        assertNotSame(retained, prepared.nv21)
    }

    @Test
    fun `detection fallback rotates before applying normalized zones`() {
        val compositor = EncodedOverlayCompositor(rasterizer).apply {
            setOverlaySettings(
                OverlaySettings(
                    enabled = false,
                    maskingEnabled = true,
                    maskingZones = listOf(MaskingZone(x = 0.25f, y = 0.25f, width = 0.25f, height = 0.25f)),
                ),
            )
        }
        val retained = ByteArray(64 * 32 * 3 / 2) { 100.toByte() }

        val prepared = SnapshotFrameStore.prepareFrame(retained, 64, 32, rotation = 90, compositor, nowMs = 0)

        assertEquals(32, prepared.width)
        assertEquals(64, prepared.height)
        assertEquals(0, prepared.nv21[20 * 32 + 10].toInt() and 0xFF)
        assertEquals(100, retained[20 * 32 + 10].toInt() and 0xFF)
    }
}
