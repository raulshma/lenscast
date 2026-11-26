package com.raulshma.lenscast.streaming

import android.graphics.Bitmap
import android.graphics.Color
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamOverlayRendererMaskingTest {

    @Test
    fun `overlapping blur cannot repaint raw pixels over an earlier blackout`() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val settings = OverlaySettings(
            enabled = false,
            maskingEnabled = true,
            maskingZones = listOf(
                MaskingZone(x = 0.25f, y = 0.25f, width = 0.5f, height = 0.5f),
                MaskingZone(
                    type = MaskingType.BLUR,
                    x = 0.25f,
                    y = 0.25f,
                    width = 0.5f,
                    height = 0.5f,
                    blurRadius = 20f,
                ),
            ),
        )

        val masked = StreamOverlayRenderer.applyOverlay(source, settings)

        assertEquals(Color.BLACK, masked.getPixel(40, 40))
        assertEquals(Color.WHITE, masked.getPixel(8, 8))
        assertEquals(Color.WHITE, source.getPixel(40, 40))
        masked.recycle()
        source.recycle()
    }
}
