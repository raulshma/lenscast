package com.raulshma.lenscast.camera.model

import android.hardware.camera2.CaptureRequest
import kotlin.math.abs

/**
 * The read-back verdict: does a capture result report the values the last
 * SUBMITTED plan asked for? A bundle CameraX accepted can still be clamped or
 * rewritten by the HAL without an error, so what the device reports running
 * is the only proof a control landed (CONTEXT.md, "Capture Read-back").
 *
 * Pure comparison and formatting. The sampling (when to look, how long to
 * wait) is [CaptureReadbackTracker]; reading the `CaptureResult` and writing
 * the log line stay in CameraService. Every line carries [LOG_PREFIX]
 * verbatim — the verification step greps logcat for it, so the prefix and
 * the ok line's shape are contract, not cosmetics.
 */
object CaptureReadbackPolicy {

    /** The exact logcat prefix of every read-back line. */
    const val LOG_PREFIX = "Camera controls readback: "

    /** At most one capture result is examined per interval (frames arrive many times a second). */
    const val SAMPLE_INTERVAL_MS = 1_000L

    /**
     * How long a submitted plan stays armed. Long enough for a slow HAL and
     * the session's in-flight frames (the first results after a submit can
     * still carry the previous request), short enough that a rejected
     * control is reported while the user is still looking at the screen.
     */
    const val TIMEOUT_MS = 10_000L

    /**
     * How far the device's ISO and shutter may sit from the requested values,
     * in percent of the requested one, and still count as the control
     * landing. HALs quantise both — the shutter to line times, the gain to
     * their own steps — so an exact comparison would report every manual
     * exposure on such a device as a mismatch.
     */
    const val SENSOR_TOLERANCE_PERCENT = 10L

    /**
     * What the plan asked the device for — its AE mode, sensor ISO and
     * shutter and scene mode after the capability fold and the clamps,
     * i.e. exactly the values that were submitted.
     */
    data class Expected(
        val aeMode: Int,
        val sensorSensitivity: Int?,
        val sensorExposureTimeNs: Long?,
        val sceneMode: Int,
        val fpsLower: Int? = null,
        val fpsUpper: Int? = null,
    )

    /** What one capture result reported; null means the device did not report that key. */
    data class Reported(
        val aeMode: Int?,
        val sensorSensitivity: Int?,
        val sensorExposureTimeNs: Long?,
        val sceneMode: Int?,
        val fpsLower: Int? = null,
        val fpsUpper: Int? = null,
    )

    enum class Verdict {
        /** The device runs the submitted plan: the modes exactly, the sensor pair within [SENSOR_TOLERANCE_PERCENT]. */
        Match,

        /** The device reports values other than the submitted ones: a control did not land as submitted. */
        Mismatch,

        /** A needed value is missing from the result: nothing may be concluded. */
        Unknown,
    }

    /** Where a concluded line goes: a confirmation at info, anything else at warn. */
    enum class Level { Info, Warning }

    /** One concluded read-back: the level and the complete log line, prefix included. */
    data class Line(val level: Level, val message: String)

    /** The read-back target of one submitted plan — its values as submitted. */
    fun expectedFrom(plan: CameraControlPlan): Expected = Expected(
        aeMode = plan.aeMode,
        sensorSensitivity = plan.sensorSensitivity,
        sensorExposureTimeNs = plan.sensorExposureTimeNs,
        sceneMode = plan.sceneMode,
        fpsLower = plan.fpsLower,
        fpsUpper = plan.fpsUpper,
    )

    /**
     * Compares one capture result with the plan. The sensor pair is compared
     * only when AE is expected OFF: under automatic exposure (and its flash
     * variants) the device varies ISO and shutter by design, so a sensor
     * value the plan never asked for must not fail the read-back. The modes
     * take no tolerance — there is no "near" mode; ISO and shutter are
     * accepted within [SENSOR_TOLERANCE_PERCENT] of the request. A missing
     * value is [Verdict.Unknown] — neither a match nor a mismatch may be
     * claimed from what the device did not say. When both expected and
     * reported carry an AE target fps range, it must match exactly.
     */
    fun compare(expected: Expected, reported: Reported): Verdict {
        if (reported.aeMode == null || reported.sceneMode == null) return Verdict.Unknown
        val modesMatch = reported.aeMode == expected.aeMode && reported.sceneMode == expected.sceneMode
        val fpsMatches = if (showFps(expected, reported)) {
            reported.fpsLower == expected.fpsLower && reported.fpsUpper == expected.fpsUpper
        } else {
            true
        }
        if (!pinsSensor(expected)) return if (modesMatch && fpsMatches) Verdict.Match else Verdict.Mismatch

        val requestedSensitivity = expected.sensorSensitivity ?: return Verdict.Unknown
        val requestedExposureTimeNs = expected.sensorExposureTimeNs ?: return Verdict.Unknown
        val deviceSensitivity = reported.sensorSensitivity ?: return Verdict.Unknown
        val deviceExposureTimeNs = reported.sensorExposureTimeNs ?: return Verdict.Unknown
        val sensorMatches = withinTolerance(requestedSensitivity, deviceSensitivity) &&
            withinTolerance(requestedExposureTimeNs, deviceExposureTimeNs)
        return if (modesMatch && fpsMatches && sensorMatches) Verdict.Match else Verdict.Mismatch
    }

    /** Whether [device] sits within [SENSOR_TOLERANCE_PERCENT] of [requested]. Whole-number maths: the boundary does not round. */
    private fun withinTolerance(requested: Int, device: Int): Boolean =
        abs(device - requested).toLong() * 100 <= requested.toLong() * SENSOR_TOLERANCE_PERCENT

    private fun withinTolerance(requested: Long, device: Long): Boolean =
        abs(device - requested) * 100 <= requested * SENSOR_TOLERANCE_PERCENT

    /** The confirmation: the device runs the submitted plan. */
    fun ok(expected: Expected): Line = Line(Level.Info, LOG_PREFIX + "ok (${describe(expected)})")

    /** The device reports values other than the submitted ones. */
    fun mismatch(expected: Expected, reported: Reported): Line {
        val includeFps = showFps(expected, reported)
        return Line(
            Level.Warning,
            LOG_PREFIX + "MISMATCH requested ${describe(expected, includeFps)} | device ${describe(expected, reported, includeFps)}",
        )
    }

    /**
     * The window closed without a conclusive sample: either no capture
     * result arrived at all ([reported] null) or a needed value was missing
     * from every one that did.
     */
    fun unknown(expected: Expected, reported: Reported?): Line {
        val includeFps = showFps(expected, reported)
        val device = reported?.let { describe(expected, it, includeFps) }
            ?: "reported no capture result within ${TIMEOUT_MS}ms"
        return Line(Level.Warning, LOG_PREFIX + "UNKNOWN requested ${describe(expected, includeFps)} | device $device")
    }

    /**
     * The fourth line: the frame hook failed and the read-back is off for the
     * rest of the process. It names no plan — it explains why none of the
     * other three will appear.
     */
    fun disabled(): Line = Line(Level.Warning, LOG_PREFIX + "disabled — the capture-result hook failed")

    private fun showFps(expected: Expected, reported: Reported?): Boolean =
        expected.fpsLower != null && expected.fpsUpper != null &&
            reported?.fpsLower != null && reported.fpsUpper != null

    /** The requested side: `AE=OFF ISO=3200 exp=100000000ns scene=NIGHT`. */
    private fun describe(expected: Expected, includeFps: Boolean = false): String = describe(
        aeMode = expected.aeMode,
        sensorSensitivity = expected.sensorSensitivity,
        sensorExposureTimeNs = expected.sensorExposureTimeNs,
        sceneMode = expected.sceneMode,
        fpsLower = expected.fpsLower,
        fpsUpper = expected.fpsUpper,
        expected = expected,
        includeFps = includeFps,
    )

    /** The device side of a line, under the same predicate as the requested side. */
    private fun describe(expected: Expected, reported: Reported, includeFps: Boolean): String = describe(
        aeMode = reported.aeMode,
        sensorSensitivity = reported.sensorSensitivity,
        sensorExposureTimeNs = reported.sensorExposureTimeNs,
        sceneMode = reported.sceneMode,
        fpsLower = reported.fpsLower,
        fpsUpper = reported.fpsUpper,
        expected = expected,
        includeFps = includeFps,
    )

    /**
     * The sensor pair is shown exactly where it is compared ([pinsSensor]):
     * under automatic exposure the device's ISO and shutter are its own
     * business and would only obscure the real difference — on either side.
     */
    private fun describe(
        aeMode: Int?,
        sensorSensitivity: Int?,
        sensorExposureTimeNs: Long?,
        sceneMode: Int?,
        fpsLower: Int?,
        fpsUpper: Int?,
        expected: Expected,
        includeFps: Boolean,
    ): String {
        val sensor = if (pinsSensor(expected)) " ISO=${sensorSensitivity ?: "null"} exp=${sensorExposureTimeNs ?: "null"}ns" else ""
        val fps = if (includeFps) " fps=[${fpsLower ?: "null"}..${fpsUpper ?: "null"}]" else ""
        return "AE=${aeModeName(aeMode)}$sensor scene=${sceneModeName(sceneMode)}$fps"
    }

    /** Whether the plan pins the sensor: only a manual plan (AE OFF) submits ISO and shutter. */
    private fun pinsSensor(expected: Expected): Boolean = expected.aeMode == CaptureRequest.CONTROL_AE_MODE_OFF

    private fun aeModeName(mode: Int?): String = when (mode) {
        null -> "null"
        CaptureRequest.CONTROL_AE_MODE_OFF -> "OFF"
        CaptureRequest.CONTROL_AE_MODE_ON -> "ON"
        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
        CaptureRequest.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "ON_EXTERNAL_FLASH"
        // A newer or vendor-private mode: the number still identifies it.
        else -> mode.toString()
    }

    private fun sceneModeName(mode: Int?): String = when (mode) {
        null -> "null"
        CaptureRequest.CONTROL_SCENE_MODE_DISABLED -> "DISABLED"
        CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY -> "FACE_PRIORITY"
        CaptureRequest.CONTROL_SCENE_MODE_ACTION -> "ACTION"
        CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT -> "PORTRAIT"
        CaptureRequest.CONTROL_SCENE_MODE_LANDSCAPE -> "LANDSCAPE"
        CaptureRequest.CONTROL_SCENE_MODE_NIGHT -> "NIGHT"
        CaptureRequest.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> "NIGHT_PORTRAIT"
        CaptureRequest.CONTROL_SCENE_MODE_THEATRE -> "THEATRE"
        CaptureRequest.CONTROL_SCENE_MODE_BEACH -> "BEACH"
        CaptureRequest.CONTROL_SCENE_MODE_SNOW -> "SNOW"
        CaptureRequest.CONTROL_SCENE_MODE_SUNSET -> "SUNSET"
        CaptureRequest.CONTROL_SCENE_MODE_STEADYPHOTO -> "STEADYPHOTO"
        CaptureRequest.CONTROL_SCENE_MODE_FIREWORKS -> "FIREWORKS"
        CaptureRequest.CONTROL_SCENE_MODE_SPORTS -> "SPORTS"
        CaptureRequest.CONTROL_SCENE_MODE_PARTY -> "PARTY"
        CaptureRequest.CONTROL_SCENE_MODE_CANDLELIGHT -> "CANDLELIGHT"
        CaptureRequest.CONTROL_SCENE_MODE_BARCODE -> "BARCODE"
        CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CaptureRequest.CONTROL_SCENE_MODE_HDR -> "HDR"
        // settings.sceneMode takes any camera2 scene mode by number; a
        // vendor-private one (DEVICE_CUSTOM_START..) has no name to show.
        else -> mode.toString()
    }
}
