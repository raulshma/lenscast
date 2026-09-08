package com.raulshma.lenscast.camera.model

/**
 * Pure Kelvin → RGB gains policy for the manual white-balance control.
 *
 * Model: a Planckian radiator at temperature K relative to the 5600 K
 * reference — warm light carries abundant red already, so the red gain drops
 * below unity to neutralize it, and cool light needs the inverse on blue.
 * Gains follow inverse power laws in (K/5600) with G pinned at 1. This is a
 * monotone approximation, not a sensor-calibrated transform; the policy
 * clamps to its own modeled [GAIN_MIN, GAIN_MAX] range, and the camera
 * device clips any out-of-range gain itself (camera2 clips values beyond the
 * per-device gains bounds). The caller wraps the channels in the platform
 * RggbChannelVector, so the math stays JVM-testable without android.hardware
 * types.
 */
object KelvinGainsPolicy {

    const val GAIN_MIN = 0.5f
    const val GAIN_MAX = 4.0f
    const val REFERENCE_KELVIN = 5600

    /** FloatArray of [R, Gr, Gb, B] gains for a color temperature in Kelvin. */
    fun gainsFor(kelvin: Int): FloatArray {
        val ratio = kelvin.coerceIn(1500, 20_000).toDouble() / REFERENCE_KELVIN
        // Warm light (ratio < 1) carries abundant red, so the red gain drops;
        // cool light needs the inverse on blue.
        val r = (ratio * ratio).toFloat().coerceIn(GAIN_MIN, GAIN_MAX)
        val b = (1.0 / (ratio * ratio)).toFloat().coerceIn(GAIN_MIN, GAIN_MAX)
        val g = 1.0f
        return floatArrayOf(r, g, g, b)
    }
}
