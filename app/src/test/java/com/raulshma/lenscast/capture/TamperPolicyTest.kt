package com.raulshma.lenscast.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tamper verdict: only an armed, live-stream charging→discharging edge fires. */
class TamperPolicyTest {

    @Test
    fun `charging to discharging while streaming and enabled fires`() {
        assertTrue(
            TamperPolicy.shouldFire(
                previousCharging = true,
                currentCharging = false,
                enabled = true,
                streamActive = true,
            ),
        )
    }

    @Test
    fun `every gate can veto the fire`() {
        assertFalse(
            TamperPolicy.shouldFire(previousCharging = true, currentCharging = false, enabled = false, streamActive = true),
        )
        assertFalse(
            TamperPolicy.shouldFire(previousCharging = true, currentCharging = false, enabled = true, streamActive = false),
        )
    }

    @Test
    fun `charging up or no edge never fires`() {
        assertFalse(
            TamperPolicy.shouldFire(previousCharging = false, currentCharging = true, enabled = true, streamActive = true),
        )
        assertFalse(
            TamperPolicy.shouldFire(previousCharging = true, currentCharging = true, enabled = true, streamActive = true),
        )
        assertFalse(
            TamperPolicy.shouldFire(previousCharging = false, currentCharging = false, enabled = true, streamActive = true),
        )
    }
}
