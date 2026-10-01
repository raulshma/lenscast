package com.raulshma.lenscast.camera.model

import android.view.Surface

/**
 * The pure output-rotation policy (issue #6): a phone mounted upside down or
 * sideways declares an [outputRotation] correction, and every output —
 * M-JPEG, snapshots, HLS, RTSP, recordings — comes out upright.
 *
 * Two persisted knobs:
 *  - `outputRotation` — the extra clockwise degrees (0/90/180/270) applied on
 *    top of the sensor-to-upright rotation the frames already carry.
 *  - `orientationLocked` — ignore the sensor's reported rotation entirely and
 *    present exactly [outputRotation] degrees. For a fixed mount whose
 *    reported orientation drifts (or lies), this makes the output a constant.
 *
 * The same policy also answers the CameraX question: stills (ImageCapture)
 * and recordings (VideoCapture/Recorder) do not flow through the NV21 seam —
 * their rotation is chosen by the use case's `targetRotation`. CameraX bakes
 * `sensorOrientation - targetRotation` degrees of rotation into the file, and
 * `sensorOrientation` ≡ sensorRotation + displayRotation (mod 360) because
 * the analysis stream's rotation is measured against the default (display)
 * target. [targetRotationDegrees] inverts that relation, so a target rotation
 * computed from it makes the file carry exactly the effective rotation the
 * streams show.
 */
object OutputRotationPolicy {

    /** The corrections the UI offers; anything else coerces to 0. */
    val VALID_DEGREES = setOf(0, 90, 180, 270)

    /** Snap a persisted/API value onto the valid ladder. */
    fun coerce(degrees: Int): Int = if (degrees in VALID_DEGREES) degrees else 0

    /**
     * The rotation every output must carry: the setting applied on top of the
     * sensor's rotation, or — locked — the setting alone.
     */
    fun effectiveRotation(sensorRotation: Int, outputRotation: Int, orientationLocked: Boolean): Int {
        val output = coerce(outputRotation)
        return if (orientationLocked) output else (sensorRotation + output) % 360
    }

    /** `Surface.ROTATION_*` constants to degrees (a quarter turn each). */
    fun displayRotationDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /** Degrees back to the `Surface.ROTATION_*` constant CameraX's API speaks. */
    fun surfaceRotationFromDegrees(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> Surface.ROTATION_90
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_270
        else -> Surface.ROTATION_0
    }

    /**
     * The target-rotation degrees that make CameraX bake [effective] degrees
     * into a still or recording: baked ≡ sensorOrientation − target and
     * sensorOrientation ≡ [sensorRotation] + [displayRotationDegrees], so
     * target ≡ sensorRotation + display − effective (mod 360).
     */
    fun targetRotationDegrees(sensorRotation: Int, displayRotationDegrees: Int, effective: Int): Int =
        (((sensorRotation + displayRotationDegrees - effective) % 360) + 360) % 360
}
