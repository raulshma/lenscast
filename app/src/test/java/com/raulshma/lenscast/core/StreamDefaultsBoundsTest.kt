package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clamp policy is the Settings Store's (every numeric saver coerces
 * through these bounds), so the bounds themselves are locked by test.
 */
class StreamDefaultsBoundsTest {

    @Test
    fun `web port bounds are the privileged unprivileged range`() {
        assertEquals(1024, StreamDefaults.WEB_PORT_MIN)
        assertEquals(65535, StreamDefaults.WEB_PORT_MAX)
    }

    @Test
    fun `rtsp port bounds match the handler's former inline range`() {
        assertEquals(1024, StreamDefaults.RTSP_PORT_MIN)
        assertEquals(65535, StreamDefaults.RTSP_PORT_MAX)
    }

    @Test
    fun `port bounds contain the default ports`() {
        assertEquals(true, StreamDefaults.WEB_PORT in StreamDefaults.WEB_PORT_MIN..StreamDefaults.WEB_PORT_MAX)
        assertEquals(true, StreamDefaults.RTSP_PORT in StreamDefaults.RTSP_PORT_MIN..StreamDefaults.RTSP_PORT_MAX)
    }

    @Test
    fun `jpeg quality bounds exist and contain the default`() {
        assertEquals(10, StreamDefaults.JPEG_QUALITY_MIN)
        assertEquals(100, StreamDefaults.JPEG_QUALITY_MAX)
        assertEquals(
            true,
            StreamDefaults.JPEG_QUALITY in StreamDefaults.JPEG_QUALITY_MIN..StreamDefaults.JPEG_QUALITY_MAX,
        )
    }

    @Test
    fun `clamping through the bounds mirrors the store savers`() {
        assertEquals(1024, 80.coerceIn(StreamDefaults.WEB_PORT_MIN, StreamDefaults.WEB_PORT_MAX))
        assertEquals(65535, 70000.coerceIn(StreamDefaults.RTSP_PORT_MIN, StreamDefaults.RTSP_PORT_MAX))
        assertEquals(10, 0.coerceIn(StreamDefaults.JPEG_QUALITY_MIN, StreamDefaults.JPEG_QUALITY_MAX))
        assertEquals(100, 150.coerceIn(StreamDefaults.JPEG_QUALITY_MIN, StreamDefaults.JPEG_QUALITY_MAX))
    }

    @Test
    fun `deterrence bounds exist and contain their defaults`() {
        assertEquals(5, StreamDefaults.SIREN_DURATION_MIN_SECONDS)
        assertEquals(60, StreamDefaults.SIREN_DURATION_MAX_SECONDS)
        assertEquals(
            true,
            StreamDefaults.SIREN_DURATION_SECONDS_DEFAULT in
                StreamDefaults.SIREN_DURATION_MIN_SECONDS..StreamDefaults.SIREN_DURATION_MAX_SECONDS,
        )
        assertEquals(30, StreamDefaults.AUTO_DETERRENCE_COOLDOWN_MIN_SECONDS)
        assertEquals(600, StreamDefaults.AUTO_DETERRENCE_COOLDOWN_MAX_SECONDS)
        assertEquals(
            true,
            StreamDefaults.AUTO_DETERRENCE_COOLDOWN_SECONDS_DEFAULT in
                StreamDefaults.AUTO_DETERRENCE_COOLDOWN_MIN_SECONDS..StreamDefaults.AUTO_DETERRENCE_COOLDOWN_MAX_SECONDS,
        )
    }
}
