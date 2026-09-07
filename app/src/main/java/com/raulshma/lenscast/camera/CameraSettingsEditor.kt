package com.raulshma.lenscast.camera

import com.raulshma.lenscast.camera.model.CameraSettings

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

    suspend fun edit(transform: (CameraSettings) -> CameraSettings) {
        val next = transform(current())
        apply?.invoke(next)
        persist(next)
    }

    companion object {
        internal fun parseIso(value: String): Int? =
            if (value == "Auto") null else value.toIntOrNull()

        internal fun parseSceneMode(mode: String): String? =
            if (mode == "OFF") null else mode
    }
}
