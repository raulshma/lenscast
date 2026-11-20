package com.raulshma.lenscast.camera

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.WhiteBalance

/**
 * The single camera-settings writer. Both the camera screen and the settings
 * screens funnel transforms through [edit]: one persisted write per edit,
 * run through [normalizeManualDefaults] first — every write lands in the
 * same normalized shape, and the Settings Applier applies it to the runtime.
 * Wiring arrives as lambdas so this module never touches Android and stays
 * testable through its interface; the field parsing below is the only logic
 * both screens shared by copy.
 *
 * Persist-ONLY, deliberately: the torch-touch verdict lives in
 * CameraService.applySettings (the one seam that sees the previously
 * applied snapshot and the new one), and it judges every persisted document
 * exactly once. The old apply-then-persist dual write broke that invariant
 * — the apply half converged and committed the live torch fact, then the
 * persist half overwrote it with the pre-convergence snapshot the editor
 * had read, and the applier honored that stale pair as an explicit torch
 * write, darkening a live-only lit LED (the in-app variant of issue #16;
 * the writer-side protocol it rode was fixes 22fe040/e84b36d/565a690).
 */
class CameraSettingsEditor(
    private val current: () -> CameraSettings,
    private val persist: suspend (CameraSettings) -> Unit,
) {

    /**
     * Persist the transform's output run through [normalizeManualDefaults] —
     * one DataStore write per edit; the Settings Applier applies it and owns
     * the torch convergence from there.
     */
    suspend fun edit(transform: (CameraSettings) -> CameraSettings) {
        persist(normalizeManualDefaults(transform(current())))
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
