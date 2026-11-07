package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole no-op ladder of [TorchStrengthPolicy.effectiveLevel], with each
 * verdict named in the test. The SDK floor is 33 (Android 13) — where
 * `CameraManager.turnOnTorchWithStrengthLevel` and the
 * `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` characteristic actually landed; 34 is
 * pinned explicitly because a wrong "API 35" gate once locked those devices
 * out (issue #16, docs/research/torch-strength.md).
 */
class TorchStrengthPolicyTest {

    // ---- isSupported: the SDK gate -------------------------------------------------

    @Test
    fun `sdk below 33 is unsupported`() {
        assertFalse(TorchStrengthPolicy.isSupported(32))
        assertFalse(TorchStrengthPolicy.isSupported(28))
        assertFalse(TorchStrengthPolicy.isSupported(23))
    }

    @Test
    fun `sdk 33 and above is supported - including 34 which the old wrong gate excluded`() {
        assertTrue(TorchStrengthPolicy.isSupported(33))
        assertTrue(TorchStrengthPolicy.isSupported(34))
        assertTrue(TorchStrengthPolicy.isSupported(35))
        assertTrue(TorchStrengthPolicy.isSupported(36))
    }

    // ---- the gate boundary (the exact verdict that flips for Android 13 devices) ----

    @Test
    fun `sdk 33 boundary - request on a capable-looking device goes through, sdk 32 does not`() {
        assertEquals(3, TorchStrengthPolicy.effectiveLevel(3, 5, 33))
        assertNull(TorchStrengthPolicy.effectiveLevel(3, 5, 32))
    }

    // ---- the ladder -----------------------------------------------------------------

    @Test
    fun `unsupported sdk returns null regardless of request or device max`() {
        assertNull(TorchStrengthPolicy.effectiveLevel(5, 10, 32))
        assertNull(TorchStrengthPolicy.effectiveLevel(null, null, 23))
        // Even an in-range request on a capable-looking device is a no-op pre-33.
        assertNull(TorchStrengthPolicy.effectiveLevel(3, 5, 28))
    }

    @Test
    fun `null requested strength means device default and skips the strength api`() {
        assertNull(TorchStrengthPolicy.effectiveLevel(null, 10, 33))
    }

    @Test
    fun `missing device max characteristic is treated as unsupported and returns null`() {
        assertNull(TorchStrengthPolicy.effectiveLevel(3, null, 33))
    }

    @Test
    fun `single-level flash device max 1 is a no-op - plain enableTorch covers it`() {
        assertNull(TorchStrengthPolicy.effectiveLevel(3, 1, 33))
    }

    @Test
    fun `degenerate device max below 1 is also a no-op`() {
        assertNull(TorchStrengthPolicy.effectiveLevel(3, 0, 33))
        assertNull(TorchStrengthPolicy.effectiveLevel(3, -1, 33))
    }

    // ---- the clamp ------------------------------------------------------------------

    @Test
    fun `requested below the floor clamps up to 1`() {
        assertEquals(1, TorchStrengthPolicy.effectiveLevel(0, 5, 33))
        assertEquals(1, TorchStrengthPolicy.effectiveLevel(-3, 5, 33))
    }

    @Test
    fun `requested above the device max clamps down to the device max - the device wins`() {
        assertEquals(10, TorchStrengthPolicy.effectiveLevel(12, 10, 33))
    }

    @Test
    fun `requested within range passes through unchanged`() {
        assertEquals(3, TorchStrengthPolicy.effectiveLevel(3, 5, 33))
        assertEquals(1, TorchStrengthPolicy.effectiveLevel(1, 5, 33))
        assertEquals(5, TorchStrengthPolicy.effectiveLevel(5, 5, 33))
    }

    @Test
    fun `catalog bound of 8 is not an apply-time ceiling when the device supports more`() {
        // A raw 12 on a 15-level device stays 12: MAX_REQUESTED_LEVEL is the
        // persistence-side sanitize bound, never enforced here — the device's
        // real max is the only ceiling.
        assertEquals(12, TorchStrengthPolicy.effectiveLevel(12, 15, 33))
    }

    @Test
    fun `raw request above both catalog bound and device max lands on the device max`() {
        // The defensive case: a value that escaped the catalog's 1..8 bound is
        // still capped by the hardware, never sent as-is.
        assertEquals(8, TorchStrengthPolicy.effectiveLevel(12, 8, 33))
    }
}
