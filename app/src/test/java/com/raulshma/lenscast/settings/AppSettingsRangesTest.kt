package com.raulshma.lenscast.settings

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parity pin: the App Settings screen's slider spans are exactly the Settings
 * Store's StreamDefaults clamps — the UI offers the full persisted range, so
 * a re-typed literal can only fail here first.
 */
class AppSettingsRangesTest {

    @Test
    fun `web port slider spans the store's web port clamp`() {
        assertEquals(StreamDefaults.WEB_PORT_MIN.toFloat(), webPortSliderRange.start)
        assertEquals(StreamDefaults.WEB_PORT_MAX.toFloat(), webPortSliderRange.endInclusive)
    }

    @Test
    fun `jpeg quality slider spans the store's jpeg quality clamp`() {
        assertEquals(StreamDefaults.JPEG_QUALITY_MIN.toFloat(), jpegQualitySliderRange.start)
        assertEquals(StreamDefaults.JPEG_QUALITY_MAX.toFloat(), jpegQualitySliderRange.endInclusive)
    }

    @Test
    fun `rtsp port slider spans the store's rtsp port clamp`() {
        assertEquals(StreamDefaults.RTSP_PORT_MIN.toFloat(), rtspPortSliderRange.start)
        assertEquals(StreamDefaults.RTSP_PORT_MAX.toFloat(), rtspPortSliderRange.endInclusive)
    }

    @Test
    fun `audio bitrate slider spans the store's audio bitrate clamp`() {
        assertEquals(StreamDefaults.AUDIO_BITRATE_MIN_KBPS.toFloat(), audioBitrateSliderRange.start)
        assertEquals(StreamDefaults.AUDIO_BITRATE_MAX_KBPS.toFloat(), audioBitrateSliderRange.endInclusive)
    }
}
