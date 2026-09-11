package com.raulshma.lenscast.camera.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The pure spirit-level math: accelerometer readings → pitch/roll degrees,
 * the near-horizontal verdict, and the two-axis level line's rendering
 * inputs. The sensor monitor owns no thresholds — it feeds raw acceleration
 * in and hands the policy's [LevelLineState] to the overlay, so the
 * thresholds and clamps are JVM-tested without a sensor.
 */
object LevelIndicatorPolicy {

    /** Within this many degrees of horizontal on both axes counts as level. */
    const val LEVEL_THRESHOLD_DEG = 2f

    /**
     * The pitch range the level line's vertical travel maps onto; beyond it
     * the line pins to its clamp instead of leaving the preview.
     */
    const val PITCH_TRAVEL_RANGE_DEG = 45f

    /** How far (fraction of preview height) the line may travel from center. */
    const val LINE_MAX_TRAVEL_FRACTION = 0.3f

    /** One accelerometer reading in m/s², gravity included. */
    data class Acceleration(val x: Float, val y: Float, val z: Float)

    /** The overlay's complete rendering verdict for one reading. */
    data class LevelLineState(
        val pitchDeg: Float,
        val rollDeg: Float,
        val isLevel: Boolean,
        /** The horizontal level line's rotation (degrees), matching the world horizon. */
        val lineRotationDeg: Float,
        /** The line's center offset from the preview middle, normalized −1..+1 of the travel budget. */
        val lineCenterOffsetFraction: Float,
    )

    /**
     * Pitch around the device's long (x) axis, degrees, zero when the device
     * lies flat screen-up: the standard accelerometer atan2 ladder.
     */
    fun pitchDeg(a: Acceleration): Float =
        Math.toDegrees(
            atan2(-a.x, sqrt(a.y * a.y + a.z * a.z)).toDouble()
        ).toFloat()

    /**
     * Roll around the device's short (y) axis, degrees, zero when the device
     * lies flat screen-up.
     */
    fun rollDeg(a: Acceleration): Float =
        Math.toDegrees(atan2(a.y, a.z).toDouble()).toFloat()

    /** Near-horizontal on both axes within [LEVEL_THRESHOLD_DEG]. */
    fun isLevel(pitchDeg: Float, rollDeg: Float): Boolean =
        abs(pitchDeg) <= LEVEL_THRESHOLD_DEG && abs(rollDeg) <= LEVEL_THRESHOLD_DEG

    /** The complete overlay verdict for one raw reading. */
    fun lineState(a: Acceleration): LevelLineState {
        val pitch = pitchDeg(a)
        val roll = rollDeg(a)
        return LevelLineState(
            pitchDeg = pitch,
            rollDeg = roll,
            isLevel = isLevel(pitch, roll),
            lineRotationDeg = roll,
            lineCenterOffsetFraction = (pitch / PITCH_TRAVEL_RANGE_DEG)
                .coerceIn(-1f, 1f) * LINE_MAX_TRAVEL_FRACTION,
        )
    }
}
