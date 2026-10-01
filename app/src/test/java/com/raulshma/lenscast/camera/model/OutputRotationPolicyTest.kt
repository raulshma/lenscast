package com.raulshma.lenscast.camera.model

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure output-rotation policy (issue #6): the effective rotation every
 * output carries, and the CameraX target-rotation inversion that makes stills
 * and recordings bake exactly that rotation into the file.
 */
class OutputRotationPolicyTest {

    // ── coerce ──

    @Test
    fun `valid ladder passes through`() {
        for (d in listOf(0, 90, 180, 270)) assertEquals(d, OutputRotationPolicy.coerce(d))
    }

    @Test
    fun `anything off the ladder coerces to 0`() {
        for (d in listOf(-90, 45, 360, Int.MIN_VALUE)) assertEquals(0, OutputRotationPolicy.coerce(d))
    }

    // ── effective rotation ──

    @Test
    fun `unlocked adds the correction on top of the sensor rotation modulo 360`() {
        assertEquals(90, OutputRotationPolicy.effectiveRotation(90, 0, false))
        assertEquals(180, OutputRotationPolicy.effectiveRotation(90, 90, false))
        assertEquals(0, OutputRotationPolicy.effectiveRotation(270, 90, false))
        assertEquals(270, OutputRotationPolicy.effectiveRotation(0, 270, false))
    }

    @Test
    fun `locked is the constant setting regardless of the sensor`() {
        assertEquals(180, OutputRotationPolicy.effectiveRotation(0, 180, true))
        assertEquals(180, OutputRotationPolicy.effectiveRotation(90, 180, true))
        assertEquals(180, OutputRotationPolicy.effectiveRotation(270, 180, true))
    }

    @Test
    fun `a coerced-off-ladder setting behaves as 0`() {
        assertEquals(90, OutputRotationPolicy.effectiveRotation(90, 45, false))
        assertEquals(0, OutputRotationPolicy.effectiveRotation(90, 45, true))
    }

    // ── surface rotation mapping ──

    @Test
    fun `surface constants map to degrees and back`() {
        val pairs = listOf(
            Surface.ROTATION_0 to 0,
            Surface.ROTATION_90 to 90,
            Surface.ROTATION_180 to 180,
            Surface.ROTATION_270 to 270,
        )
        for ((surface, degrees) in pairs) {
            assertEquals(degrees, OutputRotationPolicy.displayRotationDegrees(surface))
            assertEquals(surface, OutputRotationPolicy.surfaceRotationFromDegrees(degrees))
        }
        // Out-of-domain degrees land on ROTATION_0, not an invalid constant.
        assertEquals(Surface.ROTATION_0, OutputRotationPolicy.surfaceRotationFromDegrees(360))
    }

    // ── the target-rotation inversion ──

    @Test
    fun `target rotation cancels the sensor and display so the file carries the effective rotation`() {
        // CameraX bakes (sensorOrientation - target) where sensorOrientation
        // ≡ sensorRotation + displayRotation (mod 360). The inversion must
        // hold for every combination: baked(targetRotationFor(e)) ≡ e.
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                for (effective in listOf(0, 90, 180, 270)) {
                    val targetDegrees = OutputRotationPolicy.targetRotationDegrees(sensor, display, effective)
                    val targetSurface = OutputRotationPolicy.surfaceRotationFromDegrees(targetDegrees)
                    val baked = (sensor + display - OutputRotationPolicy.displayRotationDegrees(targetSurface) + 720) % 360
                    assertEquals("sensor=$sensor display=$display effective=$effective", effective, baked)
                }
            }
        }
    }

    @Test
    fun `the upside-down mounted phone end to end`() {
        // A phone mounted upside down: sensor reports 180, the user asks for a
        // 180 correction, unlocked — streams show (180 + 180) % 360 = 0 (upright).
        val effective = OutputRotationPolicy.effectiveRotation(180, 180, false)
        assertEquals(0, effective)
        // Stills bind with a target rotation that bakes 0 degrees: the plain
        // display-default value (sensor + display - 0 ≡ target degrees).
        val targetDegrees = OutputRotationPolicy.targetRotationDegrees(180, 0, effective)
        assertEquals(
            180,
            OutputRotationPolicy.displayRotationDegrees(
                OutputRotationPolicy.surfaceRotationFromDegrees(targetDegrees)
            ),
        )
    }
}
