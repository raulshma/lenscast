package com.raulshma.lenscast.camera

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.WhiteBalance

/**
 * The single camera-settings writer. Both the camera screen (apply now for
 * responsiveness, then persist — the Settings Applier re-applies
 * idempotently) and the settings screens (persist only; the Applier
 * applies) funnel transforms through [edit]. Wiring arrives as lambdas so
 * this module never touches Android and stays testable through its
 * interface; the field parsing below is the only logic both screens shared
 * by copy.
 */
class CameraSettingsEditor(
    private val current: () -> CameraSettings,
    private val persist: suspend (CameraSettings) -> Unit,
    private val apply: (suspend (CameraSettings) -> Unit)? = null,
) {

    /**
     * Apply-then-persist, in that order, of the transform's output run
     * through [normalizeManualDefaults] first — every write lands applied
     * and persisted in the same normalized shape.
     */
    suspend fun edit(transform: (CameraSettings) -> CameraSettings) {
        val next = normalizeManualDefaults(transform(current()))
        apply?.invoke(next)
        persist(next)
    }

    companion object {
        internal fun parseIso(value: String): Int? =
            if (value == "Auto") null else value.toIntOrNull()

        internal fun parseSceneMode(mode: String): String? =
            if (mode == "OFF") null else mode

        /**
         * The manual-axis null fill, applied at this single writer seam so
         * every client (screens, automation's camera actions, the Web API's
         * settings mapping) lands on the same shape: a MANUAL white balance
         * without a kelvin value would lock AWB off while sending no color
         * gains (the sensor drifts on its own), and a MANUAL focus without a
         * diopter would turn AF off with the lens left floating. Non-manual
         * axes pass through untouched — an explicit null is meaningful there
         * (the driver-default sentinel).
         */
        internal fun normalizeManualDefaults(next: CameraSettings): CameraSettings {
            var normalized = next
            if (next.whiteBalance == WhiteBalance.MANUAL && next.colorTemperature == null) {
                normalized = normalized.copy(colorTemperature = CameraSettings.DEFAULT_COLOR_TEMPERATURE_K)
            }
            if (next.focusMode == FocusMode.MANUAL && next.focusDistance == null) {
                // No explicit default exists at the mode writes (the quick-
                // setting sheet copies the mode alone); the settings screen's
                // slider already renders a null manual distance as 0f, and
                // 0 diopters is Camera2's infinity — the neutral that leaves
                // the lens at its far focus instead of a guessed mid-range.
                normalized = normalized.copy(focusDistance = MANUAL_FOCUS_DISTANCE_NEUTRAL_DIOPTERS)
            }
            return normalized
        }

        /** The manual-focus null fill: infinity, in diopters. See [normalizeManualDefaults]. */
        private const val MANUAL_FOCUS_DISTANCE_NEUTRAL_DIOPTERS = 0f
    }
}
