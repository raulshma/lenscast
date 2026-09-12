package com.raulshma.lenscast.capture.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The flash chip's cycle order — the one decision [FlashModePolicy] owns. */
class FlashModePolicyTest {

    @Test
    fun `cycle walks OFF - AUTO - ON and wraps`() {
        assertEquals(FlashMode.AUTO, FlashModePolicy.next(FlashMode.OFF))
        assertEquals(FlashMode.ON, FlashModePolicy.next(FlashMode.AUTO))
        assertEquals(FlashMode.OFF, FlashModePolicy.next(FlashMode.ON))
    }

    @Test
    fun `three taps from OFF land back on OFF`() {
        var mode = FlashMode.OFF
        repeat(3) { mode = FlashModePolicy.next(mode) }
        assertEquals(FlashMode.OFF, mode)
    }
}
