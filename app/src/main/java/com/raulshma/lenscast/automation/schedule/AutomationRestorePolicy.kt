package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings

/**
 * The pre-test state snapshot the Automation Test's restore answers from —
 * one plain value the [AutomationTestController] captures from the live
 * seams, pure so the restore verdicts are JVM-testable. Only the axes the
 * catalog actions can move live here.
 */
data class AutomationStateSnapshot(
    /** null = auto ISO; `exposureTimeMs` null = auto exposure. */
    val iso: Int?,
    val exposureTimeMs: Double?,
    val exposureCompensation: Int,
    val zoomRatio: Float,
    val frameRate: Int,
    val lensIndex: Int,
    val torchEnabled: Boolean,
    val torchStrength: Int?,
    val motionEnabled: Boolean,
    val soundEnabled: Boolean,
    /** Whether the whole server (web + RTSP) was running when the test armed. */
    val streamRunning: Boolean,
    /** Whether the Recording Controller was Idle — a live recording was NOT the test's doing. */
    val recordingIdle: Boolean,
)

/**
 * The pure restore half of the Automation Test (issue #18 item 3 — "press
 * again to restore the previous state"): the rule's actions + the pre-test
 * snapshot → the action list that winds the touched axes back. Verdicts:
 *
 * - Only the axes the rule itself touched are restored, per touched param —
 *   a rule that set only ISO does not also force exposure back to auto.
 * - Camera-valued and detection restores converge the PERSISTED state (the
 *   executor's camera/detection semantics), so the Settings Applier lands
 *   them everywhere, exactly like a scheduled fire would.
 * - `siren` restores as a stop (a test must never re-arm a siren on the way
 *   back); `recording` restores as a stop ONLY when the controller was Idle
 *   at arm time (the test started the only recording that can be the test's
 *   doing) — the user's own recording survives the restore.
 * - `capture_photo` is not restorable (its product is a capture, not a
 *   state); unknown ids restore nothing.
 *
 * The controller runs the returned specs through the same executor a
 * scheduled fire uses — no second execution path can drift.
 */
object AutomationRestorePolicy {

    fun restoreActions(
        actions: List<AutomationActionSpec>,
        snapshot: AutomationStateSnapshot,
    ): List<AutomationActionSpec> = buildList {
        for (action in actions) {
            when (AutomationActionCatalog.typeFor(action.type)?.id ?: continue) {
                "camera_exposure" -> {
                    val params = mutableMapOf<String, String>()
                    if ("iso" in action.params) params["iso"] = snapshot.iso?.toString() ?: "0"
                    if ("exposure_time_ms" in action.params) {
                        params["exposure_time_ms"] = snapshot.exposureTimeMs?.toString() ?: "0"
                    }
                    if (params.isNotEmpty()) add(AutomationActionSpec(type = action.type, params = params))
                }
                "camera_ev" -> add(
                    AutomationActionSpec(type = action.type, params = mapOf("index" to snapshot.exposureCompensation.toString())),
                )
                "camera_zoom" -> add(
                    AutomationActionSpec(type = action.type, params = mapOf("ratio" to snapshot.zoomRatio.toString())),
                )
                "camera_fps" -> add(
                    AutomationActionSpec(type = action.type, params = mapOf("fps" to snapshot.frameRate.toString())),
                )
                "camera_lens" -> add(
                    AutomationActionSpec(type = action.type, params = mapOf("index" to snapshot.lensIndex.toString())),
                )
                "torch" -> {
                    val params = mutableMapOf<String, String>(
                        "enabled" to snapshot.torchEnabled.toString(),
                    )
                    // Only a rule that drove a level restores one — and only
                    // to the snapshot's own level (null = device default,
                    // which is the strength param's absence).
                    if ("strength" in action.params && snapshot.torchStrength != null) {
                        params["strength"] = snapshot.torchStrength.toString()
                    }
                    add(AutomationActionSpec(type = action.type, params = params))
                }
                "stream" -> add(
                    AutomationActionSpec(type = action.type, params = mapOf("enabled" to snapshot.streamRunning.toString())),
                )
                "detection" -> {
                    val params = mutableMapOf<String, String>()
                    if ("motion_enabled" in action.params) params["motion_enabled"] = snapshot.motionEnabled.toString()
                    if ("sound_enabled" in action.params) params["sound_enabled"] = snapshot.soundEnabled.toString()
                    if (params.isNotEmpty()) add(AutomationActionSpec(type = action.type, params = params))
                }
                // A test that started the siren stops it on the way back; a
                // test that only stopped it has nothing to restore.
                "siren" -> if (action.params["enabled"] != "false") {
                    add(AutomationActionSpec(type = action.type, params = mapOf("enabled" to "false")))
                }
                // A test that started a recording stops it only when nothing
                // else was recording at arm time.
                "recording" -> if (action.params["enabled"] != "false" && snapshot.recordingIdle) {
                    add(AutomationActionSpec(type = action.type, params = mapOf("enabled" to "false")))
                }
                "capture_photo" -> {} // Not restorable — the capture already happened.
            }
        }
    }

    /**
     * The post-restore [CameraSettings] the LIVE camera session must show:
     * [restoreActions] folded over the current persisted value through the
     * same [AutomationSchedulePolicy.mergeCameraSettings] merge the executor
     * persists with, so the value applied live is exactly the value the
     * restore saved (touched axes = the snapshot's, everything else
     * untouched). This is the payload of the Automation Test Controller's
     * direct live apply — the pure half of the "restore drives the live
     * camera itself" contract; the auto sentinel "0" folds to null here the
     * same way it does on the persisted path, so a snapshot with auto
     * ISO/exposure lands as driver-auto, not as a stale manual carry-over.
     */
    fun restoredCameraSettings(
        actions: List<AutomationActionSpec>,
        base: CameraSettings,
    ): CameraSettings = actions.fold(base) { acc, action ->
        AutomationSchedulePolicy.mergeCameraSettings(acc, action)
    }
}
